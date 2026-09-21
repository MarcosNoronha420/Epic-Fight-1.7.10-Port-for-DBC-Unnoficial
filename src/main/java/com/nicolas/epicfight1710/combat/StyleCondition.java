package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.client.Compat;
import java.util.Locale;

/** Data-driven Style condition used by WeaponCapability1710. */
public final class StyleCondition {
    public final String type;
    public final boolean value;
    public StyleCondition(String type,boolean value){this.type=type==null?"always":type.toLowerCase(Locale.ENGLISH);this.value=value;}
    public boolean matches(Object player){
        boolean actual;
        if("always".equals(type))actual=true;
        else if("never".equals(type))actual=false;
        else if("riding".equals(type)||"mount".equals(type))actual=Compat.riding(player);
        else if("sprinting".equals(type))actual=Compat.sprinting(player);
        else if("sneaking".equals(type))actual=Compat.sneaking(player);
        else if("blocking".equals(type))actual=Compat.dbcBlocking(player)==1;
        else if("flying".equals(type))actual=Compat.dbcFlying(player);
        else if("airborne".equals(type))actual=!Compat.onGround(player);
        else actual=false;
        return actual==value;
    }
}
