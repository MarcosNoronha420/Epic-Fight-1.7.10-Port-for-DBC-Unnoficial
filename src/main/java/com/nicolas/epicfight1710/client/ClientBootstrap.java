package com.nicolas.epicfight1710.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.nicolas.epicfight1710.combat.WeaponDefinitionLoader;

public final class ClientBootstrap {
    private ClientBootstrap(){}

    public static void init() throws Exception {
        RuntimeAssets.load();
        Compat.init();
        // 2.0.19: no FirstPersonTuning load/config. First-person follows source matrices.
        WeaponDefinitionLoader.load();
        ClientKeyBindings.init();
        registerForgeBus(ClientHooks.INSTANCE);
        registerFmlBus(ClientHooks.INSTANCE);
        System.out.println("[EpicFight1710] Client hooks registered on Forge + FML buses.");
    }

    private static void registerForgeBus(Object listener) throws Exception {
        Class<?> forge=Class.forName("net.minecraftforge.common.MinecraftForge");
        Field eventBus=forge.getField("EVENT_BUS");
        register(eventBus.get(null),listener);
    }
    private static void registerFmlBus(Object listener) throws Exception {
        Class<?> fml=Class.forName("cpw.mods.fml.common.FMLCommonHandler");
        Method instance=fml.getMethod("instance");
        Object handler=instance.invoke(null);
        Method bus=fml.getMethod("bus");
        register(bus.invoke(handler),listener);
    }
    private static void register(Object bus,Object listener) throws Exception {
        if(bus==null)throw new IllegalStateException("event bus is null");
        Method target=null;
        for(Method m:bus.getClass().getMethods()){
            if("register".equals(m.getName())&&m.getParameterTypes().length==1){target=m;break;}
        }
        if(target==null)throw new NoSuchMethodException(bus.getClass().getName()+".register(Object)");
        target.invoke(bus,listener);
    }
}
