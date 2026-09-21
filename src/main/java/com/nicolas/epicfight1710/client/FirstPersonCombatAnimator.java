package com.nicolas.epicfight1710.client;

/**
 * First-person entry point for Forge 1.7.10 RenderHandEvent.
 * Uses the accepted WORLD-BODY world-camera framing and dedicated cached JBRA body fast path.
 * The event arrives while the world-camera matrices are still live,
 * which is the correct basis for a true/full-body first-person view.
 */
public final class FirstPersonCombatAnimator {
    public static final FirstPersonCombatAnimator INSTANCE = new FirstPersonCombatAnimator();

    private boolean warned;

    private FirstPersonCombatAnimator() {}

    public boolean render(float partialTicks) {
        if (!CombatController.INSTANCE.battleMode() || Compat.getThirdPerson() != 0 || !RuntimeAssets.READY) {
            return false;
        }

        Object player = Compat.player();
        if (player == null) return false;
        Object renderer = Compat.rendererFor(player);
        if (!NativeJbraSkinContext.isJbraRenderer(renderer)) return false;

        long prof=RuntimeProfiler.INSTANCE.begin();
        try {
            return FirstPersonBodyRenderer1710.INSTANCE.render(player, partialTicks);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("[EpicFight1710] First-person WORLD-BODY fast-path dispatch failed once; native hand fallback remains enabled for this frame.");
                t.printStackTrace();
            }
            return false;
        } finally {
            RuntimeProfiler.INSTANCE.end(RuntimeProfiler.FIRST_PERSON,prof);
        }
    }
}
