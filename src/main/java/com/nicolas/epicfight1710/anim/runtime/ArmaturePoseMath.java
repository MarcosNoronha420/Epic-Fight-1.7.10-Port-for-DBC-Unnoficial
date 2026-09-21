package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;

/** Shared, clock-free armature math. All output buffers belong to the caller. */
public final class ArmaturePoseMath {
    private ArmaturePoseMath() {}
    private static final float[] IDENTITY={0,0,0,0,0,0,1,1,1,1};

    public static void globals(SkeletonMesh mesh,AnimationPose pose,float[][] local,float[][] global,float[] scratch){
        for(int j=0;j<mesh.jointCount;j++){
            Mat4.fromTRS(pose.has(j)?pose.joint(j):IDENTITY,scratch);
            Mat4.mul(mesh.bindLocal[j],scratch,local[j]);
            int p=mesh.parent[j];
            if(p<0)Mat4.copy(local[j],global[j]);else Mat4.mul(global[p],local[j],global[j]);
        }
    }

    /** Skinning is deliberately separate from the joint-origin hierarchy. */
    public static void skinning(SkeletonMesh mesh,float[][] global,float[][] skin){
        for(int j=0;j<mesh.jointCount;j++)Mat4.mul(global[j],mesh.invBindGlobal[j],skin[j]);
    }

    /** Exact RC2 layer sanitization, extracted without camera or renderer access.
     * This is armature-space only: it does not include JBRA sockets/model placement.
     */
    public static void sanitizeDbc(SkeletonMesh mesh,int root,AnimationPose pose,boolean keepRootYaw,boolean keepRootRotation){
        if(pose==null)return;
        for(int j=0;j<mesh.jointCount;j++)if(pose.has(j)){
            float[] t=pose.joint(j);
            String n=mesh.jointName[j];
            // RC2 seam deformation helpers keep authored translations. Removing
            // them opens the bent knee/elbow surface in biped_old.dat (e.g. SNEAK).
            boolean helper="Knee_R".equals(n)||"Knee_L".equals(n)||"Elbow_R".equals(n)||"Elbow_L".equals(n);
            if(!helper)t[0]=t[1]=t[2]=0.0F;
            t[7]=t[8]=t[9]=1.0F;
            normalizeQuaternion(t);
        }
        if(root>=0){
            if(keepRootRotation&&pose.has(root))normalizeQuaternion(pose.joint(root));
            else if(!keepRootYaw||!pose.has(root))pose.setIdentity(root);
            else{
                float[] r=pose.joint(root);
                float x=r[3],y=r[4],z=r[5],w=r[6];
                float yaw=(float)Math.atan2(2.0F*(w*y+x*z),1.0F-2.0F*(y*y+z*z)),half=yaw*.5F;
                r[0]=r[1]=r[2]=0.0F;
                r[3]=0.0F;r[4]=(float)Math.sin(half);r[5]=0.0F;r[6]=(float)Math.cos(half);
                r[7]=r[8]=r[9]=1.0F;
            }
        }
    }

    public static void normalizeQuaternion(float[] t){
        float x=t[3],y=t[4],z=t[5],w=t[6];
        float len2=x*x+y*y+z*z+w*w;
        if(Float.isNaN(len2)||Float.isInfinite(len2)||len2<.000001F){t[3]=t[4]=t[5]=0.0F;t[6]=1.0F;return;}
        float inv=1.0F/(float)Math.sqrt(len2);
        t[3]=x*inv;t[4]=y*inv;t[5]=z*inv;t[6]=w*inv;
    }
}
