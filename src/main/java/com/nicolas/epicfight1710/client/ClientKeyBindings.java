package com.nicolas.epicfight1710.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Client controls. First-person screen-position tuning was removed in 2.0.19. */
public final class ClientKeyBindings {
    private static final String CATEGORY="key.categories.epicfight1710";
    private static Object battleMode,dashVanish,reloadWeapons,vanillaAttack;
    private static Method getKeyCode;
    private static boolean initialized,warned;
    private ClientKeyBindings(){}

    public static synchronized void init(){
        if(initialized)return;initialized=true;
        try{
            Class<?> kb=Class.forName("net.minecraft.client.settings.KeyBinding");
            Constructor<?> ctor=kb.getConstructor(String.class,Integer.TYPE,String.class);
            battleMode=ctor.newInstance("key.epicfight1710.battle_mode",Integer.valueOf(19),CATEGORY);
            dashVanish=ctor.newInstance("key.epicfight1710.dash_vanish",Integer.valueOf(34),CATEGORY);
            reloadWeapons=ctor.newInstance("key.epicfight1710.reload_weapons",Integer.valueOf(66),CATEGORY);
            Class<?> reg=Class.forName("cpw.mods.fml.client.registry.ClientRegistry");
            Method register=reg.getMethod("registerKeyBinding",kb);
            register.invoke(null,battleMode);
            register.invoke(null,dashVanish);
            register.invoke(null,reloadWeapons);
            getKeyCode=findNoArg(kb,"func_151463_i","getKeyCode");
            if(getKeyCode==null)throw new NoSuchMethodException("KeyBinding#getKeyCode");
            vanillaAttack=resolveVanillaAttack(kb);
            System.out.println("[EpicFight1710] Controls registered: Battle Mode=R, Dash/Vanish=G, reload Weapon Types=F8. First-person arm-position tuning controls were removed; Attack/Combo follows Minecraft's native Attack binding.");
        }catch(Throwable t){
            battleMode=null;dashVanish=null;reloadWeapons=null;vanillaAttack=null;getKeyCode=null;warn(t);
        }
    }

    public static boolean battleModeDown(){return battleMode==null||getKeyCode==null?Keyboard.isKeyDown(19):isBindingDown(battleMode,19);}
    public static boolean attackComboDown(){return isBindingDown(vanillaAttack,-100);}
    public static boolean dashVanishDown(){return dashVanish==null||getKeyCode==null?Keyboard.isKeyDown(34):isBindingDown(dashVanish,34);}
    public static boolean reloadWeaponsDown(){return isBindingDown(reloadWeapons,66);}
    public static int attackComboCode(){return keyCode(vanillaAttack,-100);}
    public static boolean attackComboIsMouse(){return attackComboCode()<0;}

    private static Object resolveVanillaAttack(Class<?> kb) throws Exception {
        Class<?> mc=Class.forName("net.minecraft.client.Minecraft");
        Method get=findNoArg(mc,"func_71410_x","getMinecraft");
        if(get==null)throw new NoSuchMethodException("Minecraft#getMinecraft");
        Object minecraft=get.invoke(null);
        Field gs=findField(mc,"field_71474_y","gameSettings");
        if(gs==null)throw new NoSuchFieldException("Minecraft.gameSettings");
        Object settings=gs.get(minecraft);if(settings==null)throw new IllegalStateException("Minecraft.gameSettings is null");
        Field attack=findField(settings.getClass(),"field_74312_F","keyBindAttack");
        if(attack==null)throw new NoSuchFieldException("GameSettings.keyBindAttack");
        Object binding=attack.get(settings);
        if(binding==null||!kb.isInstance(binding))throw new IllegalStateException("Vanilla Attack KeyBinding unavailable");
        return binding;
    }
    private static boolean isBindingDown(Object binding,int fallback){
        int code=keyCode(binding,fallback);if(code==0)return false;
        try{if(code<0){int button=code+100;return button>=0&&Mouse.isButtonDown(button);}return Keyboard.isKeyDown(code);}catch(Throwable t){return false;}
    }
    private static int keyCode(Object binding,int fallback){
        if(binding==null||getKeyCode==null)return fallback;
        try{return ((Number)getKeyCode.invoke(binding)).intValue();}catch(Throwable t){return fallback;}
    }
    private static Method findNoArg(Class<?> c,String...names){
        for(String n:names){try{Method m=c.getMethod(n);m.setAccessible(true);return m;}catch(Throwable ignored){try{Method m=c.getDeclaredMethod(n);m.setAccessible(true);return m;}catch(Throwable ignored2){}}}return null;
    }
    private static Field findField(Class<?> c,String...names){
        for(Class<?> x=c;x!=null;x=x.getSuperclass())for(String n:names){try{Field f=x.getDeclaredField(n);f.setAccessible(true);return f;}catch(Throwable ignored){}}return null;
    }
    private static void warn(Throwable t){if(warned)return;warned=true;System.err.println("[EpicFight1710] Could not register one or more client controls; hardcoded fallbacks remain available.");t.printStackTrace();}
}
