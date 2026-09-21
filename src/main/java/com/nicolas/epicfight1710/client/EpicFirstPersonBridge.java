package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;

/**
 * Epic Fight first-person fallback contract, adapted only at the 1.7.10/JBRA boundary.
 *
 * Epic Fight 20.9.5 and 20.14.17 agree on the generic fallback path:
 * final Animator/Armature pose -> save render-stage camera -> identity ->
 * translate(0,-eyeHeight-0.05, sleepingOffset) -> saved camera -> render only
 * left/right arms plus the JBRA texture/clothing passes that belong to those arms.
 *
 * This pre-WORLD-BODY bridge is retained only for transformer/ABI fallback compatibility.
 * The live 2.0.40+ RenderHand path is FirstPersonBodyRenderer1710. The weighted JBRA renderer emits ModelRenderer-space
 * coordinates; this bridge applies the exact inverse JBRA basis before the source-style
 * Epic body/camera matrix. No FOV fitting, screen-space arm tuning, body pass or POV-only
 * Root pose exists here.
 */
final class EpicFirstPersonBridge {
    static final EpicFirstPersonBridge INSTANCE=new EpicFirstPersonBridge();

    private static final int GL_MODELVIEW=5888;
    private static final int GL_MODELVIEW_MATRIX=2982;
    private static final int GL_MATRIX_MODE=2976;

    private final FloatBuffer camera=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer bodySpace=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private boolean bodySpaceReady;
    private boolean bodySpacePushed;
    private final float[] depthPoint=new float[3];
    private SkeletonMesh armMeshCache;
    private int[] armJointCache;
    private Class<?> rendererType;
    private Method renderFirstPersonArm;
    private boolean logged,warned,basisLogged,rootLogged,depthLogged;

    private EpicFirstPersonBridge(){}

    boolean render(Object player,Object renderer){
        if(player==null||renderer==null)return false;
        bodySpaceReady=false;
        bodySpacePushed=false;
        try {
            ensureRenderer(renderer,player);
            if(renderFirstPersonArm==null)return false;

            int previousMode=GL11.glGetInteger(GL_MATRIX_MODE);
            GL11.glMatrixMode(GL_MODELVIEW);
            ((Buffer)camera).clear();
            GL11.glGetFloat(GL_MODELVIEW_MATRIX,camera);
            ((Buffer)camera).rewind();

            GL11.glPushMatrix();
            try {
                GL11.glLoadIdentity();
                GL11.glTranslatef(0.0F,-Compat.eyeHeight(player)-0.05F,0.0F);
                ((Buffer)camera).rewind();
                GL11.glMultMatrix(camera);

                ((Buffer)bodySpace).clear();
                GL11.glGetFloat(GL_MODELVIEW_MATRIX,bodySpace);
                ((Buffer)bodySpace).rewind();
                bodySpaceReady=true;

                NativeJbraSkinContext.INSTANCE.beginFirstPersonCustomRendererCall();
                GL11.glColorMask(false,false,false,false);
                GL11.glDepthMask(false);
                try {
                    renderFirstPersonArm.invoke(renderer,player);
                } finally {
                    GL11.glColorMask(true,true,true,true);
                    GL11.glDepthMask(true);
                    NativeJbraSkinContext.INSTANCE.endFirstPersonCustomRendererCall();
                }
            } finally {
                GL11.glPopMatrix();
                if(previousMode!=GL_MODELVIEW)GL11.glMatrixMode(previousMode);
            }

            if(!logged){
                logged=true;
                System.out.println("[EpicFight1710] First-person 2.0.33 1.7.10 contract active: corrected vanilla hand camera -> -(eyeHeight+0.05) -> the SAME final Animator/Armature matrices as third person -> exact Epic<->JBRA basis adapter -> intact native RA/LA+sleeves. No POV-only Root, body pass, crop, FOV fitting or manual XYZ framing.");
            }
            return true;
        } catch(Throwable t){
            GL11.glColorMask(true,true,true,true);
            GL11.glDepthMask(true);
            bodySpaceReady=false;
            bodySpacePushed=false;
            if(!warned){
                warned=true;
                System.err.println("[EpicFight1710] Epic first-person source pass failed once; native rendering remains available as fallback.");
                t.printStackTrace();
            }
            return false;
        }
    }

    boolean pushBodySpace(){
        if(!bodySpaceReady)return false;
        GL11.glPushMatrix();
        ((Buffer)bodySpace).rewind();
        GL11.glLoadMatrix(bodySpace);

        // JbraWeightedPartRenderer emits m=(-ex, 1.5-ey, ez), matching the stable
        // 1.7.10 ModelRenderer contract. OpenGL post-multiply T*S maps that basis
        // back to Epic body space exactly before the FirstPersonRenderer camera matrix.
        GL11.glTranslatef(0.0F,1.5F,0.0F);
        GL11.glScalef(-1.0F,-1.0F,1.0F);

        GL11.glColorMask(true,true,true,true);
        GL11.glDepthMask(true);
        bodySpacePushed=true;
        if(!basisLogged){
            basisLogged=true;
            System.out.println("[EpicFight1710] First-person 2.0.33 Epic<->JBRA basis adapter active: weighted DBC/JBRA ModelRenderer coordinates are converted exactly once back into Epic body space before RA/LA are drawn.");
        }
        return true;
    }

