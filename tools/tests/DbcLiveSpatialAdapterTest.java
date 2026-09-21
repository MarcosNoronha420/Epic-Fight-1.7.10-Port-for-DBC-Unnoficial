import com.nicolas.epicfight1710.combat.*;
import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Exercises actual reflective runtime reader with protocol fixtures. No MC/GL classes. */
public final class DbcLiveSpatialAdapterTest {
    private static int checks,mutations;
    private static final String[] NATIVE_TOKENS=NativeLiveReadOracle.StusEfcts.clone();
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static final class World {public boolean isRemote=true;}
    public static class Player {
        public Object worldObj;
        public int ticksExisted=20;
        public boolean onGround=true,sneak,child,invisible;
        final String name;
        final Watcher watcher=new Watcher();
        Player(String name,Object world){this.name=name;worldObj=world;}
        public String getCommandSenderName(){return name;}
        public boolean isChild(){return child;}
        public boolean isSneaking(){return sneak;}
        public boolean isInvisible(){return invisible;}
        public Watcher getDataWatcher(){return watcher;}
        public void setState(){mutations++;throw new AssertionError("setter invoked");}
        public void tick(){mutations++;throw new AssertionError("tick invoked");}
    }
    public static final class RemotePlayer extends Player {RemotePlayer(String n,Object w){super(n,w);}}
    public static final class Watcher {
        String row="0;0;0f;0;0;0;0;0;0;0;0;0;";
        public String getWatchableObjectString(int id){check(id==CoreConfig.ExtendedPlayerOtherID,"watcher id");return row;}
        public void updateObject(int id,Object value){mutations++;throw new AssertionError("watcher mutation");}
    }
    public static final class Minecraft {public Object thePlayer,theWorld;}
    public static final class Client {public static Minecraft mc=new Minecraft();}
    public static final class Flight {public static boolean floating;private static boolean dodge_forwDash_STE;}
    private static final class VisualNoise {
        static int camera,lastY,draws;static Object lastPlayer;
        static void draw(Object player){draws++;lastY=(lastY+1)%8;lastPlayer=player;}
    }
    public static final class Dbc {public static boolean ConsSizeChangeOn,TransSizeChangeOn;}
    public static final class DbcConfig {public static boolean GodformCosm;}
    public static final class CoreConfig {public static int tmx=1000,ExtendedPlayerOtherID=23;}
    public static final class Years {public static String[] p;}
    public static final class YearsConfig {public static int pgut=52;}
    public static final class Core extends NativeLiveReadOracle {
        public static String[] plyrs,data1,data2,data3,data4,dat10,dat14,dat19;
        public static float[] TransHmBlk={1,1.1F},TransSaiBlk={1,1.2F},TransNaBlk={1,1.3F},TransFrBlk={1,1.4F},TransMaBulk={1,1.5F};
        public static float[] TransHmSz={1,1.1F},TransSaiSz={1,1.2F},TransNaSz={1,1.3F},TransFrSz={1,1.4F},TransMaSize={1,1.5F};
        public static boolean years=true,family=true,dbc=true;
        public static boolean JYC(){return years;}
        public static boolean JFC(){return family;}
        public static boolean DBC(){return dbc;}
        public static void serverTick(){mutations++;throw new AssertionError("native tick invoked");}
    }
    private static class Resolver implements ReflectiveDbcSpatialSource.ClassResolver {
        final Map<String,Class<?>> types=new HashMap<String,Class<?>>();
        Resolver(){
            types.put("JinRyuu.JRMCore.JRMCoreH",Core.class);
            types.put("JinRyuu.JRMCore.JRMCoreConfig",CoreConfig.class);
            types.put("JinRyuu.JRMCore.JYearsCH",Years.class);
            types.put("JinRyuu.JYearsC.JYearsCConfig",YearsConfig.class);
            types.put("JinRyuu.DragonBC.common.mod_DragonBC",Dbc.class);
            types.put("JinRyuu.DragonBC.common.DBCConfig",DbcConfig.class);
            types.put("JinRyuu.DragonBC.common.DBCClient",Client.class);
            types.put("JinRyuu.DragonBC.common.DBCKiTech",Flight.class);
        }
        public Class<?> load(String name)throws ClassNotFoundException{
            check(!name.contains("Render")&&!name.contains("Model")&&!name.contains("GL"),"forbidden class "+name);
            Class<?> c=types.get(name);if(c==null)throw new ClassNotFoundException(name);return c;
        }
    }
    private static String dns(int gender,int body){
        char[] s=new char[52];Arrays.fill(s,'0');s[2]=(char)('0'+gender);s[13]='1';s[15]=(char)('0'+body);return new String(s);
    }
    private static void reset(World world,Player a){
        Core.years=Core.family=Core.dbc=true;
        Core.plyrs=new String[]{"A","B"};
        Core.data1=new String[]{"1;"+dns(0,1)+";1;0","3;"+dns(1,2)+";1;0"};
        Core.data2=new String[]{"0;0","1;0"};Core.data3=new String[]{"0","0"};Core.data4=new String[]{"0;0;0","0;0;0"};
        Core.dat10=new String[]{"50;100","60;200"};Core.dat14=new String[]{"1,2,100,4,5,6","1,2,200,4,5,6"};
        Core.dat19=new String[]{"0; ;","0; ;"};Core.StusEfcts=NATIVE_TOKENS.clone();
        Years.p=new String[]{"B;40","A;12"};YearsConfig.pgut=52;
        Dbc.ConsSizeChangeOn=false;Dbc.TransSizeChangeOn=false;DbcConfig.GodformCosm=false;
        CoreConfig.tmx=1000;Client.mc.thePlayer=a;Client.mc.theWorld=world;
        Flight.floating=false;Flight.dodge_forwDash_STE=false;
    }
    private static DbcSpatialStateSnapshot capture(DbcLiveSpatialAdapter a,Player p){return a.capture(p,10);}
    private static void stale(DbcLiveSpatialAdapter adapter,DbcSpatialStateSnapshot old,DbcSpatialStateSnapshot fresh,String why){
        check(fresh!=old&&fresh.spatialRevision!=old.spatialRevision,why+" revision");
        check(!adapter.isCurrent(old)&&adapter.isCurrent(fresh),why+" stale authority");
    }
    public static void main(String[] args){
        World world=new World();Player a=new Player("A",world),b=new RemotePlayer("B",world);reset(world,a);
        ReflectiveDbcSpatialSource source=new ReflectiveDbcSpatialSource(new Resolver());
        DbcLiveSpatialConfigAdapter config=new DbcLiveSpatialConfigAdapter(source);
        DbcLiveSpatialAdapter adapter=new DbcLiveSpatialAdapter(source,config);
        DbcSpatialStateSnapshot sa=capture(adapter,a),sb=capture(adapter,b);
        check(sa.isUsableForNativeProvider()&&sb.isUsableForNativeProvider(),"normal live fixtures usable without render");
        check(sa.race==1&&sa.form==0&&sa.ageYears==12&&sa.gender==0&&sa.modelVariant==1,"A data");
        check(sb.race==3&&sb.form==1&&sb.ageYears==40&&sb.gender==1&&sb.modelVariant==2,"B data");
        check(!sa.dnsRevision.equals(sb.dnsRevision),"DNS isolation");
        for(Player p:new Player[]{a,b,a,b,b,a,b,a})check(capture(adapter,p)==(p==a?sa:sb),"alternating players reuse");
        long lookups=source.structuralLookups(),evaluations=adapter.completeCaptures();
        for(int renders:new int[]{0,1,10}){
            // No renderer/camera classes exist in this JVM. Presentation activity
            // is deliberately outside the only readable source interface.
            for(int camera=0;camera<3;camera++){
                VisualNoise.camera=camera;
                for(int i=0;i<renders;i++)VisualNoise.draw(i%2==0?a:b);
                check(capture(adapter,a)==sa&&capture(adapter,b)==sb,"render/camera noise must not affect snapshot");
            }
        }
        for(int i=0;i<100;i++)check(capture(adapter,a)==sa,"shared target sample");
        check(adapter.completeCaptures()==evaluations,"no full captures per target");
        check(source.structuralLookups()==lookups,"structural discovery cached across A/B");
        check(config.capture()==adapter.config(),"one global config");

        Core.data1[0]="2;"+dns(0,1)+";1;0";
        DbcSpatialStateSnapshot changed=capture(adapter,a);stale(adapter,sa,changed,"race");sa=changed;
        Core.data2[0]="1;0";changed=capture(adapter,a);stale(adapter,sa,changed,"form/transformation");sa=changed;
        check(sa.form==1&&sa.transformationState==1,"preRender form is data2 state");
        Core.data1[0]="2;"+dns(0,3)+";1;0";changed=capture(adapter,a);stale(adapter,sa,changed,"DNS");sa=changed;
        check(sa.bodyType==3,"body selector parsed");
        Years.p=new String[]{"A;20","B;40"};changed=capture(adapter,a);stale(adapter,sa,changed,"age");sa=changed;
        Core.data1[0]="2;"+dns(1,3)+";1;0";changed=capture(adapter,a);stale(adapter,sa,changed,"gender");sa=changed;
        check(sa.modelVariant==2&&sa.gender==1,"gender/model variant");
        check(capture(adapter,b)==sb,"A mutations do not change B revision");

        NativeDbcSpatialProvider provider=new NativeDbcSpatialProvider();
        NativeDbcSpatialProvider.Config cfg=config.capture().config;
        NativeDbcSpatialProvider.Descriptor da=provider.evaluate(sa,cfg);
        check(da.isValid()&&da.state.revision==sa.spatialRevision,"provider gets spatial revision");
        Core.data1[0]="2;"+dns(1,4)+";1;0";changed=capture(adapter,a);
        check(!da.isValidFor(a,world,20,changed.spatialRevision,cfg.revision),"old descriptor stale");
        check(provider.evaluate(changed,cfg).isValidFor(a,world,20,changed.spatialRevision,cfg.revision),"new descriptor valid");
        sa=changed;
        a.onGround=false;Flight.floating=true;changed=capture(adapter,a);stale(adapter,sa,changed,"normal flight");sa=changed;
        check(sa.flight==FlightState.NORMAL&&sa.nativeState==1,"local floating does not manufacture prone");
        b.onGround=false;check(capture(adapter,b).flight==FlightState.UNKNOWN,"local floating cannot leak to remote");
        Core.data3[0]="1";Core.dat19[0]="0;"+Core.StusEfcts[9]+";";sa=capture(adapter,a);
        check(sa.flight==FlightState.DIRECTIONAL&&sa.presentation==BodyPresentation.PRONE&&sa.nativeState==2,"directional/prone");
        Core.dat19[0]="0;"+Core.StusEfcts[7]+";";sa=capture(adapter,a);
        check(sa.flight==FlightState.FAST&&!provider.evaluate(sa,cfg).isValid(),"fast represented but world provider blocked");
        Core.dat19[0]="0; ;";Core.data3[0]="0";Flight.floating=false;Flight.dodge_forwDash_STE=true;
        check(capture(adapter,a).flight==FlightState.FAST,"local live swoop while floating false");
        Flight.dodge_forwDash_STE=false;a.onGround=true;b.onGround=true;
        Core.data4[0]="0;0;1";sa=capture(adapter,a);
        check(sa.presentation==BodyPresentation.KO&&sa.nativeState==3,"KO logical source");
        Core.data4[0]="0;0;0";a.watcher.row="0;0;0f;0;0;0;0;2;0;3;0;0;";sa=capture(adapter,a);
        check(sa.presentation==BodyPresentation.UI&&sa.nativeState==7,"UI logical state 7");
        check(sa.ageScale<1,"visual state 7 cannot trigger age exception");
        a.watcher.row="0;0;0f;0;0;0;0;0;0;0;0;0;";
        for(int state:new int[]{7,8,14}){
            Core.data2[0]=state+";0";sa=capture(adapter,a);
            check(sa.transformationState==state&&sa.nativeState==1&&sa.ageScale==1&&sa.ageDivisor==1,"age exception "+state);
        }
        Core.data1[0]="2;"+dns(0,1)+";2;0";
        check(capture(adapter,a).ageScale<1,"chakra gate before age state");
        Core.data1[0]="0;"+dns(0,1)+";1;0";
        check(capture(adapter,a).ageScale<1,"human gate before age state");

        reset(world,a);sa=capture(adapter,a);
        Core.data2=new String[]{"0;0"};changed=capture(adapter,a);stale(adapter,sa,changed,"short parallel array");
        check(changed.dbcAvailability==Availability.UNAVAILABLE&&!changed.isUsableForNativeProvider(),"native dnn bounds enforced");
        Core.data2=new String[]{"0;0","1;0"};sa=capture(adapter,a);stale(adapter,changed,sa,"late data arrival");
        Years.p=null;changed=capture(adapter,a);check(changed.ageAvailability==Availability.UNAVAILABLE,"delayed age");
        Years.p=new String[]{"A;12","B;40"};stale(adapter,changed,capture(adapter,a),"late age arrival");
        Core.years=false;sa=capture(adapter,a);
        check(sa.jYearsCAvailability==Availability.NOT_APPLICABLE&&sa.ageDivisor==1,"JYearsC absent");
        Core.family=false;sa=capture(adapter,b);
        check(sa.jFamilyCAvailability==Availability.NOT_APPLICABLE&&sa.modelVariant==1,"JFC absent native initial variant");
        Core.years=true;Core.family=true;
        Core.data1[0]="1;;1;0";sa=capture(adapter,a);
        check(sa.jFamilyCAvailability==Availability.UNAVAILABLE&&sa.dnsRevision==null&&!sa.isUsableForNativeProvider(),"missing DNS never inherited");
        reset(world,a);sa=capture(adapter,a);
        World nextWorld=new World();a.worldObj=nextWorld;changed=capture(adapter,a);stale(adapter,sa,changed,"world change");
        check(changed.dbcAvailability==Availability.UNAVAILABLE,"other-world client rows refused");
        Client.mc.theWorld=nextWorld;changed=capture(adapter,a);
        check(changed.worldIdentity==nextWorld&&changed.isUsableForNativeProvider(),"new matching client world");
        a.worldObj=world;Client.mc.theWorld=world;
        Core.plyrs=new String[]{"B","A"};
        for(String[] array:new String[][]{Core.data1,Core.data2,Core.data3,Core.data4,Core.dat10,Core.dat14,Core.dat19}){
            String t=array[0];array[0]=array[1];array[1]=t;
        }
        check(capture(adapter,a).race==1&&capture(adapter,b).race==3,"index resolved after protocol reordering");
        Core.plyrs=new String[]{"A","A"};check(capture(adapter,a).dbcAvailability==Availability.UNAVAILABLE,"duplicate names refused");
        reset(world,a);a.watcher.row="pending";sa=capture(adapter,a);
        check(sa.nativeState==-1&&sa.bodyAvailability==Availability.UNAVAILABLE,"missing watcher cannot invent normal presentation");
        a.watcher.row="0;0;0f;0;0;0;0;0;0;0;0;0;";
        Core.dat19[0]="0;"+Core.StusEfcts[11]+";";sa=capture(adapter,a);check(sa.spectator&&sa.presentation==BodyPresentation.SPECTATOR,"fusion spectator");
        reset(world,a);sa=capture(adapter,a);
        DbcLiveSpatialConfigAdapter.Snapshot c1=config.capture();
        Dbc.ConsSizeChangeOn=true;Dbc.TransSizeChangeOn=true;
        DbcLiveSpatialConfigAdapter.Snapshot c2=config.capture();
        check(c2.revision!=c1.revision&&config.capture()==c2,"real global config change revision");
        changed=capture(adapter,a);stale(adapter,sa,changed,"config geometry revision");
        check(capture(adapter,b).geometryRevision==changed.geometryRevision,"same config for both players");
        NativeDbcSpatialProvider.Descriptor before=provider.evaluate(changed,c2.config);
        float[] original=new float[3],again=new float[3];check(before.copyOuterScale(original),"original config scale");
        float bulk=Core.TransSaiBlk[0];Core.TransSaiBlk[0]=1.25F;
        DbcLiveSpatialConfigAdapter.Snapshot c3=config.capture();check(c3.revision!=c2.revision,"in-place table edit revision");
        check(provider.evaluate(changed,c2.config).copyOuterScale(again)&&Arrays.equals(original,again),"old immutable config owns its arrays");
        Core.TransSaiBlk[0]=bulk;CoreConfig.tmx++;check(config.capture().revision!=c3.revision,"maxAttribute revision");
        long revision=config.capture().revision;DbcConfig.GodformCosm=true;
        check(config.capture().revision!=revision,"god cosmetic config revision");

        independentFields(world,a);
        nativePresentationOracle(world,a);
        Resolver noCore=new Resolver();noCore.types.remove("JinRyuu.JRMCore.JRMCoreH");
        ReflectiveDbcSpatialSource absent=new ReflectiveDbcSpatialSource(noCore);
        check(absent.read(a).jrmCore==Availability.NOT_APPLICABLE,"missing addon safe");
        long absentLookups=absent.structuralLookups();absent.read(a);
        check(absent.structuralLookups()==absentLookups,"negative class discovery cached");
        Resolver broken=new Resolver(){public Class<?> load(String name)throws ClassNotFoundException{
            if(name.equals("JinRyuu.JRMCore.JRMCoreH"))throw new NoClassDefFoundError("broken dependency");return super.load(name);
        }};
        check(new ReflectiveDbcSpatialSource(broken).read(a).jrmCore==Availability.UNAVAILABLE,"broken class differs from absent addon");
        Resolver noConfig=new Resolver();noConfig.types.remove("JinRyuu.DragonBC.common.DBCConfig");
        ReflectiveDbcSpatialSource missingConfig=new ReflectiveDbcSpatialSource(noConfig);
        DbcLiveSpatialAdapter unavailable=new DbcLiveSpatialAdapter(missingConfig,new DbcLiveSpatialConfigAdapter(missingConfig));
        check(capture(unavailable,a).geometryAvailability==Availability.UNAVAILABLE,"missing runtime config fails closed");
        check(!capture(unavailable,a).isUsableForNativeProvider(),"missing config not replaced by defaults");
        sa=capture(adapter,a);Core.data1[0]="broken;"+dns(0,1)+";1;0";
        changed=capture(adapter,a);stale(adapter,sa,changed,"malformed data");
        check(changed.dbcAvailability==Availability.UNAVAILABLE&&changed.nativeState==-1,"malformed row cannot synthesize presentation");
        reset(world,a);Years.p=new String[]{"A;12","A;13"};
        check(capture(adapter,a).ageAvailability==Availability.UNAVAILABLE,"duplicate age rows fail closed");
        check(mutations==0,"no setters/ticks/mutators");
        adapter.clear();check(!adapter.isCurrent(changed),"disconnect invalidates retained samples");
        System.out.println("PASS live spatial adapter: "+checks+" checks; no graphics/native runtime required");
    }
    private static void nativePresentationOracle(World world,Player a){
        reset(world,a);
        ReflectiveDbcSpatialSource source=new ReflectiveDbcSpatialSource(new Resolver());
        for(boolean ground:new boolean[]{false,true})for(int ko:new int[]{0,1})
        for(int time:new int[]{0,5,-1})for(int ui=0;ui<4;ui++)for(int mask=0;mask<8;mask++)for(int flag=0;flag<2;flag++){
            String statuses="0;"+((mask&1)!=0?Core.StusEfcts[7]:"")+((mask&2)!=0?Core.StusEfcts[9]:"")+((mask&4)!=0?Core.StusEfcts[4]:"")+" ;";
            a.onGround=ground;Core.data3[0]=""+flag;Core.dat19[0]=statuses;Core.data4[0]="0;0;"+ko;
            a.watcher.row="0;0;0f;0;0;0;0;"+time+";0;"+ui+";0;0;";
            int expected=NativeLiveReadOracle.presentation(Core.data4[0],Core.data3[0],statuses,ground,time,ui);
            check(source.read(a).nativeState==expected,"native preRender condition oracle");
        }
        a.onGround=true;a.watcher.row="0;0;0f;0;0;0;0;0;0;0;0;0;";
        reset(world,a);
    }
    private static void independentFields(World world,Player player){
        reset(world,player);
        final ReflectiveDbcSpatialSource runtime=new ReflectiveDbcSpatialSource(new Resolver());
        final int[] form={2},transform={7},nativeState={1};
        DbcLiveSpatialAdapter.Source fixture=new DbcLiveSpatialAdapter.Source(){public DbcLiveSpatialAdapter.Readings read(Object p){
            DbcLiveSpatialAdapter.Readings r=runtime.read(p);r.form=form[0];r.transformationState=transform[0];r.nativeState=nativeState[0];return r;
        }};
        DbcLiveSpatialAdapter adapter=new DbcLiveSpatialAdapter(fixture,new DbcLiveSpatialConfigAdapter(runtime));
        DbcSpatialStateSnapshot a=capture(adapter,player);
        check(a.ageScale==1&&a.form==2&&a.nativeState==1,"independent transformation exception");
        form[0]=3;DbcSpatialStateSnapshot b=capture(adapter,player);stale(adapter,a,b,"only form");
        transform[0]=0;nativeState[0]=7;a=capture(adapter,player);
        check(a.ageScale<1&&a.nativeState==7,"native visual state independent");
        for(int t:new int[]{7,8,14}){transform[0]=t;nativeState[0]=1;b=capture(adapter,player);
            check(b.ageScale==1&&b.ageDivisor==1,"independent state "+t);stale(adapter,a,b,"transformation only");a=b;}
    }
}
