package com.nicolas.epicfight1710.combat;

/**
 * Compact 1.7.10 counterpart of Epic Fight's StateSpectrum/EntityState contract.
 * Contact determines ATTACKING, recovery opens CAN_BASIC_ATTACK, and lock flags
 * expire from the same elapsed animation clock used by rendering/collision.
 */
public final class AttackStateSpectrum {
    public final float attackStart,attackEnd,canBasicAttackAt,canBasicAttackEnd,inactionEnd,movementLockEnd,turningLockEnd;

    public AttackStateSpectrum(float attackStart,float attackEnd,float canBasicAttackAt,float canBasicAttackEnd,
                               float inactionEnd,float movementLockEnd,float turningLockEnd){
        this.attackStart=attackStart;this.attackEnd=attackEnd;this.canBasicAttackAt=canBasicAttackAt;this.canBasicAttackEnd=canBasicAttackEnd;
        this.inactionEnd=inactionEnd;this.movementLockEnd=movementLockEnd;this.turningLockEnd=turningLockEnd;
    }

    public static AttackStateSpectrum fromPhase(AttackPhase p){
        if(p==null)return new AttackStateSpectrum(Float.MAX_VALUE,-Float.MAX_VALUE,0,Float.MAX_VALUE,0,0,0);
        return new AttackStateSpectrum(p.startup,p.activeEnd,p.chainOpen,p.chainClose,p.chainOpen,p.chainOpen,p.activeEnd);
    }

    public AttackState stateAt(float t){
        return new AttackState(t>=attackStart&&t<=attackEnd,
                t>=canBasicAttackAt&&t<=canBasicAttackEnd,
                t<inactionEnd,
                t<movementLockEnd,
                t<turningLockEnd);
    }
}
