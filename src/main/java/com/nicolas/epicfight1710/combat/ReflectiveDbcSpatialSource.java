package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.util.Reflect;
import com.nicolas.epicfight1710.combat.DbcLiveSpatialAdapter.Readings;
import com.nicolas.epicfight1710.combat.DbcSpatialStateSnapshot.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact 1.7.10 client protocol reader. No native tick, NBT helper, render class,
 * Minecraft bootstrap or input method is invoked. All discovery is cached,
 * including negative lookups. Construct after mod initialization; game thread only. */
public final class ReflectiveDbcSpatialSource implements DbcLiveSpatialAdapter.Source,DbcLiveSpatialConfigAdapter.Source {
    public interface ClassResolver { Class<?> load(String name) throws ClassNotFoundException; }
    private static final String CORE="JinRyuu.JRMCore.JRMCoreH";
    private static final String CORE_CONFIG="JinRyuu.JRMCore.JRMCoreConfig";
    private static final String DBC="JinRyuu.DragonBC.common.mod_DragonBC";
    private static final String DBC_CONFIG="JinRyuu.DragonBC.common.DBCConfig";
    private static final String CLIENT="JinRyuu.DragonBC.common.DBCClient";
    private static final String FLIGHT="JinRyuu.DragonBC.common.DBCKiTech";
    private static final String YEARS="JinRyuu.JRMCore.JYearsCH";
    private static final String YEARS_CONFIG="JinRyuu.JYearsC.JYearsCConfig";
    private final ClassResolver resolver;
    private final Map<String,Class<?>> classes=new HashMap<String,Class<?>>();
    private final Set<String> absent=new HashSet<String>();
    private final Map<Class<?>,Access> access=new HashMap<Class<?>,Access>();
    private final Set<String> reported=new HashSet<String>();
    private final List<String> diagnostics=new ArrayList<String>();
    private long lookups;

    public ReflectiveDbcSpatialSource(){
        this(new ClassResolver(){public Class<?> load(String name)throws ClassNotFoundException{
            return Class.forName(name,false,ReflectiveDbcSpatialSource.class.getClassLoader());
        }});
    }
    /** Injectable class resolver exercises the SAME reflection/protocol reader in tests. */
    public ReflectiveDbcSpatialSource(ClassResolver resolver){
        if(resolver==null)throw new IllegalArgumentException("Class resolver required");this.resolver=resolver;
    }
    public long structuralLookups(){return lookups;}
    public List<String> diagnostics(){return new ArrayList<String>(diagnostics);}

    public Readings read(Object player){
        Readings r=new Readings();
        if(player==null)return r;
        r.world=value(player,"field_70170_p","worldObj");
        r.tick=integer(value(player,"field_70173_aa","ticksExisted"),-1);
        String name=string(call(player,"func_70005_c_","getCommandSenderName"));
        r.jrmCore=presence(CORE);
        r.years=mod("JYC");r.family=mod("JFC");
        Availability dbc=mod("DBC");
        r.dbc=dbc==Availability.NOT_APPLICABLE?dbc:Availability.UNAVAILABLE;
        if(r.jrmCore!=Availability.AVAILABLE||dbc!=Availability.AVAILABLE)return r;

        // Client-wide parallel arrays may only describe players in this live client world.
        Object mc=staticValue(CLIENT,"mc");
        Object local=value(mc,"field_71439_g","thePlayer");
        Object world=value(mc,"field_71441_e","theWorld");
        if(r.world==null||r.world!=world||!Boolean.TRUE.equals(value(world,"field_72995_K","isRemote")))return r;
        Rows rows=rows(name);
        if(rows==null)return r;
        String[] d1=split(rows.data1,";"),d2=split(rows.data2,";");
        String[] d10=split(rows.dat10,";"),attributes=split(rows.dat14,",");
        r.race=number(d1,0);r.powerType=number(d1,2);
        r.transformationState=number(d2,0);
        // The preRender scale table uses data2[0] directly. Cosmetic remapping in
        // other draw branches is NOT this form selector and is not imported.
        r.form=r.transformationState;r.release=number(d10,0);r.constitution=number(attributes,2);
        r.dns=d1.length>1&&!d1[1].isEmpty()?d1[1]:null;
        if(r.race>=0&&r.race<=5&&r.transformationState>=0&&r.release>=0&&r.release<=100
                &&r.constitution>=0&&r.powerType>=0)r.dbc=Availability.AVAILABLE;

        readAge(r,name);
        if(r.family==Availability.AVAILABLE&&r.dns==null)r.family=Availability.UNAVAILABLE;
        readBody(r,player,rows);
        readFlight(r,player==local,rows,player);
        return r;
    }

