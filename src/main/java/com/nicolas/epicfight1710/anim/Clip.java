package com.nicolas.epicfight1710.anim;

public final class Clip {
    public final String name;
    public final float duration;
    private final Track[] tracks;

    public Clip(String name, float duration, Track[] tracksByJoint) {
        this.name = name;
        this.duration = duration;
        this.tracks = tracksByJoint;
    }

    public boolean hasTrack(int joint) {
        return joint >= 0 && joint < tracks.length && tracks[joint] != null;
    }

    public void sampleTRS(int joint, float time, boolean loop, float[] out) {
        Track t = joint >= 0 && joint < tracks.length ? tracks[joint] : null;
        if (t == null) {
            out[0] = out[1] = out[2] = 0.0F;
            out[3] = out[4] = out[5] = 0.0F; out[6] = 1.0F;
            out[7] = out[8] = out[9] = 1.0F;
            return;
        }
        float tt = time;
        if (loop && duration > 0.0001F) {
            tt %= duration;
            if (tt < 0.0F) tt += duration;
        } else {
            if (tt < 0.0F) tt = 0.0F;
            if (tt > duration) tt = duration;
        }
        t.sample(tt, out);
    }

    public static final class Track {
        public final float[] time;
        public final float[][] trs;

        public Track(float[] time, float[][] trs) {
            this.time = time;
            this.trs = trs;
        }

        public void sample(float t, float[] out) {
            int n = time.length;
            if (n == 1 || t <= time[0]) {
                System.arraycopy(trs[0], 0, out, 0, 10);
                return;
            }
            if (t >= time[n - 1]) {
                System.arraycopy(trs[n - 1], 0, out, 0, 10);
                return;
            }
            // Converted Epic clips can contain many keys per joint. The old linear
            // scan restarted at key 1 for every joint/layer/display frame, making
            // animation sampling cost grow with both key count and FPS. Binary search
            // selects the exact same bracketing keys without changing interpolation.
            int lo=0,hi=n-1;
            while(hi-lo>1){
                int mid=(lo+hi)>>>1;
                if(time[mid]<t)lo=mid;else hi=mid;
            }
            float span = time[hi] - time[lo];
            float a = span <= 0.000001F ? 0.0F : (t - time[lo]) / span;
            blendTRS(trs[lo], trs[hi], a, out);
        }
    }

    public static void blendTRS(float[] a, float[] b, float t, float[] out) {
        if (t <= 0.0F) { System.arraycopy(a, 0, out, 0, 10); return; }
        if (t >= 1.0F) { System.arraycopy(b, 0, out, 0, 10); return; }
        out[0] = a[0] + (b[0] - a[0]) * t;
        out[1] = a[1] + (b[1] - a[1]) * t;
        out[2] = a[2] + (b[2] - a[2]) * t;
        out[7] = a[7] + (b[7] - a[7]) * t;
        out[8] = a[8] + (b[8] - a[8]) * t;
        out[9] = a[9] + (b[9] - a[9]) * t;
        slerp(a, 3, b, 3, t, out, 3);
    }

    private static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] o, int oo) {
        float ax=a[ao], ay=a[ao+1], az=a[ao+2], aw=a[ao+3];
        float bx=b[bo], by=b[bo+1], bz=b[bo+2], bw=b[bo+3];
        float dot=ax*bx+ay*by+az*bz+aw*bw;
        if (dot < 0.0F) { dot=-dot; bx=-bx; by=-by; bz=-bz; bw=-bw; }
        float x,y,z,w;
        if (dot > 0.9995F) {
            x=ax+(bx-ax)*t; y=ay+(by-ay)*t; z=az+(bz-az)*t; w=aw+(bw-aw)*t;
        } else {
            if (dot > 1.0F) dot=1.0F;
            float theta=(float)Math.acos(dot);
            float sin=(float)Math.sin(theta);
            float wa=(float)Math.sin((1.0F-t)*theta)/sin;
            float wb=(float)Math.sin(t*theta)/sin;
            x=ax*wa+bx*wb; y=ay*wa+by*wb; z=az*wa+bz*wb; w=aw*wa+bw*wb;
        }
        float inv=1.0F/(float)Math.sqrt(x*x+y*y+z*z+w*w);
        o[oo]=x*inv; o[oo+1]=y*inv; o[oo+2]=z*inv; o[oo+3]=w*inv;
    }
}
