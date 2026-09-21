package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;

/**
 * Cached anatomical view of the effective Epic->JBRA deformation rig.
 *
 * JBRA body vertices are weighted mainly to Torso/Chest, Arm/Hand and Thigh/Leg.
 * Names such as Elbow/Knee exist in the imported Epic armature but are not primary
 * deformers for the current DBC mesh profile. This class therefore exposes the bones
 * that actually move geometry and calibrates the sign of a local X bend from the bind
 * pose instead of assuming that "negative X means forward".
 */
public final class DbcAnatomicalRig {
    // JbraWeightedPartRenderer keeps the source Z axis when compiling native cuboids.
    // In the vanilla/JBRA biped convention the visual forward direction is -Z.
    private static final float MODEL_FORWARD_Z=-1.0F;

    public final int root,torso,chest,head;
    public final int shoulderR,armR,handR,shoulderL,armL,handL;
    public final int thighR,legR,thighL,legL;
    public final float armForwardSignR,armForwardSignL;
    public final float legForwardSignR,legForwardSignL;

    private final SkeletonMesh mesh;

    public DbcAnatomicalRig(SkeletonMesh mesh) {
        this.mesh=mesh;
        root=index("Root");torso=index("Torso");chest=index("Chest");head=index("Head");
        shoulderR=index("Shoulder_R");armR=index("Arm_R");handR=index("Hand_R");
        shoulderL=index("Shoulder_L");armL=index("Arm_L");handL=index("Hand_L");
        thighR=index("Thigh_R");legR=index("Leg_R");thighL=index("Thigh_L");legL=index("Leg_L");
        armForwardSignR=calibrateForwardX(armR,handR);
        armForwardSignL=calibrateForwardX(armL,handL);
        legForwardSignR=calibrateForwardX(thighR,legR);
        legForwardSignL=calibrateForwardX(thighL,legL);
    }

    public float armForward(boolean left,float degrees) {
        return degrees*(left?armForwardSignL:armForwardSignR);
    }

    public float legForward(boolean left,float degrees) {
        return degrees*(left?legForwardSignL:legForwardSignR);
    }

    private float calibrateForwardX(int joint,int child) {
        if(joint<0||child<0)return 1.0F;
        float bindZ=globalZ(child,-1,0.0F);
        float plusZ=globalZ(child,joint,10.0F);
        float dz=plusZ-bindZ;
        if(Math.abs(dz)<.000001F)return 1.0F;
        // If +X moves the child in model-forward (-Z), +X is the forward bend.
        return dz*MODEL_FORWARD_Z>0.0F?1.0F:-1.0F;
    }

    private float globalZ(int target,int deltaJoint,float xDegrees) {
        int n=mesh.jointCount;
        float[][] global=new float[n][16];
        float[] local=new float[16];
        float[] delta=new float[16];
        float[] trs={0,0,0,0,0,0,1,1,1,1};
        if(deltaJoint>=0) {
            float h=(float)Math.toRadians(xDegrees)*.5F;
            trs[3]=(float)Math.sin(h);trs[6]=(float)Math.cos(h);
            Mat4.fromTRS(trs,delta);
        }
        for(int i=0;i<n;i++) {
            if(i==deltaJoint)Mat4.mul(mesh.bindLocal[i],delta,local);else Mat4.copy(mesh.bindLocal[i],local);
            int p=mesh.parent[i];
            if(p<0)Mat4.copy(local,global[i]);else Mat4.mul(global[p],local,global[i]);
        }
        return global[target][11];
    }

    private int index(String name) {
        if(mesh==null||name==null)return -1;
        for(int i=0;i<mesh.jointCount;i++)if(name.equals(mesh.jointName[i]))return i;
        return -1;
    }
}