    private void readAge(Readings r,String name){
        if(r.years!=Availability.AVAILABLE)return;
        r.adultGrowth=integer(staticValue(YEARS_CONFIG,"pgut"),-1);
        String[] ages=strings(staticValue(YEARS,"p"));
        if(ages==null)return;
        boolean found=false;
        for(String row:ages){
            String[] columns=split(row,";");
            if(columns.length<1||!columns[0].equals(name))continue;
            if(found){r.ageRowPresent=false;problem("Duplicate JYearsC player row");return;}
            found=true;
            try{
                r.age=columns.length>1?Float.parseFloat(columns[1]):Float.NaN;
                r.ageRowPresent=!Float.isNaN(r.age)&&!Float.isInfinite(r.age)&&r.age>=0;
            }catch(NumberFormatException e){problem("Malformed JYearsC age row");}
        }
    }

    private void readBody(Readings r,Object player,Rows rows){
        Object child=call(player,"func_70631_g_","isChild");
        Object sneak=call(player,"func_70093_af","isSneaking");
        Object invisible=call(player,"func_82150_aj","isInvisible");
        r.child=Boolean.TRUE.equals(child);r.sneaking=Boolean.TRUE.equals(sneak);
        Boolean spectator=status(rows,11,true),divine=status(rows,17,false);
        r.spectator=Boolean.TRUE.equals(spectator);r.divine=r.race==3&&Boolean.TRUE.equals(divine);
        // These parsers are string-only helpers; short/missing DNS is rejected
        // before they can supply their native default zero.
        if(r.dns!=null&&r.dns.length()>=21){
            r.gender=integer(callStatic(CORE,"dnsGender",r.dns),-1);
            int skin=integer(callStatic(CORE,"dnsSkinT",r.dns),-1);
            if(skin>=0)r.bodyType=integer(callStatic(CORE,skin==0?"dnsBodyC1_0":"dnsBodyT",r.dns),-1);
        }
        if(r.family==Availability.NOT_APPLICABLE)r.modelVariant=1; // native initial gen without JFC
        else if(r.family==Availability.AVAILABLE&&r.gender>=0){
            int a=r.gender+1;r.modelVariant=a==2?2:a==3?3:1;
            int state=(r.powerType==2||r.race==0)?0:r.transformationState;
            if(r.powerType==1&&(r.race==1||r.race==2)&&(state==7||state==8))r.modelVariant=1;
        }
        // Fixed pixel unit of the exact native player model, not a fitted offset.
        r.modelPixelScale=.0625F;
        r.bodyKnown=child instanceof Boolean&&sneak instanceof Boolean&&invisible instanceof Boolean
            &&!Boolean.TRUE.equals(invisible)&&spectator!=null&&(r.race!=3||divine!=null)
            &&r.gender>=0&&r.bodyType>=0&&r.modelVariant>=1;
        if(r.spectator){r.presentation=BodyPresentation.SPECTATOR;return;}
        if(r.dbc!=Availability.AVAILABLE||!(invisible instanceof Boolean)||Boolean.TRUE.equals(invisible)||r.powerType!=1)return;
        int ko=number(split(rows.data4,";"),2);
        if(ko<0)return;
        if(ko==1){r.nativeState=3;r.presentation=BodyPresentation.KO;return;}
        // Read the SAME watcher string as ExtendedPlayer.getUIAnim/getUIAnimID.
        // Unlike getOtherCode, reject an unsynchronized row instead of substituting zeros.
        Object watcher=call(player,"func_70096_w","getDataWatcher");
        int id=integer(staticValue(CORE_CONFIG,"ExtendedPlayerOtherID"),-1);
        String[] ui=split(string(id<0?null:callWith(watcher,new String[]{"func_75681_e","getWatchableObjectString"},Integer.valueOf(id))),";");
        if(ui.length!=12)return;
        int time=signedNumber(ui,7),animation=number(ui,9);
        if(time==Integer.MIN_VALUE||animation<0)return;
        if(time!=0){
            if(animation>3)return;
            r.nativeState=4+animation;r.presentation=BodyPresentation.UI;return;
        }
        Boolean seven=status(rows,7,true),nine=status(rows,9,true),four=status(rows,4,true);
        Object ground=value(player,"field_70122_E","onGround");
        if(seven==null||nine==null||four==null||rows.data3==null||!(ground instanceof Boolean))return;
        boolean flag=rows.data3.contains("1");
        boolean w=seven||(nine&&flag&&!four);
        boolean prone=(w||flag)&&!Boolean.TRUE.equals(ground);
        r.nativeState=prone?2:1;r.presentation=prone?BodyPresentation.PRONE:BodyPresentation.NORMAL;
    }

