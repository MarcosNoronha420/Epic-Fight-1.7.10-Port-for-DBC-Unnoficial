package com.nicolas.epicfight1710.combat;

/** Immutable armature-space result; no mutable matrix storage escapes. */
public final class CombatPoseSnapshot {
    public final CombatPoseState.Frame frame;
    public final CombatPoseEvaluator.Profile profile;
    public final float sampleTime;
    private final float[][] locals,globals;
    private final String failure;

    CombatPoseSnapshot(CombatPoseState.Frame frame,CombatPoseEvaluator.Profile profile,float time,
                       float[][] locals,float[][] globals,String failure){
        this.frame=frame;this.profile=profile;sampleTime=time;
        this.locals=locals;this.globals=globals;this.failure=failure;
    }
    public boolean isValid(){return failure==null&&frame!=null&&frame.isValid();}
    public String invalidReason(){return failure!=null?failure:isValid()?null:"Expired action/context/tick/revision";}
    public int jointCount(){return globals==null?0:globals.length;}
    public boolean copyGlobalJointMatrix(int joint,float[] out){return copy(globals,joint,out);}
    public boolean copyLocalJointMatrix(int joint,float[] out){return copy(locals,joint,out);}
    private boolean copy(float[][] source,int joint,float[] out){
        if(!isValid()||source==null||joint<0||joint>=source.length||out==null||out.length<16)return false;
        System.arraycopy(source[joint],0,out,0,16);return true;
    }
    /** Explicit caller-supplied transform only. This is not automatic JBRA placement. */
    public boolean copyWorldJointMatrix(int joint,ModelToWorld transform,double[] out){
        if(!isValid()||joint<0||joint>=globals.length||transform==null||out==null||out.length<16)return false;
        transform.transformJoint(globals[joint],out);return true;
    }
}
