package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Full Epic Fight-style weighted player renderer.
 *
 * This is intentionally NOT a ModelBiped angle overlay.  Every visible vertex is
 * deformed by the real 20-joint Epic Fight armature, including elbows, knees,
 * shoulders, hands, torso/chest and the attack keyframes themselves.
 */
public final class SkinnedPlayerRenderer {
    public static final SkinnedPlayerRenderer INSTANCE=new SkinnedPlayerRenderer();

    private SkeletonMesh workingMesh;
    private float[][] skinnedPos;
    private int skinnedPoseSerial=Integer.MIN_VALUE;
    private final float[] tmpPoint=new float[3], tmpNormal=new float[3];
    private boolean loggedRenderer;
    private boolean loggedMesh;

    private SkinnedPlayerRenderer() {}

    public boolean render(Object player,Object vanillaRenderer,float partial) {
        if(!RuntimeAssets.READY || player==null)return false;
        try {
            PoseEngine.INSTANCE.update(player,partial);
            if(!Compat.bindPlayerTexture(player,vanillaRenderer))return false;

            SkeletonMesh mesh=chooseRenderMesh(vanillaRenderer);
            ensureWorkingMesh(mesh);
            float[][] matrices=PoseEngine.INSTANCE.skinMatrices();
            int poseSerial=PoseEngine.INSTANCE.poseSerial();
            skinVertices(mesh,matrices,poseSerial);

            double px=Compat.px(player)+(Compat.x(player)-Compat.px(player))*partial-Compat.renderX();
            double py=Compat.py(player)+(Compat.y(player)-Compat.py(player))*partial-Compat.renderY();
            double pz=Compat.pz(player)+(Compat.z(player)-Compat.pz(player))*partial-Compat.renderZ();
            float yaw=lerpAngle(Compat.prevYaw(player),Compat.yaw(player),partial);

            GL11.glPushMatrix();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glTranslated(px,py,pz);
            // Same authoring direction used by the first working skinned prototype.
            GL11.glRotatef(180.0F-yaw,0.0F,1.0F,0.0F);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColor4f(1.0F,1.0F,1.0F,1.0F);

            // Opaque body first.  0.1 rendered the entire skin with blending enabled,
            // which made legacy/JBRA textures look like a translucent white ghost.
            GL11.glDisable(GL11.GL_BLEND);
            drawParts(mesh,false,matrices);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            drawParts(mesh,true,matrices);

            GL11.glPopAttrib();
            GL11.glPopMatrix();
            if(!loggedRenderer) {
                loggedRenderer=true;
                System.out.println("[EpicFight1710] Full 20-joint skinned renderer active; ModelBiped pose overlay disabled.");
            }
            return true;
        } catch(Throwable t) {
            try { GL11.glPopAttrib(); } catch(Throwable ignored) {}
            try { GL11.glPopMatrix(); } catch(Throwable ignored) {}
            System.err.println("[EpicFight1710] Full skinned renderer failed; vanilla/JBRA renderer will be kept for this frame.");
            t.printStackTrace();
            return false;
        }
    }

    private SkeletonMesh chooseRenderMesh(Object renderer) {
        SkeletonMesh modern=RuntimeAssets.MESH, legacy=RuntimeAssets.OLD_MESH;
        if(legacy==null)return modern;
        int w=0,h=0;
        try {
            w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        } catch(Throwable ignored) {}

        boolean oldTexture=(w>0 && h>0 && h*2<=w+2);
        // If the GL backend doesn't expose dimensions, prefer the legacy mesh for
        // JinRyuu's 1.7.10 renderer; this is exactly what biped_old_texture exists for.
        if(w<=0 || h<=0) {
            String rn=renderer==null?"":renderer.getClass().getName();
            oldTexture=rn.startsWith("JinRyuu.");
        }
        SkeletonMesh selected=oldTexture?legacy:modern;
        if(!loggedMesh) {
            loggedMesh=true;
            System.out.println("[EpicFight1710] Epic body mesh="+(oldTexture?"legacy-UV":"modern-UV")+" textureSize="+w+"x"+h+" vertices="+selected.vertexCount);
        }
        return selected;
    }

