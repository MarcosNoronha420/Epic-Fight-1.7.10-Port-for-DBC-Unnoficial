package com.nicolas.epicfight1710.combat;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Complete immutable-install payload produced by WeaponDefinitionLoader. */
public final class WeaponRegistryData {
    public final Map<String,WeaponCapability1710> types=new LinkedHashMap<String,WeaponCapability1710>();
    public final Map<String,AttackProfile> profiles=new LinkedHashMap<String,AttackProfile>();
    public final List<ItemCapabilityRule> itemRules=new ArrayList<ItemCapabilityRule>();
    public void addProfile(AttackProfile p){if(p!=null&&p.clip!=null)profiles.put(p.clip,p);}
}
