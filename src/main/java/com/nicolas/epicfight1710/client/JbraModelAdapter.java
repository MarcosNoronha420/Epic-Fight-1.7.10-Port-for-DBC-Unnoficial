package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Cached structural adapter for JBRA 1.6.x ModelBipedDBC layouts.
 *
 * 1.4.0 keeps the cached discovery used by 1.3.x and also supports the
 * two places where the real 1.7.10 renderer is intentionally late/dynamic:
 *
 *  - RenderBiped/RenderPlayer can assign renderPassModel only after
 *    RenderPlayerEvent.Pre while an armor/clothing pass is being prepared.
 *  - JRMCore ModelBipedBody rewrites the live RA/LA/RL/LL/B* aliases from
 *    setRotationAngles. Those aliases therefore have to be read at the actual draw,
 *    not frozen from the pre-render snapshot.
 *
 * Reflection metadata (Field objects and field classifications) is cached by class.
 * The draw-time path only reads a small set of already-resolved fields; it never
 * repeats full class-hierarchy scans per ModelRenderer call.
 */
final class JbraModelAdapter {
    static final JbraModelAdapter INSTANCE=new JbraModelAdapter();
    static final class Mapping {
        final Object mainModel;
        final IdentityHashMap<Object,NativeJbraSkinContext.PartRole> parts;
        final String diagnostics;
        Mapping(Object main,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> p,String d){mainModel=main;parts=p;diagnostics=d;}
    }

    private static final class PartField {
        final Field field;
        final NativeJbraSkinContext.PartRole role;
        PartField(Field f,NativeJbraSkinContext.PartRole r){field=f;role=r;}
    }
    private static final class ModelAccess {
        final Field[] allFields;
        final PartField[] liveBodyFields;
        ModelAccess(Field[] all,PartField[] live){allFields=all;liveBodyFields=live;}
    }
    private static final class RendererAccess {
        final Field[] modelFields;
        RendererAccess(Field[] f){modelFields=f;}
    }

    private final Map<Object,Mapping> cache=new WeakHashMap<Object,Mapping>();
    private final Map<Class<?>,ModelAccess> modelAccessCache=new HashMap<Class<?>,ModelAccess>();
    private final Map<Class<?>,RendererAccess> rendererAccessCache=new HashMap<Class<?>,RendererAccess>();
    private final Map<Class<?>,Field> childFieldCache=new HashMap<Class<?>,Field>();
    private final Map<Class<?>,Field> mainModelFieldCache=new HashMap<Class<?>,Field>();
    /**
     * Live RA/LA/RL/LL/B* and renderPassModel aliases are dynamic, but scanning every
     * alias field for every intercepted ModelRenderer made the cost scale with FPS.
     * Build one identity snapshot per tick/current renderer-model signature instead.
     * This preserves the same one-tick freshness contract as the previous negative
     * miss cache while turning the hot path into an IdentityHashMap lookup.
     */
    private final IdentityHashMap<Object,NativeJbraSkinContext.PartRole> liveSnapshot=
            new IdentityHashMap<Object,NativeJbraSkinContext.PartRole>();
    private Object liveSnapshotRenderer,liveSnapshotMain;
    private int liveSnapshotTick=Integer.MIN_VALUE,liveSnapshotSignature;
    private long liveResolveCalls,liveSnapshotBuilds,liveSnapshotRoleHits,liveSignatureChecks;
    /** Incremented only when the stable model/alias topology actually changes.
     * Weighted mesh caches use this as a cheap invalidation epoch instead of
     * recursively re-walking every ModelRenderer tree at 20 TPS. */
    private volatile int topologyGeneration=1;
    private Object lastRenderer,lastMain;
    private Mapping lastMapping;
    private JbraModelAdapter(){}