    private void ensureWorkingMesh(SkeletonMesh mesh) {
        if(workingMesh==mesh && skinnedPos!=null && skinnedPos.length==mesh.vertexCount)return;
        workingMesh=mesh;
        skinnedPos=new float[mesh.vertexCount][3];
        skinnedPoseSerial=Integer.MIN_VALUE;
    }

    private void skinVertices(SkeletonMesh mesh,float[][] matrices,int poseSerial) {
        if(skinnedPoseSerial==poseSerial)return;
        long prof=RuntimeProfiler.INSTANCE.begin();
        for(int i=0;i<mesh.vertexCount;i++) {
            float[] src=mesh.positions[i]; float x=0,y=0,z=0,sw=0;
            for(int k=0;k<4;k++) {
                int j=mesh.jointIds[i][k]; float weight=mesh.jointWeights[i][k];
                if(j<0 || weight<=0 || j>=matrices.length)continue;
                Mat4.transformPoint(matrices[j],src[0],src[1],src[2],tmpPoint,0);
                x+=tmpPoint[0]*weight; y+=tmpPoint[1]*weight; z+=tmpPoint[2]*weight; sw+=weight;
            }
            if(sw<=0.0001F){x=src[0];y=src[1];z=src[2];}
            skinnedPos[i][0]=x; skinnedPos[i][1]=y; skinnedPos[i][2]=z;
        }
        skinnedPoseSerial=poseSerial;
        RuntimeProfiler.INSTANCE.end(RuntimeProfiler.JBRA_SKIN,prof);
    }

    private void drawParts(SkeletonMesh mesh,boolean overlays,float[][] matrices) {
        Tessellator t=Tessellator.field_78398_a;
        t.func_78371_b(GL11.GL_TRIANGLES);
        for(SkeletonMesh.Part part:mesh.parts) {
            if(isOverlay(part.name)!=overlays)continue;
            for(int[] ref:part.refs) {
                int pi=ref[0], ui=ref[1], ni=ref[2];
                emitNormal(t,mesh,pi,ni,matrices);
                float u=0.0F,v=0.0F;
                if(ui>=0 && ui<mesh.uvs.length){u=mesh.uvs[ui][0];v=mesh.uvs[ui][1];}
                float[] p=skinnedPos[pi];
                t.func_78374_a(p[0],p[1],p[2],u,v);
            }
        }
        t.func_78381_a();
    }

    private void emitNormal(Tessellator t,SkeletonMesh mesh,int positionIndex,int normalIndex,float[][] matrices) {
        if(normalIndex<0 || normalIndex>=mesh.normals.length){t.func_78375_b(0,1,0);return;}
        float[] n=mesh.normals[normalIndex]; float x=0,y=0,z=0;
        for(int k=0;k<4;k++) {
            int j=mesh.jointIds[positionIndex][k]; float w=mesh.jointWeights[positionIndex][k];
            if(j<0 || w<=0 || j>=matrices.length)continue;
            Mat4.transformVector(matrices[j],n[0],n[1],n[2],tmpNormal,0);
            x+=tmpNormal[0]*w; y+=tmpNormal[1]*w; z+=tmpNormal[2]*w;
        }
        float len=(float)Math.sqrt(x*x+y*y+z*z);
        if(len<0.00001F)t.func_78375_b(0,1,0); else t.func_78375_b(x/len,y/len,z/len);
    }

    private static boolean isOverlay(String n) {
        return "hat".equals(n)||"jacket".equals(n)||"leftSleeve".equals(n)||"rightSleeve".equals(n)||"leftPants".equals(n)||"rightPants".equals(n);
    }

    private static float lerpAngle(float a,float b,float t){float d=(b-a)%360.0F;if(d<-180)d+=360;if(d>=180)d-=360;return a+d*t;}
}
