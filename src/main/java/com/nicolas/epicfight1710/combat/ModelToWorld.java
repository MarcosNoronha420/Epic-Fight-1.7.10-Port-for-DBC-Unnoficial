package com.nicolas.epicfight1710.combat;

/** Pure affine coordinate contract. Row-major, column vectors:
 * worldJoint = translate(worldOrigin) * modelToPlayer * globalJoint.
 * The caller must supply the authoritative model basis, scale and pivot. No
 * camera, yaw guess, item-display correction, GL state or implied DBC offset.
 */
public final class ModelToWorld {
    private final double[] matrix;

    public ModelToWorld(double worldX,double worldY,double worldZ,float[] modelToPlayer){
        if(modelToPlayer==null||modelToPlayer.length!=16)throw new IllegalArgumentException("Affine matrix required");
        if(!finite(worldX)||!finite(worldY)||!finite(worldZ))throw new IllegalArgumentException("World origin");
        matrix=new double[16];
        for(int i=0;i<16;i++){
            if(!finite(modelToPlayer[i]))throw new IllegalArgumentException("Non-finite model matrix");
            matrix[i]=modelToPlayer[i];
        }
        if(matrix[12]!=0||matrix[13]!=0||matrix[14]!=0||matrix[15]!=1)
            throw new IllegalArgumentException("Perspective matrices are not model transforms");
        double det=matrix[0]*(matrix[5]*matrix[10]-matrix[6]*matrix[9])
                -matrix[1]*(matrix[4]*matrix[10]-matrix[6]*matrix[8])
                +matrix[2]*(matrix[4]*matrix[9]-matrix[5]*matrix[8]);
        if(Math.abs(det)<1e-12||!finite(det))throw new IllegalArgumentException("Singular model transform");
        matrix[3]+=worldX;matrix[7]+=worldY;matrix[11]+=worldZ;
    }

    public void transformJoint(float[] globalJoint,double[] out){
        if(globalJoint==null||globalJoint.length<16||out==null||out.length<16)
            throw new IllegalArgumentException("Matrix buffers");
        for(float v:globalJoint)if(!finite(v))throw new IllegalArgumentException("Non-finite joint");
        for(int r=0;r<4;r++)for(int c=0;c<4;c++){
            double sum=0;
            for(int k=0;k<4;k++)sum+=matrix[r*4+k]*globalJoint[k*4+c];
            out[r*4+c]=sum;
        }
    }
    private static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
}