    Mapping map(Object renderer) {
        if(renderer==null)return null;
        Object main=findMainModel(renderer);if(main==null)return null;
        Mapping fast=lastMapping;
        if(renderer==lastRenderer&&main==lastMain&&fast!=null)return fast;
        synchronized(this){
            Mapping cached=cache.get(main);
            if(cached==null){
                IdentityHashMap<Object,NativeJbraSkinContext.PartRole> parts=new IdentityHashMap<Object,NativeJbraSkinContext.PartRole>();
                StringBuilder diag=new StringBuilder();
                collectModel(main,parts,diag);
                collectRendererModels(renderer,main,parts,diag);
                cached=new Mapping(main,parts,diag.toString());cache.put(main,cached);bumpTopologyGeneration();
            }
            lastRenderer=renderer;lastMain=main;lastMapping=cached;
            return cached;
        }
    }

    synchronized void invalidate(Object main){if(main!=null&&cache.remove(main)!=null){if(main==lastMain){lastRenderer=null;lastMain=null;lastMapping=null;}bumpTopologyGeneration();}}

    int topologyGeneration(){return topologyGeneration;}
    private void bumpTopologyGeneration(){if(++topologyGeneration==Integer.MIN_VALUE)topologyGeneration=1;}

    synchronized void mergeCachedParts(Object main,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> target) {
        if(main==null||target==null)return;
        Mapping m=cache.get(main);
        if(m!=null)target.putAll(m.parts);
    }

    /**
     * Resolve a part that was not present in the RenderPlayerEvent.Pre snapshot.
     *
     * This is intentionally identity based. The current renderer model fields are
     * read first, then the current RA/LA/RL/LL/B* and normal biped aliases on those
     * models are compared with the ModelRenderer that is being drawn right now.
     */
    NativeJbraSkinContext.PartRole resolveLivePart(Object renderer,Object main,Object part) {
        if(part==null)return null;
        liveResolveCalls++;
        Mapping mapping=main==null?null:cache.get(main);
        NativeJbraSkinContext.PartRole known=mapping==null?null:mapping.parts.get(part);
        if(concrete(known)){liveSnapshotRoleHits++;return known;}

        int tick=CombatController.INSTANCE.tick();
        // Build one baseline live-alias snapshot per tick. 2.0.41 still computed the
        // renderer-model signature for every unresolved ModelRenderer call (tens of
        // thousands of field reads in a few seconds). Stable GENERIC parts now stop
        // here after this one snapshot; only truly late/unknown pass objects trigger
        // an additional signature probe.
        if(liveSnapshotTick!=tick||liveSnapshotRenderer!=renderer||liveSnapshotMain!=main) {
            int signature=rendererModelSignature(renderer,main);liveSignatureChecks++;
            rebuildLiveSnapshot(renderer,main,mapping,tick,signature);
        }
        NativeJbraSkinContext.PartRole role=liveSnapshot.get(part);
        if(concrete(role)) {
            liveSnapshotRoleHits++;
            if(mapping!=null)put(mapping.parts,part,role);
            return role;
        }
        // Initial discovery records generic ModelRenderers explicitly. They are known
        // native geometry, so do not rescan every renderer field just to rediscover a
        // negative answer on every display frame.
        if(known==NativeJbraSkinContext.PartRole.GENERIC)return null;

        // A genuinely unknown identity may belong to a renderPassModel assigned after
        // RenderPlayerEvent.Pre. Recompute only for that late case; if the model-field
        // signature changed, rebuild once and persist any concrete alias discovered.
        int signature=rendererModelSignature(renderer,main);liveSignatureChecks++;
        if(liveSnapshotSignature!=signature) {
            rebuildLiveSnapshot(renderer,main,mapping,tick,signature);
            role=liveSnapshot.get(part);
            if(concrete(role)) {
                liveSnapshotRoleHits++;
                if(mapping!=null)put(mapping.parts,part,role);
                return role;
            }
        }
        return null;
    }

