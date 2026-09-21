package com.nicolas.epicfight1710.anim.runtime;

/**
 * Pure 1.7.10 port of Epic Fight 20.9.5's creative-flight selection/correction rules.
 * DBC owns physics and supplies velocity; this class owns only the Epic visual motion.
 */
public final class EpicCreativeFlightPolicy {
    public static final float MOVE_EPS=.01F;

    private EpicCreativeFlightPolicy() {}

    /** Epic's AbstractClientPlayerPatch: moving if horizontal delta exceeds 0.01. */
    public static boolean moving(float vx,float vz) {
        return Math.abs(vx)>MOVE_EPS||Math.abs(vz)>MOVE_EPS;
    }

    /**
     * Exact 20.9.5 split: AbstractClientPlayerPatch uses per-tick position delta to
     * decide idle vs moving, while SelectiveAnimation uses look dot deltaMovement to
     * choose forward/backward once moving.
     */
    public static LivingMotion select(float dx,float dz,float vx,float vy,float vz,float lookX,float lookY,float lookZ) {
        if(!moving(dx,dz))return LivingMotion.CREATIVE_IDLE;
        float dot=lookX*vx+lookY*vy+lookZ*vz;
        return dot<0.0F?LivingMotion.FLY_BACKWARD:LivingMotion.FLY_FORWARD;
    }

    /** Backward-compatible pure helper when position delta and velocity are the same vector. */
    public static LivingMotion select(float vx,float vy,float vz,float lookX,float lookY,float lookZ) {
        return select(vx,vz,vx,vy,vz,lookX,lookY,lookZ);
    }

    /**
     * DBC adapter for Epic's selective creative-flight child. DBC publishes velocity
     * and render-state evidence on slightly different ticks, so the literal sign test
     * can oscillate FORWARD/BACKWARD several times in one second. This keeps Epic's
     * look-dot-velocity rule, but adds only an input hysteresis/dead-band around it.
     */
    public static LivingMotion selectStable(LivingMotion current,float dx,float dz,float vx,float vy,float vz,float lookX,float lookY,float lookZ) {
        float horizontalDelta=(float)Math.sqrt(dx*dx+dz*dz);
        boolean alreadyFlying=current==LivingMotion.FLY_FORWARD||current==LivingMotion.FLY_BACKWARD;
        float moveThreshold=alreadyFlying?.015F:.035F;
        if(horizontalDelta<moveThreshold)return LivingMotion.CREATIVE_IDLE;

        float vl=(float)Math.sqrt(vx*vx+vy*vy+vz*vz);
        float ll=(float)Math.sqrt(lookX*lookX+lookY*lookY+lookZ*lookZ);
        if(vl<1.0E-5F||ll<1.0E-5F)return alreadyFlying?current:LivingMotion.FLY_FORWARD;
        float dot=(lookX*vx+lookY*vy+lookZ*vz)/(vl*ll);
        if(dot<-.18F)return LivingMotion.FLY_BACKWARD;
        if(dot>.18F)return LivingMotion.FLY_FORWARD;
        return alreadyFlying?current:LivingMotion.FLY_FORWARD;
    }

    /** Equivalent to Epic MathUtils.getXRotOfVector, returned in degrees. */
    public static float xRotDeg(float vx,float vy,float vz) {
        double len=Math.sqrt(vx*vx+vy*vy+vz*vz);
        if(len<1.0E-7)return 0.0F;
        double x=vx/len,y=vy/len,z=vz/len;
        return (float)(-Math.atan2(y,Math.sqrt(x*x+z*z))*180.0/Math.PI);
    }

    /** Both Epic flying modifiers use this velocity-pitch scalar (getXRot * 2). */
    public static float pitchDeg(float vx,float vy,float vz) {
        return xRotDeg(vx,vy,vz)*2.0F;
    }

    /**
     * FLYING_CORRECTION (forward)'s horizontal signed roll, in radians, clamped exactly to [-1,1].
     * The denominator intentionally uses full velocity/view lengths like the source.
     */
    public static float forwardRollRad(float vx,float vy,float vz,float lookX,float lookY,float lookZ) {
        double vl=Math.sqrt(vx*vx+vy*vy+vz*vz),ll=Math.sqrt(lookX*lookX+lookY*lookY+lookZ*lookZ);
        if(vl<1.0E-7||ll<1.0E-7)return 0.0F;
        double cos=(vx*lookX+vz*lookZ)/(vl*ll);
        if(cos>1.0)cos=1.0;else if(cos<-1.0)cos=-1.0;
        double cross=vx*lookZ-vz*lookX;
        double sign=cross<0.0?-1.0:(cross>0.0?1.0:0.0);
        double roll=sign*Math.acos(cos);
        if(roll>1.0)roll=1.0;else if(roll<-1.0)roll=-1.0;
        return (float)roll;
    }

    /** @deprecated 1.1.0 mislabeled the forward FLYING_CORRECTION roll helper. */
    @Deprecated
    public static float backwardRollRad(float vx,float vy,float vz,float lookX,float lookY,float lookZ) {
        return forwardRollRad(vx,vy,vz,lookX,lookY,lookZ);
    }
}
