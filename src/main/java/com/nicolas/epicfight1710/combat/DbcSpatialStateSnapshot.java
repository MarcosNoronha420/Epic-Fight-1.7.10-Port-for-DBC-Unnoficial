package com.nicolas.epicfight1710.combat;

import java.util.IdentityHashMap;

/**
 * Immutable, per-player/per-world spatial input for the DBC/JBRA provider.
 *
 * <p>This type is deliberately a data boundary.  It does not discover state,
 * call Minecraft/DBC methods, read renderer statics, touch OpenGL, or advance
 * any clock.  A future read-only adapter may build an {@link Input} from
 * authoritative game data; until then missing data remains explicitly
 * unavailable rather than being guessed.</p>
 */
public final class DbcSpatialStateSnapshot {
    public enum Availability { AVAILABLE, NOT_APPLICABLE, UNAVAILABLE }
    public enum FlightState { GROUNDED, NORMAL, DIRECTIONAL, FAST, UNKNOWN }
    public enum BodyPresentation { NORMAL, PRONE, KO, UI, SPECTATOR, UNKNOWN }

    public final Object playerIdentity;
    public final Object worldIdentity;
    public final int gameTick;
    public final long actionRevision;
    public final long geometryRevision;
    public final Availability playerAvailability, worldAvailability, dbcAvailability;
    public final Availability jrmCoreAvailability, jYearsCAvailability, jFamilyCAvailability;
    public final Availability bodyAvailability, ageAvailability, geometryAvailability, flightAvailability;
    /** transformationState is JRMCore data(player,2)[0]; nativeState is ModelBipedBody.y. */
    public final int race, form, transformationState, constitution, release, nativeState, powerType;
    public final int bodyType, modelVariant, gender;
    public final boolean child, sneaking, divine, spectator;
    public final float modelPixelScale, bodyScale, ageYears, adultGrowth, ageScale, ageDivisor;
    public final FlightState flight;
    public final BodyPresentation presentation;
    /** A stable caller-provided revision/digest of DNS data; raw DNS is not copied. */
    public final String dnsRevision;
    /** Monotonic identity inside the cache for this player's current spatial state. */
    public final long spatialRevision;
    // The data is immutable; this private token is only the cache's liveness
    // marker, so an older object can be recognized as stale without mutating
    // any snapshot field or depending on a caller-provided hash.
    private final CurrentRevision currentRevision;
    private final String failure;

    private DbcSpatialStateSnapshot(Input in,CurrentRevision current,long spatialRevision) {
        playerIdentity=in.playerIdentity;worldIdentity=in.worldIdentity;gameTick=in.gameTick;
        actionRevision=in.actionRevision;geometryRevision=in.geometryRevision;
        this.currentRevision=current;this.spatialRevision=spatialRevision;
        playerAvailability=in.playerAvailability;worldAvailability=in.worldAvailability;
        dbcAvailability=in.dbcAvailability;jrmCoreAvailability=in.jrmCoreAvailability;
        jYearsCAvailability=in.jYearsCAvailability;jFamilyCAvailability=in.jFamilyCAvailability;
        bodyAvailability=in.bodyAvailability;ageAvailability=in.ageAvailability;
        geometryAvailability=in.geometryAvailability;flightAvailability=in.flightAvailability;
        race=in.race;form=in.form;transformationState=in.transformationState;constitution=in.constitution;release=in.release;
        nativeState=in.nativeState;powerType=in.powerType;bodyType=in.bodyType;
        modelVariant=in.modelVariant;gender=in.gender;child=in.child;sneaking=in.sneaking;
        divine=in.divine;spectator=in.spectator;modelPixelScale=in.modelPixelScale;bodyScale=in.bodyScale;
        ageYears=in.ageYears;adultGrowth=in.adultGrowth;ageScale=in.ageScale;
        ageDivisor=in.ageDivisor;flight=in.flight;presentation=in.presentation;
        dnsRevision=in.dnsRevision;failure=validate(in);
    }

    /** Captures only the already-resolved input object. It has no side effects. */
    public static DbcSpatialStateSnapshot capture(Input input) {
        if(input==null)throw new IllegalArgumentException("Input required");
        CurrentRevision current=new CurrentRevision();
        current.value=1;
        return new DbcSpatialStateSnapshot(input,current,1);
    }

    public boolean isValid() { return failure==null; }
    public String invalidReason() { return failure; }

