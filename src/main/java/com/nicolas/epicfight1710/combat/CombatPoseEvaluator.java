package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.anim.Clip;
import com.nicolas.epicfight1710.anim.ClipLibrary;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import com.nicolas.epicfight1710.anim.runtime.AnimationPose;
import com.nicolas.epicfight1710.anim.runtime.ArmaturePoseMath;
import com.nicolas.epicfight1710.anim.runtime.JointMask;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stateless animation evaluation with bounded frame-local memoization, NOT an
 * Animator: no clocks, events, transitions, motion selection or render callbacks.
 * A Plan explicitly supplies already-decided layer times/weights/masks. Reuses
 * Clip.sampleTRS, AnimationPose.overlay and the renderer's extracted hierarchy.
 * Single game-thread API. Construct once for an immutable asset generation.
 */
public final class CombatPoseEvaluator {
    public enum Profile { SOURCE_ARMATURE, DBC_ARMATURE }
    private static final int MAX_SAMPLES=128;
    private final SkeletonMesh mesh;
    private final ClipLibrary clips;
    private final AnimationPose composed,temp;
    private final float[] scratch=new float[16];
    private final int root;
    private CombatPoseState.Frame cachedFrame;
    private long evaluations,cacheHits;
    private final Map<Key,CombatPoseSnapshot> cache=new LinkedHashMap<Key,CombatPoseSnapshot>();

    public CombatPoseEvaluator(SkeletonMesh mesh,ClipLibrary clips){
        if(mesh==null||clips==null||mesh.jointCount<=0)throw new IllegalArgumentException("Assets required");
        this.mesh=copyArmature(mesh);this.clips=clips;
        composed=new AnimationPose(mesh.jointCount);temp=new AnimationPose(mesh.jointCount);
        int found=-1;for(int i=0;i<mesh.jointCount;i++)if("Root".equals(this.mesh.jointName[i]))found=i;
        root=found;
    }

    public CombatPoseSnapshot evaluate(CombatPoseState.Frame frame,Plan plan,float sampleTime){
        if(frame==null||!frame.isValid())return invalid(frame,plan,sampleTime,"Missing or expired frame");
        if(plan==null||!finite(sampleTime)||sampleTime<frame.previousAge||sampleTime>frame.currentAge)
            return invalid(frame,plan,sampleTime,"Sample outside captured interval or missing plan");
        if(cachedFrame!=frame){cache.clear();cachedFrame=frame;}
        Key key=new Key(plan,sampleTime);
        CombatPoseSnapshot previous=cache.get(key);
        if(previous!=null){cacheHits++;return previous;}
        // Fail explicitly instead of evicting and recomputing samples per target.
        if(cache.size()>=MAX_SAMPLES)return invalid(frame,plan,sampleTime,"Frame sample budget exceeded");
        Clip action=clips.get(frame.clip);
        if(action==null)return invalid(frame,plan,sampleTime,"Missing action clip");
        composed.clear();
        for(Layer layer:plan.layers){
            Clip clip=clips.get(layer.clip);
            if(clip==null)return invalid(frame,plan,sampleTime,"Missing layer clip: "+layer.clip);
            float partial=frame.currentAge==frame.previousAge?0:(sampleTime-frame.previousAge)/(frame.currentAge-frame.previousAge);
            float time=layer.previousTime+(layer.currentTime-layer.previousTime)*partial;
            temp.sample(clip,time,layer.loop);
            if(plan.profile==Profile.DBC_ARMATURE)ArmaturePoseMath.sanitizeDbc(mesh,root,temp,layer.keepRootYaw,false);
            composed.overlay(temp,layer.weight,layer.mask);
        }
        temp.sample(action,sampleTime,false);
        if(plan.profile==Profile.DBC_ARMATURE)ArmaturePoseMath.sanitizeDbc(mesh,root,temp,plan.keepActionRootYaw,false);
        composed.overlay(temp,plan.actionWeight,plan.actionMask);
        float[][] local=new float[mesh.jointCount][16],global=new float[mesh.jointCount][16];
        ArmaturePoseMath.globals(mesh,composed,local,global,scratch);
        evaluations++;
        for(float[] matrix:global)for(float v:matrix)if(!finite(v))return invalid(frame,plan,sampleTime,"Non-finite hierarchy");
        CombatPoseSnapshot result=new CombatPoseSnapshot(frame,plan.profile,sampleTime,local,global,null);
        cache.put(key,result);return result;
    }

