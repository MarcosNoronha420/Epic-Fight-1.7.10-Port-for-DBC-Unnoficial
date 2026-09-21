package com.nicolas.epicfight1710.client;

/** Entry hook shared by vanilla/JBRA ModelRenderer draws. */
public final class ModelRendererSkinHook {
    private static long activeCalls;
    private static long handledCalls;
    private static long bypassedDuplicateCalls;
    private static boolean loggedEntry;
    private static boolean disabledAfterFailure;
    private static boolean warnedFailure;
    /**
     * JRMCore's direct call-site bridge already asks this hook whether a part is owned.
     * When that exact part falls back to native ModelRenderer.render(), the global
     * ModelRenderer entry hook would otherwise run the whole dispatch a second time.
     * This depth guard skips only that proven duplicate invocation.
     */
    private static int nativeFallbackDepth;

    private ModelRendererSkinHook() {}

    static void beginNativeFallback(){nativeFallbackDepth++;}
    static void endNativeFallback(){if(nativeFallbackDepth>0)nativeFallbackDepth--;}

    public static boolean render(Object part, float scale) {
        try {
            if (disabledAfterFailure) return false;
            if (nativeFallbackDepth > 0) {
                ++bypassedDuplicateCalls;
                return false;
            }

            int fp = FirstPersonBodyRenderer1710.modelRendererHook(part, scale);
            if (fp > 0) return true;
            if (fp < 0) return false;

            if (!NativeJbraSkinContext.INSTANCE.active()) return false;
            ++activeCalls;
            if (!loggedEntry) {
                loggedEntry = true;
                System.out.println("[EpicFight1710] Epic skeletal skinning dispatch is executing inside the live JBRA player render.");
            }

            long prof=RuntimeProfiler.INSTANCE.begin();
            boolean handled;
            try { handled = NativeJbraSkinContext.INSTANCE.renderPart(part, scale); }
            finally { RuntimeProfiler.INSTANCE.end(RuntimeProfiler.JBRA_SKIN,prof); }
            if (handled) ++handledCalls;
            return handled;
        } catch (Throwable t) {
            disabledAfterFailure = true;
            if (!warnedFailure) {
                warnedFailure = true;
                System.err.println("[EpicFight1710] Skeletal skinning disabled after a fatal bridge/render failure; native JBRA fallback remains active without retry storm.");
                t.printStackTrace();
            }
            return false;
        }
    }

    public static long activeCalls() { return activeCalls; }
    public static long handledCalls() { return handledCalls; }
    public static long bypassedDuplicateCalls() { return bypassedDuplicateCalls; }
    static void resetPerformanceCounters() { activeCalls=handledCalls=bypassedDuplicateCalls=0L; }
}
