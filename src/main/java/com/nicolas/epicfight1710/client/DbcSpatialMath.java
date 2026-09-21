package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;

/** Pure LOCAL spatial operators extracted from RC2 renderer code. No entity,
 * camera, GL, mutable model or render cache. This is NOT a native body-to-world
 * provider: that requires an authoritative scale/pivot/presentation descriptor.
 * Matrices are row-major, column vectors; angles below are radians.
 */
public final class DbcSpatialMath {
    private DbcSpatialMath() {}
    private static final float[] BASIS={-1,0,0,0, 0,-1,0,1.5F, 0,0,1,0, 0,0,0,1};

    // Exact involution used for both vertex compilation and emission. 1.5 is
    // the existing model origin, not a hand/tool offset or a body scale.
    public static float basisX(float x){return -x;}
    public static float basisY(float y){return 1.5F-y;}
    public static void convertPoint(float x,float y,float z,float[] out){
        out[0]=basisX(x);out[1]=basisY(y);out[2]=z;
    }

    /** Existing Tool mount frame convention: [R*A3*R, R*t+q].
     * Converts an Epic GLOBAL joint frame, NOT a skinning deformation. The right
     * R changes the local axis convention; this is not simply C*A or C*A*C.
     * Does NOT add item correction, legacy hand cancellation or socket deltas.
     * out may alias epic (each element is independent).
     */
    public static void jointFrameToModel(float[] epic,float[] out){
        out[0]= epic[0]; out[1]= epic[1]; out[2]=-epic[2]; out[3]=-epic[3];
        out[4]= epic[4]; out[5]= epic[5]; out[6]=-epic[6]; out[7]=1.5F-epic[7];
        out[8]=-epic[8]; out[9]=-epic[9]; out[10]=epic[10]; out[11]=epic[11];
        out[12]=out[13]=out[14]=0.0F;out[15]=1.0F;
    }

    /** Existing native-head parent operation C*D*C^-1, C^-1=C. This accepts a
     * SKINNING deformation, never a global joint origin. Scratch must be distinct
     * from input/output; operation order matches the old renderer bit for bit.
     */
    public static void deformationToModel(float[] skin,float[] scratch,float[] out){
        Mat4.mul(BASIS,skin,scratch);Mat4.mul(scratch,BASIS,out);
    }

    /** Existing ModelRenderer tree bind transform: T * Rz * Ry * Rx.
     * Translation inputs already include offset + rotationPoint * model scale.
     * Root animation exclusions and neutral pivot selection remain with caller.
     */
    public static float[] nodeTransform(float tx,float ty,float tz,float rx,float ry,float rz){
        float[] t=new float[16];Mat4.identity(t);t[3]=tx;t[7]=ty;t[11]=tz;
        if(rz!=0.0F)t=mulNew(t,rotationZ(rz));
        if(ry!=0.0F)t=mulNew(t,rotationY(ry));
        if(rx!=0.0F)t=mulNew(t,rotationX(rx));
        return t;
    }
    private static float[] mulNew(float[] a,float[] b){float[] o=new float[16];Mat4.mul(a,b,o);return o;}
    private static float[] rotationX(float a){float[] m=new float[16];Mat4.identity(m);float c=(float)Math.cos(a),s=(float)Math.sin(a);m[5]=c;m[6]=-s;m[9]=s;m[10]=c;return m;}
    private static float[] rotationY(float a){float[] m=new float[16];Mat4.identity(m);float c=(float)Math.cos(a),s=(float)Math.sin(a);m[0]=c;m[2]=s;m[8]=-s;m[10]=c;return m;}
    private static float[] rotationZ(float a){float[] m=new float[16];Mat4.identity(m);float c=(float)Math.cos(a),s=(float)Math.sin(a);m[0]=c;m[1]=-s;m[4]=s;m[5]=c;return m;}
}
