import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot;
import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.*;
import com.nicolas.epicfight1710.combat.NativeDbcSpatialProvider;

/** Deterministic contract tests for the per-player DBC spatial boundary. */
public final class DbcSpatialStateSnapshotTest {
    private static long checks;
    private static final Object PLAYER_A=new Object(),PLAYER_B=new Object();
    private static final Object WORLD_A=new Object(),WORLD_B=new Object();
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Input input(Object player,Object world,int tick,long revision,long geometry,String dns){
        return Input.builder().context(player,world,tick,revision).geometry(geometry,Availability.AVAILABLE)
            .dbc(0,0,0,50,1,1).constitution(100).body(0,1,0,false,false,false,false,.0625F).bodyScale(1.0F)
            .jrmCore(Availability.AVAILABLE).jYearsC(Availability.NOT_APPLICABLE)
            .jFamilyC(Availability.NOT_APPLICABLE).age(Float.NaN,Float.NaN,Float.NaN,1.0F,Availability.NOT_APPLICABLE)
            .flight(FlightState.GROUNDED,BodyPresentation.NORMAL).dnsRevision(dns);
    }
    private static DbcSpatialStateSnapshot.Cache cache(){return new DbcSpatialStateSnapshot.Cache();}

    public static void main(String[] args){
        DbcSpatialStateSnapshot.Cache c=cache();
        Input a=input(PLAYER_A,WORLD_A,100,10,7,"a");
        DbcSpatialStateSnapshot first=c.capture(a);
        check(first.isValid(),first.invalidReason());
        check(first.isValidFor(PLAYER_A,WORLD_A,100,10,7),"initial identity");
        check(first.isUsableForNativeProvider(),"snapshot feeds proven provider inputs");
        NativeDbcSpatialProvider.Config cfg=new NativeDbcSpatialProvider.Config(3,false,false,false,1000,null,null);
        check(new NativeDbcSpatialProvider().evaluate(first,cfg).isValid(),"provider consumes snapshot");

        // Zero, one or ten render attempts cannot create another spatial evaluation.
        for(int renders=0;renders<=10;renders++){
            DbcSpatialStateSnapshot same=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"a"));
            check(same==first,"render count changed cached snapshot "+renders);
            for(int camera=0;camera<8;camera++)
                check(c.capture(input(PLAYER_A,WORLD_A,100,10,7,"a"))==first,"camera affected snapshot");
        }
        check(c.completeEvaluations()==1,"one full evaluation per player/tick");

        // Alternating players and reverse order never share identity-keyed state.
        DbcSpatialStateSnapshot b=c.capture(input(PLAYER_B,WORLD_A,100,20,7,"b"));
        check(b!=first&&b.playerIdentity==PLAYER_B,"player isolation");
        DbcSpatialStateSnapshot aAgain=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"a"));
        check(aAgain==first,"A was not evicted by B");
        c.capture(input(PLAYER_B,WORLD_B,101,21,8,"b2"));
        check(c.completeEvaluations()==3,"world/tick revision invalidates B only");
        check(!first.isValidFor(PLAYER_B,WORLD_A,100,10,7),"player swap invalidation");
        check(!first.isValidFor(PLAYER_A,WORLD_B,100,10,7),"world swap invalidation");
        check(!first.isValidFor(PLAYER_A,WORLD_A,101,10,7),"tick invalidation");
        check(!first.isValidFor(PLAYER_A,WORLD_A,100,11,7),"action serial invalidation");
        check(!first.isValidFor(PLAYER_A,WORLD_A,100,10,8),"geometry revision invalidation");

        // DNS, age, form and flight are all part of the cache key/state revision.
        check(c.capture(input(PLAYER_A,WORLD_A,100,10,7,"dns-new"))!=first,"DNS revision not reused");
        Input form=input(PLAYER_A,WORLD_A,100,11,7,"form").dbc(0,3,3,50,1,1);
        check(c.capture(form).form==3,"form captured");
        Input fast=input(PLAYER_A,WORLD_A,100,12,7,"fast").flight(FlightState.FAST,BodyPresentation.PRONE);
        DbcSpatialStateSnapshot fastSnapshot=DbcSpatialStateSnapshot.capture(fast);
        check(fastSnapshot.isValid(),"fast flight state remains representable");
        check(!fastSnapshot.isUsableForNativeProvider(),"fast flight is rejected without fallback");
        DbcSpatialStateSnapshot directional=DbcSpatialStateSnapshot.capture(input(PLAYER_A,WORLD_A,100,12,7,"directional").flight(FlightState.DIRECTIONAL,BodyPresentation.NORMAL));
        check(!directional.isUsableForNativeProvider(),"directional flight is not flattened into normal flight");
        DbcSpatialStateSnapshot spectator=DbcSpatialStateSnapshot.capture(input(PLAYER_A,WORLD_A,100,12,7,"spectator").body(0,1,0,false,false,false,true,.0625F).bodyScale(1.0F).flight(FlightState.GROUNDED,BodyPresentation.SPECTATOR));
        check(!spectator.isUsableForNativeProvider(),"spectator presentation is rejected");
        Input delayed=input(PLAYER_A,WORLD_A,100,13,7,"delayed").jYearsC(Availability.UNAVAILABLE);
        DbcSpatialStateSnapshot delayedSnapshot=DbcSpatialStateSnapshot.capture(delayed);
        check(delayedSnapshot.jYearsCAvailability==Availability.UNAVAILABLE,"delayed JYearsC status retained");
        check(!delayedSnapshot.isUsableForNativeProvider(),"delayed JYearsC cannot feed provider");
        check(delayedSnapshot!=fastSnapshot,"unavailable source cannot reuse old snapshot");
        Input familyReady=input(PLAYER_A,WORLD_A,100,14,7,"family-ready").jFamilyC(Availability.AVAILABLE);
        check(DbcSpatialStateSnapshot.capture(familyReady).jFamilyCAvailability==Availability.AVAILABLE,"JFamilyC presence retained");
        Input generic=Input.builder().context(PLAYER_A,WORLD_A,100,15).geometry(7,Availability.AVAILABLE)
            .body(0,1,0,false,false,false,false,.0625F).bodyScale(1.0F).jrmCore(Availability.NOT_APPLICABLE)
            .jYearsC(Availability.NOT_APPLICABLE).jFamilyC(Availability.NOT_APPLICABLE)
            .age(Float.NaN,Float.NaN,Float.NaN,1.0F,Availability.NOT_APPLICABLE)
            .flight(FlightState.GROUNDED,BodyPresentation.NORMAL);
        DbcSpatialStateSnapshot genericSnapshot=DbcSpatialStateSnapshot.capture(generic);
        check(genericSnapshot.isValid()&&!genericSnapshot.isUsableForNativeProvider(),"generic profile is isolated from DBC provider");

        AgeScale notInstalled=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.NOT_APPLICABLE,false,0,0,0,1,0);
        check(notInstalled.availability==Availability.NOT_APPLICABLE&&notInstalled.childScale==1.0F,"JYearsC absent is explicit");
        AgeScale young=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,0,1,0);
        check(young.availability==Availability.AVAILABLE&&young.normalizedAge==.5531915F,"JYearsC minimum age scale");
        check(young.childScale==3.0F-young.normalizedAge*2.0F,"JYearsC childScl equation");
        AgeScale adult=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,52,52,0,1,0);
        check(adult.normalizedAge==1.0F&&adult.childScale==1.0F,"JYearsC adult scale");
        AgeScale oozaru=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,7,7);
        check(oozaru.normalizedAge==1.0F,"JYearsC Sai form exception");
        AgeScale missing=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.UNAVAILABLE,false,0,52,0,1,0);
        check(missing.availability==Availability.UNAVAILABLE,"missing JYearsC data is not guessed");

        // The class is a pure data boundary: no renderer, GL, camera, animation,
        // guard, input or flight mutator is reachable from this API.
        Package p=DbcSpatialStateSnapshot.class.getPackage();
        check(p!=null&&"com.nicolas.epicfight1710.combat".equals(p.getName()),"pure combat package");
        System.out.println("PASS DbcSpatialStateSnapshotTest: "+checks+" assertions; cache evaluations="+c.completeEvaluations());
    }
}
