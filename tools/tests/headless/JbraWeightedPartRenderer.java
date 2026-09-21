package com.nicolas.epicfight1710.client;
/** Test-only graphics boundary. A pose test must not call it. Never packaged. */
public final class JbraWeightedPartRenderer {
    public static final JbraWeightedPartRenderer INSTANCE=new JbraWeightedPartRenderer();
    public void resetPerformanceCounters(){throw new AssertionError("Unexpected renderer access");}
}
