package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.runtime.LayeredAnimator;
import com.nicolas.epicfight1710.anim.runtime.LivingMotion;

/**
 * Public pose facade used by render bridges.
 *
 * v0.7 moved animation playback/composition into a layered animator inspired by
 * Epic Fight's ClientAnimator.  This facade remains so renderer code does not own
 * animation state and so old integration points stay binary/simple.
 */
public final class PoseEngine {
    public static final PoseEngine INSTANCE=new PoseEngine();
    private LayeredAnimator animator;
    private Object lastGenericPlayer,lastDbcPlayer;
    private int lastGenericTick=Integer.MIN_VALUE,lastDbcTick=Integer.MIN_VALUE;
    private int lastGenericPartialBits,lastDbcPartialBits,lastDbcRenderState=Integer.MIN_VALUE,lastDbcRevision=Integer.MIN_VALUE;
    private final float[] relBase=new float[9],relCurrent=new float[9],deltaRotation=new float[9],rotationA=new float[9],rotationB=new float[9];
    private final float[] positionBase=new float[3],positionCurrent=new float[3];
    private PoseEngine(){}

    private void ensure(){if(animator==null&&RuntimeAssets.READY)animator=new LayeredAnimator(RuntimeAssets.MESH,RuntimeAssets.CLIPS);}
    public void reset(){ensure();if(animator!=null)animator.reset();invalidateFrameCache();}
    public void update(Object player,float partial){
        ensure();if(animator==null||player==null)return;
        int tick=CombatController.INSTANCE.tick(),bits=Float.floatToIntBits(partial);
        if(player==lastGenericPlayer&&tick==lastGenericTick&&bits==lastGenericPartialBits)return;
        long prof=RuntimeProfiler.INSTANCE.begin();animator.update(player,partial);RuntimeProfiler.INSTANCE.end(RuntimeProfiler.ANIMATOR,prof);
        lastGenericPlayer=player;lastGenericTick=tick;lastGenericPartialBits=bits;
    }
    /** JBRA/DBC render path: DBC owns global/root orientation; Epic owns articulated body pose. */
    public void updateDbcJbra(Object player,float partial,int renderState){
        ensure();if(animator==null||player==null)return;
        int tick=CombatController.INSTANCE.tick(),bits=Float.floatToIntBits(partial);
        int revision=DbcClientState.INSTANCE.poseRevision(player);
        if(player==lastDbcPlayer&&tick==lastDbcTick&&bits==lastDbcPartialBits&&renderState==lastDbcRenderState&&revision==lastDbcRevision)return;
        long prof=RuntimeProfiler.INSTANCE.begin();animator.updateDbcJbra(player,partial,renderState);RuntimeProfiler.INSTANCE.end(RuntimeProfiler.ANIMATOR,prof);
        lastDbcPlayer=player;lastDbcTick=tick;lastDbcPartialBits=bits;lastDbcRenderState=renderState;lastDbcRevision=DbcClientState.INSTANCE.poseRevision(player);
    }
    private void invalidateFrameCache(){lastGenericPlayer=lastDbcPlayer=null;lastGenericTick=lastDbcTick=Integer.MIN_VALUE;lastDbcRenderState=lastDbcRevision=Integer.MIN_VALUE;}
    public float[][] skinMatrices(){ensure();return animator==null?EMPTY:animator.skinMatrices();}
    public boolean actionActive(){ensure();return animator!=null&&animator.actionActive();}
    public LivingMotion currentMotion(){ensure();return animator==null?LivingMotion.IDLE:animator.currentMotion();}
    public LayeredAnimator animator(){ensure();return animator;}
    public int poseSerial(){ensure();return animator==null?0:animator.poseSerial();}

    public int root(){ensure();return animator==null?-1:animator.root();}public int torso(){ensure();return animator==null?-1:animator.torso();}public int chest(){ensure();return animator==null?-1:animator.chest();}public int head(){ensure();return animator==null?-1:animator.head();}
    public int thighR(){ensure();return animator==null?-1:animator.thighR();}public int thighL(){ensure();return animator==null?-1:animator.thighL();}public int shoulderR(){ensure();return animator==null?-1:animator.shoulderR();}public int shoulderL(){ensure();return animator==null?-1:animator.shoulderL();}public int armR(){ensure();return animator==null?-1:animator.armR();}public int armL(){ensure();return animator==null?-1:animator.armL();}
    public int handR(){ensure();return animator==null?-1:animator.handR();}public int handL(){ensure();return animator==null?-1:animator.handL();}public int toolR(){ensure();return animator==null?-1:animator.toolR();}public int toolL(){ensure();return animator==null?-1:animator.toolL();}

    /** Copies the composed armature-space transform of one joint. */
    public boolean globalJointMatrix(int joint,float[] out){
        ensure();if(animator==null||joint<0||out==null||out.length<16)return false;
        float[][] g=animator.globalMatrices();if(joint>=g.length)return false;
        System.arraycopy(g[joint],0,out,0,16);return true;
    }

    public boolean deltaEuler(int anchor,int target,float[] out){
        ensure();if(animator==null||anchor<0||target<0||out==null||out.length<3)return false;
        relativeRotation(animator.bindGlobalMatrices()[anchor],animator.bindGlobalMatrices()[target],relBase);
        relativeRotation(animator.globalMatrices()[anchor],animator.globalMatrices()[target],relCurrent);
        mulTransposeLeft(relBase,relCurrent,deltaRotation);toEulerXYZ(deltaRotation,out);return finite3(out);
    }