    private void rebuildLiveSnapshot(Object renderer,Object main,Mapping mapping,int tick,int signature) {
        liveSnapshot.clear();
        collectLiveModel(main,liveSnapshot,mapping);
        if(renderer!=null) {
            RendererAccess ra=rendererAccess(renderer.getClass());
            for(Field f:ra.modelFields) {
                Object model=get(f,renderer);
                if(model==null||model==main)continue;
                collectLiveModel(model,liveSnapshot,mapping);
            }
        }
        liveSnapshotRenderer=renderer;
        liveSnapshotMain=main;
        liveSnapshotTick=tick;
        liveSnapshotSignature=signature;
        liveSnapshotBuilds++;
    }

    private void collectLiveModel(Object model,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,Mapping mapping) {
        if(model==null)return;
        ModelAccess a=modelAccess(model.getClass());
        for(PartField pf:a.liveBodyFields) {
            Object value=get(pf.field,model);
            if(value==null)continue;
            if(value.getClass().isArray()) {
                int n=Array.getLength(value);
                for(int i=0;i<n;i++)collectLiveValue(Array.get(value,i),pf.role,out,mapping);
            } else collectLiveValue(value,pf.role,out,mapping);
        }
    }

    private void collectLiveValue(Object value,NativeJbraSkinContext.PartRole role,
                                  IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,Mapping mapping) {
        if(value==null||!concrete(role)||value.getClass().getName().indexOf("ModelRenderer")<0)return;
        put(out,value,role);
        if(mapping!=null) {
            boolean fresh=!mapping.parts.containsKey(value);
            put(mapping.parts,value,role);
            if(fresh&&role!=NativeJbraSkinContext.PartRole.HEAD)
                promoteWearableDescendants(value,role,mapping.parts,0);
        }
    }

    private int rendererModelSignature(Object renderer,Object main) {
        int h=System.identityHashCode(main);
        if(renderer==null)return h;
        RendererAccess ra=rendererAccess(renderer.getClass());
        for(Field f:ra.modelFields) {
            Object model=get(f,renderer);
            h=31*h+System.identityHashCode(model);
        }
        return h;
    }



    private void promoteWearableDescendants(Object root,NativeJbraSkinContext.PartRole role,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,int depth) {
        // DBC/JBRA head trees mix skull, face, hair, ears and form-specific layers.
        // They are not an Epic HumanoidMesh head subtree and must remain native.
        if(role==NativeJbraSkinContext.PartRole.HEAD)return;
        if(root==null||out==null||!concrete(role)||depth>8)return;
        Field child=childField(root.getClass());
        Object value=get(child,root);
        if(!(value instanceof Iterable))return;
        int count=0;
        for(Object v:(Iterable<?>)value) {
            if(count++>=64)break;
            if(v==null||v.getClass().getName().indexOf("ModelRenderer")<0)continue;
            put(out,v,role);
            promoteWearableDescendants(v,role,out,depth+1);
        }
    }

    private Field childField(Class<?> type) {
        if(type==null)return null;
        if(childFieldCache.containsKey(type))return childFieldCache.get(type);
        Field f=Reflect.field(type,"field_78805_m","childModels");
        childFieldCache.put(type,f);
        return f;
    }

    private void collectModel(Object model,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,StringBuilder diag) {
        if(model==null)return;
        for(Field f:modelAccess(model.getClass()).allFields) {
            try {
                Object v=f.get(model);if(v==null)continue;
                NativeJbraSkinContext.PartRole r=NativeJbraSkinContext.roleFor(f.getName());
                int before=out.size();collectValue(v,r,out);
                if(out.size()>before){if(diag.length()>0)diag.append(',');diag.append(f.getName()).append('=').append(r.name());}
            } catch(Throwable ignored){}
        }
    }

