package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.SkeletonMesh;

/** Per-joint layer weights, mirroring Epic Fight's joint-mask concept. */
public final class JointMask {
    private final float[] weight;
    private JointMask(float[] w){this.weight=w;}
    public float weight(int joint){return joint>=0&&joint<weight.length?weight[joint]:0.0F;}
    public int size(){return weight.length;}

    public static JointMask full(SkeletonMesh mesh){
        float[] w=new float[mesh.jointCount];for(int i=0;i<w.length;i++)w[i]=1.0F;return new JointMask(w);
    }

    public static JointMask none(SkeletonMesh mesh){return new JointMask(new float[mesh.jointCount]);}

    /**
     * Exact joint set used by Epic Fight 20.9.5 BASIC_ATTACK_MASK:
     * Root + biped upper-body attack chain.  Lower-body locomotion remains on the
     * base layer, matching BasicAttackAnimation instead of our old procedural mask.
     */
    public static JointMask basicAttack(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];
        for(int i=0;i<w.length;i++) {
            String n=mesh.jointName[i];
            if("Root".equals(n)||"Torso".equals(n)||"Chest".equals(n)||"Head".equals(n)
                    ||"Shoulder_R".equals(n)||"Arm_R".equals(n)||"Hand_R".equals(n)||"Elbow_R".equals(n)||"Tool_R".equals(n)
                    ||"Shoulder_L".equals(n)||"Arm_L".equals(n)||"Hand_L".equals(n)||"Elbow_L".equals(n)||"Tool_L".equals(n)) w[i]=1.0F;
        }
        return new JointMask(w);
    }

    public static JointMask upperBody(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];
        for(int i=0;i<w.length;i++) {
            String n=mesh.jointName[i];
            if("Torso".equals(n)||"Chest".equals(n)||"Head".equals(n)||n.indexOf("Arm_")==0||n.indexOf("Hand_")==0) w[i]=1.0F;
        }
        propagateChildren(mesh,w);
        return new JointMask(w);
    }

    public static JointMask arms(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];
        for(int i=0;i<w.length;i++) {
            String n=mesh.jointName[i];
            if(n.indexOf("Arm_")==0||n.indexOf("Hand_")==0)w[i]=1.0F;
        }
        propagateChildren(mesh,w);
        return new JointMask(w);
    }


    /** Softer landing: legs absorb impact, torso follows partially, arms/head stay free. */
    public static JointMask landingBody(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];
        for(int i=0;i<w.length;i++) {
            String n=mesh.jointName[i];
            if("Torso".equals(n))w[i]=.42F;
            else if("Chest".equals(n))w[i]=.22F;
            else if(n.indexOf("Thigh_")==0||n.indexOf("Leg_")==0||n.indexOf("Knee_")==0||n.indexOf("Foot_")==0)w[i]=1.0F;
        }
        return new JointMask(w);
    }

    public static JointMask head(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];
        for(int i=0;i<w.length;i++)if("Head".equals(mesh.jointName[i]))w[i]=1.0F;
        propagateChildren(mesh,w);return new JointMask(w);
    }

    public static JointMask withoutRootTranslation(SkeletonMesh mesh) {
        float[] w=new float[mesh.jointCount];for(int i=0;i<w.length;i++)w[i]=1.0F;
        return new JointMask(w);
    }

    private static void propagateChildren(SkeletonMesh mesh,float[] w) {
        boolean changed=true;
        while(changed){changed=false;for(int i=0;i<w.length;i++){int p=mesh.parent[i];if(p>=0&&w[p]>0&&w[i]==0){String n=mesh.jointName[i];if(!"Root".equals(n)){w[i]=w[p];changed=true;}}}}
    }
}
