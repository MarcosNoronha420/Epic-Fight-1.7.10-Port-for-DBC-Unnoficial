package com.nicolas.epicfight1710.client;

/**
 * Measures recent attack input cadence without coupling the click history to the
 * action/animation state machine. Epic's fast-fist adapter consumes the measured
 * cadence, but the source action clock remains authoritative for contact/recovery.
 */
final class AttackCadenceTracker {
    static final float FAST_FIST_CPS_THRESHOLD=6.8F;
    static final float MAX_FAST_FIST_PLAYBACK=2.0F;
    private static final long CPS_WINDOW_NANOS=1000000000L;
    private static final long CPS_STALE_NANOS=500000000L;

    private final long[] pressNanos=new long[16];
    private int pressHead,pressCount;
    private float measuredCps;

    void notePress(){
        long now=System.nanoTime();
        pressNanos[pressHead]=now;
        pressHead=(pressHead+1)%pressNanos.length;
        if(pressCount<pressNanos.length)pressCount++;
        refresh(now);
    }

    float measuredCps(){refresh(System.nanoTime());return measuredCps;}

    float playbackSpeed(){
        float cps=measuredCps();
        if(cps<=FAST_FIST_CPS_THRESHOLD)return 1.0F;
        float attackSpeedRatio=cps/FAST_FIST_CPS_THRESHOLD;
        attackSpeedRatio=Math.round(attackSpeedRatio*1000.0F)/1000.0F;
        float speed=attackSpeedRatio;
        return speed>MAX_FAST_FIST_PLAYBACK?MAX_FAST_FIST_PLAYBACK:speed;
    }

    void reset(){
        for(int i=0;i<pressNanos.length;i++)pressNanos[i]=0L;
        pressHead=pressCount=0;
        measuredCps=0.0F;
    }

    /** Package-private deterministic seam for static regression tests. */
    void seed(long[] orderedPresses,int count,int head){
        for(int i=0;i<pressNanos.length;i++)pressNanos[i]=i<orderedPresses.length?orderedPresses[i]:0L;
        pressCount=Math.max(0,Math.min(count,pressNanos.length));
        pressHead=((head%pressNanos.length)+pressNanos.length)%pressNanos.length;
        refresh(System.nanoTime());
    }

    private void refresh(long now){
        if(pressCount<2){measuredCps=0.0F;return;}
        int newestIndex=(pressHead-1+pressNanos.length)%pressNanos.length;
        long newest=pressNanos[newestIndex];
        if(newest<=0L||now-newest>CPS_STALE_NANOS){measuredCps=0.0F;return;}
        int used=1;long oldest=newest;
        for(int step=1;step<pressCount;step++){
            int idx=(newestIndex-step+pressNanos.length)%pressNanos.length;
            long t=pressNanos[idx];
            if(t<=0L||newest-t>CPS_WINDOW_NANOS)break;
            oldest=t;used++;
        }
        if(used<5||newest<=oldest){measuredCps=0.0F;return;}
        measuredCps=(float)((used-1)*1000000000.0/(double)(newest-oldest));
    }
}