    private void collectRendererModels(Object renderer,Object main,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,StringBuilder diag) {
        // 1.3.0 already removed the historical 96-field budget. 1.3.1 additionally
        // caches this hierarchy metadata so late-pass checks do not rescan reflection.
        for(Field f:rendererAccess(renderer.getClass()).modelFields) {
            Object v=get(f,renderer);if(v==null||v==main)continue;
            String n=v.getClass().getName();
            if(n.indexOf("Model")>=0&&n.indexOf("ModelRenderer")<0)collectModel(v,out,diag);
        }
    }

    private ModelAccess modelAccess(Class<?> type) {
        ModelAccess cached=modelAccessCache.get(type);if(cached!=null)return cached;
        ArrayList<Field> all=new ArrayList<Field>();
        ArrayList<PartField> live=new ArrayList<PartField>();
        Class<?> c=type;
        while(c!=null) {
            Field[] fs;try{fs=c.getDeclaredFields();}catch(Throwable t){fs=new Field[0];}
            for(Field f:fs) {
                if(Modifier.isStatic(f.getModifiers()))continue;
                try{f.setAccessible(true);}catch(Throwable ignored){}
                all.add(f);
                NativeJbraSkinContext.PartRole role=NativeJbraSkinContext.roleFor(f.getName());
                if(concrete(role)&&fieldCanHoldRenderer(f))live.add(new PartField(f,role));
            }
            c=c.getSuperclass();
        }
        ModelAccess a=new ModelAccess(all.toArray(new Field[all.size()]),live.toArray(new PartField[live.size()]));
        modelAccessCache.put(type,a);return a;
    }

    private RendererAccess rendererAccess(Class<?> type) {
        RendererAccess cached=rendererAccessCache.get(type);if(cached!=null)return cached;
        ArrayList<Field> modelFields=new ArrayList<Field>();
        Class<?> c=type;
        while(c!=null) {
            Field[] fs;try{fs=c.getDeclaredFields();}catch(Throwable t){fs=new Field[0];}
            for(Field f:fs) {
                if(Modifier.isStatic(f.getModifiers()))continue;
                String fn=f.getName();String tn=f.getType().getName();
                boolean named="modelArmorChestplate".equals(fn)||"field_82423_g".equals(fn)
                        ||"modelArmor".equals(fn)||"field_82425_h".equals(fn)
                        ||"renderPassModel".equals(fn)||"field_77046_h".equals(fn)
                        ||"field_77045_g".equals(fn)||"mainModel".equals(fn)||"modelBipedMain".equals(fn)||"modelMain".equals(fn);
                boolean typed=tn.indexOf("Model")>=0&&tn.indexOf("ModelRenderer")<0;
                if(named||typed) {
                    try{f.setAccessible(true);}catch(Throwable ignored){}
                    modelFields.add(f);
                }
            }
            c=c.getSuperclass();
        }
        RendererAccess a=new RendererAccess(modelFields.toArray(new Field[modelFields.size()]));
        rendererAccessCache.put(type,a);return a;
    }

    private static boolean fieldCanHoldRenderer(Field f) {
        Class<?> t=f.getType();String n=t.getName();
        return n.indexOf("ModelRenderer")>=0||t.isArray()||Object.class.equals(t);
    }

    private static Object get(Field f,Object owner) {
        if(f==null)return null;try{return f.get(owner);}catch(Throwable ignored){return null;}
    }

    private static boolean concrete(NativeJbraSkinContext.PartRole r) {
        return r!=null&&r!=NativeJbraSkinContext.PartRole.GENERIC;
    }

