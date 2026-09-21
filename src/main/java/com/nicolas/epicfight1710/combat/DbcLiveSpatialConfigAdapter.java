package com.nicolas.epicfight1710.combat;

import java.util.Arrays;
import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.Availability;

/** Global config capture; compares content, including in-place table edits.
 * Tables are copied only on real changes, never per joint/target. Game-thread only. */
public final class DbcLiveSpatialConfigAdapter {
    public interface Source { Values readConfig(); }
    public static final class Values {
        public Availability availability=Availability.UNAVAILABLE;
        public boolean constitutionSize,transformSize,godCosmetics;
        public int maxAttribute;
        public float[][] bulk,size;
    }
    public static final class Snapshot {
        public final long revision;
        public final Availability availability;
        public final NativeDbcSpatialProvider.Config config;
        private Snapshot(long revision,Values v){
            this.revision=revision;availability=v.availability;
            config=availability==Availability.AVAILABLE?new NativeDbcSpatialProvider.Config(revision,
                v.constitutionSize,v.transformSize,v.godCosmetics,v.maxAttribute,v.bulk,v.size):null;
        }
    }
    private final Source source;
    private Values previous;
    private Snapshot current;
    private long revisions;
    public DbcLiveSpatialConfigAdapter(Source source){
        if(source==null)throw new IllegalArgumentException("Config source required");this.source=source;
    }
    public Snapshot capture(){
        Values v=source.readConfig();
        if(v==null)v=new Values();
        if(previous!=null&&same(previous,v))return current;
        if(revisions==Long.MAX_VALUE)throw new IllegalStateException("Config revision exhausted");
        current=new Snapshot(++revisions,v);
        previous=new Values();previous.availability=v.availability;
        previous.constitutionSize=v.constitutionSize;previous.transformSize=v.transformSize;
        previous.godCosmetics=v.godCosmetics;previous.maxAttribute=v.maxAttribute;
        previous.bulk=copy(v.bulk);previous.size=copy(v.size);
        return current;
    }
    private static boolean same(Values a,Values b){
        return a.availability==b.availability&&a.constitutionSize==b.constitutionSize
            &&a.transformSize==b.transformSize&&a.godCosmetics==b.godCosmetics
            &&a.maxAttribute==b.maxAttribute&&Arrays.deepEquals(a.bulk,b.bulk)&&Arrays.deepEquals(a.size,b.size);
    }
    private static float[][] copy(float[][] a){
        if(a==null)return null;float[][] b=new float[a.length][];
        for(int i=0;i<a.length;i++)b[i]=a[i]==null?null:a[i].clone();return b;
    }
}
