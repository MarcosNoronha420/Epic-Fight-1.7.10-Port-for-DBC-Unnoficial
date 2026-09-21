package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.client.Compat;

/** Maps 1.7.10/JRM movement state to Epic Fight-style LivingMotion values. */
public final class LivingMotionResolver {
    private LivingMotionResolver(){}

    public static LivingMotion resolve(Object player,int airborneTicks) {
        return resolve(player,airborneTicks,Compat.dbcFlying(player));
    }

    /** Same resolver, but lets the live JBRA render pass override DBC flight state. */
    public static LivingMotion resolve(Object player,int airborneTicks,boolean dbcFlying) {
        if(player==null)return LivingMotion.IDLE;
        if(Compat.dead(player))return LivingMotion.DEATH;
        if(Compat.sleeping(player))return LivingMotion.SLEEP;
        if(Compat.riding(player))return LivingMotion.MOUNT;

        // DBC flight is a gameplay state, not merely "has been airborne for a while".
        // Resolve it before generic jump/fall heuristics so take-off enters an Epic
        // flight pose on the first useful frame instead of spending ~0.8s as FALL.
        if(dbcFlying) {
            float speed=Compat.speed(player);
            if(speed>0.025F) {
                float forward=Compat.forwardMotion(player);
                if(forward<-0.008F)return LivingMotion.FLY_BACKWARD;
                return LivingMotion.FLY_FORWARD;
            }
            return LivingMotion.FLY;
        }

        if(Compat.onLadder(player))return LivingMotion.CLIMB;
        if(Compat.inWater(player) && !Compat.onGround(player))return Compat.speed(player)>.018F?LivingMotion.SWIM:LivingMotion.FLOAT;

        float speed=Compat.speed(player),v=Compat.verticalSpeed(player);
        if(Compat.onGround(player)) {
            if(Compat.sneaking(player))return LivingMotion.SNEAK;
            if(Compat.sprinting(player)||speed>0.13F)return LivingMotion.RUN;
            if(speed>0.008F)return LivingMotion.WALK;
            return LivingMotion.IDLE;
        }
        // Non-DBC flight-like movement remains as a compatibility fallback for
        // creative/other mods that don't expose DBC's explicit state.
        if(airborneTicks>16&&Math.abs(v)<0.085F) {
            if(speed>0.035F)return Compat.forwardMotion(player)<-0.01F?LivingMotion.FLY_BACKWARD:LivingMotion.FLY_FORWARD;
            return LivingMotion.FLY;
        }
        return v>0.015F?LivingMotion.JUMP:LivingMotion.FALL;
    }

    public static float speedMultiplier(AnimationDefinition d,Object player) {
        return speedMultiplier(d,player,player==null?0.0F:Compat.speed(player));
    }

    /** DBC/JBRA may supply a filtered movement speed because its position deltas can
     * arrive in alternating small/large bursts even during a constant walk. */
    public static float speedMultiplier(AnimationDefinition d,Object player,float speed) {
        if(d==null||player==null)return 1.0F;
        switch(d.speedMode) {
            // Do not force a high minimum cadence at low movement speed. The old
            // WALK floor (.72x) made the feet cycle quickly while DBC moved only a
            // few hundredths of a block/tick, producing the "treadmill" look.
            case WALK:
                if(speed<.002F)return 0.0F;
                // Slightly longer gait cycle and lower minimum cadence. Together with
                // loop-boundary interpolation this removes the short/restarting walk.
                return clamp(speed/.155F,.10F,1.20F);
            case SNEAK:
                if(speed<.004F)return 0.0F; // hold Epic sneak frame 0 while stationary
                return clamp(speed/.055F,.16F,1.25F);
            case RUN:
                if(speed<.020F)return 0.0F;
                return clamp(speed/.200F,.50F,1.35F);
            case FLY:
                if(speed<.010F)return .45F;
                return clamp(speed/.22F,.45F,1.35F);
            default:return 1.0F;
        }
    }
    private static float clamp(float x,float a,float b){return x<a?a:(x>b?b:x);}
}
