import com.nicolas.epicfight1710.combat.NativeDbcSpatialProvider;
import com.nicolas.epicfight1710.combat.NativeDbcSpatialProvider.*;

/** Compares native equation slices generated locally from hash-checked JARs.
 * No GPU/native draw or claim of full world/Hand/Tool placement parity.
 */
public final class NativeDbcSpatialProviderTest {
    private static long checks,states;
    private static final Object PLAYER=new Object(),WORLD=new Object();
    private static final NativeDbcSpatialProvider provider=new NativeDbcSpatialProvider();
    private static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    private static void bits(float[] a,float[] b,String m){for(int i=0;i<a.length;i++)check(Float.floatToIntBits(a[i])==Float.floatToIntBits(b[i]),m+"["+i+"] "+a[i]+" != "+b[i]);}
    private static State state(int race,int form,int con,int release,int variant,float f,boolean child,boolean sneak,boolean divine,int y,Flight flight){
        return new State(PLAYER,WORLD,12,44,race,form,con,release,variant,f,.0625F,child,sneak,divine,false,y,flight,1);
    }
    public static void main(String[] args){
        NativeFormulaOracle formula=new NativeFormulaOracle();
        NativeBodyOracle body=new NativeBodyOracle();
        float[][] bulk=NativeFormulaOracle.tables(false),size=NativeFormulaOracle.tables(true);
        for(boolean conOn:new boolean[]{false,true})for(boolean transOn:new boolean[]{false,true})
        for(boolean god:new boolean[]{false,true})for(boolean divine:new boolean[]{false,true}){
            NativeFormulaOracle.configure(conOn,transOn,god,1000);
            Config config=new Config(91,conOn,transOn,god,1000,bulk,size);
            for(int race=0;race<6;race++)for(int form=0;form<bulk[race].length;form++){
                if((race==1||race==2)&&(form==7||form==8))continue;
                for(int variant=1;variant<=3;variant++)for(int release:new int[]{0,49,50,51,100})for(int con:new int[]{0,100,1250}){
                    State input=state(race,form,con,release,variant,1,false,false,divine,1,Flight.GROUNDED);
                    Descriptor d=provider.evaluate(input,config);check(d.isValid(),d.invalidReason());
                    float[] got=new float[3];d.copyOuterScale(got);
                    bits(formula.scale(variant,race,form,con,release,divine),got,"native preRender scale");
                    check(!d.hasCompleteWorldTransform(),"must not claim world/socket completeness");states++;
                }
            }
        }
        Config config=new Config(91,true,true,true,1000,bulk,size);
        for(int variant=1;variant<=3;variant++)for(float f:new float[]{.75F,1,1.25F,1.5F,1.75F,2,2.5F})
        for(boolean child:new boolean[]{false,true})for(boolean sneak:new boolean[]{false,true}){
            State input=state(0,0,100,50,variant,f,child,sneak,false,1,Flight.GROUNDED);
            Descriptor d=provider.evaluate(input,config);check(d.isValid(),d.invalidReason());
            NativeBodyOracle.f=f;NativeBodyOracle.g=variant;body.field_78091_s=child;body.field_78117_n=sneak;
            NativeBodyOracle.output.clear();body.renderBody(.0625F);
            String[] names=variant<=1?new String[]{"field_78116_c","field_78115_e","field_78112_f","field_78113_g","field_78123_h","field_78124_i"}
                :new String[]{"field_78116_c","body","Brightarm","Bleftarm","rightleg","leftleg"};
            for(Part part:Part.values()){
                float[] ops=new float[6];check(d.copyPartOperations(part,ops),"part available");
                bits(NativeBodyOracle.output.get(names[part.ordinal()]),ops,"native part S/T "+part);
                float[] matrix=new float[16];d.copyPartMatrix(part,matrix);
                check(matrix[7]==ops[1]*ops[4],"S*T scales translation (not T*S)");
            }
        }
        State input=state(0,0,100,50,1,1,false,false,false,1,Flight.NORMAL);
        Descriptor first=provider.evaluate(input,config);check(first.isValid(),"normalized non-prone flight");
        float[] expected=new float[3];first.copyOuterScale(expected);
        // There is no camera, yaw, renderer or GL input/dependency to the provider.
        // Yaw rotates the eventual world basis, not these native local descriptors.
        for(int yaw:new int[]{0,90,180,-90})for(int camera=0;camera<3;camera++)for(int n=0;n<10;n++){
            float[] result=new float[3];provider.evaluate(input,config).copyOuterScale(result);bits(expected,result,"camera/yaw-independent local inputs "+yaw);
        }
        check(first.isValidFor(PLAYER,WORLD,12,44,91),"context matches");
        check(!first.isValidFor(new Object(),WORLD,12,44,91),"player invalidation");
        check(!first.isValidFor(PLAYER,new Object(),12,44,91),"world invalidation");
        check(!first.isValidFor(PLAYER,WORLD,13,44,91),"tick invalidation");
        check(!first.isValidFor(PLAYER,WORLD,12,45,91),"form revision invalidation");
        check(!first.isValidFor(PLAYER,WORLD,12,44,92),"config revision invalidation");
        for(int y:new int[]{2,3,4,5,6,7})check(!provider.evaluate(state(0,0,100,50,1,1,false,false,false,y,Flight.GROUNDED),config).isValid(),"unsupported y rejected");
        check(!provider.evaluate(state(0,0,100,50,1,1,false,false,false,1,Flight.FAST),config).isValid(),"fast flight rejected");
        check(!provider.evaluate(state(1,7,100,50,1,1,false,false,false,1,Flight.GROUNDED),config).isValid(),"Oozaru rejected");
        check(!provider.evaluate(state(0,99,100,50,1,1,false,false,false,1,Flight.GROUNDED),config).isValid(),"missing table has no fallback");
        check(!provider.evaluate(input,new Config(0,true,true,true,99,bulk,size)).isValid(),"zero denominator rejected");
        check(!provider.evaluate(input,new Config(0,true,true,true,1000,null,null)).isValid(),"missing config rejected");
        // Config and outputs cannot be mutated through caller buffers.
        float[] old=new float[3];first.copyOuterScale(old);expected[0]=999;first.copyOuterScale(expected);bits(old,expected,"output ownership");
        bulk[0][0]=99;float[] result=new float[3];provider.evaluate(input,config).copyOuterScale(result);bits(old,result,"config array ownership");
        System.out.println("PASS NativeDbcSpatialProviderTest: "+states+" native scale states; 84 body cases; "+checks+" assertions");
        System.out.println("BLOCKED by design: complete world/Hand/Tool sockets, fast flight, native y=2..7, Oozaru, cosmetic/accessory topology, live capture adapter");
    }
}
