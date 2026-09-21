package com.nicolas.epicfight1710.combat;

import com.nicolas.epicfight1710.EpicFight1710;
import com.nicolas.epicfight1710.client.Compat;
import com.nicolas.epicfight1710.util.MiniJson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the Epic-style Weapon Type / Item Capability database. The embedded JSON is
 * always a safe baseline; users/modpack authors may edit the generated external file
 * and restart Minecraft without recompiling this JAR.
 */
public final class WeaponDefinitionLoader {
    public static final String RESOURCE="/assets/epicfight1710/data/weapon_types.json";
    private static final Charset UTF8=Charset.forName("UTF-8");
    private WeaponDefinitionLoader(){}

    public static synchronized void load(){
        try{
            String embedded=readResource();
            File external=externalFile();
            if(external!=null&&!external.exists())writeFile(external,embedded);
            String source=embedded;String origin="embedded";
            if(external!=null&&external.isFile()){
                try{source=readFile(external);origin=external.getAbsolutePath();}
                catch(Throwable t){System.err.println("[EpicFight1710] Could not read external weapon definition; using embedded defaults: "+t);}
            }
            WeaponRegistryData data;
            try{data=parse(source);}
            catch(Throwable bad){
                if(!"embedded".equals(origin)){
                    System.err.println("[EpicFight1710] External weapon definition is invalid; falling back to embedded defaults. Cause: "+bad);
                    data=parse(embedded);origin="embedded fallback";
                } else throw bad;
            }
            validateClips(data);
            WeaponCapabilityRegistry.install(data);
            System.out.println("[EpicFight1710] Epic-style data-driven weapon registry loaded from "+origin+": "+data.types.size()+" Weapon Types, "+data.itemRules.size()+" Item Capability rules, "+data.profiles.size()+" attack profiles. Edit the external JSON and press the Reload Weapon Types control (F8 by default) to reload it without restarting.");
        }catch(Throwable t){
            throw new RuntimeException("Could not load Epic-style weapon definitions",t);
        }
    }

    static WeaponRegistryData parse(String json){
        Map<String,Object> root=MiniJson.object(MiniJson.parse(json));if(root==null)throw new IllegalArgumentException("root must be object");
        WeaponRegistryData out=new WeaponRegistryData();
        List<Object> types=MiniJson.array(root.get("weaponTypes"));if(types==null)throw new IllegalArgumentException("weaponTypes array missing");
        for(Object raw:types)parseType(requireObject(raw,"weapon type"),out);
        List<Object> rules=MiniJson.array(root.get("itemRules"));
        if(rules!=null)for(Object raw:rules){Map<String,Object> r=requireObject(raw,"item rule");String type=requiredString(r,"type");List<Object> a=MiniJson.array(r.get("match"));if(a==null||a.isEmpty())throw new IllegalArgumentException("item rule match missing for "+type);String[] tokens=new String[a.size()];for(int i=0;i<a.size();i++)tokens[i]=String.valueOf(a.get(i)).toLowerCase(Locale.ENGLISH);out.itemRules.add(new ItemCapabilityRule(type,tokens));}
        return out;
    }

    private static void parseType(Map<String,Object> o,WeaponRegistryData out){
        String id=requiredString(o,"id");WeaponStyle category=WeaponStyle.valueOf(requiredString(o,"category").toUpperCase(Locale.ENGLISH));
        EpicCombatStyle def=EpicCombatStyle.valueOf(MiniJson.string(o.get("defaultStyle"),"COMMON").toUpperCase(Locale.ENGLISH));
        boolean offhand=MiniJson.bool(o.get("offhand"),true);
        List<Object> stylesRaw=MiniJson.array(o.get("styles"));if(stylesRaw==null||stylesRaw.isEmpty())throw new IllegalArgumentException("styles missing for "+id);
        EnumMap<EpicCombatStyle,WeaponStyleDefinition> styles=new EnumMap<EpicCombatStyle,WeaponStyleDefinition>(EpicCombatStyle.class);
        for(Object sr:stylesRaw){WeaponStyleDefinition sd=parseStyle(requireObject(sr,"style"),out,category);styles.put(sd.style,sd);}
        out.types.put(id,new WeaponCapability1710(id,category,def,offhand,styles));
    }