    /** True only when the partial native provider can consume this snapshot. */
    public boolean isUsableForNativeProvider() {
        return isValid() && isCurrentRevision() && playerAvailability==Availability.AVAILABLE
            && worldAvailability==Availability.AVAILABLE && dbcAvailability==Availability.AVAILABLE
            && jrmCoreAvailability==Availability.AVAILABLE
            && bodyAvailability==Availability.AVAILABLE && geometryAvailability==Availability.AVAILABLE
            && jYearsCAvailability!=Availability.UNAVAILABLE
            && (jYearsCAvailability==Availability.NOT_APPLICABLE
                ? ageAvailability==Availability.NOT_APPLICABLE
                : ageAvailability==Availability.AVAILABLE)
            && ageAvailability!=Availability.UNAVAILABLE && finite(ageDivisor) && ageDivisor>0
            && flightAvailability==Availability.AVAILABLE && (flight==FlightState.GROUNDED||flight==FlightState.NORMAL)
            && presentation==BodyPresentation.NORMAL
            && !spectator && nativeState==1 && powerType==1;
    }

    /** Identity/revision check used by combat consumers before retaining a sample. */
    public boolean isValidFor(Object player,Object world,int tick,long revision,long geometryRev) {
        return isValid() && playerIdentity==player && worldIdentity==world && gameTick==tick
            && actionRevision==revision && geometryRevision==geometryRev && isCurrentRevision();
    }

    /** True when this object is still the cache's current spatial sample. */
    public boolean isCurrentIn(Cache cache) { return cache!=null&&cache.isCurrent(this); }

    private boolean isCurrentRevision() {
        return currentRevision!=null&&currentRevision.value==spatialRevision;
    }

    /** Convert only a proven, complete partial input to the existing math kernel. */
    NativeDbcSpatialProvider.State toProviderState() {
        if(!isUsableForNativeProvider())return null;
        return new NativeDbcSpatialProvider.State(playerIdentity,worldIdentity,gameTick,actionRevision,
            race,form,constitution,release,modelVariant,ageDivisor,
            modelPixelScale,child,sneaking,divine,spectator,nativeState,
            flight==FlightState.GROUNDED?NativeDbcSpatialProvider.Flight.GROUNDED:NativeDbcSpatialProvider.Flight.NORMAL,
            powerType);
    }

    /** Exact pure age equation observed in JRMCoreHJYC.JYCsizeBasedOnAge. */
    public static AgeScale resolveJYearsCAge(Availability addon,boolean rowPresent,float age,int growth,
                                             int race,int powerType,int transformationState) {
        if(addon==Availability.NOT_APPLICABLE)
            return new AgeScale(Availability.NOT_APPLICABLE,Float.NaN,Float.NaN,1.0F,1.0F,"JYearsC not installed");
        if(addon!=Availability.AVAILABLE||!rowPresent||!finite(age)||age<0||growth<=5)
            return new AgeScale(Availability.UNAVAILABLE,Float.NaN,Float.NaN,Float.NaN,Float.NaN,"JYearsC age row/config unavailable");
        boolean sai=(race==1||race==2);
        // JRMCoreHJYC derives State from data(player,2)[0].  Its own gate
        // first maps chakra or human data to zero; ModelBipedBody.y/nativeState
        // and the cosmetic form id are deliberately not consulted here.
        int selectedState=(powerType==2||race==0)?0:transformationState;
        boolean fixed=sai&&(selectedState==7||selectedState==8||selectedState==14);
        float yc;
        if(fixed||age>growth)yc=1.0F;
        else if(age<=5.0F)yc=.5F;
        else yc=.5F+(age-5.0F)/(growth-5.0F)*.5F;
        if(yc<.5531915F)yc=.5531915F;
        float childScale=3.0F-yc*2.0F;
        if(!finite(yc)||!finite(childScale)||childScale<=0)
            return new AgeScale(Availability.UNAVAILABLE,Float.NaN,Float.NaN,Float.NaN,Float.NaN,"Non-finite JYearsC scale");
        return new AgeScale(Availability.AVAILABLE,age,growth,yc,childScale);
    }

    public static final class AgeScale {
        public final Availability availability;
        public final float ageYears, adultGrowth, normalizedAge, childScale;
        public final String reason;
        private AgeScale(Availability a,float age,float growth,float normalized,float childScale) {
            this(a,age,growth,normalized,childScale,null);
        }
        private AgeScale(Availability a,float age,float growth,float normalized,float childScale,String reason) {
            availability=a;ageYears=age;adultGrowth=growth;normalizedAge=normalized;
            this.childScale=childScale;this.reason=reason;
        }
    }