    public long completeEvaluations(){return evaluations;}
    public long cacheHits(){return cacheHits;}

    private static CombatPoseSnapshot invalid(CombatPoseState.Frame f,Plan p,float t,String reason){
        return new CombatPoseSnapshot(f,p==null?null:p.profile,t,null,null,reason);
    }
    private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
    private static void weight(float w){if(!finite(w)||w<0||w>1)throw new IllegalArgumentException("Weight outside [0,1]");}

    /** Ordered base/composite/reaction inputs. Time endpoints must be UNWRAPPED
     * for looping clips; this avoids interpolating backwards across the loop.
     */
    public static final class Layer {
        private final String clip;
        private final float previousTime,currentTime,weight;
        private final boolean loop,keepRootYaw;
        private final JointMask mask;
        public Layer(String clip,float previousTime,float currentTime,boolean loop,float weight,JointMask mask,boolean keepRootYaw){
            if(clip==null||!finite(previousTime)||!finite(currentTime)||previousTime<0||currentTime<previousTime)
                throw new IllegalArgumentException("Layer clip/time");
            weight(weight);this.clip=clip;this.previousTime=previousTime;this.currentTime=currentTime;
            this.loop=loop;this.weight=weight;this.mask=mask;this.keepRootYaw=keepRootYaw;
        }
    }

    /** Immutable explicit composition. No implicit locomotion, aim, landing,
     * transition or camera modifier. DBC_ARMATURE covers sanitization only.
     */
    public static final class Plan {
        public final Profile profile;
        private final Layer[] layers;
        private final float actionWeight;
        private final JointMask actionMask;
        private final boolean keepActionRootYaw;
        public Plan(Profile profile,float actionWeight,JointMask actionMask,boolean keepActionRootYaw,Layer... layers){
            if(profile==null||layers==null)throw new IllegalArgumentException("Profile/layers");
            weight(actionWeight);this.profile=profile;this.actionWeight=actionWeight;
            this.actionMask=actionMask;this.keepActionRootYaw=keepActionRootYaw;this.layers=layers.clone();
            for(Layer layer:this.layers)if(layer==null)throw new IllegalArgumentException("Null layer");
        }
    }

    private static final class Key {
        final Plan plan;final int time;
        Key(Plan plan,float time){this.plan=plan;this.time=Float.floatToIntBits(time==0?0:time);}
        @Override public int hashCode(){return 31*System.identityHashCode(plan)+time;}
        @Override public boolean equals(Object other){return other instanceof Key&&((Key)other).plan==plan&&((Key)other).time==time;}
    }

    private static SkeletonMesh copyArmature(SkeletonMesh source){
        SkeletonMesh copy=new SkeletonMesh();copy.jointCount=source.jointCount;
        copy.jointName=source.jointName.clone();copy.parent=source.parent.clone();
        copy.bindLocal=new float[source.jointCount][16];
        for(int i=0;i<source.jointCount;i++){
            if(copy.parent[i]<-1||copy.parent[i]>=i)throw new IllegalArgumentException("Armature parent order");
            for(int j=0;j<16;j++){
                float v=source.bindLocal[i][j];if(!finite(v))throw new IllegalArgumentException("Invalid bind matrix");
                copy.bindLocal[i][j]=v;
            }
        }
        return copy;
    }
}
