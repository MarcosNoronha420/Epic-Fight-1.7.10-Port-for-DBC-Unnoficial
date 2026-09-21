package com.nicolas.epicfight1710.anim.runtime;

/** Pure DBC/JBRA ground-locomotion routing. Kept free of Minecraft classes so
 * regression tests can prove WALK never becomes RUN from a position-delta spike
 * while sprint is false. Creative flight is routed exclusively by
 * EpicCreativeFlightPolicy. */
public final class DbcLocomotionPolicy {
    private DbcLocomotionPolicy() {}

    public static LivingMotion ground(boolean sneaking,boolean sprinting,float speed,LivingMotion current) {
        if(sneaking)return LivingMotion.SNEAK;
        if(sprinting)return LivingMotion.RUN;
        // DBC reports bursty position deltas; once WALK is active keep it through
        // tiny near-zero samples so the base layer does not restart every few steps.
        float walkThreshold=current==LivingMotion.WALK?.0012F:.0050F;
        return speed>walkThreshold?LivingMotion.WALK:LivingMotion.IDLE;
    }

}
