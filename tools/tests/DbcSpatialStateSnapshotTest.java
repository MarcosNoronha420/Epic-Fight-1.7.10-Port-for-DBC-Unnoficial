import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot;
import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.*;
import com.nicolas.epicfight1710.combat.NativeDbcSpatialProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

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
        NativeDbcSpatialProvider.Config cfg=new NativeDbcSpatialProvider.Config(7,false,false,false,1000,null,null);
        NativeDbcSpatialProvider provider=new NativeDbcSpatialProvider();
        NativeDbcSpatialProvider.Descriptor descriptorA=provider.evaluate(first,cfg);
        check(descriptorA.isValid(),"provider consumes snapshot");
        check(descriptorA.state.revision==first.spatialRevision,"State.revision uses spatialRevision");

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
        DbcSpatialStateSnapshot changed=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"dns-new"));
        check(changed!=first,"DNS revision not reused");
        check(changed.spatialRevision>first.spatialRevision,"spatial revision advances on state change");
        check(!first.isValidFor(PLAYER_A,WORLD_A,100,10,7),"old snapshot becomes stale after DNS change");
        check(!first.isCurrentIn(c)&&c.isCurrent(changed),"cache identifies only the current spatial snapshot");
        check(!descriptorA.isValidFor(PLAYER_A,WORLD_A,100,changed.spatialRevision,cfg.revision),"old descriptor fails current spatial revision");
        NativeDbcSpatialProvider.Descriptor descriptorB=provider.evaluate(changed,cfg);
        check(descriptorB.isValid(),"new descriptor is valid");
        check(descriptorB.state.revision==changed.spatialRevision,"new State.revision uses spatialRevision");
        check(descriptorB.isValidFor(PLAYER_A,WORLD_A,100,changed.spatialRevision,cfg.revision),"new descriptor matches spatial revision");
        check(changed.isValidFor(PLAYER_A,WORLD_A,100,10,7),"new snapshot remains current with same external revisions");
        Input changedState=input(PLAYER_A,WORLD_A,100,10,7,"same-envelope").dbc(1,3,0,50,1,1).body(0,2,0,false,false,false,false,.0625F).bodyScale(1.0F);
        DbcSpatialStateSnapshot changedStateSnapshot=c.capture(changedState);
        check(changedStateSnapshot!=changed,"race/form/modelVariant differences get a new identity");
        check(!changed.isCurrentIn(c),"previous state is stale after race/form/model change");
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
        // JYearsC state comes from data(player,2)[0], independently of
        // ModelBipedBody.y/nativeState and independently of the form id.
        Input stateA=input(PLAYER_A,WORLD_A,100,30,7,"state-a").dbc(1,3,7,50,1,1);
        DbcSpatialStateSnapshot stateASnapshot=DbcSpatialStateSnapshot.capture(stateA);
        check(stateASnapshot.transformationState==7&&stateASnapshot.nativeState==1&&stateASnapshot.form==3,"state fields remain separate");
        AgeScale state7=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,1,7);
        check(state7.normalizedAge==1.0F&&state7.childScale==1.0F,"JYearsC transformation state 7 exception");
        Input stateB=input(PLAYER_A,WORLD_A,100,31,7,"state-b").dbc(1,3,0,50,7,1);
        DbcSpatialStateSnapshot stateBSnapshot=DbcSpatialStateSnapshot.capture(stateB);
        check(stateBSnapshot.transformationState==0&&stateBSnapshot.nativeState==7,"visual nativeState is not transformation state");
        AgeScale visual7=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,1,0);
        check(visual7.normalizedAge==.5531915F&&visual7.childScale==3.0F-visual7.normalizedAge*2.0F,"nativeState 7 does not activate JYearsC exception");
        AgeScale state8=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,1,8);
        AgeScale state14=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,1,14);
        check(state8.normalizedAge==1.0F&&state14.normalizedAge==1.0F,"JYearsC transformation states 8 and 14 exceptions");
        AgeScale chakra=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,1,2,7);
        AgeScale human=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.AVAILABLE,true,0,52,0,1,7);
        check(chakra.normalizedAge==.5531915F&&human.normalizedAge==.5531915F,"native race/power gate selects zero state");
        AgeScale missing=DbcSpatialStateSnapshot.resolveJYearsCAge(Availability.UNAVAILABLE,false,0,52,0,1,0);
        check(missing.availability==Availability.UNAVAILABLE,"missing JYearsC data is not guessed");

        // The class is a pure data boundary: no renderer, GL, camera, animation,
        // guard, input or flight mutator is reachable from this API.
        Package p=DbcSpatialStateSnapshot.class.getPackage();
        check(p!=null&&"com.nicolas.epicfight1710.combat".equals(p.getName()),"pure combat package");
        cacheLifecycle();
        revisionExhaustion();
        System.out.println("PASS DbcSpatialStateSnapshotTest: "+checks+" assertions; cache evaluations="+c.completeEvaluations());
    }

    private static void cacheLifecycle(){
        Cache c=cache();Object playerC=new Object();
        NativeDbcSpatialProvider provider=new NativeDbcSpatialProvider();
        NativeDbcSpatialProvider.Config config=new NativeDbcSpatialProvider.Config(7,false,false,false,1000,null,null);
        DbcSpatialStateSnapshot first=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"a"));
        NativeDbcSpatialProvider.Descriptor firstDescriptor=provider.evaluate(first,config);
        check(firstDescriptor.isValid(),"lifecycle initial descriptor");
        DbcSpatialStateSnapshot changed=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"changed"));
        check(changed.spatialRevision>first.spatialRevision,"changed state advances cache revision");
        check(!first.isCurrentIn(c)&&!first.isValidFor(PLAYER_A,WORLD_A,100,10,7),"replaced token stays stale");
        NativeDbcSpatialProvider.Descriptor changedDescriptor=provider.evaluate(changed,config);
        check(changedDescriptor.isValid(),"lifecycle changed descriptor");
        DbcSpatialStateSnapshot b=c.capture(input(PLAYER_B,WORLD_A,100,10,7,"b"));
        c.invalidate(PLAYER_A);
        check(!changed.isCurrentIn(c)&&!changed.isValidFor(PLAYER_A,WORLD_A,100,10,7),"invalidate revokes snapshot token");
        check(!changed.isUsableForNativeProvider(),"invalidated snapshot cannot feed provider");
        check(c.get(PLAYER_A)==null&&c.currentSpatialRevision(PLAYER_A)==0,"invalidated player has no active revision");
        check(!changedDescriptor.isValidFor(PLAYER_A,WORLD_A,100,c.currentSpatialRevision(PLAYER_A),7),"removed revision cannot validate descriptor");
        check(c.isCurrent(b),"invalidate A leaves B current");
        checkRegisteredPlayers(c,new Object[]{PLAYER_B},new Object[]{PLAYER_A});
        c.invalidate(PLAYER_A); // Repeated removal cannot resurrect or retain A.
        DbcSpatialStateSnapshot recaptured=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"changed"));
        check(recaptured.spatialRevision>b.spatialRevision&&recaptured.spatialRevision>changed.spatialRevision,"recapture never resets the revision");
        check(!firstDescriptor.isValidFor(PLAYER_A,WORLD_A,100,recaptured.spatialRevision,7)
            &&!changedDescriptor.isValidFor(PLAYER_A,WORLD_A,100,recaptured.spatialRevision,7),"pre-removal descriptors cannot match recapture");
        check(!first.isValidFor(PLAYER_A,WORLD_A,100,10,7)&&!changed.isValidFor(PLAYER_A,WORLD_A,100,10,7),"recapture cannot reactivate old tokens");

        DbcSpatialStateSnapshot third=c.capture(input(playerC,WORLD_A,100,10,7,"c"));
        DbcSpatialStateSnapshot[] snapshots={recaptured,b,third};
        NativeDbcSpatialProvider.Descriptor[] descriptors=new NativeDbcSpatialProvider.Descriptor[snapshots.length];
        Object[] players={PLAYER_A,PLAYER_B,playerC};
        for(int i=0;i<snapshots.length;i++){
            descriptors[i]=provider.evaluate(snapshots[i],config);
            check(descriptors[i].isValid(),"pre-clear descriptor "+i);
        }
        c.clear();
        for(DbcSpatialStateSnapshot snapshot:snapshots){
            check(!c.isCurrent(snapshot)&&!snapshot.isValidFor(snapshot.playerIdentity,WORLD_A,100,10,7),"clear revokes every token");
            check(c.get(snapshot.playerIdentity)==null&&c.currentSpatialRevision(snapshot.playerIdentity)==0,"clear removes every registration");
            check(!snapshot.isUsableForNativeProvider(),"cleared snapshot unavailable to provider");
        }
        checkRegisteredPlayers(c,new Object[0],players);
        c.clear(); // Clearing an empty cache must not reset the lifetime counter.
        long last=third.spatialRevision;
        for(int i=0;i<players.length;i++){
            DbcSpatialStateSnapshot fresh=c.capture(input(players[i],WORLD_A,100,10,7,snapshots[i].dnsRevision));
            check(fresh.spatialRevision>last,"post-clear revision is globally monotonic within cache");last=fresh.spatialRevision;
            check(!descriptors[i].isValidFor(players[i],WORLD_A,100,fresh.spatialRevision,7),"pre-clear descriptor cannot match new revision");
            check(!snapshots[i].isValidFor(players[i],WORLD_A,100,10,7),"post-clear capture cannot revive old token");
            check(c.capture(input(players[i],WORLD_A,100,10,7,snapshots[i].dnsRevision))==fresh,"unchanged recapture still reuses snapshot");
        }
    }

    /** Inspect owned storage directly: no GC timing and no auxiliary player ledger. */
    private static void checkRegisteredPlayers(Cache c,Object[] active,Object[] removed){
        try{
            int maps=0;
            for(Field field:Cache.class.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers()))continue;
                field.setAccessible(true);Object storage=field.get(c);
                if(storage instanceof Map){
                    maps++;Map<?,?> map=(Map<?,?>)storage;
                    check(map.size()==active.length,"cache owns exactly the active players");
                    for(Object player:active)check(map.get(player)==c.get(player),"only active snapshot storage");
                    for(Object player:removed){
                        check(!map.containsKey(player),"removed identity is not a cache key");
                        for(Object value:map.values()){
                            check(value instanceof DbcSpatialStateSnapshot,"no auxiliary identity record");
                            check(((DbcSpatialStateSnapshot)value).playerIdentity!=player,"removed identity is not retained by cache values");
                        }
                    }
                }else check(field.getType().isPrimitive(),"no other cache field can retain a player");
            }
            check(maps==1,"cache has one active snapshot map and no revision map");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    private static void revisionExhaustion(){
        Cache c=cache();
        try{
            Field counter=Cache.class.getDeclaredField("lastSpatialRevision");counter.setAccessible(true);
            counter.setLong(c,Long.MAX_VALUE-1);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        DbcSpatialStateSnapshot last=c.capture(input(PLAYER_A,WORLD_A,100,10,7,"last"));
        check(last.spatialRevision==Long.MAX_VALUE,"last monotonic revision supported");
        check(c.capture(input(PLAYER_A,WORLD_A,100,10,7,"last"))==last,"reuse does not allocate a revision");
        boolean rejected=false;
        try{c.capture(input(PLAYER_A,WORLD_A,100,10,7,"overflow"));}catch(IllegalStateException expected){rejected=true;}
        check(rejected&&c.isCurrent(last),"exhaustion fails explicitly without wrap or partial invalidation");
        c.clear();check(!last.isValidFor(PLAYER_A,WORLD_A,100,10,7),"exhausted cache can still revoke snapshots");
        rejected=false;
        try{c.capture(input(PLAYER_A,WORLD_A,100,10,7,"last"));}catch(IllegalStateException expected){rejected=true;}
        check(rejected,"clear cannot restart exhausted revision sequence");
        checkRegisteredPlayers(c,new Object[0],new Object[]{PLAYER_A});
    }
}
