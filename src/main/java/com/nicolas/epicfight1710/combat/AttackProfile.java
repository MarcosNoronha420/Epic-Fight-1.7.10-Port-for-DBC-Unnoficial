package com.nicolas.epicfight1710.combat;

/** Data for one source-authored attack action, separated from input/state code. */
public final class AttackProfile {
    public final String clip;
    /** Phase used for BasicAttack-style chaining/recovery gating. */
    public final AttackPhase phase;
    /** Every source hit window. Ordinary attacks contain one; multi-hit skills contain many. */
    public final AttackPhase[] hitPhases;
    public final int nextCombo;
    public final boolean dash;
    public final AttackStateSpectrum stateSpectrum;
    public final ColliderDefinition collider;

    public AttackProfile(String clip,AttackPhase phase,int nextCombo,boolean dash){
        this(clip,phase,new AttackPhase[]{phase},nextCombo,dash,AttackStateSpectrum.fromPhase(phase),null);
    }

    public AttackProfile(String clip,AttackPhase chainPhase,AttackPhase[] hitPhases,int nextCombo,boolean dash){
        this(clip,chainPhase,hitPhases,nextCombo,dash,AttackStateSpectrum.fromPhase(chainPhase),null);
    }

    public AttackProfile(String clip,AttackPhase chainPhase,AttackPhase[] hitPhases,int nextCombo,boolean dash,
                         AttackStateSpectrum stateSpectrum,ColliderDefinition collider){
        this.clip=clip;
        this.phase=chainPhase;
        this.hitPhases=hitPhases==null||hitPhases.length==0?new AttackPhase[]{chainPhase}:hitPhases.clone();
        this.nextCombo=nextCombo;
        this.dash=dash;
        this.stateSpectrum=stateSpectrum==null?AttackStateSpectrum.fromPhase(chainPhase):stateSpectrum;
        this.collider=collider;
    }

    public AttackState stateAt(float elapsed){return stateSpectrum.stateAt(elapsed);}
}
