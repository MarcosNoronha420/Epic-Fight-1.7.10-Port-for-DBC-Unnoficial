package com.nicolas.epicfight1710.combat;

/** Immutable capability + active Style snapshot for one combat decision. */
public final class ResolvedWeaponCapability {
    public final WeaponCapability1710 capability;
    public final WeaponStyleDefinition style;
    public ResolvedWeaponCapability(WeaponCapability1710 capability,WeaponStyleDefinition style){this.capability=capability;this.style=style;}
    public String id(){return capability.id;}
    public EpicCombatStyle combatStyle(){return style==null?capability.defaultStyle:style.style;}
    public boolean armed(){return capability.armed();}
    public boolean isFist(){return capability.isFist();}
    public boolean sameTypeAndStyle(ResolvedWeaponCapability o){return o!=null&&capability.id.equals(o.capability.id)&&combatStyle()==o.combatStyle();}
    public AttackProfile combo(int counter,boolean relentless){return isFist()?FistMoveset.combo(counter,relentless):(style==null?null:style.combo(counter));}
    public AttackProfile dash(){return isFist()?FistMoveset.dash():(style==null?null:style.dashAttack);}
    public AttackProfile mount(){return style==null?null:style.mountAttack;}
    public String airClip(){return isFist()?"fist_airslash":(style==null?null:style.airAttack);}
    public String livingMotion(String key){return style==null?null:style.livingMotion(key);}
    public String guardHitClip(){return style==null?null:style.guardHitClip;}
    @Override public String toString(){return id()+"/"+combatStyle();}
}
