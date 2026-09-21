package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.Clip;
import java.util.Arrays;

/**
 * Dense per-joint pose in Epic Fight's bind-relative TRS format.
 *
 * Keeping TRS until the final compose step is important: rotations are blended as
 * quaternions rather than by interpolating Euler angles, which is one of the major
 * differences between this runtime and the old ModelBiped pose experiments.
 */
public final class AnimationPose {
    private final float[][] trs;
    private final boolean[] present;
    // Reused scratch buffer: overlay() runs several times per rendered frame.
    private final float[] overlayScratch=new float[10];

    public AnimationPose(int joints) {
        this.trs=new float[joints][10];
        this.present=new boolean[joints];
        clear();
    }

    public int jointCount(){return trs.length;}
    public float[] joint(int i){return trs[i];}
    public boolean has(int i){return i>=0&&i<present.length&&present[i];}

    public void setIdentity(int i){if(i>=0&&i<trs.length){identity(trs[i]);present[i]=true;}}

    public void clear() {
        Arrays.fill(present,false);
        for(int i=0;i<trs.length;i++) identity(trs[i]);
    }

    public void setIdentityPresent() {
        for(int i=0;i<trs.length;i++){identity(trs[i]);present[i]=true;}
    }

    public void copyFrom(AnimationPose other) {
        int n=Math.min(trs.length,other.trs.length);
        for(int i=0;i<n;i++) {
            System.arraycopy(other.trs[i],0,trs[i],0,10);
            present[i]=other.present[i];
        }
        for(int i=n;i<trs.length;i++){identity(trs[i]);present[i]=false;}
    }

    public void sample(Clip clip,float time,boolean loop) {
        clear();
        if(clip==null)return;
        for(int j=0;j<trs.length;j++) if(clip.hasTrack(j)) {
            clip.sampleTRS(j,time,loop,trs[j]);
            present[j]=true;
        }
    }

    public void blend(AnimationPose from,AnimationPose to,float alpha,JointMask mask) {
        float a=clamp01(alpha);
        for(int j=0;j<trs.length;j++) {
            float mw=mask==null?1.0F:mask.weight(j);
            float w=a*mw;
            boolean fh=from!=null&&from.has(j), th=to!=null&&to.has(j);
            if(!fh&&!th){identity(trs[j]);present[j]=false;continue;}
            float[] fa=fh?from.trs[j]:IDENTITY;
            float[] tb=th?to.trs[j]:IDENTITY;
            Clip.blendTRS(fa,tb,w,trs[j]);
            present[j]=true;
        }
    }

    /** Overlay {@code layer} over this pose using a per-joint mask and weight. */
    public void overlay(AnimationPose layer,float weight,JointMask mask) {
        if(layer==null||weight<=0.0F)return;
        for(int j=0;j<trs.length;j++) if(layer.has(j)) {
            float w=clamp01(weight*(mask==null?1.0F:mask.weight(j)));
            if(w<=0.0F)continue;
            Clip.blendTRS(present[j]?trs[j]:IDENTITY,layer.trs[j],w,overlayScratch);
            System.arraycopy(overlayScratch,0,trs[j],0,10);
            present[j]=true;
        }
    }

    public static void identity(float[] out) {
        out[0]=out[1]=out[2]=0.0F;
        out[3]=out[4]=out[5]=0.0F;out[6]=1.0F;
        out[7]=out[8]=out[9]=1.0F;
    }

    private static float clamp01(float v){return v<0?0:(v>1?1:v);}
    private static final float[] IDENTITY={0,0,0,0,0,0,1,1,1,1};
}
