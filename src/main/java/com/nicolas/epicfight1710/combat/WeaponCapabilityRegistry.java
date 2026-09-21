package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.client.Compat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime Item Capability / Weapon Type registry. 2.0 installs it from the embedded
 * Epic-style JSON definition (optionally overridden in config/epicfight1710) instead
 * of compiling every weapon decision into CombatController.
 */
public final class WeaponCapabilityRegistry {
    private static volatile Map<String,WeaponCapability1710> TYPES;
    private static volatile Map<String,AttackProfile> PROFILES;
    private static volatile List<ItemCapabilityRule> ITEM_RULES;
    private static volatile WeaponCapability1710 FIST;
    private static volatile long generation;
    // Item class hierarchy strings are stable for the life of a classloader. Keep the
    // type-rule result outside the 20 TPS player update path; install() clears it on F8.
    private static final ConcurrentHashMap<String,WeaponCapability1710> TYPE_RESOLVE_CACHE=new ConcurrentHashMap<String,WeaponCapability1710>();

    static { install(minimalFallback()); }
    private WeaponCapabilityRegistry(){}

    public static synchronized void install(WeaponRegistryData data){
        if(data==null||data.types.isEmpty())throw new IllegalArgumentException("empty weapon registry");
        WeaponCapability1710 fist=data.types.get("epicfight:fist");
        if(fist==null)throw new IllegalArgumentException("weapon registry requires epicfight:fist");
        TYPES=Collections.unmodifiableMap(new LinkedHashMap<String,WeaponCapability1710>(data.types));
        PROFILES=Collections.unmodifiableMap(new LinkedHashMap<String,AttackProfile>(data.profiles));
        ITEM_RULES=Collections.unmodifiableList(new ArrayList<ItemCapabilityRule>(data.itemRules));
        FIST=fist;TYPE_RESOLVE_CACHE.clear();generation++;
    }

    public static WeaponCapability1710 fist(){return FIST;}
    public static long generation(){return generation;}
    public static WeaponCapability1710 resolve(Object player){return resolveHierarchy(Compat.heldItemTypeName(player));}
    public static ResolvedWeaponCapability resolveActive(Object player){return resolve(player).resolve(player);}
    public static WeaponCapability1710 resolveHierarchy(String hierarchy){
        if(hierarchy==null||hierarchy.length()==0)return FIST;
        String n=hierarchy.toLowerCase(Locale.ENGLISH);
        WeaponCapability1710 cached=TYPE_RESOLVE_CACHE.get(n);if(cached!=null)return cached;
        WeaponCapability1710 resolved=FIST;
        List<ItemCapabilityRule> rules=ITEM_RULES;
        for(ItemCapabilityRule r:rules)if(r.matches(n)){WeaponCapability1710 c=TYPES.get(r.typeId);if(c!=null){resolved=c;break;}}
        if(TYPE_RESOLVE_CACHE.size()>512)TYPE_RESOLVE_CACHE.clear();
        TYPE_RESOLVE_CACHE.put(n,resolved);return resolved;
    }
    public static WeaponCapability1710 type(String id){WeaponCapability1710 c=TYPES.get(id);return c==null?FIST:c;}
    public static AttackProfile profile(String clip){return clip==null?null:PROFILES.get(clip);}
    public static int typeCount(){return TYPES.size();}
    public static int itemRuleCount(){return ITEM_RULES.size();}
    public static Map<String,WeaponCapability1710> snapshot(){return TYPES;}

    private static WeaponRegistryData minimalFallback(){
        WeaponRegistryData d=new WeaponRegistryData();
        EnumMap<EpicCombatStyle,WeaponStyleDefinition> styles=new EnumMap<EpicCombatStyle,WeaponStyleDefinition>(EpicCombatStyle.class);
        styles.put(EpicCombatStyle.COMMON,new WeaponStyleDefinition(EpicCombatStyle.COMMON,null,null,null,"fist_airslash",null,null,
                new ColliderDefinition(1,0,0,0,.5F,.5F,.8F,3.25F,.28F),null,null));
        d.types.put("epicfight:fist",new WeaponCapability1710("epicfight:fist",WeaponStyle.FIST,EpicCombatStyle.COMMON,true,styles));
        return d;
    }
}