    private static void collectValue(Object v,NativeJbraSkinContext.PartRole suggested,IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out) {
        if(v==null)return;Class<?> type=v.getClass();String n=type.getName();
        if(n.indexOf("ModelRenderer")>=0){put(out,v,suggested);return;}
        if(type.isArray()){int len=Array.getLength(v);for(int i=0;i<len;i++){Object x=Array.get(v,i);if(x!=null&&x.getClass().getName().indexOf("ModelRenderer")>=0)put(out,x,suggested);}}
        if(v instanceof Iterable){int i=0;for(Object x:(Iterable<?>)v){if(i++>128)break;if(x!=null&&x.getClass().getName().indexOf("ModelRenderer")>=0)put(out,x,suggested);}}
    }
    private static void put(IdentityHashMap<Object,NativeJbraSkinContext.PartRole> out,Object part,NativeJbraSkinContext.PartRole role){
        if(part==null||role==null)return;
        // Keep GENERIC as an explicit negative classification. It remains native, but
        // remembering the identity prevents an expensive live-alias search on every
        // intercepted draw. A later concrete RA/LA/RL/LL/B* alias still promotes it.
        NativeJbraSkinContext.PartRole old=out.get(part);
        if(old==null||old==NativeJbraSkinContext.PartRole.GENERIC)out.put(part,role);
    }

    /** Find the opposite live arm alias from the same JBRA model/pass as part. */
    synchronized Object pairedArm(Object renderer,Object main,Object part,NativeJbraSkinContext.PartRole desired) {
        if(part==null||desired==null)return null;
        Object peer=pairedInModel(main,part,desired);if(peer!=null)return peer;
        if(renderer==null)return null;
        RendererAccess ra=rendererAccess(renderer.getClass());
        for(Field f:ra.modelFields){Object model=get(f,renderer);if(model==null||model==main)continue;peer=pairedInModel(model,part,desired);if(peer!=null)return peer;}
        return null;
    }

    private Object pairedInModel(Object model,Object part,NativeJbraSkinContext.PartRole desired) {
        if(model==null)return null;boolean owns=false;Object peer=null;ModelAccess a=modelAccess(model.getClass());
        for(PartField pf:a.liveBodyFields){Object v=get(pf.field,model);if(v==null)continue;
            if(v==part)owns=true;
            if(pf.role==desired&&peer==null&&!v.getClass().isArray())peer=v;
            if(v.getClass().isArray()){int n=Array.getLength(v);for(int i=0;i<n;i++){Object x=Array.get(v,i);if(x==part)owns=true;if(pf.role==desired&&peer==null&&x!=null)peer=x;}}
        }
        return owns?peer:null;
    }

    long liveMissChecks(){return liveResolveCalls;}
    long liveMissCacheHits(){return Math.max(0L,liveResolveCalls-liveSignatureChecks);}
    long liveSnapshotBuilds(){return liveSnapshotBuilds;}
    long liveSnapshotRoleHits(){return liveSnapshotRoleHits;}
    long liveSignatureChecks(){return liveSignatureChecks;}
    void resetPerformanceCounters(){liveResolveCalls=liveSnapshotBuilds=liveSnapshotRoleHits=liveSignatureChecks=0L;}

    private Object findMainModel(Object renderer) {
        if(renderer==null)return null;
        Class<?> type=renderer.getClass();
        Field f;
        if(mainModelFieldCache.containsKey(type))f=mainModelFieldCache.get(type);
        else {
            f=Reflect.field(type,"field_77045_g","mainModel","modelBipedMain","modelMain");
            if(f==null){
                Class<?> c=type;
                search:while(c!=null){Field[] fs;try{fs=c.getDeclaredFields();}catch(Throwable t){fs=new Field[0];}
                    for(Field x:fs){if(Modifier.isStatic(x.getModifiers()))continue;try{x.setAccessible(true);Object v=x.get(renderer);if(v==null)continue;String n=v.getClass().getName();if(n.indexOf("ModelBipedDBC")>=0||n.endsWith("ModelBiped")||n.endsWith("ModelPlayer")){f=x;break search;}}catch(Throwable ignored){}}
                    c=c.getSuperclass();
                }
            }
            mainModelFieldCache.put(type,f);
        }
        Object m=get(f,renderer);
        if(m!=null)return m;
        // A renderer that swaps its main model field at runtime is rare, but do not
        // keep a stale null cache forever. Re-discover on the next map() call.
        if(f!=null)mainModelFieldCache.remove(type);
        return null;
    }
}
