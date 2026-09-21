package com.nicolas.epicfight1710;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;

@Mod(modid = EpicFight1710.MODID, name = EpicFight1710.NAME, version = EpicFight1710.VERSION, acceptableRemoteVersions = "*", clientSideOnly = true)
public final class EpicFight1710 {
    public static final String MODID = "epicfight1710";
    public static final String NAME = "Epic Fight 1.7.10 Port Runtime";
    public static final String VERSION = "2.2.0-RC2";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Keep the physical server safe: load every client-only class reflectively.
        try {
            Class.forName("net.minecraft.client.Minecraft");
            Class<?> bootstrap = Class.forName("com.nicolas.epicfight1710.client.ClientBootstrap");
            bootstrap.getMethod("init").invoke(null);
            System.out.println("[EpicFight1710] Client runtime loaded.");
        } catch (ClassNotFoundException ignored) {
            System.out.println("[EpicFight1710] Dedicated server detected; client renderer disabled.");
        } catch (Throwable t) {
            System.err.println("[EpicFight1710] Failed to initialize client runtime:");
            t.printStackTrace();
        }
    }
}
