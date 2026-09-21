package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;

/**
 * Pure retarget-space corrections used only to reconcile Epic Fight's biped bind
 * pivots with JBRA/JRMCore's different anatomical anchors. These operations do not
 * author animation: they preserve the source rotation and only change the pivot or
 * reconnect a socket that would otherwise split because the two rigs have different
 * bind geometry.
 */
public final class DbcRetargetMath {
    private DbcRetargetMath() {}

    /**
     * Re-express an already-authored skin transform around targetPivot instead of
     * sourcePivot without changing its 3x3 rotation. out may alias skin.
     *
     * If d = target-source, t' = t + d - R*d.
     */
    public static void pivotCorrect(float[] skin,
                             float sourceX,float sourceY,float sourceZ,
                             float targetX,float targetY,float targetZ,
                             float[] out) {
        if(out!=skin)System.arraycopy(skin,0,out,0,16);
        float dx=targetX-sourceX,dy=targetY-sourceY,dz=targetZ-sourceZ;
        float rx=skin[0]*dx+skin[1]*dy+skin[2]*dz;
        float ry=skin[4]*dx+skin[5]*dy+skin[6]*dz;
        float rz=skin[8]*dx+skin[9]*dy+skin[10]*dz;
        out[3]=skin[3]+dx-rx;
        out[7]=skin[7]+dy-ry;
        out[11]=skin[11]+dz-rz;
    }

    /**
     * Compute the translation needed to move a child bind socket to the same place
     * the parent skin transform carries that socket. The vector is magnitude-capped
     * because this is a bind-space compatibility correction, never root motion.
     */
    /**
     * Preserve childSkin's authored rotation but translate it so targetBindPivot is
     * attached to the same targetBindPivot carried by parentSkin. This is the skin-
     * matrix equivalent of retargeting a hierarchy bind translation; it fixes the
     * Shoulder->Arm->Hand chain without altering any Epic quaternion.
     */
    public static void socketCorrectMatrix(float[] parentSkin,float[] childSkin,
                                           float targetX,float targetY,float targetZ,
                                           float maxDistance,float[] out) {
        if(out!=childSkin)System.arraycopy(childSkin,0,out,0,16);
        float ax=parentSkin[0]*targetX+parentSkin[1]*targetY+parentSkin[2]*targetZ+parentSkin[3];
        float ay=parentSkin[4]*targetX+parentSkin[5]*targetY+parentSkin[6]*targetZ+parentSkin[7];
        float az=parentSkin[8]*targetX+parentSkin[9]*targetY+parentSkin[10]*targetZ+parentSkin[11];
        float bx=childSkin[0]*targetX+childSkin[1]*targetY+childSkin[2]*targetZ+childSkin[3];
        float by=childSkin[4]*targetX+childSkin[5]*targetY+childSkin[6]*targetZ+childSkin[7];
        float bz=childSkin[8]*targetX+childSkin[9]*targetY+childSkin[10]*targetZ+childSkin[11];
        float dx=ax-bx,dy=ay-by,dz=az-bz;
        float len=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(len>maxDistance&&len>.000001F){float k=maxDistance/len;dx*=k;dy*=k;dz*=k;}
        out[3]=childSkin[3]+dx;out[7]=childSkin[7]+dy;out[11]=childSkin[11]+dz;
    }

    public static void socketCorrection(float[] parentSkin,
                                 float bindX,float bindY,float bindZ,
                                 float actualX,float actualY,float actualZ,
                                 float maxDistance,float[] out3) {
        Mat4.transformPoint(parentSkin,bindX,bindY,bindZ,out3,0);
        float dx=out3[0]-actualX,dy=out3[1]-actualY,dz=out3[2]-actualZ;
        float len=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(len>maxDistance&&len>.000001F){float k=maxDistance/len;dx*=k;dy*=k;dz*=k;}
        out3[0]=dx;out3[1]=dy;out3[2]=dz;
    }
}