    void popBodySpace(){
        bodySpacePushed=false;
        GL11.glColorMask(false,false,false,false);
        GL11.glDepthMask(false);
        GL11.glPopMatrix();
    }

    boolean pushEpicBodySpace(){
        if(!bodySpaceReady)return false;
        GL11.glPushMatrix();
        ((Buffer)bodySpace).rewind();
        GL11.glLoadMatrix(bodySpace);
        return true;
    }

    void popEpicBodySpace(){GL11.glPopMatrix();}

    static boolean useDirectFirstPersonVertexEmission(){
        return INSTANCE.bodySpacePushed;
    }

    /**
     * 2.0.33 no longer treats POV as a detached pair of Epic arms. The visible arms
     * are part of the same DBC body surface as torso/legs, so the Brightarm/Bleftarm
     * bind socket retarget must stay active to keep the shoulder seam connected.
     */
    static boolean useRawFirstPersonArmMatrices(){
        return false;
    }

    private float computeSourceNearPlaneShift(){
        SkeletonMesh mesh=RuntimeAssets.MESH;
        float[][] matrices=PoseEngine.INSTANCE.skinMatrices();
        if(mesh==null||matrices==null||matrices.length<mesh.jointCount)return 0.0F;
        ensureArmJointCache(mesh);
        float minZ=Float.MAX_VALUE;boolean any=false;
        int count=Math.min(mesh.vertexCount,Math.min(mesh.positions.length,Math.min(mesh.jointIds.length,mesh.jointWeights.length)));
        for(int v=0;v<count;v++){
            int[] ids=mesh.jointIds[v];float[] ws=mesh.jointWeights[v];
            if(ids==null||ws==null)continue;
            float armWeight=0.0F;int n=Math.min(ids.length,ws.length);
            for(int k=0;k<n;k++)if(isArmJoint(ids[k]))armWeight+=ws[k];
            if(armWeight<0.45F)continue;
            float z=0.0F,total=0.0F;
            for(int k=0;k<n;k++){
                int j=ids[k];float w=ws[k];
                if(j<0||j>=matrices.length||w<=0.00001F)continue;
                Mat4.transformPoint(matrices[j],mesh.positions[v][0],mesh.positions[v][1],mesh.positions[v][2],depthPoint,0);
                z+=depthPoint[2]*w;total+=w;
            }
            if(total>0.00001F){z/=total;if(finite(z)){if(z<minZ)minZ=z;any=true;}}
        }
        if(!any)return 0.0F;
        final float target=-0.10F; // exactly 2x vanilla 1.7.10 hand near plane
        // Preserve Epic's authored body/camera distance whenever ANY arm surface is
        // already in front of the near plane. This is not an envelope-fitting rule.
        if(minZ<=target)return 0.0F;
        float shift=target-minZ;
        return shift>=-0.85F?shift:0.0F;
    }

    private void ensureArmJointCache(SkeletonMesh mesh){
        if(armMeshCache==mesh&&armJointCache!=null)return;
        armMeshCache=mesh;armJointCache=new int[mesh.jointCount];
        for(int i=0;i<mesh.jointCount;i++){
            String n=mesh.jointName[i];
            if("Shoulder_R".equals(n)||"Arm_R".equals(n)||"Hand_R".equals(n)||"Elbow_R".equals(n)
                    ||"Shoulder_L".equals(n)||"Arm_L".equals(n)||"Hand_L".equals(n)||"Elbow_L".equals(n))armJointCache[i]=1;
        }
    }
    private boolean isArmJoint(int j){return armJointCache!=null&&j>=0&&j<armJointCache.length&&armJointCache[j]!=0;}
    private static boolean finite(float v){return !Float.isNaN(v)&&!Float.isInfinite(v);}

    void finishFrame(){bodySpaceReady=false;bodySpacePushed=false;}

    private void ensureRenderer(Object renderer,Object player){
        Class<?> c=renderer.getClass();
        if(rendererType==c&&renderFirstPersonArm!=null)return;
        rendererType=c;renderFirstPersonArm=null;
        Method m=Reflect.method(c,1,"func_82441_a","renderFirstPersonArm");
        if(m!=null){
            Class<?>[] p=m.getParameterTypes();
            if(p.length==1&&p[0].isAssignableFrom(player.getClass()))renderFirstPersonArm=m;
            else if(p.length==1&&p[0].getName().indexOf("EntityPlayer")>=0)renderFirstPersonArm=m;
        }
    }
}
