package com.nicolas.epicfight1710.client;

/**
 * Very small opt-in client profiler for the 1.7.10 port hot paths.
 * Disabled cost is one boolean branch; no maps/strings/allocations are touched.
 * Enable with -Depicfight1710.profiler=true.
 */
public final class RuntimeProfiler {
    public static final int ANIMATOR=0, COLLIDER=1, DBC_STATE=2, JBRA_SKIN=3, FIRST_PERSON=4, COMBAT=5;
    private static final String[] NAMES={"Animator","collider","DBC-state","JBRA-skin","first-person","combat"};
    private static final boolean ENABLED=Boolean.getBoolean("epicfight1710.profiler");
    public static final RuntimeProfiler INSTANCE=new RuntimeProfiler();
    private final long[] nanos=new long[NAMES.length];
    private final long[] calls=new long[NAMES.length];
    private int lastReportTick=Integer.MIN_VALUE;
    private RuntimeProfiler(){}
    public boolean enabled(){return ENABLED;}
    public long begin(){return enabled()?System.nanoTime():0L;}
    public void end(int slot,long start){if(start==0L||slot<0||slot>=nanos.length)return;nanos[slot]+=System.nanoTime()-start;calls[slot]++;}
    public void reset(){for(int i=0;i<nanos.length;i++){nanos[i]=0L;calls[i]=0L;}lastReportTick=Integer.MIN_VALUE;}
    public void reportIfDue(int tick){
        if(!enabled())return;
        if(lastReportTick==Integer.MIN_VALUE){lastReportTick=tick;return;}
        if(tick-lastReportTick<200)return;
        lastReportTick=tick;
        StringBuilder b=new StringBuilder(192).append("[EpicFight1710] profiler 10s:");
        for(int i=0;i<NAMES.length;i++){
            long c=calls[i],n=nanos[i];
            double us=c==0?0.0:(n/1000.0)/c;
            b.append(' ').append(NAMES[i]).append('=').append(round2(us)).append("us/").append(c);
            nanos[i]=0L;calls[i]=0L;
        }
        System.out.println(b.toString());
    }
    private static double round2(double v){return Math.round(v*100.0)/100.0;}
}