    private static WeaponStyleDefinition parseStyle(Map<String,Object> o,WeaponRegistryData out,WeaponStyle category){
        EpicCombatStyle style=EpicCombatStyle.valueOf(requiredString(o,"style").toUpperCase(Locale.ENGLISH));
        ColliderDefinition collider=parseCollider(MiniJson.object(o.get("collider")));
        List<Object> comboRaw=MiniJson.array(o.get("combo"));AttackProfile[] combo=new AttackProfile[comboRaw==null?0:comboRaw.size()];
        for(int i=0;i<combo.length;i++){combo[i]=parseAttack(requireObject(comboRaw.get(i),"combo attack"),i+1,false,collider);out.addProfile(combo[i]);}
        AttackProfile dash=parseOptionalAttack(MiniJson.object(o.get("dash")),0,true,collider);out.addProfile(dash);
        AttackProfile mount=parseOptionalAttack(MiniJson.object(o.get("mountAttack")),1,false,collider);out.addProfile(mount);
        String air=MiniJson.string(o.get("air"),null);String guardHit=MiniJson.string(o.get("guardHit"),null);String innate=MiniJson.string(o.get("innateSkill"),null);
        Map<String,String> motions=new HashMap<String,String>();Map<String,Object> lm=MiniJson.object(o.get("living"));if(lm!=null)for(Map.Entry<String,Object> e:lm.entrySet())if(e.getValue()!=null)motions.put(e.getKey().toLowerCase(Locale.ENGLISH),String.valueOf(e.getValue()));
        List<Object> conditionsRaw=MiniJson.array(o.get("conditions"));StyleCondition[] conditions=new StyleCondition[conditionsRaw==null?0:conditionsRaw.size()];
        for(int i=0;i<conditions.length;i++){Map<String,Object> c=requireObject(conditionsRaw.get(i),"style condition");conditions[i]=new StyleCondition(requiredString(c,"type"),MiniJson.bool(c.get("value"),true));}
        return new WeaponStyleDefinition(style,combo,dash,mount,air,guardHit,innate,collider,motions,conditions);
    }

    private static AttackProfile parseOptionalAttack(Map<String,Object> o,int next,boolean dash,ColliderDefinition collider){return o==null?null:parseAttack(o,next,dash,collider);}
    private static AttackProfile parseAttack(Map<String,Object> o,int defaultNext,boolean dash,ColliderDefinition collider){
        String clip=requiredString(o,"clip");float startup=f(o,"startup",0),activeEnd=f(o,"activeEnd",startup),chainOpen=f(o,"chainOpen",activeEnd),chainClose=f(o,"chainClose",Float.MAX_VALUE);
        float range=f(o,"range",collider==null?3.5F:collider.range),dot=f(o,"facing",collider==null?.2F:collider.minFacingDot);
        AttackPhase chain=new AttackPhase(startup,activeEnd,chainOpen,chainClose,range,dot);
        List<Object> windows=MiniJson.array(o.get("hitWindows"));AttackPhase[] phases;
        if(windows==null||windows.isEmpty())phases=new AttackPhase[]{chain};
        else{phases=new AttackPhase[windows.size()];for(int i=0;i<phases.length;i++){Map<String,Object> w=requireObject(windows.get(i),"hit window");phases[i]=new AttackPhase(f(w,"startup",startup),f(w,"activeEnd",activeEnd),chainOpen,chainClose,f(w,"range",range),f(w,"facing",dot));}}
        AttackStateSpectrum spectrum=new AttackStateSpectrum(startup,activeEnd,chainOpen,chainClose,
                f(o,"inactionEnd",chainOpen),f(o,"movementLockEnd",chainOpen),f(o,"turningLockEnd",activeEnd));
        return new AttackProfile(clip,chain,phases,MiniJson.integer(o.get("nextCombo"),defaultNext),dash,spectrum,collider);
    }

