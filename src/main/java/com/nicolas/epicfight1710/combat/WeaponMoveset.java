package com.nicolas.epicfight1710.combat;

/** Backward-compatible facade; new code should use ResolvedWeaponCapability. */
public final class WeaponMoveset {
    private WeaponMoveset(){}

    public static AttackProfile get(String clip){AttackProfile p=WeaponCapabilityRegistry.profile(clip);return p!=null?p:FistMoveset.get(clip);}
    public static AttackProfile combo(WeaponStyle style,int counter){ResolvedWeaponCapability c=resolved(style);return c.isFist()?FistMoveset.combo(counter,false):c.combo(counter,false);}
    public static AttackProfile dash(WeaponStyle style){return resolved(style).dash();}
    public static String airClip(WeaponStyle style){return resolved(style).airClip();}
    public static int comboLength(WeaponStyle style){ResolvedWeaponCapability c=resolved(style);return c.isFist()?3:(c.style==null?0:c.style.comboLength());}
    public static WeaponStyle detect(Object player){return WeaponCapabilityRegistry.resolve(player).category;}
    public static WeaponStyle fromHierarchy(String hierarchy){return WeaponCapabilityRegistry.resolveHierarchy(hierarchy).category;}
    public static String guardClip(WeaponStyle style){return resolved(style).livingMotion(WeaponCapability1710.MOTION_BLOCK);}
    public static String holdClip(WeaponStyle style){return resolved(style).livingMotion(WeaponCapability1710.MOTION_IDLE);}
    public static String walkClip(WeaponStyle style){return resolved(style).livingMotion(WeaponCapability1710.MOTION_WALK);}
    public static String runClip(WeaponStyle style){return resolved(style).livingMotion(WeaponCapability1710.MOTION_RUN);}

    private static ResolvedWeaponCapability resolved(WeaponStyle style){return capability(style).resolve(null);}
    private static WeaponCapability1710 capability(WeaponStyle style){
        if(style==null||style==WeaponStyle.FIST)return WeaponCapabilityRegistry.fist();
        if(style==WeaponStyle.SWORD)return WeaponCapabilityRegistry.type("epicfight:sword");
        if(style==WeaponStyle.UCHIGATANA)return WeaponCapabilityRegistry.type("epicfight:uchigatana");
        if(style==WeaponStyle.LONGSWORD)return WeaponCapabilityRegistry.type("epicfight:longsword");
        if(style==WeaponStyle.GREATSWORD)return WeaponCapabilityRegistry.type("epicfight:greatsword");
        if(style==WeaponStyle.AXE)return WeaponCapabilityRegistry.type("epicfight:axe");
        if(style==WeaponStyle.DAGGER)return WeaponCapabilityRegistry.type("epicfight:dagger");
        if(style==WeaponStyle.SPEAR)return WeaponCapabilityRegistry.type("epicfight:spear_twohand");
        return WeaponCapabilityRegistry.fist();
    }
}
