package com.nicolas.epicfight1710.combat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** One Epic Fight Style inside a reusable Weapon Type. */
public final class WeaponStyleDefinition {
    public final EpicCombatStyle style;
    private final AttackProfile[] normalCombo;
    public final AttackProfile dashAttack;
    public final AttackProfile mountAttack;
    public final String airAttack;
    public final String guardHitClip;
    public final String innateSkill;
    public final ColliderDefinition collider;
    private final Map<String,String> livingMotions;
    private final StyleCondition[] conditions;

    public WeaponStyleDefinition(EpicCombatStyle style,AttackProfile[] combo,AttackProfile dashAttack,
                                 AttackProfile mountAttack,String airAttack,String guardHitClip,String innateSkill,
                                 ColliderDefinition collider,Map<String,String> livingMotions,StyleCondition[] conditions){
        this.style=style==null?EpicCombatStyle.COMMON:style;
        this.normalCombo=combo==null?new AttackProfile[0]:combo.clone();
        this.dashAttack=dashAttack;this.mountAttack=mountAttack;this.airAttack=airAttack;
        this.guardHitClip=guardHitClip;this.innateSkill=innateSkill;this.collider=collider;
        this.livingMotions=livingMotions==null?Collections.<String,String>emptyMap():Collections.unmodifiableMap(new HashMap<String,String>(livingMotions));
        this.conditions=conditions==null?new StyleCondition[0]:conditions.clone();
    }
    public int comboLength(){return normalCombo.length;}
    public AttackProfile combo(int counter){if(normalCombo.length==0)return null;int i=counter%normalCombo.length;if(i<0)i+=normalCombo.length;return normalCombo[i];}
    public String livingMotion(String motion){return livingMotions.get(motion);}
    public boolean matches(Object player){for(StyleCondition c:conditions)if(c!=null&&!c.matches(player))return false;return true;}
    public boolean conditional(){return conditions.length>0;}
}
