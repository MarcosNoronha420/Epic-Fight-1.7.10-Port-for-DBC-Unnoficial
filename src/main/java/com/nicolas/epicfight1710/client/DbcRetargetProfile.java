package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.SkeletonMesh;
import com.nicolas.epicfight1710.anim.Mat4;
import java.util.Arrays;

/**
 * Deterministic retarget profile from JBRA/JRMCore body geometry to Epic Fight's
 * legacy 20-joint biped. The current profile keeps the actual Epic hinge topology instead of a
 * broad Arm/Hand or Thigh/Leg gradient: the legacy biped has a very narrow
 * Elbow/Knee seam and rigid segments on either side of it.
 */
final class DbcRetargetProfile {
    private final int torso,chest;
    private final int shoulderR,armR,handR,elbowR,shoulderL,armL,handL,elbowL;
    private final int thighR,legR,kneeR,thighL,legL,kneeL;
    private final float[] armPivotR=new float[3],armPivotL=new float[3];
    private final float[] handPivotR=new float[3],handPivotL=new float[3];
    private final float[] elbowPivotR=new float[3],elbowPivotL=new float[3];
    private final float[] thighPivotR=new float[3],thighPivotL=new float[3];
    private final float[] legPivotR=new float[3],legPivotL=new float[3];
    private final float[] kneePivotR=new float[3],kneePivotL=new float[3];

    DbcRetargetProfile(SkeletonMesh mesh) {
        torso=index(mesh,"Torso"); chest=index(mesh,"Chest");
        shoulderR=index(mesh,"Shoulder_R");armR=index(mesh,"Arm_R");handR=index(mesh,"Hand_R");elbowR=index(mesh,"Elbow_R");
        shoulderL=index(mesh,"Shoulder_L");armL=index(mesh,"Arm_L");handL=index(mesh,"Hand_L");elbowL=index(mesh,"Elbow_L");
        thighR=index(mesh,"Thigh_R");legR=index(mesh,"Leg_R");kneeR=index(mesh,"Knee_R");
        thighL=index(mesh,"Thigh_L");legL=index(mesh,"Leg_L");kneeL=index(mesh,"Knee_L");
        // Build bind globals once. Older revisions rebuilt all 20 globals for every
        // single pivot lookup; harmless at startup, but unnecessary churn.
        float[][] bind=buildBindGlobal(mesh);
        readBindPivot(bind,armR,armPivotR);readBindPivot(bind,armL,armPivotL);
        readBindPivot(bind,handR,handPivotR);readBindPivot(bind,handL,handPivotL);
        readBindPivot(bind,elbowR,elbowPivotR);readBindPivot(bind,elbowL,elbowPivotL);
        readBindPivot(bind,thighR,thighPivotR);readBindPivot(bind,thighL,thighPivotL);
        readBindPivot(bind,legR,legPivotR);readBindPivot(bind,legL,legPivotL);
        readBindPivot(bind,kneeR,kneePivotR);readBindPivot(bind,kneeL,kneePivotL);
    }

