package com.nicolas.epicfight1710.combat;

import java.util.Locale;

/** Concrete 1.7.10 item hierarchy -> reusable Weapon Type mapping. */
public final class ItemCapabilityRule {
    public final String typeId;
    public final String[] tokens;
    public ItemCapabilityRule(String typeId,String[] tokens){this.typeId=typeId;this.tokens=tokens==null?new String[0]:tokens.clone();}
    public boolean matches(String hierarchy){
        if(hierarchy==null)return false;String n=hierarchy.toLowerCase(Locale.ENGLISH);
        for(String t:tokens)if(t!=null&&t.length()>0&&n.indexOf(t.toLowerCase(Locale.ENGLISH))>=0)return true;
        return false;
    }
}