    /** Full composed-pose displacement from bind, expressed in anchor-local space. */
    public boolean deltaPosition(int anchor,int target,float[] out){
        ensure();if(animator==null||anchor<0||target<0||out==null||out.length<3)return false;
        relativePosition(animator.bindGlobalMatrices()[anchor],animator.bindGlobalMatrices()[target],positionBase);
        relativePosition(animator.globalMatrices()[anchor],animator.globalMatrices()[target],positionCurrent);
        out[0]=positionCurrent[0]-positionBase[0];
        out[1]=positionCurrent[1]-positionBase[1];
        out[2]=positionCurrent[2]-positionBase[2];
        return finite3(out);
    }

    public boolean actionParentDeltaEuler(int anchor,int target,float[] out){
        ensure();if(animator==null||(!animator.actionActive()&&!animator.guardActive())||anchor<0||target<0||out==null||out.length<3)return false;
        relativeRotation(animator.baseGlobalMatrices()[anchor],animator.baseGlobalMatrices()[target],relBase);
        relativeRotation(animator.globalMatrices()[anchor],animator.globalMatrices()[target],relCurrent);
        mulRightTranspose(relCurrent,relBase,deltaRotation);toEulerXYZ(deltaRotation,out);return finite3(out);
    }

    /**
     * Action/guard displacement of a target joint relative to an anchor, expressed in
     * the anchor's local coordinates. Epic Fight first-person rendering is driven by
     * the same armature pose as third person; this gives the 1.7.10 compatibility
     * hand bridge the actual animated hand endpoint instead of guessing from Euler
     * angles alone.
     */
    public boolean actionParentDeltaPosition(int anchor,int target,float[] out){
        ensure();if(animator==null||(!animator.actionActive()&&!animator.guardActive())||anchor<0||target<0||out==null||out.length<3)return false;
        relativePosition(animator.baseGlobalMatrices()[anchor],animator.baseGlobalMatrices()[target],positionBase);
        relativePosition(animator.globalMatrices()[anchor],animator.globalMatrices()[target],positionCurrent);
        out[0]=positionCurrent[0]-positionBase[0];
        out[1]=positionCurrent[1]-positionBase[1];
        out[2]=positionCurrent[2]-positionBase[2];
        return finite3(out);
    }

    /** Bind-space anchor-to-target distance, useful for dimensionless FP clamping. */
    public float bindDistance(int anchor,int target){
        ensure();if(animator==null||anchor<0||target<0)return 0.0F;
        float[] a=animator.bindGlobalMatrices()[anchor],b=animator.bindGlobalMatrices()[target];
        float dx=b[3]-a[3],dy=b[7]-a[7],dz=b[11]-a[11];
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    private void relativeRotation(float[] parent,float[] child,float[] out){
        normalizedRotation(parent,rotationA);normalizedRotation(child,rotationB);
        for(int r=0;r<3;r++)for(int col=0;col<3;col++)out[r*3+col]=rotationA[r]*rotationB[col]+rotationA[3+r]*rotationB[3+col]+rotationA[6+r]*rotationB[6+col];
    }
    private void relativePosition(float[] parent,float[] child,float[] out){
        normalizedRotation(parent,rotationA);
        float dx=child[3]-parent[3],dy=child[7]-parent[7],dz=child[11]-parent[11];
        // Dot with the normalized parent basis columns (inverse rigid rotation).
        out[0]=rotationA[0]*dx+rotationA[3]*dy+rotationA[6]*dz;
        out[1]=rotationA[1]*dx+rotationA[4]*dy+rotationA[7]*dz;
        out[2]=rotationA[2]*dx+rotationA[5]*dy+rotationA[8]*dz;
    }
    private static void mulTransposeLeft(float[] left,float[] right,float[] out){for(int r=0;r<3;r++)for(int c=0;c<3;c++)out[r*3+c]=left[r]*right[c]+left[3+r]*right[3+c]+left[6+r]*right[6+c];}
    private static void mulRightTranspose(float[] left,float[] right,float[] out){for(int r=0;r<3;r++){int rr=r*3;for(int c=0;c<3;c++){int rc=c*3;out[rr+c]=left[rr]*right[rc]+left[rr+1]*right[rc+1]+left[rr+2]*right[rc+2];}}}
    private static void normalizedRotation(float[] m,float[] r){float l0=len(m[0],m[4],m[8]),l1=len(m[1],m[5],m[9]),l2=len(m[2],m[6],m[10]);r[0]=m[0]/l0;r[3]=m[4]/l0;r[6]=m[8]/l0;r[1]=m[1]/l1;r[4]=m[5]/l1;r[7]=m[9]/l1;r[2]=m[2]/l2;r[5]=m[6]/l2;r[8]=m[10]/l2;}
    private static void toEulerXYZ(float[] r,float[] out){float sy=clamp(-r[6],-1,1),y=(float)Math.asin(sy),cy=(float)Math.cos(y),x,z;if(Math.abs(cy)>.0001F){x=(float)Math.atan2(r[7],r[8]);z=(float)Math.atan2(r[3],r[0]);}else{x=(float)Math.atan2(-r[5],r[4]);z=0;}out[0]=x;out[1]=y;out[2]=z;}
    private static float len(float x,float y,float z){float l=(float)Math.sqrt(x*x+y*y+z*z);return l<.000001F?1:l;}private static boolean finite(float v){return !Float.isNaN(v)&&!Float.isInfinite(v);}private static boolean finite3(float[] v){return finite(v[0])&&finite(v[1])&&finite(v[2]);}private static float clamp(float x,float a,float b){return x<a?a:(x>b?b:x);}
    private static final float[][] EMPTY=new float[0][0];
}