    private static ColliderDefinition parseCollider(Map<String,Object> o){
        if(o==null)return null;return new ColliderDefinition(MiniJson.integer(o.get("count"),1),f(o,"centerX",0),f(o,"centerY",0),f(o,"centerZ",0),f(o,"sizeX",.5F),f(o,"sizeY",.5F),f(o,"sizeZ",.8F),f(o,"range",3.5F),f(o,"facing",.2F));
    }
    private static void validateClips(WeaponRegistryData data){
        if(com.nicolas.epicfight1710.client.RuntimeAssets.CLIPS==null)return;
        List<String> missing=new ArrayList<String>();
        for(AttackProfile p:data.profiles.values())if(p!=null&&p.clip!=null&&com.nicolas.epicfight1710.client.RuntimeAssets.CLIPS.get(p.clip)==null)missing.add(p.clip);
        for(WeaponCapability1710 cap:data.types.values())for(WeaponStyleDefinition s:cap.styles().values()){
            for(String key:new String[]{WeaponCapability1710.MOTION_IDLE,WeaponCapability1710.MOTION_WALK,WeaponCapability1710.MOTION_RUN,WeaponCapability1710.MOTION_BLOCK,WeaponCapability1710.MOTION_KNEEL,WeaponCapability1710.MOTION_FLY,WeaponCapability1710.MOTION_CREATIVE_IDLE}){
                String clip=s.livingMotion(key);if(clip!=null&&com.nicolas.epicfight1710.client.RuntimeAssets.CLIPS.get(clip)==null)missing.add(clip);
            }
            if(s.guardHitClip!=null&&com.nicolas.epicfight1710.client.RuntimeAssets.CLIPS.get(s.guardHitClip)==null)missing.add(s.guardHitClip);
            if(s.airAttack!=null&&com.nicolas.epicfight1710.client.RuntimeAssets.CLIPS.get(s.airAttack)==null)missing.add(s.airAttack);
        }
        if(!missing.isEmpty())throw new IllegalArgumentException("weapon definition references missing clips: "+missing);
    }

    private static Map<String,Object> requireObject(Object o,String what){Map<String,Object> m=MiniJson.object(o);if(m==null)throw new IllegalArgumentException(what+" must be object");return m;}
    private static String requiredString(Map<String,Object> o,String k){String s=MiniJson.string(o.get(k),null);if(s==null||s.length()==0)throw new IllegalArgumentException("missing "+k);return s;}
    private static float f(Map<String,Object> o,String k,float d){return (float)MiniJson.number(o.get(k),d);}

    private static File externalFile(){try{File root=Compat.gameDirectory();if(root==null)return null;return new File(new File(root,"config/epicfight1710"),"weapon_types.json");}catch(Throwable t){return null;}}
    private static String readResource()throws Exception{InputStream in=EpicFight1710.class.getResourceAsStream(RESOURCE);if(in==null)throw new IllegalStateException("missing "+RESOURCE);try{return readAll(in);}finally{in.close();}}
    private static String readFile(File f)throws Exception{InputStream in=new FileInputStream(f);try{return readAll(in);}finally{in.close();}}
    private static String readAll(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);return new String(out.toByteArray(),UTF8);}
    private static void writeFile(File f,String text)throws Exception{File p=f.getParentFile();if(p!=null&&!p.exists()&&!p.mkdirs()&&!p.isDirectory())throw new IllegalStateException("cannot create "+p);FileOutputStream out=new FileOutputStream(f);try{out.write(text.getBytes(UTF8));}finally{out.close();}}
}
