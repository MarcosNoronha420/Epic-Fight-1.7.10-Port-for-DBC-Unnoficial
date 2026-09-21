package com.nicolas.epicfight1710.combat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reusable 1.7.10 Weapon Type modeled after Epic Fight 20.9.5 WeaponCapability.
 * Concrete Item Capability matching lives in WeaponCapabilityRegistry. One Weapon
 * Type can contain multiple Styles, each with its own combo/living motions/collider.
 */
public final class WeaponCapability1710 {
    public static final String MOTION_IDLE="idle";
    public static final String MOTION_WALK="walk";
    public static final String MOTION_RUN="run";
    public static final String MOTION_BLOCK="block";
    public static final String MOTION_KNEEL="kneel";
    public static final String MOTION_FLY="fly";
    public static final String MOTION_CREATIVE_IDLE="creative_idle";

    public final String id;
    public final WeaponStyle category;
    public final EpicCombatStyle defaultStyle;
    public final boolean canBePlacedOffhand;
    private final Map<EpicCombatStyle,WeaponStyleDefinition> styles;
    private final Map<EpicCombatStyle,ResolvedWeaponCapability> resolvedStyles;
    private final ResolvedWeaponCapability unresolvedStyle;

    public WeaponCapability1710(String id,WeaponStyle category,EpicCombatStyle defaultStyle,
                                boolean canBePlacedOffhand,Map<EpicCombatStyle,WeaponStyleDefinition> styles){
        this.id=id;this.category=category;this.defaultStyle=defaultStyle==null?EpicCombatStyle.COMMON:defaultStyle;
        this.canBePlacedOffhand=canBePlacedOffhand;
        EnumMap<EpicCombatStyle,WeaponStyleDefinition> copy=new EnumMap<EpicCombatStyle,WeaponStyleDefinition>(EpicCombatStyle.class);
        if(styles!=null)copy.putAll(styles);this.styles=Collections.unmodifiableMap(copy);
        EnumMap<EpicCombatStyle,ResolvedWeaponCapability> resolved=new EnumMap<EpicCombatStyle,ResolvedWeaponCapability>(EpicCombatStyle.class);
        for(Map.Entry<EpicCombatStyle,WeaponStyleDefinition> e:copy.entrySet())if(e.getValue()!=null)resolved.put(e.getKey(),new ResolvedWeaponCapability(this,e.getValue()));
        this.resolvedStyles=Collections.unmodifiableMap(resolved);this.unresolvedStyle=new ResolvedWeaponCapability(this,null);
    }

    public boolean isFist(){return category==WeaponStyle.FIST;}
    public boolean armed(){return !isFist();}
    public Map<EpicCombatStyle,WeaponStyleDefinition> styles(){return styles;}
    public WeaponStyleDefinition style(EpicCombatStyle style){return styles.get(style);}

    /**
     * Mirrors Epic's styleProvider idea: conditional Styles have priority; otherwise
     * fall back to the Weapon Type's default Style. The default itself may have no
     * conditions and therefore never needs hard-coded item checks in CombatController.
     */
    public WeaponStyleDefinition resolveStyle(Object player){
        for(Map.Entry<EpicCombatStyle,WeaponStyleDefinition> e:styles.entrySet()){
            WeaponStyleDefinition d=e.getValue();
            if(d!=null&&d.conditional()&&d.matches(player))return d;
        }
        WeaponStyleDefinition d=styles.get(defaultStyle);
        if(d!=null)return d;
        for(WeaponStyleDefinition any:styles.values())if(any!=null&&any.matches(player))return any;
        return null;
    }

    public ResolvedWeaponCapability resolve(Object player){
        WeaponStyleDefinition d=resolveStyle(player);if(d==null)return unresolvedStyle;
        ResolvedWeaponCapability r=resolvedStyles.get(d.style);return r==null?unresolvedStyle:r;
    }
    public boolean sameType(WeaponCapability1710 other){return other!=null&&id.equals(other.id);}
    @Override public String toString(){return id+"["+category+"/default="+defaultStyle+" styles="+styles.keySet()+"]";}
}