    private void readFlight(Readings r,boolean local,Rows rows,Object player){
        if(r.powerType!=1)return;
        Object ground=value(player,"field_70122_E","onGround");
        if(!(ground instanceof Boolean))return;
        if(Boolean.TRUE.equals(ground)){r.flight=FlightState.GROUNDED;return;}
        Boolean seven=status(rows,7,false),nine=status(rows,9,false),four=status(rows,4,false);
        Object floating=local?staticValue(FLIGHT,"floating"):null;
        Object swoop=local?staticValue(FLIGHT,"dodge_forwDash_STE"):null;
        if(Boolean.TRUE.equals(seven)||Boolean.TRUE.equals(swoop)){r.flight=FlightState.FAST;return;}
        boolean flag=rows.data3!=null&&rows.data3.contains("1");
        if(Boolean.TRUE.equals(nine)&&Boolean.FALSE.equals(four)&&flag){r.flight=FlightState.DIRECTIONAL;return;}
        if(Boolean.TRUE.equals(floating)||flag)r.flight=FlightState.NORMAL;
        // Airborne with no positive logical evidence is UNKNOWN, never inferred flight.
    }

    private Boolean status(Rows rows,int id,boolean entityOverload){
        String[] tokens=strings(staticValue(CORE,"StusEfcts"));
        if(tokens==null||tokens.length<=id||tokens[id]==null||tokens[id].isEmpty()||rows.dat19==null)return null;
        String[] row=split(rows.dat19,";");
        if(row.length<2)return null;
        // Native EntityPlayer overload tests the whole row; int-index overload
        // tests column 1. Preserve that difference for presentation vs divine.
        return Boolean.valueOf((entityOverload?rows.dat19:row[1]).contains(tokens[id]));
    }

    private Rows rows(String name){
        if(name==null||name.isEmpty())return null;
        String[] names=strings(staticValue(CORE,"plyrs"));
        if(names==null)return null;
        int index=-1;
        for(int i=0;i<names.length;i++)if(name.equals(names[i])){
            if(index!=-1){problem("Duplicate JRMCore player name");return null;}index=i;
        }
        if(index<0)return null;
        Rows r=new Rows();
        r.data1=row("data1",names,index);r.data2=row("data2",names,index);r.data3=row("data3",names,index);
        r.data4=row("data4",names,index);r.dat10=row("dat10",names,index);
        r.dat14=row("dat14",names,index);r.dat19=row("dat19",names,index);
        if(staticValue(CORE,"plyrs")!=names||!name.equals(names[index]))return null;
        return r;
    }
    private String row(String field,String[] names,int index){
        String[] data=strings(staticValue(CORE,field));
        return data!=null&&data.length>=names.length?data[index]:null;
    }
    private static final class Rows {String data1,data2,data3,data4,dat10,dat14,dat19;}

