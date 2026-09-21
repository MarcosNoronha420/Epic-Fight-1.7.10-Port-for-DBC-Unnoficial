package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.*;

/** Read-only client boundary. Call on the game thread, once per spatial sample,
 * then pass the returned snapshot to all consumers of that sample. */
public final class DbcLiveSpatialAdapter {
    public interface Source {
        Readings read(Object player);
    }

    /** Short-lived, owned values read from one player. Unknowns are explicit;
     * a Source must never fill these fields from another player or a draw. */
    public static final class Readings {
        public Object world;
        public int tick=-1;
        public Availability jrmCore=Availability.UNAVAILABLE, dbc=Availability.UNAVAILABLE;
        public Availability years=Availability.UNAVAILABLE, family=Availability.UNAVAILABLE;
        public int race=-1,form=-1,transformationState=-1,release=-1,powerType=-1,constitution=-1;
        public int nativeState=-1,bodyType=-1,modelVariant=-1,gender=-1;
        public boolean bodyKnown,child,sneaking,divine,spectator;
        public float modelPixelScale=Float.NaN,age=Float.NaN;
        public int adultGrowth=-1;
        public boolean ageRowPresent;
        /** Exact immutable DNS string: collision-free identity, no lossy hash. */
        public String dns;
        public FlightState flight=FlightState.UNKNOWN;
        public BodyPresentation presentation=BodyPresentation.UNKNOWN;
    }

    private final Source source;
    private final DbcLiveSpatialConfigAdapter configs;
    private final Cache cache=new Cache();

    public DbcLiveSpatialAdapter(Source source,DbcLiveSpatialConfigAdapter configs){
        if(source==null||configs==null)throw new IllegalArgumentException("Source/config adapter required");
        this.source=source;this.configs=configs;
    }

    /** Lazy optional class discovery; does not bootstrap Minecraft or a renderer. */
    public static DbcLiveSpatialAdapter createClient(){
        ReflectiveDbcSpatialSource source=new ReflectiveDbcSpatialSource();
        return new DbcLiveSpatialAdapter(source,new DbcLiveSpatialConfigAdapter(source));
    }

    /** actionRevision belongs to the caller's action, never to a native static. */
    public DbcSpatialStateSnapshot capture(Object player,long actionRevision){
        if(player==null)throw new IllegalArgumentException("Explicit player required");
        Readings r=source.read(player);
        if(r==null)r=new Readings();
        DbcLiveSpatialConfigAdapter.Snapshot config=configs.capture();
        NativeDbcSpatialProvider.Config c=config.config;
        AgeScale age=DbcSpatialStateSnapshot.resolveJYearsCAge(r.years,
            r.ageRowPresent&&r.dbc==Availability.AVAILABLE,r.age,r.adultGrowth,
            r.race,r.powerType,r.transformationState);
        float bodyScale=c==null?Float.NaN:NativeDbcSpatialProvider.bodyScale(r.modelVariant,r.constitution,r.release,c);
        // The native Oozaru branch overrides f1/release; the existing partial
        // kernel rejects its geometry. Do not publish the ordinary scalar for it.
        if((r.race==1||r.race==2)&&(r.form==7||r.form==8))bodyScale=Float.NaN;
        boolean bodyKnown=r.bodyKnown&&r.nativeState>=1&&r.presentation!=BodyPresentation.UNKNOWN
            &&r.dbc==Availability.AVAILABLE&&c!=null&&!Float.isNaN(bodyScale)&&!Float.isInfinite(bodyScale)&&bodyScale>0;
        Input input=Input.builder().context(player,r.world,r.tick,actionRevision)
            .geometry(config.revision,config.availability)
            .dbc(r.race,r.form,r.transformationState,r.release,r.nativeState,r.powerType)
            .dbcAvailability(r.dbc).constitution(r.constitution)
            .body(r.bodyType,r.modelVariant,r.gender,r.child,r.sneaking,r.divine,r.spectator,r.modelPixelScale)
            .bodyScale(bodyScale).bodyAvailability(bodyKnown?Availability.AVAILABLE:Availability.UNAVAILABLE)
            .jrmCore(r.jrmCore).jYearsC(r.years).jFamilyC(r.family)
            .age(age.ageYears,age.adultGrowth,age.normalizedAge,age.childScale,age.availability)
            .flight(r.flight,r.presentation).dnsRevision(r.dns);
        return cache.capture(input);
    }

    /** Current configuration is independent of the player. */
    public DbcLiveSpatialConfigAdapter.Snapshot config(){return configs.capture();}
    public boolean isCurrent(DbcSpatialStateSnapshot snapshot){return cache.isCurrent(snapshot);}
    public long completeCaptures(){return cache.completeEvaluations();}
    public void invalidate(Object player){cache.invalidate(player);}
    /** Call on disconnect; native row synchronization remains the source's authority. */
    public void clear(){cache.clear();}
}