    int shoulderJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_ARM?shoulderR:(role==NativeJbraSkinContext.PartRole.LEFT_ARM?shoulderL:-1);}
    int upperArmJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_ARM?armR:(role==NativeJbraSkinContext.PartRole.LEFT_ARM?armL:-1);}
    int handJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_ARM?handR:(role==NativeJbraSkinContext.PartRole.LEFT_ARM?handL:-1);}
    int elbowJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_ARM?elbowR:(role==NativeJbraSkinContext.PartRole.LEFT_ARM?elbowL:-1);}

    void upperArmBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_ARM?armPivotR:armPivotL,out);}
    void handBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_ARM?handPivotR:handPivotL,out);}
    void elbowBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_ARM?elbowPivotR:elbowPivotL,out);}

    int thighJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_LEG?thighR:(role==NativeJbraSkinContext.PartRole.LEFT_LEG?thighL:-1);}
    int lowerLegJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_LEG?legR:(role==NativeJbraSkinContext.PartRole.LEFT_LEG?legL:-1);}
    int kneeJoint(NativeJbraSkinContext.PartRole role){return role==NativeJbraSkinContext.PartRole.RIGHT_LEG?kneeR:(role==NativeJbraSkinContext.PartRole.LEFT_LEG?kneeL:-1);}
    void thighBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_LEG?thighPivotR:thighPivotL,out);}
    void lowerLegBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_LEG?legPivotR:legPivotL,out);}
    void kneeBindPivot(NativeJbraSkinContext.PartRole role,float[] out){copy(role==NativeJbraSkinContext.PartRole.RIGHT_LEG?kneePivotR:kneePivotL,out);}

    private static void copy(float[] a,float[] o){o[0]=a[0];o[1]=a[1];o[2]=a[2];}
    private static float[][] buildBindGlobal(SkeletonMesh mesh) {
        float[][] g=new float[mesh.jointCount][16];
        for(int i=0;i<mesh.jointCount;i++){
            if(mesh.parent[i]<0)Mat4.copy(mesh.bindLocal[i],g[i]);else Mat4.mul(g[mesh.parent[i]],mesh.bindLocal[i],g[i]);
        }
        return g;
    }
    private static void readBindPivot(float[][] g,int joint,float[] out) {
        if(joint<0||joint>=g.length){out[0]=out[1]=out[2]=0;return;}
        out[0]=g[joint][3];out[1]=g[joint][7];out[2]=g[joint][11];
    }

    void weights(NativeJbraSkinContext.PartRole role,float epicY,float epicZ,float[] out) {
        Arrays.fill(out,0.0F);
        switch(role) {
            case TORSO: torsoWeights(epicY,out); break;
            case RIGHT_ARM: legacyArmWeights(epicY,epicZ,armR,handR,elbowR,out); break;
            case LEFT_ARM: legacyArmWeights(epicY,epicZ,armL,handL,elbowL,out); break;
            case RIGHT_LEG: legacyLegWeights(epicY,epicZ,thighR,legR,kneeR,out); break;
            case LEFT_LEG: legacyLegWeights(epicY,epicZ,thighL,legL,kneeL,out); break;
            default: break;
        }
    }

    private void torsoWeights(float y,float[] out) {
        float chestWeight;
        if(y<=.750F) chestWeight=0.0F;
        else if(y<=.875F) chestWeight=lerp(0.0F,.25F,smooth((y-.750F)/.125F));
        else if(y<=1.000F) chestWeight=.25F;
        else if(y<=1.125F) chestWeight=lerp(.25F,.50F,smooth((y-1.000F)/.125F));
        else if(y<=1.250F) chestWeight=lerp(.50F,.74F,smooth((y-1.125F)/.125F));
        else if(y<=1.375F) chestWeight=lerp(.74F,.77F,smooth((y-1.250F)/.125F));
        else if(y<1.500F) chestWeight=lerp(.77F,1.0F,smooth((y-1.375F)/.125F));
        else chestWeight=1.0F;
        put(out,torso,1.0F-chestWeight);put(out,chest,chestWeight);
    }

    /** Source biped_old: rigid Arm above 1.125, rigid Hand below, Elbow only at the
     * extremely narrow forward seam. The small blend band prevents cracks after the
     * DBC cube is subdivided while remaining far closer to source weights than 0.20. */
    private static void legacyArmWeights(float y,float z,int upper,int lower,int elbow,float[] out) {
        final float c=1.125F,band=.004F;
        if(y>=c+band){put(out,upper,1);return;}
        if(y<=c-band){put(out,lower,1);return;}
        float t=smooth((c+band-y)/(band*2));
        float seam=(1.0F-Math.abs(y-c)/band)*(z>=0.0F?.90F:0.0F);
        seam=clamp01(seam);
        put(out,elbow,seam);
        float r=1.0F-seam;put(out,upper,r*(1.0F-t));put(out,lower,r*t);
    }

    /**
     * Exact legacy biped knee semantics. biped_old.dat has duplicated rings around
     * y~=0.375: the ring above is rigid Thigh, the ring below rigid Leg, while the
     * middle front (-Z) edge belongs to Knee and the back edge remains Thigh.
     * Intermediate Z on a side face linearly blends Knee->Thigh, which is equivalent
     * to the source triangle interpolation between those two authored seam vertices.
     */
    private static void legacyLegWeights(float y,float z,int upper,int lower,int knee,float[] out) {
        final float kneeY=.375045F;
        final float tol=.00035F;
        if(y>kneeY+tol){put(out,upper,1.0F);return;}
        if(y<kneeY-tol){put(out,lower,1.0F);return;}
        // Source old-biped leg depth is +/-0.125 block. Clamp custom JBRA geometry
        // outside that envelope to the same front/back ownership rather than making
        // up a new seam rule.
        float kneeWeight=clamp01((.125F-z)/.25F);
        put(out,knee,kneeWeight);
        put(out,upper,1.0F-kneeWeight);
    }

    private static void put(float[] out,int joint,float value){if(joint>=0&&joint<out.length&&value>0.00001F)out[joint]=value;}
    private static float smooth(float x){x=clamp01(x);return x*x*(3.0F-2.0F*x);}
    private static float clamp01(float x){return x<0?0:(x>1?1:x);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static int index(SkeletonMesh mesh,String name){for(int i=0;i<mesh.jointCount;i++)if(name.equals(mesh.jointName[i]))return i;return -1;}
}