    /** Single-thread game-loop cache. Identity keys prevent player/world mixing. */
    public static final class Cache {
        private final IdentityHashMap<Object,DbcSpatialStateSnapshot> byPlayer=new IdentityHashMap<Object,DbcSpatialStateSnapshot>();
        private final IdentityHashMap<Object,CurrentRevision> revisions=new IdentityHashMap<Object,CurrentRevision>();
        private long evaluations;
        public DbcSpatialStateSnapshot capture(Input input) {
            if(input==null||input.playerIdentity==null)throw new IllegalArgumentException("Player input required");
            DbcSpatialStateSnapshot old=byPlayer.get(input.playerIdentity);
            if(old!=null&&old.matches(input))return old;
            CurrentRevision current=revisions.get(input.playerIdentity);
            if(current==null){current=new CurrentRevision();revisions.put(input.playerIdentity,current);}
            current.value=current.value==Long.MAX_VALUE?1:current.value+1;
            DbcSpatialStateSnapshot fresh=new DbcSpatialStateSnapshot(input,current,current.value);
            byPlayer.put(input.playerIdentity,fresh);evaluations++;return fresh;
        }
        public long completeEvaluations(){return evaluations;}
        public DbcSpatialStateSnapshot get(Object player){return byPlayer.get(player);}
        public long currentSpatialRevision(Object player){
            CurrentRevision current=revisions.get(player);return current==null?0:current.value;
        }
        public boolean isCurrent(DbcSpatialStateSnapshot snapshot){
            return snapshot!=null&&byPlayer.get(snapshot.playerIdentity)==snapshot&&snapshot.isCurrentRevision();
        }
        public void invalidate(Object player){
            byPlayer.remove(player);CurrentRevision current=revisions.get(player);if(current!=null)current.value=current.value==Long.MAX_VALUE?1:current.value+1;
        }
        public void clear(){
            byPlayer.clear();for(CurrentRevision current:revisions.values())current.value=current.value==Long.MAX_VALUE?1:current.value+1;
        }
    }

    private static final class CurrentRevision { long value; }

    private boolean matches(Input in) {
        return playerIdentity==in.playerIdentity&&worldIdentity==in.worldIdentity&&gameTick==in.gameTick
            &&actionRevision==in.actionRevision&&geometryRevision==in.geometryRevision
            &&playerAvailability==in.playerAvailability&&worldAvailability==in.worldAvailability
            &&dbcAvailability==in.dbcAvailability&&jrmCoreAvailability==in.jrmCoreAvailability
            &&jYearsCAvailability==in.jYearsCAvailability&&jFamilyCAvailability==in.jFamilyCAvailability
            &&bodyAvailability==in.bodyAvailability&&ageAvailability==in.ageAvailability
            &&geometryAvailability==in.geometryAvailability&&flightAvailability==in.flightAvailability
            &&race==in.race&&form==in.form&&transformationState==in.transformationState&&constitution==in.constitution&&release==in.release
            &&nativeState==in.nativeState&&powerType==in.powerType&&bodyType==in.bodyType
            &&modelVariant==in.modelVariant&&gender==in.gender&&child==in.child&&sneaking==in.sneaking
            &&divine==in.divine&&spectator==in.spectator&&bits(modelPixelScale,in.modelPixelScale)&&bits(bodyScale,in.bodyScale)
            &&bits(ageYears,in.ageYears)&&bits(adultGrowth,in.adultGrowth)&&bits(ageScale,in.ageScale)
            &&bits(ageDivisor,in.ageDivisor)&&flight==in.flight&&presentation==in.presentation
            &&same(dnsRevision,in.dnsRevision);
    }

