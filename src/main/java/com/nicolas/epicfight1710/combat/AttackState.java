package com.nicolas.epicfight1710.combat;

/** Snapshot of the Epic-style entity state generated from an action timeline. */
public final class AttackState {
    public final boolean attacking;
    public final boolean canBasicAttack;
    public final boolean inaction;
    public final boolean movementLocked;
    public final boolean turningLocked;

    AttackState(boolean attacking,boolean canBasicAttack,boolean inaction,boolean movementLocked,boolean turningLocked){
        this.attacking=attacking;this.canBasicAttack=canBasicAttack;this.inaction=inaction;
        this.movementLocked=movementLocked;this.turningLocked=turningLocked;
    }
}