    public DbcLiveSpatialConfigAdapter.Values readConfig(){
        DbcLiveSpatialConfigAdapter.Values v=new DbcLiveSpatialConfigAdapter.Values();
        Availability dbc=mod("DBC");
        if(dbc!=Availability.AVAILABLE){v.availability=dbc;return v;}
        Object con=staticValue(DBC,"ConsSizeChangeOn"),trans=staticValue(DBC,"TransSizeChangeOn");
        Object god=staticValue(DBC_CONFIG,"GodformCosm"),max=staticValue(CORE_CONFIG,"tmx");
        if(!(con instanceof Boolean)||!(trans instanceof Boolean)||!(god instanceof Boolean)||!(max instanceof Number))return v;
        v.constitutionSize=(Boolean)con;v.transformSize=(Boolean)trans;v.godCosmetics=(Boolean)god;
        v.maxAttribute=((Number)max).intValue();
        v.bulk=tables("TransHmBlk","TransSaiBlk","TransNaBlk","TransFrBlk","TransMaBulk");
        v.size=tables("TransHmSz","TransSaiSz","TransNaSz","TransFrSz","TransMaSize");
        if(v.transformSize&&(v.bulk==null||v.size==null))return v;
        v.availability=Availability.AVAILABLE;return v;
    }
    private float[][] tables(String human,String saiyan,String namek,String arcosian,String majin){
        String[] fields={human,saiyan,saiyan,namek,arcosian,majin};float[][] rows=new float[6][];
        for(int i=0;i<fields.length;i++){
            Object row=staticValue(CORE,fields[i]);if(!(row instanceof float[]))return null;rows[i]=(float[])row;
        }
        return rows; // references only; config adapter copies on content changes
    }
    private Availability mod(String method){
        Availability core=presence(CORE);if(core!=Availability.AVAILABLE)return core;
        Object loaded=callStatic(CORE,method);
        return loaded instanceof Boolean?((Boolean)loaded?Availability.AVAILABLE:Availability.NOT_APPLICABLE):Availability.UNAVAILABLE;
    }
    private Availability presence(String name){
        return type(name)!=null?Availability.AVAILABLE:absent.contains(name)?Availability.NOT_APPLICABLE:Availability.UNAVAILABLE;
    }
    private Class<?> type(String name){
        if(classes.containsKey(name))return classes.get(name);
        Class<?> result=null;lookups++;
        try{result=resolver.load(name);}
        catch(ClassNotFoundException e){absent.add(name);}
        catch(LinkageError e){problem(name+": "+e.getClass().getSimpleName());}
        catch(SecurityException e){problem(name+": "+e.getClass().getSimpleName());}
        classes.put(name,result);return result;
    }
    private Access access(Class<?> type){
        Access a=access.get(type);if(a==null){a=new Access();access.put(type,a);}return a;
    }
    private Object staticValue(String owner,String name){return field(type(owner),null,new String[]{name});}
    private Object value(Object owner,String... names){return owner==null?null:field(owner.getClass(),owner,names);}
    private Object field(Class<?> type,Object owner,String[] names){
        if(type==null)return null;
        Access a=access(type);String key=names[0];
        if(!a.fields.containsKey(key)){
            lookups++;Field f=Reflect.field(type,names);a.fields.put(key,f);
            if(f==null)problem(type.getName()+" missing field "+key);
        }
        Field f=a.fields.get(key);if(f==null)return null;
        try{return f.get(owner);}catch(IllegalAccessException e){problem(type.getName()+" read "+key+": "+e.getClass().getSimpleName());}
        catch(RuntimeException e){problem(type.getName()+" read "+key+": "+e.getClass().getSimpleName());}
        catch(LinkageError e){problem(type.getName()+" read "+key+": "+e.getClass().getSimpleName());}
        return null;
    }
    private Object call(Object owner,String... names){return callWith(owner,names);}
    private Object callWith(Object owner,String[] names,Object... args){return owner==null?null:invoke(owner.getClass(),owner,names,args);}
    private Object callStatic(String owner,String name,Object... args){return invoke(type(owner),null,new String[]{name},args);}
    private Object invoke(Class<?> type,Object owner,String[] names,Object[] args){
        if(type==null)return null;
        Access a=access(type);String key=names[0]+"/"+args.length;
        if(!a.methods.containsKey(key)){
            lookups++;Method m=Reflect.method(type,args.length,names);a.methods.put(key,m);
            if(m==null)problem(type.getName()+" missing getter "+key);
        }
        Method m=a.methods.get(key);if(m==null)return null;
        try{return m.invoke(owner,args);}catch(ReflectiveOperationException e){problem(type.getName()+" getter "+key+": "+e.getClass().getSimpleName());}
        catch(RuntimeException e){problem(type.getName()+" getter "+key+": "+e.getClass().getSimpleName());}
        catch(LinkageError e){problem(type.getName()+" getter "+key+": "+e.getClass().getSimpleName());}
        return null;
    }
    private static final class Access {
        final Map<String,Field> fields=new HashMap<String,Field>();
        final Map<String,Method> methods=new HashMap<String,Method>();
    }
    private void problem(String message){
        if(reported.add(message)){diagnostics.add(message);System.err.println("[EpicFight1710] Live spatial source: "+message);}
    }
    private int number(String[] row,int index){int n=signedNumber(row,index);return n<0?-1:n;}
    private int signedNumber(String[] row,int index){
        if(index>=row.length||row[index].isEmpty())return Integer.MIN_VALUE;
        try{return Integer.parseInt(row[index]);}catch(NumberFormatException e){problem("Malformed numeric native row");return Integer.MIN_VALUE;}
    }
    private static String[] split(String s,String delimiter){return s==null?new String[0]:s.split(delimiter);}
    private static int integer(Object o,int fallback){return o instanceof Number?((Number)o).intValue():fallback;}
    private static String string(Object o){return o instanceof String?(String)o:null;}
    private static String[] strings(Object o){return o instanceof String[]?(String[])o:null;}
}