    public static final class Input {
        private Object playerIdentity,worldIdentity;
        private int gameTick,race,form,transformationState,constitution,release,nativeState=1,powerType=1,bodyType,modelVariant=1,gender;
        private long actionRevision,geometryRevision;
        private Availability playerAvailability=Availability.UNAVAILABLE,worldAvailability=Availability.UNAVAILABLE;
        private Availability dbcAvailability=Availability.UNAVAILABLE,jrmCoreAvailability=Availability.UNAVAILABLE;
        private Availability jYearsCAvailability=Availability.UNAVAILABLE,jFamilyCAvailability=Availability.UNAVAILABLE;
        private Availability bodyAvailability=Availability.UNAVAILABLE,ageAvailability=Availability.UNAVAILABLE;
        private Availability geometryAvailability=Availability.UNAVAILABLE,flightAvailability=Availability.UNAVAILABLE;
        private boolean child,sneaking,divine,spectator;
        private float modelPixelScale=Float.NaN,bodyScale=Float.NaN,ageYears=Float.NaN,adultGrowth=Float.NaN,ageScale=Float.NaN,ageDivisor=Float.NaN;
        private FlightState flight=FlightState.UNKNOWN;
        private BodyPresentation presentation=BodyPresentation.UNKNOWN;
        private String dnsRevision;
        private Input(){}
        public static Input builder(){return new Input();}
        public Input context(Object player,Object world,int tick,long revision){playerIdentity=player;worldIdentity=world;gameTick=tick;actionRevision=revision;playerAvailability=player==null?Availability.UNAVAILABLE:Availability.AVAILABLE;worldAvailability=world==null?Availability.UNAVAILABLE:Availability.AVAILABLE;return this;}
        public Input geometry(long revision,Availability status){geometryRevision=revision;geometryAvailability=require(status);return this;}
        /** nativeState is visual/native y; it is never used by JYearsC age math. */
        public Input dbc(int race,int form,int transformationState,int release,int nativeState,int powerType){this.race=race;this.form=form;this.transformationState=transformationState;this.release=release;this.nativeState=nativeState;this.powerType=powerType;dbcAvailability=Availability.AVAILABLE;return this;}
        public Input constitution(int value){constitution=value;return this;}
        public Input body(int bodyType,int modelVariant,int gender,boolean child,boolean sneaking,boolean divine,boolean spectator,float modelPixelScale){this.bodyType=bodyType;this.modelVariant=modelVariant;this.gender=gender;this.child=child;this.sneaking=sneaking;this.divine=divine;this.spectator=spectator;this.modelPixelScale=modelPixelScale;bodyAvailability=Availability.AVAILABLE;return this;}
        public Input bodyScale(float value){bodyScale=value;return this;}
        public Input jrmCore(Availability status){jrmCoreAvailability=require(status);return this;}
        public Input jYearsC(Availability status){jYearsCAvailability=require(status);return this;}
        public Input jFamilyC(Availability status){jFamilyCAvailability=require(status);return this;}
        public Input age(float years,float growth,float normalized,float divisor,Availability status){ageYears=years;adultGrowth=growth;ageScale=normalized;ageDivisor=divisor;ageAvailability=require(status);return this;}
        public Input flight(FlightState state,BodyPresentation presentation){flight=state==null?FlightState.UNKNOWN:state;this.presentation=presentation==null?BodyPresentation.UNKNOWN:presentation;flightAvailability=(state==null||state==FlightState.UNKNOWN)?Availability.UNAVAILABLE:Availability.AVAILABLE;return this;}
        public Input dnsRevision(String revision){dnsRevision=revision;return this;}
        public Input availability(Availability player,Availability world){playerAvailability=require(player);worldAvailability=require(world);return this;}
        public Input revisions(long action,long geometry){actionRevision=action;geometryRevision=geometry;return this;}
        public Input bodyAvailability(Availability status){bodyAvailability=require(status);return this;}
        public Input ageAvailability(Availability status){ageAvailability=require(status);return this;}
        public Input flightAvailability(Availability status){flightAvailability=require(status);return this;}
        private static Availability require(Availability a){return a==null?Availability.UNAVAILABLE:a;}
    }

    private static String validate(Input in) {
        if(in.playerIdentity==null||in.worldIdentity==null)return "Missing player/world identity";
        if(in.playerAvailability!=Availability.AVAILABLE||in.worldAvailability!=Availability.AVAILABLE)return "Player/world identity unavailable";
        if(in.gameTick<0)return "Invalid game tick";
        if(in.dbcAvailability==Availability.AVAILABLE&&(in.race<0||in.race>5||in.form<0||in.transformationState<0||in.constitution<0||in.release<0||in.release>100))return "Invalid DBC state";
        if(in.bodyAvailability==Availability.AVAILABLE&&(!finite(in.modelPixelScale)||in.modelPixelScale<=0||!finite(in.bodyScale)||in.bodyScale<=0||in.modelVariant<1||in.modelVariant>3))return "Invalid body/model data";
        if(in.ageAvailability==Availability.AVAILABLE&&(!finite(in.ageDivisor)||in.ageDivisor<=0||!finite(in.ageScale)))return "Invalid age data";
        if(in.flightAvailability==Availability.AVAILABLE&&in.flight==FlightState.UNKNOWN)return "Invalid flight state";
        if(in.geometryAvailability==Availability.AVAILABLE&&in.geometryRevision<0)return "Invalid geometry revision";
        return null;
    }
    private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}
    private static boolean bits(float a,float b){return Float.floatToIntBits(a)==Float.floatToIntBits(b);}
    private static boolean same(Object a,Object b){return a==null?b==null:a.equals(b);}
}
