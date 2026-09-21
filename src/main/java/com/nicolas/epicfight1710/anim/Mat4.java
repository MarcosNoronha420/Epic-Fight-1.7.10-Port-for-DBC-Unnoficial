package com.nicolas.epicfight1710.anim;

public final class Mat4 {
    private Mat4() {}

    public static void identity(float[] o) {
        for (int i = 0; i < 16; i++) o[i] = 0.0F;
        o[0] = o[5] = o[10] = o[15] = 1.0F;
    }

    public static void copy(float[] a, float[] o) {
        System.arraycopy(a, 0, o, 0, 16);
    }

    // Row-major matrix multiplication, column-vector convention: out = a * b.
    public static void mul(float[] a, float[] b, float[] o) {
        float[] r = o == a || o == b ? new float[16] : o;
        for (int row = 0; row < 4; row++) {
            int rr = row * 4;
            for (int col = 0; col < 4; col++) {
                r[rr + col] = a[rr] * b[col]
                        + a[rr + 1] * b[4 + col]
                        + a[rr + 2] * b[8 + col]
                        + a[rr + 3] * b[12 + col];
            }
        }
        if (r != o) System.arraycopy(r, 0, o, 0, 16);
    }

    public static void fromTRS(float[] trs, float[] o) {
        float tx = trs[0], ty = trs[1], tz = trs[2];
        float x = trs[3], y = trs[4], z = trs[5], w = trs[6];
        float sx = trs[7], sy = trs[8], sz = trs[9];

        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;

        // Rotation columns scaled independently.
        o[0] = (1.0F - 2.0F * (yy + zz)) * sx;
        o[1] = (2.0F * (xy - wz)) * sy;
        o[2] = (2.0F * (xz + wy)) * sz;
        o[3] = tx;

        o[4] = (2.0F * (xy + wz)) * sx;
        o[5] = (1.0F - 2.0F * (xx + zz)) * sy;
        o[6] = (2.0F * (yz - wx)) * sz;
        o[7] = ty;

        o[8] = (2.0F * (xz - wy)) * sx;
        o[9] = (2.0F * (yz + wx)) * sy;
        o[10] = (1.0F - 2.0F * (xx + yy)) * sz;
        o[11] = tz;

        o[12] = o[13] = o[14] = 0.0F;
        o[15] = 1.0F;
    }

    public static void transformPoint(float[] m, float x, float y, float z, float[] out, int off) {
        out[off] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[off + 1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[off + 2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    public static void transformVector(float[] m, float x, float y, float z, float[] out, int off) {
        out[off] = m[0] * x + m[1] * y + m[2] * z;
        out[off + 1] = m[4] * x + m[5] * y + m[6] * z;
        out[off + 2] = m[8] * x + m[9] * y + m[10] * z;
    }
}
