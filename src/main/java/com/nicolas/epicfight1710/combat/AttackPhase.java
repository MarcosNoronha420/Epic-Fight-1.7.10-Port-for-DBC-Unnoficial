package com.nicolas.epicfight1710.combat;

/** Startup/active/recovery windows for an Epic Fight-style action. */
public final class AttackPhase {
    public final float startup;
    public final float activeEnd;
    public final float chainOpen;
    public final float chainClose;
    public final float range;
    public final float minFacingDot;

    public AttackPhase(float startup,float activeEnd,float chainOpen,float chainClose,float range,float minFacingDot){
        this.startup=startup;this.activeEnd=activeEnd;this.chainOpen=chainOpen;this.chainClose=chainClose;this.range=range;this.minFacingDot=minFacingDot;
    }
    public boolean active(float t){return t>=startup&&t<=activeEnd;}

    /**
     * True when the animation-player interval intersects this authored hit window.
     * Epic Fight 20.9.5 collision sampling receives both previous and current elapsed
     * time; checking only the current sample can tunnel across a short contact window
     * when AttackAnimation#getPlaySpeed is greater than 1.
     */
    public boolean activeBetween(float previous,float current){
        float from=previous,to=current;
        if(to<from){float swap=from;from=to;to=swap;}
        return to>=startup&&from<=activeEnd;
    }
    public boolean chainable(float t){return t>=chainOpen&&t<=chainClose;}
}
