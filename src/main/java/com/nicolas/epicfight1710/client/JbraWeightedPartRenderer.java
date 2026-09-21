package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import com.nicolas.epicfight1710.anim.runtime.LivingMotion;
import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Skins the original JBRA ModelRenderer cuboids through the Epic Fight armature.
 *
 * Each rigid ModelRenderer is compiled into a subdivided weighted mesh once. The
 * DBC retarget is deterministic (body role + anatomical height), so custom muscle
 * geometry cannot randomly jump to a different nearest Epic source vertex. After
 * compilation the hot path is matrix skinning + Tessellator emission only.
 */
final class JbraWeightedPartRenderer {
    static final JbraWeightedPartRenderer INSTANCE=new JbraWeightedPartRenderer();

    private final Map<Class<?>,PartAccess> partCache=new HashMap<Class<?>,PartAccess>();
    private final Map<Class<?>,BoxAccess> boxCache=new HashMap<Class<?>,BoxAccess>();
    private final Map<Class<?>,QuadAccess> quadCache=new HashMap<Class<?>,QuadAccess>();
    private final Map<Class<?>,VertexAccess> vertexCache=new HashMap<Class<?>,VertexAccess>();
    private final Map<Class<?>,VecAccess> vecCache=new HashMap<Class<?>,VecAccess>();
    private final Map<WeightKey,float[]> weightCache=new HashMap<WeightKey,float[]>();
    private final IdentityHashMap<Object,CacheEntry> compiledCache=new IdentityHashMap<Object,CacheEntry>();
    /** Negative structural results are also tick-scoped. Without this, a rejected
     * supplementary/native-only part would recursively revalidate on every display
     * frame even though the model cannot change again before the next client tick. */
    private final IdentityHashMap<Object,RejectEntry> rejectedCache=new IdentityHashMap<Object,RejectEntry>();
    // Battle Mode is rendered at display-frame frequency, while JBRA topology changes
    // only on model/form boundaries. 2.0.41 still deep-walked every accepted/rejected
    // tree once on the same 20 TPS boundary, producing a small periodic frametime pulse.
    // Stagger deep integrity audits over sixteen ticks and invalidate immediately when
    // JbraModelAdapter reports a real topology/alias generation change.
    private static final int TOPOLOGY_AUDIT_PERIOD=16;
    private static final int DEEP_TOPOLOGY_WATCHDOG_PERIOD=128;
    private static final int REJECT_AUDIT_PERIOD=128;
    private int frameSerial;
    private int observedTopologyGeneration=Integer.MIN_VALUE;
    private long perfRenderCalls,perfValidationRuns,perfValidationSkips,perfCompiles,perfShallowChecks,perfDeepAudits,perfRejectSkips;
    private long perfUniqueSkinPoints,perfTriangleCorners,perfNormalTriangles,perfNormalReuseTriangles;
    private int perfStartTick=Integer.MIN_VALUE;
    private boolean perfSummaryLogged,skinDedupeLogged;

    private final float[] transformed=new float[3];
    private final float[] pa=new float[3],pb=new float[3],pc=new float[3];
    private final float[] povDepthPoint=new float[3],povSourcePoint=new float[3],povEyePoint=new float[3];
    private final float[] povSourceBounds=new float[2],povDbcBounds=new float[2],povDepthObjectShift=new float[3];
    private final FloatBuffer povModelView=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] armCorrected=new float[16],handCorrected=new float[16],elbowCorrected=new float[16];
    private final float[] thighCorrected=new float[16],legCorrected=new float[16],kneeCorrected=new float[16];
    private final float[] sourcePivot=new float[3],sourceHandPivot=new float[3],sourceElbowPivot=new float[3];
    private final float[] sourceThighPivot=new float[3],sourceLegPivot=new float[3],sourceKneePivot=new float[3];
    private final float[] targetHandPivot=new float[3],targetElbowPivot=new float[3];
    private final float[] targetLegPivot=new float[3],targetKneePivot=new float[3];
    private final float[] neutralRootPivot=new float[3];
    private final float[] rightHandSocketDelta=new float[3];
    private boolean rightHandSocketDeltaValid;
    // Tool_R must follow the correction measured under the actual RIGHT_ARM parent.
    private final float[] rightArmModelOffset=new float[3];
    private boolean rightArmModelOffsetValid;
    private boolean logged,warned,cacheLogged,retargetLogged,pivotLogged,legPivotLogged,neutralBindLogged,passiveFlightLogged;
    private boolean firstPersonAnchorLogged,firstPersonSourceRootLogged,firstPersonIntactArmLogged;
    private boolean firstPersonSourceVisibilityLogged,firstPersonSourceSuppressedLogged,firstPersonDepthReconcileLogged;
    private boolean firstPersonBodyRendered,firstPersonBodyLogged,firstPersonWorldLogged;
    private int firstPersonWorldDepth;
    private Field nativeActiveMainModelField,nativeActivePlayerField,nativeActivePartialField;
    private Class<?> firstPersonPlayerClass;
    private Field firstPersonPitchField,firstPersonPrevPitchField;
    private final Map<Class<?>,PovBodyAccess> povBodyAccessCache=new HashMap<Class<?>,PovBodyAccess>();
    private SkeletonMesh retargetMesh;
    private DbcRetargetProfile retargetProfile;

    private JbraWeightedPartRenderer(){}

    void beginFrame(){
        if(++frameSerial==Integer.MIN_VALUE)frameSerial=1;
        rightHandSocketDeltaValid=false;rightHandSocketDelta[0]=rightHandSocketDelta[1]=rightHandSocketDelta[2]=0.0F;
        rightArmModelOffsetValid=false;rightArmModelOffset[0]=rightArmModelOffset[1]=rightArmModelOffset[2]=0.0F;
        firstPersonBodyRendered=false;
        firstPersonWorldDepth=0;
        maybeLogPerformanceSummary();
    }

    private void maybeLogPerformanceSummary(){
        if(perfSummaryLogged||!CombatController.INSTANCE.battleMode())return;
        int tick=CombatController.INSTANCE.tick();
        if(perfStartTick==Integer.MIN_VALUE){perfStartTick=tick;return;}
        if(tick-perfStartTick<100)return;
        perfSummaryLogged=true;
        long totalValidationDecisions=perfValidationRuns+perfValidationSkips;
        long avoided=totalValidationDecisions<=0?0:(perfValidationSkips*100L/totalValidationDecisions);
        long skinSaved=perfTriangleCorners<=0?0:Math.max(0L,100L-(perfUniqueSkinPoints*100L/perfTriangleCorners));
        long liveChecks=JbraModelAdapter.INSTANCE.liveMissChecks(),liveHits=JbraModelAdapter.INSTANCE.liveMissCacheHits();
        long liveSaved=liveChecks<=0?0:(liveHits*100L/liveChecks);
        System.out.println("[EpicFight1710] Battle Mode performance cache active: renderCalls="+perfRenderCalls
                +", structuralValidations="+perfValidationRuns+", validationCacheHits="+perfValidationSkips
                +" (~"+avoided+"% recursive topology checks avoided), recompiles="+perfCompiles
                +", weightedSkinPoints="+perfUniqueSkinPoints+" vs triangleCorners="+perfTriangleCorners
                +" (~"+skinSaved+"% repeated matrix-skin work avoided), normalTriangles="+perfNormalTriangles
                +", normalReuseTriangles="+perfNormalReuseTriangles
                +", liveAliasSnapshotBuilds="+JbraModelAdapter.INSTANCE.liveSnapshotBuilds()
                +", liveAliasSignatureChecks="+JbraModelAdapter.INSTANCE.liveSignatureChecks()
                +", liveAliasRoleHits="+JbraModelAdapter.INSTANCE.liveSnapshotRoleHits()
                +", liveAliasFastLookups="+liveHits+"/"+liveChecks
                +" (~"+liveSaved+"%), callsiteBridgeCalls="+ModelRendererCallsiteHook.bridgeCalls()
                +", callsiteSkinnedCalls="+ModelRendererCallsiteHook.skinnedCalls()
                +", callsiteNativeFallbacks="+ModelRendererCallsiteHook.nativeFallbackCalls()
                +", duplicateGlobalHookBypasses="+ModelRendererSkinHook.bypassedDuplicateCalls()
                +". Geometry/UV topology is unchanged.");
    }
    /** Compatibility method retained for WeaponItemMountHook's established API. */
    void setFrameModelOffset(float x,float y,float z){
        rightArmModelOffset[0]=x;rightArmModelOffset[1]=y;rightArmModelOffset[2]=z;rightArmModelOffsetValid=true;
    }
    boolean frameModelOffset(float[] out){if(!rightArmModelOffsetValid||out==null||out.length<3)return false;out[0]=rightArmModelOffset[0];out[1]=rightArmModelOffset[1];out[2]=rightArmModelOffset[2];return true;}
    boolean rightHandSocketDelta(float[] out){
        if(!rightHandSocketDeltaValid||out==null||out.length<3)return false;
        out[0]=rightHandSocketDelta[0];out[1]=rightHandSocketDelta[1];out[2]=rightHandSocketDelta[2];return true;
    }

    boolean render(Object part,float scale,NativeJbraSkinContext.PartRole role) {
        if(part==null||role==null||!RuntimeAssets.READY)return false;
        perfRenderCalls++;
        try {
            PartAccess access=partAccess(part.getClass());if(access==null)return false;
            // Root visibility remains live per draw; forms/clothing may toggle these flags
            // independently of topology and must never wait for an integrity audit.
            if(access.hidden!=null&&Reflect.getBoolean(access.hidden,part,false))return true;
            if(access.show!=null&&!Reflect.getBoolean(access.show,part,true))return true;

            CacheEntry ce=compiledCache.get(part);
            int tick=CombatController.INSTANCE.tick();
            int generation=JbraModelAdapter.INSTANCE.topologyGeneration();
            observeTopologyGeneration(generation);
            int scaleBits=Float.floatToIntBits(scale);
            RejectEntry rejected=rejectedCache.get(part);
            if(rejected!=null&&rejected.role==role&&rejected.scaleBits==scaleBits
                    &&rejected.generation==generation&&tick<rejected.nextAuditTick){
                perfValidationSkips++;perfRejectSkips++;
                return false;
            }
            boolean compatible=ce!=null&&ce.role==role&&ce.scaleBits==scaleBits;
            if(compatible&&ce.generation==generation){
                if(tick<ce.nextAuditTick){
                    perfValidationSkips++;
                    draw(ce.mesh);
                    return true;
                }
                // The 2.0.41 cache still performed a recursive tree walk on a fixed
                // cadence. That moved work from every display frame to a visible 20 Hz
                // pulse. A cheap one-level structural stamp catches ordinary cube/child
                // list mutations immediately; the full recursive audit is only needed
                // when that stamp changes or on a rare staggered watchdog.
                perfShallowChecks++;
                int shallow=shallowTopologyStamp(part);
                if(shallow==ce.shallowStamp&&tick<ce.deepWatchdogTick){
                    ce.nextAuditTick=tick+TOPOLOGY_AUDIT_PERIOD;
                    perfValidationSkips++;
                    draw(ce.mesh);
                    return true;
                }
            }

            perfValidationRuns++;perfDeepAudits++;
            if(!treeEligible(part,role,0)){reject(part,role,scaleBits,tick,generation);return false;}
            int sig=treeSignature(part,role,scale,0);
            int shallow=shallowTopologyStamp(part);
            if(!compatible||ce.signature!=sig) {
                CompiledMesh mesh=compileTree(part,role,scale);
                if(mesh==null||mesh.vertices.length==0){reject(part,role,scaleBits,tick,generation);return false;}
                ce=new CacheEntry(sig,mesh,role,scaleBits,generation,nextAuditTick(part,tick),nextDeepWatchdogTick(part,tick),shallow);
                compiledCache.put(part,ce);rejectedCache.remove(part);perfCompiles++;
                if(!cacheLogged){cacheLogged=true;System.out.println("[EpicFight1710] JBRA body-tree compiler/cache active: same-role child pivots/static rotations are baked once, then Epic matrices skin the original DBC geometry.");}
            } else {
                ce.generation=generation;
                ce.shallowStamp=shallow;
                ce.nextAuditTick=tick+TOPOLOGY_AUDIT_PERIOD;
                ce.deepWatchdogTick=nextDeepWatchdogTick(part,tick);
            }
            rejectedCache.remove(part);
            draw(ce.mesh);
            if(!logged){logged=true;System.out.println("[EpicFight1710] JBRA original cube/UV skinning is live; current DBC texture/tint is preserved while Epic matrices deform cached vertices.");}
            return true;
        } catch(Throwable ex) {
            compiledCache.remove(part);reject(part,role,Float.floatToIntBits(scale),CombatController.INSTANCE.tick(),JbraModelAdapter.INSTANCE.topologyGeneration());
            if(!warned){warned=true;System.err.println("[EpicFight1710] Cached JBRA skeletal draw failed once; this part will fall back to native rendering.");ex.printStackTrace();}
            return false;
        }
    }

    boolean renderFast(Object part,float scale,NativeJbraSkinContext.PartRole role,boolean validate) {
        if(part==null||role==null||!RuntimeAssets.READY)return false;
        perfRenderCalls++;
        try {
            PartAccess access=partAccess(part.getClass());if(access==null)return false;
            if(access.hidden!=null&&Reflect.getBoolean(access.hidden,part,false))return true;
            if(access.show!=null&&!Reflect.getBoolean(access.show,part,true))return true;
            CacheEntry ce=compiledCache.get(part);
            int tick=CombatController.INSTANCE.tick();
            int generation=JbraModelAdapter.INSTANCE.topologyGeneration();
            observeTopologyGeneration(generation);
            int scaleBits=Float.floatToIntBits(scale);
            RejectEntry rejected=rejectedCache.get(part);
            if(rejected!=null&&rejected.role==role&&rejected.scaleBits==scaleBits
                    &&rejected.generation==generation&&tick<rejected.nextAuditTick){
                perfValidationSkips++;perfRejectSkips++;
                return false;
            }
            boolean compatible=ce!=null&&ce.role==role&&ce.scaleBits==scaleBits;
            if(compatible&&ce.generation==generation){
                if(tick<ce.nextAuditTick){
                    perfValidationSkips++;
                    draw(ce.mesh);
                    return true;
                }
                perfShallowChecks++;
                int shallow=shallowTopologyStamp(part);
                if(shallow==ce.shallowStamp&&tick<ce.deepWatchdogTick){
                    ce.nextAuditTick=tick+TOPOLOGY_AUDIT_PERIOD;
                    perfValidationSkips++;
                    draw(ce.mesh);
                    return true;
                }
            }

            // Cold identities compile immediately. Warm identities use the same cheap
            // structural sentinel as third person; a full recursive walk is exceptional.
            perfValidationRuns++;perfDeepAudits++;
            NativeJbraSkinContext.PartRole mapped=FirstPersonBodyRenderer1710.fastMappedRole(part);
            if(mapped!=null&&mapped!=NativeJbraSkinContext.PartRole.GENERIC&&mapped!=role){
                compiledCache.remove(part);reject(part,role,scaleBits,tick,generation);return false;
            }
            if(!treeEligible(part,role,0)){compiledCache.remove(part);reject(part,role,scaleBits,tick,generation);return false;}
            int sig=treeSignature(part,role,scale,0);
            int shallow=shallowTopologyStamp(part);
            if(!compatible||ce.signature!=sig) {
                CompiledMesh mesh=compileTree(part,role,scale);
                if(mesh==null||mesh.vertices.length==0){compiledCache.remove(part);reject(part,role,scaleBits,tick,generation);return false;}
                ce=new CacheEntry(sig,mesh,role,scaleBits,generation,nextAuditTick(part,tick),nextDeepWatchdogTick(part,tick),shallow);
                compiledCache.put(part,ce);rejectedCache.remove(part);perfCompiles++;
            } else {
                ce.generation=generation;
                ce.shallowStamp=shallow;
                ce.nextAuditTick=tick+TOPOLOGY_AUDIT_PERIOD;
                ce.deepWatchdogTick=nextDeepWatchdogTick(part,tick);
            }
            rejectedCache.remove(part);
            draw(ce.mesh);
            return true;
        } catch(Throwable ex) {
            compiledCache.remove(part);reject(part,role,Float.floatToIntBits(scale),CombatController.INSTANCE.tick(),JbraModelAdapter.INSTANCE.topologyGeneration());
            if(!warned){warned=true;System.err.println("[EpicFight1710] Cached POV body draw failed once; this part falls back instead of retry-storming.");ex.printStackTrace();}
            return false;
        }
    }

    private void reject(Object part,NativeJbraSkinContext.PartRole role,int scaleBits,int tick,int generation){
        if(part==null||role==null)return;
        rejectedCache.put(part,new RejectEntry(role,scaleBits,generation,nextRejectAuditTick(part,tick)));
    }


    /**
     * Model/form swaps create a new adapter topology generation. Old ModelRenderer
     * identities must not be retained indefinitely: besides memory growth, the old
     * 512-entry emergency clear could erase hot current meshes mid-frame and create a
     * visible compile spike. Prune only stale generations, once per real topology
     * change. The normal display-frame path pays one integer comparison.
     */
    private void observeTopologyGeneration(int generation){
        if(observedTopologyGeneration==generation)return;
        observedTopologyGeneration=generation;
        for(Iterator<Map.Entry<Object,CacheEntry>> it=compiledCache.entrySet().iterator();it.hasNext();)
            if(it.next().getValue().generation!=generation)it.remove();
        for(Iterator<Map.Entry<Object,RejectEntry>> it=rejectedCache.entrySet().iterator();it.hasNext();)
            if(it.next().getValue().generation!=generation)it.remove();
    }

    void resetPerformanceCounters(){
        perfRenderCalls=perfValidationRuns=perfValidationSkips=perfCompiles=0L;
        perfShallowChecks=perfDeepAudits=perfRejectSkips=0L;
        perfUniqueSkinPoints=perfTriangleCorners=perfNormalTriangles=perfNormalReuseTriangles=0L;
        perfStartTick=Integer.MIN_VALUE;perfSummaryLogged=false;
        JbraModelAdapter.INSTANCE.resetPerformanceCounters();
        ModelRendererCallsiteHook.resetPerformanceCounters();
        ModelRendererSkinHook.resetPerformanceCounters();
    }

    private static int nextAuditTick(Object part,int tick){
        int h=System.identityHashCode(part);h^=(h>>>16);
        return tick+1+(h&(TOPOLOGY_AUDIT_PERIOD-1));
    }
    private static int nextDeepWatchdogTick(Object part,int tick){
        int h=System.identityHashCode(part);h^=(h>>>16);
        return tick+DEEP_TOPOLOGY_WATCHDOG_PERIOD+(h&(DEEP_TOPOLOGY_WATCHDOG_PERIOD-1));
    }
    private static int nextRejectAuditTick(Object part,int tick){
        int h=System.identityHashCode(part);h^=(h>>>16);
        return tick+REJECT_AUDIT_PERIOD+(h&(REJECT_AUDIT_PERIOD-1));
    }

    /** Cheap sentinel for the overwhelmingly common stable-model case. It intentionally
     * avoids recursive descent and all per-box reflection. The adapter generation catches
     * model/form swaps; this stamp catches direct root list/child membership changes; the
     * deep watchdog remains a safety net for exotic in-place descendant mutation. */
    private int shallowTopologyStamp(Object part){
        if(part==null)return 0;
        PartAccess a=partAccess(part.getClass());if(a==null)return 0;
        int h=System.identityHashCode(part);
        Object cubes=Reflect.get(a.cubes,part);
        if(cubes instanceof List){List<?> l=(List<?>)cubes;h=31*h+System.identityHashCode(l);h=31*h+l.size();}
        Object children=Reflect.get(a.children,part);
        if(children instanceof List){
            List<?> l=(List<?>)children;h=31*h+System.identityHashCode(l);h=31*h+l.size();
            for(Object child:l){
                if(child==null)continue;
                h=31*h+System.identityHashCode(child);
                PartAccess ca=partAccess(child.getClass());
                if(ca!=null){
                    Object cc=Reflect.get(ca.cubes,child);if(cc instanceof List)h=31*h+((List<?>)cc).size();
                    Object ch=Reflect.get(ca.children,child);if(ch instanceof List)h=31*h+((List<?>)ch).size();
                }
            }
        }
        return h;
    }

    private boolean treeEligible(Object part,NativeJbraSkinContext.PartRole role,int depth) {
        if(part==null||depth>8)return false;
        PartAccess a=partAccess(part.getClass());if(a==null)return false;
        if(a.hidden!=null&&Reflect.getBoolean(a.hidden,part,false))return true;
        if(a.show!=null&&!Reflect.getBoolean(a.show,part,true))return true;
        boolean geometry=false;
        Object cubeObj=Reflect.get(a.cubes,part);
        if(cubeObj instanceof List&&!((List<?>)cubeObj).isEmpty())geometry=true;
        Object childObj=Reflect.get(a.children,part);
        if(childObj instanceof List)for(Object child:(List<?>)childObj) {
            if(child==null)continue;
            NativeJbraSkinContext.PartRole cr=FirstPersonBodyRenderer1710.active()?FirstPersonBodyRenderer1710.fastMappedRole(child):NativeJbraSkinContext.INSTANCE.mappedRole(child);
            // Epic's Humanoid Head owns attached headwear/layer geometry as one rigid
            // head subtree. JBRA may expose hair/headwear children without named aliases;
            // when the concrete skull is HEAD, inherit HEAD for those children.
            if(role!=NativeJbraSkinContext.PartRole.HEAD&&cr!=role)return false;
            if(!treeEligible(child,role,depth+1))return false;
            geometry=true;
        }
        return geometry;
    }

    private int treeSignature(Object part,NativeJbraSkinContext.PartRole role,float scale,int depth) {
        if(part==null||depth>8)return 0;
        PartAccess a=partAccess(part.getClass());if(a==null)return 0;
        float rpX=Reflect.getFloat(a.rpX,part,0),rpY=Reflect.getFloat(a.rpY,part,0),rpZ=Reflect.getFloat(a.rpZ,part,0);
        float offX=Reflect.getFloat(a.offX,part,0),offY=Reflect.getFloat(a.offY,part,0),offZ=Reflect.getFloat(a.offZ,part,0);
        int h=31*(role.ordinal()+1)+System.identityHashCode(part);
        h=31*h+Float.floatToIntBits(scale);h=31*h+q(offX);h=31*h+q(offY);h=31*h+q(offZ);
        // JRMCore mutates the ROOT ModelRenderer rotationPoint every frame for native
        // crouch/flight (e.g. legs Y=12/Z~0 -> Y=9/Z=4 while sneaking). Those are
        // animation state, not bind geometry. If they enter the signature, the cache is
        // rebuilt at the temporary native pivot and Epic then animates that displaced
        // mesh a second time. Descendant pivots/rotations remain legitimate static shape
        // information and still invalidate the compiled tree.
        if(depth>0){h=31*h+q(rpX);h=31*h+q(rpY);h=31*h+q(rpZ);h=31*h+q(Reflect.getFloat(a.rotX,part,0));h=31*h+q(Reflect.getFloat(a.rotY,part,0));h=31*h+q(Reflect.getFloat(a.rotZ,part,0));}
        Object cubeObj=Reflect.get(a.cubes,part);
        if(cubeObj instanceof List){List<?> boxes=(List<?>)cubeObj;h=31*h+System.identityHashCode(boxes);h=31*h+boxes.size();for(Object box:boxes)h=31*h+System.identityHashCode(box);}
        Object childObj=Reflect.get(a.children,part);
        if(childObj instanceof List){List<?> children=(List<?>)childObj;h=31*h+System.identityHashCode(children);h=31*h+children.size();for(Object child:children)h=31*h+treeSignature(child,role,scale,depth+1);}
        return h;
    }

    private CompiledMesh compileTree(Object part,NativeJbraSkinContext.PartRole role,float scale)throws Exception {
        ArrayList<CompiledVertex> verts=new ArrayList<CompiledVertex>();
        float[] identity=new float[16];Mat4.identity(identity);
        PartAccess a=partAccess(part.getClass());
        rootBindPivot(part,a,role,neutralRootPivot);
        float rpX=neutralRootPivot[0],rpY=neutralRootPivot[1],rpZ=neutralRootPivot[2];
        float offX=a==null?0:Reflect.getFloat(a.offX,part,0),offY=a==null?0:Reflect.getFloat(a.offY,part,0),offZ=a==null?0:Reflect.getFloat(a.offZ,part,0);
        // Same coordinate conversion used for compiled vertices: Minecraft model X is
        // mirrored into Epic X and model Y is measured down from the 1.5-block top.
        float pivotX=DbcSpatialMath.basisX(offX+rpX*scale),pivotY=DbcSpatialMath.basisY(offY+rpY*scale),pivotZ=offZ+rpZ*scale;
        compileNode(part,role,scale,identity,true,0,verts);
        CompiledMesh compiled=new CompiledMesh(verts.toArray(new CompiledVertex[verts.size()]),role,pivotX,pivotY,pivotZ);
        if(!skinDedupeLogged&&compiled.vertices.length>0){skinDedupeLogged=true;System.out.println("[EpicFight1710] Weighted skin-point cache active: UV/triangle topology stays unchanged while duplicate triangle corners share one matrix-skin result per frame (first mesh "+compiled.vertices.length+" corners -> "+compiled.skinVertices.length+" unique points).");}
        return compiled;
    }

    private void compileNode(Object part,NativeJbraSkinContext.PartRole role,float scale,float[] parent,boolean rootNode,int depth,ArrayList<CompiledVertex> verts)throws Exception {
        if(part==null||depth>8)return;
        PartAccess a=partAccess(part.getClass());if(a==null)return;
        if(a.hidden!=null&&Reflect.getBoolean(a.hidden,part,false))return;
        if(a.show!=null&&!Reflect.getBoolean(a.show,part,true))return;
        float rpX,rpY,rpZ;
        if(rootNode){rootBindPivot(part,a,role,neutralRootPivot);rpX=neutralRootPivot[0];rpY=neutralRootPivot[1];rpZ=neutralRootPivot[2];}
        else {rpX=Reflect.getFloat(a.rpX,part,0);rpY=Reflect.getFloat(a.rpY,part,0);rpZ=Reflect.getFloat(a.rpZ,part,0);}
        float offX=Reflect.getFloat(a.offX,part,0),offY=Reflect.getFloat(a.offY,part,0),offZ=Reflect.getFloat(a.offZ,part,0);
        float[] local=transform(offX+rpX*scale,offY+rpY*scale,offZ+rpZ*scale,
                rootNode?0.0F:Reflect.getFloat(a.rotX,part,0),rootNode?0.0F:Reflect.getFloat(a.rotY,part,0),rootNode?0.0F:Reflect.getFloat(a.rotZ,part,0));
        float[] world=new float[16];Mat4.mul(parent,local,world);

        Object cubeObj=Reflect.get(a.cubes,part);
        if(cubeObj instanceof List)compileBoxes((List<?>)cubeObj,role,scale,world,verts);
        Object childObj=Reflect.get(a.children,part);
        if(childObj instanceof List)for(Object child:(List<?>)childObj)if(child!=null)compileNode(child,role,scale,world,false,depth+1,verts);
    }

    /** Freeze known JRMCore body anchors to their constructor/bind values. Native
     * animation is deliberately ignored here; Epic is the sole animation source once
     * Battle Mode owns the body. Values are in ModelRenderer pixel coordinates. */
    private void rootBindPivot(Object part,PartAccess a,NativeJbraSkinContext.PartRole role,float[] out) {
        float x=a==null?0:Reflect.getFloat(a.rpX,part,0),y=a==null?0:Reflect.getFloat(a.rpY,part,0),z=a==null?0:Reflect.getFloat(a.rpZ,part,0);
        if(role==NativeJbraSkinContext.PartRole.RIGHT_LEG){x=-2.0F;y=12.0F;z=0.0F;}
        else if(role==NativeJbraSkinContext.PartRole.LEFT_LEG){x=2.0F;y=12.0F;z=0.0F;}
        else if((role==NativeJbraSkinContext.PartRole.RIGHT_ARM||role==NativeJbraSkinContext.PartRole.LEFT_ARM)&&hasChildren(part,a)) {
            // Muscular ModelBipedBody uses Brightarm/Bleftarm parent anchors with the
            // visible arm cube below them as a child. These constructor pivots are stable.
            x=role==NativeJbraSkinContext.PartRole.RIGHT_ARM?-5.0F:5.0F;y=2.0F;z=0.0F;
        }
        out[0]=x;out[1]=y;out[2]=z;
        if(!neutralBindLogged&&(role==NativeJbraSkinContext.PartRole.RIGHT_LEG||role==NativeJbraSkinContext.PartRole.LEFT_LEG||role==NativeJbraSkinContext.PartRole.RIGHT_ARM||role==NativeJbraSkinContext.PartRole.LEFT_ARM)) {
            neutralBindLogged=true;
            System.out.println("[EpicFight1710] Neutral JBRA bind cache active: live JRMCore rotationPoint animation is excluded from compiled limb geometry; Epic pose owns sneak/flight deformation.");
        }
    }

    private boolean hasChildren(Object part,PartAccess a) {
        if(part==null||a==null||a.children==null)return false;
        Object c=Reflect.get(a.children,part);return c instanceof List&&!((List<?>)c).isEmpty();
    }

    private void compileBoxes(List<?> boxes,NativeJbraSkinContext.PartRole role,float scale,float[] bind,ArrayList<CompiledVertex> verts)throws Exception {
        float[][] corners=new float[4][5];int div=subdivisions(role);
        for(Object box:boxes) {
            if(box==null)continue;Object qs=getQuads(box);if(qs==null||!qs.getClass().isArray())continue;
            for(int qi=0;qi<Array.getLength(qs);qi++) {
                Object q=Array.get(qs,qi);if(q==null||!readQuad(q,corners,bind,scale))continue;
                if(role==NativeJbraSkinContext.PartRole.RIGHT_LEG||role==NativeJbraSkinContext.PartRole.LEFT_LEG){
                    compileLegacyLegQuad(corners,role,verts);
                    continue;
                }
                for(int iy=0;iy<div;iy++) {
                    float v0=iy/(float)div,v1=(iy+1)/(float)div;
                    for(int ix=0;ix<div;ix++) {
                        float u0=ix/(float)div,u1=(ix+1)/(float)div;
                        emitQuadCell(corners,u0,v0,u1,v1,role,verts);
                    }
                }
            }
        }
    }

    /**
     * Retopologize a one-piece JBRA leg using the actual three-ring seam present in
     * Epic Fight's biped_old.dat. 2.0.15 added only one ring at y=.375; the next
     * lower-leg row was still two Minecraft pixels away, so the whole band stretched
     * through the knee. The source mesh instead has a rigid Thigh ring just above the
     * seam, a Knee helper ring, and a rigid Leg ring immediately below it.
     */
    private void compileLegacyLegQuad(float[][] c,NativeJbraSkinContext.PartRole role,ArrayList<CompiledVertex> verts){
        float s0y=.5F*((1.5F-c[0][1])+(1.5F-c[3][1]));
        float s1y=.5F*((1.5F-c[1][1])+(1.5F-c[2][1]));
        float t0y=.5F*((1.5F-c[0][1])+(1.5F-c[1][1]));
        float t1y=.5F*((1.5F-c[3][1])+(1.5F-c[2][1]));
        boolean verticalS=Math.abs(s1y-s0y)>Math.abs(t1y-t0y);
        float verticalSpan=verticalS?Math.abs(s1y-s0y):Math.abs(t1y-t0y);
        if(verticalSpan<.05F){
            // Top/bottom cap: no knee crossing. A small regular grid is enough.
            for(int iy=0;iy<3;iy++)for(int ix=0;ix<3;ix++)emitQuadCell(c,ix/3.0F,iy/3.0F,(ix+1)/3.0F,(iy+1)/3.0F,role,verts);
            return;
        }
        float y0=verticalS?s0y:t0y,y1=verticalS?s1y:t1y;
        ArrayList<Float> vc=new ArrayList<Float>();
        vc.add(Float.valueOf(0.0F));vc.add(Float.valueOf(1.0F/3.0F));vc.add(Float.valueOf(2.0F/3.0F));vc.add(Float.valueOf(1.0F));
        // Exact legacy source seam rows extracted from biped_old.dat. Right/left have
        // tiny exporter differences, so preserve their authored values independently.
        if(role==NativeJbraSkinContext.PartRole.RIGHT_LEG){addCutForEpicY(vc,y0,y1,.376942F);addCutForEpicY(vc,y0,y1,.375045F);addCutForEpicY(vc,y0,y1,.374273F);}
        else {addCutForEpicY(vc,y0,y1,.377014F);addCutForEpicY(vc,y0,y1,.375045F);addCutForEpicY(vc,y0,y1,.373535F);}
        Collections.sort(vc);
        ArrayList<Float> cuts=new ArrayList<Float>();
        for(Float f:vc){float x=f.floatValue();if(cuts.isEmpty()||Math.abs(x-cuts.get(cuts.size()-1).floatValue())>.00001F)cuts.add(f);}
        final int cross=3;
        for(int vi=0;vi+1<cuts.size();vi++)for(int hi=0;hi<cross;hi++){
            float v0=cuts.get(vi).floatValue(),v1=cuts.get(vi+1).floatValue();
            float h0=hi/(float)cross,h1=(hi+1)/(float)cross;
            if(verticalS)emitQuadCell(c,v0,h0,v1,h1,role,verts);else emitQuadCell(c,h0,v0,h1,v1,role,verts);
        }
    }

    private static void addCutForEpicY(ArrayList<Float> cuts,float y0,float y1,float target){
        float d=y1-y0;if(Math.abs(d)<.000001F)return;float p=(target-y0)/d;if(p>.00001F&&p<.99999F)cuts.add(Float.valueOf(p));
    }

    private void emitQuadCell(float[][] c,float u0,float v0,float u1,float v1,NativeJbraSkinContext.PartRole role,ArrayList<CompiledVertex> verts){
        CompiledVertex a=compilePoint(c,u0,v0,role),b=compilePoint(c,u1,v0,role),cc=compilePoint(c,u1,v1,role),d=compilePoint(c,u0,v1,role);
        if(a!=null&&b!=null&&cc!=null&&d!=null){verts.add(a);verts.add(b);verts.add(cc);verts.add(a);verts.add(cc);verts.add(d);}
    }

    private static float[] transform(float tx,float ty,float tz,float rx,float ry,float rz) {
        return DbcSpatialMath.nodeTransform(tx,ty,tz,rx,ry,rz);
    }

    private boolean readQuad(Object quad,float[][] out,float[] bind,float scale)throws Exception {
        QuadAccess qa=quadAccess(quad.getClass());if(qa==null)return false;Object arr=Reflect.get(qa.vertices,quad);if(arr==null||!arr.getClass().isArray()||Array.getLength(arr)<4)return false;
        float[] p=new float[3];
        for(int i=0;i<4;i++){
            Object v=Array.get(arr,i);if(v==null)return false;VertexAccess va=vertexAccess(v.getClass());if(va==null)return false;Object vec=Reflect.get(va.vec,v);if(vec==null)return false;VecAccess xa=vecAccess(vec.getClass());if(xa==null)return false;
            float x=(float)Reflect.getDouble(xa.x,vec,0)*scale,y=(float)Reflect.getDouble(xa.y,vec,0)*scale,z=(float)Reflect.getDouble(xa.z,vec,0)*scale;
            Mat4.transformPoint(bind,x,y,z,p,0);out[i][0]=p[0];out[i][1]=p[1];out[i][2]=p[2];out[i][3]=Reflect.getFloat(va.u,v,0);out[i][4]=Reflect.getFloat(va.v,v,0);
        }
        return true;
    }

    private CompiledVertex compilePoint(float[][] c,float s,float t,NativeJbraSkinContext.PartRole role) {
        float a=(1-s)*(1-t),b=s*(1-t),d=s*t,e=(1-s)*t;
        float mx=c[0][0]*a+c[1][0]*b+c[2][0]*d+c[3][0]*e;
        float my=c[0][1]*a+c[1][1]*b+c[2][1]*d+c[3][1]*e;
        float mz=c[0][2]*a+c[1][2]*b+c[2][2]*d+c[3][2]*e;
        float u=c[0][3]*a+c[1][3]*b+c[2][3]*d+c[3][3]*e;
        float v=c[0][4]*a+c[1][4]*b+c[2][4]*d+c[3][4]*e;
        return compiledVertexFromEpic(DbcSpatialMath.basisX(mx),DbcSpatialMath.basisY(my),mz,u,v,role);
    }

    private CompiledVertex compiledVertexFromEpic(float ex,float ey,float ez,float u,float v,NativeJbraSkinContext.PartRole role) {
        float[] full=weightsFor(ex,ey,ez,role);if(full==null)return null;
        int[] ids={-1,-1,-1,-1};float[] ws={0,0,0,0};
        for(int j=0;j<full.length;j++)if(full[j]>0) {
            for(int k=0;k<4;k++)if(full[j]>ws[k]){for(int z=3;z>k;z--){ws[z]=ws[z-1];ids[z]=ids[z-1];}ws[k]=full[j];ids[k]=j;break;}
        }
        float sum=ws[0]+ws[1]+ws[2]+ws[3];if(sum<=.00001F)return null;for(int k=0;k<4;k++)ws[k]/=sum;
        return new CompiledVertex(ex,ey,ez,u,v,ids,ws);
    }

    private void draw(CompiledMesh mesh) {
        Tessellator t=Tessellator.field_78398_a;boolean started=false;
        final boolean povArmDraw=EpicFirstPersonBridge.useDirectFirstPersonVertexEmission()
                &&(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM||mesh.role==NativeJbraSkinContext.PartRole.LEFT_ARM);
        // Epic Fight's first-person mesh is rendered with entityCutoutNoCull. The
        // native DBC/JBRA arm replacement must preserve that rasterization contract;
        // culling here can expose the wrong face exactly when the arm approaches the
        // hand near plane.
        final boolean restoreCull=povArmDraw&&GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if(restoreCull)GL11.glDisable(GL11.GL_CULL_FACE);
        try {
            // Epic 20.9.5 FirstPersonRenderer consumes the same FINAL Animator pose
            // and Armature matrices as third person. 2.0.26-2.0.31 incorrectly restored
            // a raw authored Root only for POV; that split-pose path is removed.
            float[][] matrices=PoseEngine.INSTANCE.skinMatrices();
            final boolean povArm=EpicFirstPersonBridge.useDirectFirstPersonVertexEmission()
                    &&(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM||mesh.role==NativeJbraSkinContext.PartRole.LEFT_ARM);
            int armJoint=-1,handJoint=-1,elbowJoint=-1,thighJoint=-1,legJoint=-1,kneeJoint=-1;
            float[] armM=null,handM=null,elbowM=null,thighM=null,legM=null,kneeM=null;
            if(povArm&&retargetProfile!=null){
                armJoint=retargetProfile.upperArmJoint(mesh.role);
                handJoint=retargetProfile.handJoint(mesh.role);
                elbowJoint=retargetProfile.elbowJoint(mesh.role);
            }

            if(retargetProfile!=null&&(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM||mesh.role==NativeJbraSkinContext.PartRole.LEFT_ARM)) {
                int shoulder=retargetProfile.shoulderJoint(mesh.role);
                armJoint=retargetProfile.upperArmJoint(mesh.role);handJoint=retargetProfile.handJoint(mesh.role);elbowJoint=retargetProfile.elbowJoint(mesh.role);
                if(shoulder>=0&&armJoint>=0&&shoulder<matrices.length&&armJoint<matrices.length) {
                    retargetProfile.upperArmBindPivot(mesh.role,sourcePivot);
                    float dx=mesh.pivotX-sourcePivot[0],dy=mesh.pivotY-sourcePivot[1],dz=mesh.pivotZ-sourcePivot[2];
                    float dist2=dx*dx+dy*dy+dz*dz;
                    if(dist2<.0324F) {
                        DbcRetargetMath.socketCorrectMatrix(matrices[shoulder],matrices[armJoint],mesh.pivotX,mesh.pivotY,mesh.pivotZ,.25F,armCorrected);armM=armCorrected;
                        retargetProfile.handBindPivot(mesh.role,sourceHandPivot);
                        retargetProfile.elbowBindPivot(mesh.role,sourceElbowPivot);
                        targetHandPivot[0]=sourceHandPivot[0]+dx;targetHandPivot[1]=sourceHandPivot[1]+dy;targetHandPivot[2]=sourceHandPivot[2]+dz;
                        targetElbowPivot[0]=sourceElbowPivot[0]+dx;targetElbowPivot[1]=sourceElbowPivot[1]+dy;targetElbowPivot[2]=sourceElbowPivot[2]+dz;
                        if(handJoint>=0&&handJoint<matrices.length){
                            DbcRetargetMath.socketCorrectMatrix(armM,matrices[handJoint],targetHandPivot[0],targetHandPivot[1],targetHandPivot[2],.25F,handCorrected);handM=handCorrected;
                            if(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM){
                                rightHandSocketDelta[0]=handCorrected[3]-matrices[handJoint][3];
                                rightHandSocketDelta[1]=handCorrected[7]-matrices[handJoint][7];
                                rightHandSocketDelta[2]=handCorrected[11]-matrices[handJoint][11];
                                rightHandSocketDeltaValid=true;
                            }
                        }
                        if(elbowJoint>=0&&elbowJoint<matrices.length){DbcRetargetMath.socketCorrectMatrix(armM,matrices[elbowJoint],targetElbowPivot[0],targetElbowPivot[1],targetElbowPivot[2],.25F,elbowCorrected);elbowM=elbowCorrected;}

                        // The DBC muscular arm is effectively one continuous cuboid while
                        // Epic's legacy mesh has a visibly articulated Hand segment. In the
                        // passive creative_idle hover this lower-joint bend is what makes a
                        // blocky DBC arm look broken sideways. Keep the source upper-arm
                        // quaternion, but treat the passive limb as rigid. Actions/guard keep
                        // the full Hand/Elbow articulation from Epic.
                        com.nicolas.epicfight1710.anim.runtime.LayeredAnimator animator=PoseEngine.INSTANCE.animator();
                        LivingMotion lm=PoseEngine.INSTANCE.currentMotion();
                        boolean passiveFlight=(lm==LivingMotion.CREATIVE_IDLE||lm==LivingMotion.FLY_FORWARD||lm==LivingMotion.FLY_BACKWARD)&&!PoseEngine.INSTANCE.actionActive()&&(animator==null||!animator.guardActive());
                        if(passiveFlight) {
                            // Preserve each Epic side exactly as authored. The muscular
                            // JBRA arm is one continuous cuboid, so Hand/Elbow vertices
                            // follow that side's corrected upper-arm matrix only during
                            // passive flight; no LEFT<->RIGHT source copying occurs.
                            handM=armM;elbowM=armM;
                            if(!passiveFlightLogged){passiveFlightLogged=true;System.out.println("[EpicFight1710] Passive DBC creative-flight arm retarget active: each authored Epic arm keeps its own quaternion/socket; the one-piece muscular cuboid is rigidified without cross-arm mirroring.");}
                        }
                        if(!pivotLogged){pivotLogged=true;System.out.println("[EpicFight1710] Hierarchical JBRA arm retarget active: Shoulder -> Brightarm -> Hand/Elbow sockets are reconnected while source Epic quaternions remain untouched.");}
                    }
                }
            } else if(retargetProfile!=null&&(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_LEG||mesh.role==NativeJbraSkinContext.PartRole.LEFT_LEG)) {
                thighJoint=retargetProfile.thighJoint(mesh.role);legJoint=retargetProfile.lowerLegJoint(mesh.role);kneeJoint=retargetProfile.kneeJoint(mesh.role);
                // The neutral JBRA 4x12x4 leg and Epic biped_old bind are already in the
                // same anatomical space (hip ~= y .761, knee ~= y .386). Applying a
                // second pivotCorrect to Thigh/Knee/Leg moved an already-correct source
                // hierarchy and was the remaining source of the SNEAK hip/leg split.
                // Use the authoritative Epic skin matrices directly; the retopologized
                // three-ring seam below is the only adaptation the one-piece DBC cuboid
                // needs in order to bend around the authored Knee helper.
                if(thighJoint>=0&&thighJoint<matrices.length)thighM=matrices[thighJoint];
                if(legJoint>=0&&legJoint<matrices.length)legM=matrices[legJoint];
                if(kneeJoint>=0&&kneeJoint<matrices.length)kneeM=matrices[kneeJoint];
                if(!legPivotLogged){legPivotLogged=true;System.out.println("[EpicFight1710] Direct Epic leg skinning active: DBC leg geometry uses the authored biped_old Thigh/Knee/Leg matrices without a second pivot retarget; only the source seam retopology adapts the one-piece cuboid.");}
            }

            CompiledVertex[] vs=mesh.vertices;
            // Skin each unique weighted model-space point once per rendered frame.
            // The compiled triangle list intentionally duplicates corners for UV seams
            // and face topology; transforming those duplicates 3-5x was pure CPU work.
            skinUniquePoints(mesh,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
            boolean suppressPovArm=false;
            povDepthObjectShift[0]=povDepthObjectShift[1]=povDepthObjectShift[2]=0.0F;
            if(povArm){
                // The replacement mesh is DBC/JBRA geometry, not Epic's own humanoid
                // mesh. Their bind proportions differ enough that a source-hidden arm
                // can leak only a tiny DBC sliver through Minecraft 1.7.10's fixed
                // 0.05 hand near plane. Perspective-clipping that sliver creates the
                // huge screen-filling cuboid seen in the 2.0.33 live test.
                //
                // Resolve visibility from the ORIGINAL Epic right/left arm+sleeve
                // under the SAME final Armature matrices. This is not camera fitting:
                // no FOV/aspect or user XYZ enters the decision. We only prevent the
                // replacement DBC geometry from violating the source part's near-plane
                // visibility class, and when the source is fully visible we reconcile
                // only the minimum depth necessary to keep the longer DBC cuboid on
                // the same side of that plane.
                if(readCurrentModelView()&&sourceFirstPersonDepthBounds(mesh.role,matrices,povSourceBounds)
                        &&dbcFirstPersonDepthBounds(mesh,povDbcBounds)){
                    final float near=-0.05F;
                    final float eps=0.001F;
                    boolean sourceFullyHidden=povSourceBounds[0]>near+eps;
                    boolean sourceFullyVisible=povSourceBounds[1]<near-eps;
                    if(sourceFullyHidden){
                        suppressPovArm=true;
                        if(!firstPersonSourceSuppressedLogged){
                            firstPersonSourceSuppressedLogged=true;
                            System.out.println("[EpicFight1710] First-person 2.0.33 source near-plane ownership active: source "+mesh.role+" is fully behind the fixed 0.05 hand near plane (sourceZ="+povSourceBounds[0]+".."+povSourceBounds[1]+", dbcZ="+povDbcBounds[0]+".."+povDbcBounds[1]+"); its longer DBC/JBRA replacement is suppressed instead of leaking a clipped sliver into view.");
                        }
                    } else if(sourceFullyVisible&&povDbcBounds[1]>=near-eps){
                        float eyeShift=povSourceBounds[1]-povDbcBounds[1];
                        if(eyeShift<0.0F&&eyeShift>-0.35F&&eyeDepthToObjectShift(eyeShift,povDepthObjectShift)){
                            if(!firstPersonDepthReconcileLogged){
                                firstPersonDepthReconcileLogged=true;
                                System.out.println("[EpicFight1710] First-person 2.0.33 source-depth reconciliation active: source "+mesh.role+" is wholly visible but the longer DBC/JBRA cuboid crosses the hand near plane; camera-space depth delta "+eyeShift+" is derived from the original Epic arm envelope, not FOV or manual framing.");
                            }
                        }
                    }
                    if(!firstPersonSourceVisibilityLogged){
                        firstPersonSourceVisibilityLogged=true;
                        System.out.println("[EpicFight1710] First-person 2.0.33 source mesh visibility gate active: original Epic arm+sleeve depth is evaluated under the same final Animator matrices before native DBC/JBRA geometry is emitted.");
                    }
                }
            }
            if(suppressPovArm)return;

            prepareTriangleNormals(mesh);
            t.func_78371_b(GL11.GL_TRIANGLES);started=true;
            int tri=0;
            for(int i=0;i+2<vs.length;i+=3,tri++) {
                // Geometry/UV topology is unchanged. Weighted positions and face normals
                // are evaluated once per mesh per display frame, then reused by the
                // multiple JBRA texture/tint passes that replay the same ModelRenderer.
                emitCachedTriangle(t,mesh,tri,vs[i],vs[i+1],vs[i+2],povDepthObjectShift);
            }
            if(povArm&&!firstPersonIntactArmLogged){
                firstPersonIntactArmLogged=true;
                System.out.println("[EpicFight1710] First-person 2.0.33 intact native-arm path active: no crop/proxy/cap or FOV fit; final Animator matrices are shared with third person and source Epic near-plane visibility constrains only the DBC/JBRA replacement boundary.");
            }
            finishTessellation(t);started=false;
        } catch(Throwable x){if(started)try{finishTessellation(t);}catch(Throwable ignored){}throw x;}
        finally {if(restoreCull)GL11.glEnable(GL11.GL_CULL_FACE);}
    }


    /**
     * Modern Epic Fight's POV renderer has explicit CAMERA/WORLD root modes and
     * per-part visibility. Our old adapter hard-coded an arm-only CAMERA pass, so it
     * could never expose torso/legs when the player looked down. 2.0.33 keeps the
     * already-correct 1.7.10 hand camera but adds the local player's view pitch as a
     * WORLD-root transform for the complete visible POV body. No FOV-dependent
     * distance or manual arm offset is involved.
     */
    private void pushFirstPersonWorldSpace(){
        if(firstPersonWorldDepth++>0)return;
        GL11.glPushMatrix();
        try {
            NativeJbraSkinContext ctx=NativeJbraSkinContext.INSTANCE;
            if(nativeActivePlayerField==null)nativeActivePlayerField=Reflect.field(NativeJbraSkinContext.class,"activePlayer");
            if(nativeActivePartialField==null)nativeActivePartialField=Reflect.field(NativeJbraSkinContext.class,"activePartial");
            Object player=Reflect.get(nativeActivePlayerField,ctx);
            float partial=Reflect.getFloat(nativeActivePartialField,ctx,0.0F);
            if(player!=null){
                Class<?> pc=player.getClass();
                if(firstPersonPlayerClass!=pc){
                    firstPersonPlayerClass=pc;
                    firstPersonPitchField=Reflect.field(pc,"field_70125_A","rotationPitch");
                    firstPersonPrevPitchField=Reflect.field(pc,"field_70127_C","prevRotationPitch");
                }
                float now=Reflect.getFloat(firstPersonPitchField,player,0.0F);
                float prev=Reflect.getFloat(firstPersonPrevPitchField,player,now);
                float pitch=prev+(now-prev)*partial;
                if(finite(pitch))GL11.glRotatef(pitch,1.0F,0.0F,0.0F);
                if(!firstPersonWorldLogged){
                    firstPersonWorldLogged=true;
                    System.out.println("[EpicFight1710] First-person 2.0.33 WORLD-root body view active: camera pitch rotates the shared headless body/arms skeleton so torso and legs become naturally visible when looking down; FOV is not used for placement.");
                }
            }
        } catch(Throwable ignored){}
    }

    private void popFirstPersonWorldSpace(){
        if(firstPersonWorldDepth<=0){firstPersonWorldDepth=0;return;}
        if(--firstPersonWorldDepth==0)GL11.glPopMatrix();
    }

    /**
     * Emit a conservative headless body reference using the canonical Minecraft/JBRA
     * body and leg ModelRenderers. These are deliberately chosen instead of every
     * mapped torso alias: DBC has many alternative breast/waist/body shells and
     * drawing all of them together would stack mutually-exclusive geometry.
     *
     * The current RIGHT_ARM pass already owns the live JBRA skin/tint texture, so the
     * standard torso/leg UVs sample the same player skin atlas. Head/face/hair are never
     * emitted, preventing the camera from being enclosed by the skull.
     */
    private void renderFirstPersonBody(float scale){
        try {
            if(nativeActiveMainModelField==null)nativeActiveMainModelField=Reflect.field(NativeJbraSkinContext.class,"activeMainModel");
            Object model=Reflect.get(nativeActiveMainModelField,NativeJbraSkinContext.INSTANCE);
            if(model==null)return;
            PovBodyAccess a=povBodyAccess(model.getClass());
            if(a==null)return;
            Object torso=firstPart(model,a.torsoPrimary,a.torsoFallback);
            Object rightLeg=firstPart(model,a.rightLegPrimary,a.rightLegFallback);
            Object leftLeg=firstPart(model,a.leftLegPrimary,a.leftLegFallback);
            int emitted=0;
            if(torso!=null&&render(torso,scale,NativeJbraSkinContext.PartRole.TORSO))emitted++;
            if(rightLeg!=null&&render(rightLeg,scale,NativeJbraSkinContext.PartRole.RIGHT_LEG))emitted++;
            if(leftLeg!=null&&render(leftLeg,scale,NativeJbraSkinContext.PartRole.LEFT_LEG))emitted++;
            if(emitted>0&&!firstPersonBodyLogged){
                firstPersonBodyLogged=true;
                System.out.println("[EpicFight1710] First-person 2.0.33 headless body pass active: canonical torso + right/left legs share the same Epic first-person Armature and live JBRA skin texture; head/face/hair remain hidden at the camera.");
            }
        } catch(Throwable ignored){}
    }

    private PovBodyAccess povBodyAccess(Class<?> c){
        if(povBodyAccessCache.containsKey(c))return povBodyAccessCache.get(c);
        PovBodyAccess a=new PovBodyAccess();
        // The vanilla fields are full closed cuboids and are the safest common POV
        // body surface. DBC's segmented custom shells remain third-person/native.
        a.torsoPrimary=Reflect.field(c,"field_78115_e");
        a.torsoFallback=Reflect.field(c,"body");
        a.rightLegPrimary=Reflect.field(c,"field_78123_h");
        a.rightLegFallback=Reflect.field(c,"rightleg");
        a.leftLegPrimary=Reflect.field(c,"field_78124_i");
        a.leftLegFallback=Reflect.field(c,"leftleg");
        if(a.torsoPrimary==null&&a.torsoFallback==null&&a.rightLegPrimary==null&&a.rightLegFallback==null&&a.leftLegPrimary==null&&a.leftLegFallback==null){
            povBodyAccessCache.put(c,null);return null;
        }
        povBodyAccessCache.put(c,a);return a;
    }

    private static Object firstPart(Object owner,Field primary,Field fallback){
        Object p=Reflect.get(primary,owner);return p!=null?p:Reflect.get(fallback,owner);
    }


    /**
     * First-person only: preserve JBRA's live camera-local hand anchor and remove the
     * world/body translation from Epic's already-skinned vertices. The limb rotation,
     * wrist/elbow articulation and source quaternions remain exactly the same as the
     * third-person weighted path; only the coordinate origin changes.
     *
     * ModelRenderer.render would normally apply current rotationPoint + native DBC
     * angles. We apply ONLY its current pivot/offset here. Native rotation is omitted
     * because the weighted vertices already contain the Epic arm rotation.
     */
    private void drawFirstPersonAnchored(CompiledMesh mesh,Object part,float scale,PartAccess access) {
        Tessellator t=Tessellator.field_78398_a;boolean started=false;
        GL11.glPushMatrix();
        try {
            float rpX=access==null?0:Reflect.getFloat(access.rpX,part,0),rpY=access==null?0:Reflect.getFloat(access.rpY,part,0),rpZ=access==null?0:Reflect.getFloat(access.rpZ,part,0);
            float offX=access==null?0:Reflect.getFloat(access.offX,part,0),offY=access==null?0:Reflect.getFloat(access.offY,part,0),offZ=access==null?0:Reflect.getFloat(access.offZ,part,0);
            GL11.glTranslatef(offX+rpX*scale,offY+rpY*scale,offZ+rpZ*scale);

            t.func_78371_b(GL11.GL_TRIANGLES);started=true;
            float[][] matrices=PoseEngine.INSTANCE.skinMatrices();
            int armJoint=-1,handJoint=-1,elbowJoint=-1;
            float[] armM=null,handM=null,elbowM=null;

            if(retargetProfile!=null&&(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM||mesh.role==NativeJbraSkinContext.PartRole.LEFT_ARM)) {
                int shoulder=retargetProfile.shoulderJoint(mesh.role);
                armJoint=retargetProfile.upperArmJoint(mesh.role);handJoint=retargetProfile.handJoint(mesh.role);elbowJoint=retargetProfile.elbowJoint(mesh.role);
                if(shoulder>=0&&armJoint>=0&&shoulder<matrices.length&&armJoint<matrices.length) {
                    retargetProfile.upperArmBindPivot(mesh.role,sourcePivot);
                    float dx=mesh.pivotX-sourcePivot[0],dy=mesh.pivotY-sourcePivot[1],dz=mesh.pivotZ-sourcePivot[2];
                    float dist2=dx*dx+dy*dy+dz*dz;
                    if(dist2<.0324F) {
                        DbcRetargetMath.socketCorrectMatrix(matrices[shoulder],matrices[armJoint],mesh.pivotX,mesh.pivotY,mesh.pivotZ,.25F,armCorrected);armM=armCorrected;
                        retargetProfile.handBindPivot(mesh.role,sourceHandPivot);
                        retargetProfile.elbowBindPivot(mesh.role,sourceElbowPivot);
                        targetHandPivot[0]=sourceHandPivot[0]+dx;targetHandPivot[1]=sourceHandPivot[1]+dy;targetHandPivot[2]=sourceHandPivot[2]+dz;
                        targetElbowPivot[0]=sourceElbowPivot[0]+dx;targetElbowPivot[1]=sourceElbowPivot[1]+dy;targetElbowPivot[2]=sourceElbowPivot[2]+dz;
                        if(handJoint>=0&&handJoint<matrices.length){
                            DbcRetargetMath.socketCorrectMatrix(armM,matrices[handJoint],targetHandPivot[0],targetHandPivot[1],targetHandPivot[2],.25F,handCorrected);handM=handCorrected;
                            if(mesh.role==NativeJbraSkinContext.PartRole.RIGHT_ARM){
                                rightHandSocketDelta[0]=handCorrected[3]-matrices[handJoint][3];
                                rightHandSocketDelta[1]=handCorrected[7]-matrices[handJoint][7];
                                rightHandSocketDelta[2]=handCorrected[11]-matrices[handJoint][11];
                                rightHandSocketDeltaValid=true;
                            }
                        }
                        if(elbowJoint>=0&&elbowJoint<matrices.length){DbcRetargetMath.socketCorrectMatrix(armM,matrices[elbowJoint],targetElbowPivot[0],targetElbowPivot[1],targetElbowPivot[2],.25F,elbowCorrected);elbowM=elbowCorrected;}
                        com.nicolas.epicfight1710.anim.runtime.LayeredAnimator animator=PoseEngine.INSTANCE.animator();
                        LivingMotion lm=PoseEngine.INSTANCE.currentMotion();
                        boolean passiveFlight=(lm==LivingMotion.CREATIVE_IDLE||lm==LivingMotion.FLY_FORWARD||lm==LivingMotion.FLY_BACKWARD)&&!PoseEngine.INSTANCE.actionActive()&&(animator==null||!animator.guardActive());
                        if(passiveFlight){handM=armM;elbowM=armM;}
                    }
                }
            }
            if(armJoint<0||armJoint>=matrices.length){
                // No trustworthy root means a native-anchored weighted draw would have
                // no stable origin. Suppress rather than falling back to a leaked arm.
                finishTessellation(t);started=false;return;
            }
            float[] rootM=armM!=null?armM:matrices[armJoint];
            // Existing skin() emits JBRA/model coordinates: (-EpicX,1.5-EpicY,+EpicZ).
            // Convert the current Epic arm-root translation into that same basis and
            // subtract it from every vertex. Native JBRA supplies the root's camera
            // anchor above, so this removes only the world/body displacement.
            final float rootX=-rootM[3],rootY=1.5F-rootM[7],rootZ=rootM[11];

            CompiledVertex[] vs=mesh.vertices;
            for(int i=0;i+2<vs.length;i+=3) {
                skin(vs[i],pa,mesh.role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,-1,null,-1,null,-1,null);
                skin(vs[i+1],pb,mesh.role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,-1,null,-1,null,-1,null);
                skin(vs[i+2],pc,mesh.role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,-1,null,-1,null,-1,null);
                pa[0]-=rootX;pa[1]-=rootY;pa[2]-=rootZ;
                pb[0]-=rootX;pb[1]-=rootY;pb[2]-=rootZ;
                pc[0]-=rootX;pc[1]-=rootY;pc[2]-=rootZ;
                float ux=pb[0]-pa[0],uy=pb[1]-pa[1],uz=pb[2]-pa[2],vx=pc[0]-pa[0],vy=pc[1]-pa[1],vz=pc[2]-pa[2];
                float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(len<.000001F){nx=0;ny=1;nz=0;}else{nx/=len;ny/=len;nz/=len;}
                t.func_78375_b(nx,ny,nz);
                emit(t,pa,vs[i]);emit(t,pb,vs[i+1]);emit(t,pc,vs[i+2]);
            }
            finishTessellation(t);started=false;
            if(!firstPersonAnchorLogged){
                firstPersonAnchorLogged=true;
                System.out.println("[EpicFight1710] Legacy root-normalized first-person path should be unreachable in 2.0.5.");
            }
        } catch(Throwable x){if(started)try{finishTessellation(t);}catch(Throwable ignored){}throw x;}
        finally {GL11.glPopMatrix();}
    }

    /**
     * 1.7.10 Tessellator.draw / SRG func_78381_a returns int. Keeping the
     * return type in this helper makes a stale void stub fail compilation
     * instead of silently packaging the invalid ()V descriptor seen in 0.24.0.
     */
    private static int finishTessellation(Tessellator t){return t.func_78381_a();}


    private void skin(CompiledVertex v,float[] out,NativeJbraSkinContext.PartRole role,float[][] matrices,
                      int armJoint,float[] armM,int handJoint,float[] handM,int elbowJoint,float[] elbowM,
                      int thighJoint,float[] thighM,int legJoint,float[] legM,int kneeJoint,float[] kneeM) {
        skin(v,out,0,role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
    }

    private void skin(CompiledVertex v,float[] out,int outOff,NativeJbraSkinContext.PartRole role,float[][] matrices,
                      int armJoint,float[] armM,int handJoint,float[] handM,int elbowJoint,float[] elbowM,
                      int thighJoint,float[] thighM,int legJoint,float[] legM,int kneeJoint,float[] kneeM) {
        float sx=0,sy=0,sz=0,total=0;
        int influenceCount=v.influenceCount;
        if(influenceCount==1){
            int j=v.joint[0];float w=v.weight[0];
            if(j>=0&&j<matrices.length&&w>.00001F){
                float[] m=matrixForJoint(j,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
                Mat4.transformPoint(m,v.x,v.y,v.z,transformed,0);
                if(w>.99999F){sx=transformed[0];sy=transformed[1];sz=transformed[2];total=1.0F;}
                else {sx=transformed[0]*w;sy=transformed[1]*w;sz=transformed[2]*w;total=w;}
            }
        } else {
            for(int k=0;k<influenceCount;k++){
                int j=v.joint[k];float w=v.weight[k];if(j<0||j>=matrices.length||w<=.00001F)continue;
                float[] m=matrixForJoint(j,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
                Mat4.transformPoint(m,v.x,v.y,v.z,transformed,0);sx+=transformed[0]*w;sy+=transformed[1]*w;sz+=transformed[2]*w;total+=w;
            }
        }
        if(total<=.00001F){sx=v.x;sy=v.y;sz=v.z;}
        // Always return to the native JBRA/ModelRenderer emission basis. First-person
        // then uses EpicFirstPersonBridge's exact inverse T(0,1.5,0)*S(-1,-1,1).
        // Keeping one basis contract for both camera modes prevents the arm geometry
        // from changing scale/winding/UV behavior merely because POV is active.
        float ox=DbcSpatialMath.basisX(v.x),oy=DbcSpatialMath.basisY(v.y),oz=v.z;
        float nx=DbcSpatialMath.basisX(sx),ny=DbcSpatialMath.basisY(sy),nz=sz;
        float dx=nx-ox,dy=ny-oy,dz=nz-oz;
        if(!finite(nx)||!finite(ny)||!finite(nz)||dx*dx+dy*dy+dz*dz>9.0F){nx=ox;ny=oy;nz=oz;}
        // Head/body socket reconciliation is intentionally NOT performed here.
        // It is a rigid parent-space correction owned by NativeJbraSkinContext. Keeping
        // it out of skinning prevents a single DBC torso cuboid from being sheared when
        // SNEAK/guard rotates Chest and Torso.
        out[outOff]=nx;out[outOff+1]=ny;out[outOff+2]=nz;
    }
    private static float[] matrixForJoint(int j,float[][] matrices,
                      int armJoint,float[] armM,int handJoint,float[] handM,int elbowJoint,float[] elbowM,
                      int thighJoint,float[] thighM,int legJoint,float[] legM,int kneeJoint,float[] kneeM){
        if(j==armJoint&&armM!=null)return armM;
        if(j==handJoint&&handM!=null)return handM;
        if(j==elbowJoint&&elbowM!=null)return elbowM;
        if(j==thighJoint&&thighM!=null)return thighM;
        if(j==legJoint&&legM!=null)return legM;
        if(j==kneeJoint&&kneeM!=null)return kneeM;
        return matrices[j];
    }

    private boolean readCurrentModelView(){
        try{
            ((Buffer)povModelView).clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,povModelView);
            return finite(povModelView.get(0))&&finite(povModelView.get(5))&&finite(povModelView.get(10))&&finite(povModelView.get(15));
        }catch(Throwable ignored){return false;}
    }

    private void modelToEye(float x,float y,float z,float[] out){
        out[0]=povModelView.get(0)*x+povModelView.get(4)*y+povModelView.get(8)*z+povModelView.get(12);
        out[1]=povModelView.get(1)*x+povModelView.get(5)*y+povModelView.get(9)*z+povModelView.get(13);
        out[2]=povModelView.get(2)*x+povModelView.get(6)*y+povModelView.get(10)*z+povModelView.get(14);
    }

    private boolean sourceFirstPersonDepthBounds(NativeJbraSkinContext.PartRole role,float[][] matrices,float[] out){
        SkeletonMesh source=RuntimeAssets.MESH;
        if(source==null||source.parts==null||source.positions==null||source.jointIds==null||source.jointWeights==null||matrices==null)return false;
        String arm=role==NativeJbraSkinContext.PartRole.RIGHT_ARM?"rightArm":"leftArm";
        String sleeve=role==NativeJbraSkinContext.PartRole.RIGHT_ARM?"rightSleeve":"leftSleeve";
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;boolean any=false;
        for(SkeletonMesh.Part part:source.parts){
            if(part==null||part.refs==null||(!arm.equals(part.name)&&!sleeve.equals(part.name)))continue;
            for(int[] ref:part.refs){
                if(ref==null||ref.length==0)continue;
                int v=ref[0];
                if(v<0||v>=source.positions.length||v>=source.jointIds.length||v>=source.jointWeights.length)continue;
                if(!skinSourceEpicPoint(source,v,matrices,povSourcePoint))continue;
                // Current POV ModelView includes EpicFirstPersonBridge's
                // T(0,+1.5,0)*S(-1,-1,+1) inverse basis. Feed the source point in the
                // same native emission basis used by the replacement geometry.
                modelToEye(-povSourcePoint[0],1.5F-povSourcePoint[1],povSourcePoint[2],povEyePoint);
                float z=povEyePoint[2];if(!finite(z))continue;
                if(z<min)min=z;if(z>max)max=z;any=true;
            }
        }
        if(any){out[0]=min;out[1]=max;}return any;
    }

    private boolean skinSourceEpicPoint(SkeletonMesh source,int vertex,float[][] matrices,float[] out){
        int[] ids=source.jointIds[vertex];float[] ws=source.jointWeights[vertex];float[] pos=source.positions[vertex];
        if(ids==null||ws==null||pos==null||pos.length<3)return false;
        float sx=0,sy=0,sz=0,total=0;int n=Math.min(ids.length,ws.length);
        for(int k=0;k<n;k++){
            int j=ids[k];float w=ws[k];if(j<0||j>=matrices.length||w<=.00001F)continue;
            Mat4.transformPoint(matrices[j],pos[0],pos[1],pos[2],transformed,0);
            sx+=transformed[0]*w;sy+=transformed[1]*w;sz+=transformed[2]*w;total+=w;
        }
        if(total<=.00001F)return false;
        out[0]=sx/total;out[1]=sy/total;out[2]=sz/total;
        return finite(out[0])&&finite(out[1])&&finite(out[2]);
    }

    private void skinUniquePoints(CompiledMesh mesh,float[][] matrices,
                      int armJoint,float[] armM,int handJoint,float[] handM,int elbowJoint,float[] elbowM,
                      int thighJoint,float[] thighM,int legJoint,float[] legM,int kneeJoint,float[] kneeM){
        if(mesh==null)return;
        if(mesh.skinFrame==frameSerial){
            // Same ModelRenderer may be replayed for multiple JBRA texture/tint passes
            // in one frame. Its local skinned positions are identical across those passes.
            return;
        }
        CompiledVertex[] unique=mesh.skinVertices;
        float[] out=mesh.skinned;
        for(int i=0;i<unique.length;i++){
            skin(unique[i],out,i*3,mesh.role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
        }
        mesh.skinFrame=frameSerial;
        perfUniqueSkinPoints+=unique.length;
        perfTriangleCorners+=mesh.vertices.length;
    }

    private boolean dbcFirstPersonDepthBounds(CompiledMesh mesh,float[] out){
        if(mesh==null||mesh.skinned==null)return false;
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;boolean any=false;
        float[] p=mesh.skinned;
        for(int i=0;i+2<p.length;i+=3){
            modelToEye(p[i],p[i+1],p[i+2],povEyePoint);
            float z=povEyePoint[2];if(!finite(z))continue;
            if(z<min)min=z;if(z>max)max=z;any=true;
        }
        if(any){out[0]=min;out[1]=max;}return any;
    }

    private void prepareTriangleNormals(CompiledMesh mesh){
        if(mesh==null)return;
        int triangles=mesh.vertices.length/3;
        if(mesh.normalsFrame==frameSerial){
            perfNormalReuseTriangles+=triangles;
            return;
        }
        float[] p=mesh.skinned,n=mesh.normals;
        CompiledVertex[] v=mesh.vertices;
        int tri=0;
        for(int i=0;i+2<v.length;i+=3,tri++){
            CompiledVertex a=v[i],b=v[i+1],c=v[i+2];
            int ia=a.skinIndex*3,ib=b.skinIndex*3,ic=c.skinIndex*3;
            float ax=p[ia],ay=p[ia+1],az=p[ia+2];
            float ux=p[ib]-ax,uy=p[ib+1]-ay,uz=p[ib+2]-az;
            float vx=p[ic]-ax,vy=p[ic+1]-ay,vz=p[ic+2]-az;
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            int o=tri*3;
            if(len<.000001F){n[o]=0.0F;n[o+1]=1.0F;n[o+2]=0.0F;}
            else{float inv=1.0F/len;n[o]=nx*inv;n[o+1]=ny*inv;n[o+2]=nz*inv;}
        }
        mesh.normalsFrame=frameSerial;
        perfNormalTriangles+=triangles;
    }

    private void emitCachedTriangle(Tessellator t,CompiledMesh mesh,int triangle,CompiledVertex a,CompiledVertex b,CompiledVertex c,float[] objectShift){
        if(a==null||b==null||c==null||mesh==null)return;
        float[] p=mesh.skinned;
        int ia=a.skinIndex*3,ib=b.skinIndex*3,ic=c.skinIndex*3;
        float sx=objectShift==null?0.0F:objectShift[0],sy=objectShift==null?0.0F:objectShift[1],sz=objectShift==null?0.0F:objectShift[2];
        float ax=p[ia]+sx,ay=p[ia+1]+sy,az=p[ia+2]+sz;
        float bx=p[ib]+sx,by=p[ib+1]+sy,bz=p[ib+2]+sz;
        float cx=p[ic]+sx,cy=p[ic+1]+sy,cz=p[ic+2]+sz;
        int no=triangle*3;
        t.func_78375_b(mesh.normals[no],mesh.normals[no+1],mesh.normals[no+2]);
        t.func_78374_a(ax,ay,az,a.u,a.v);t.func_78374_a(bx,by,bz,b.u,b.v);t.func_78374_a(cx,cy,cz,c.u,c.v);
    }

    /** Solve current ModelView 3x3 * objectDelta = (0,0,eyeZDelta). */
    private boolean eyeDepthToObjectShift(float eyeZDelta,float[] out){
        float a00=povModelView.get(0),a01=povModelView.get(4),a02=povModelView.get(8);
        float a10=povModelView.get(1),a11=povModelView.get(5),a12=povModelView.get(9);
        float a20=povModelView.get(2),a21=povModelView.get(6),a22=povModelView.get(10);
        float c00=a11*a22-a12*a21,c01=a02*a21-a01*a22,c02=a01*a12-a02*a11;
        float c10=a12*a20-a10*a22,c11=a00*a22-a02*a20,c12=a02*a10-a00*a12;
        float c20=a10*a21-a11*a20,c21=a01*a20-a00*a21,c22=a00*a11-a01*a10;
        float det=a00*c00+a01*c10+a02*c20;
        if(!finite(det)||Math.abs(det)<.000001F)return false;
        float inv=1.0F/det;
        // Third column of A^-1 times eyeZDelta.
        out[0]=c02*inv*eyeZDelta;
        out[1]=c12*inv*eyeZDelta;
        out[2]=c22*inv*eyeZDelta;
        return finite(out[0])&&finite(out[1])&&finite(out[2]);
    }

    private void emitSkinnedTriangle(Tessellator t,CompiledVertex a,CompiledVertex b,CompiledVertex c,NativeJbraSkinContext.PartRole role,float[][] matrices,
                      int armJoint,float[] armM,int handJoint,float[] handM,int elbowJoint,float[] elbowM,
                      int thighJoint,float[] thighM,int legJoint,float[] legM,int kneeJoint,float[] kneeM,float[] objectShift){
        if(a==null||b==null||c==null)return;
        skin(a,pa,role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
        skin(b,pb,role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
        skin(c,pc,role,matrices,armJoint,armM,handJoint,handM,elbowJoint,elbowM,thighJoint,thighM,legJoint,legM,kneeJoint,kneeM);
        if(objectShift!=null){
            pa[0]+=objectShift[0];pa[1]+=objectShift[1];pa[2]+=objectShift[2];
            pb[0]+=objectShift[0];pb[1]+=objectShift[1];pb[2]+=objectShift[2];
            pc[0]+=objectShift[0];pc[1]+=objectShift[1];pc[2]+=objectShift[2];
        }
        float ux=pb[0]-pa[0],uy=pb[1]-pa[1],uz=pb[2]-pa[2],vx=pc[0]-pa[0],vy=pc[1]-pa[1],vz=pc[2]-pa[2];
        float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(len<.000001F){nx=0;ny=1;nz=0;}else{nx/=len;ny/=len;nz/=len;}
        t.func_78375_b(nx,ny,nz);emit(t,pa,a);emit(t,pb,b);emit(t,pc,c);
    }

    private static boolean finite(float v){return !Float.isNaN(v)&&!Float.isInfinite(v);}
    private static void emit(Tessellator t,float[] p,CompiledVertex v){t.func_78374_a(p[0],p[1],p[2],v.u,v.v);}

    private float[] weightsFor(float ex,float ey,float ez,NativeJbraSkinContext.PartRole role) {
        // X never participates in the anatomical profile. Arm seam ownership only needs
        // the Z sign, while the legacy knee seam interpolates continuously across Z; keep
        // that exact source semantics instead of collapsing both leg side-face weights.
        int zkey=(role==NativeJbraSkinContext.PartRole.RIGHT_LEG||role==NativeJbraSkinContext.PartRole.LEFT_LEG)
                ?q(ez):((role==NativeJbraSkinContext.PartRole.RIGHT_ARM||role==NativeJbraSkinContext.PartRole.LEFT_ARM)?(ez>=0.0F?1:0):0);
        WeightKey key=new WeightKey(role.ordinal(),q(ey),zkey);
        float[] cached=weightCache.get(key);
        if(cached!=null)return cached;
        if(weightCache.size()>48000)weightCache.clear();

        SkeletonMesh ref=RuntimeAssets.MESH;
        if(ref==null)return null;
        if(retargetProfile==null||retargetMesh!=ref) {
            retargetMesh=ref;
            retargetProfile=new DbcRetargetProfile(ref);
            weightCache.clear();
        }
        float[] out=new float[ref.jointCount];
        retargetProfile.weights(role,ey,ez,out);
        float total=0.0F;
        for(int j=0;j<out.length;j++)total+=out[j];
        if(total<=.00001F)return null;
        if(Math.abs(total-1.0F)>.0001F)for(int j=0;j<out.length;j++)out[j]/=total;
        weightCache.put(key,out);
        if(!retargetLogged) {
            retargetLogged=true;
            System.out.println("[EpicFight1710] Source-topology DBC retarget active: head/face/hair remain JBRA-native; torso follows Torso/Chest weights and limbs use the original Arm/Hand/Elbow + Thigh/Leg/Knee hinge layout.");
        }
        return out;
    }

    private static int subdivisions(NativeJbraSkinContext.PartRole role){
        switch(role){
            case RIGHT_ARM:case LEFT_ARM:return 5;
            // Leg side faces use compileLegacyLegQuad() and the exact three-ring
            // seam from biped_old.dat; this count is only a fallback/cap density.
            case RIGHT_LEG:case LEFT_LEG:return 3;
            case TORSO:return 4;
            case HEAD:return 3;
            default:return 3;
        }
    }

    private Object getQuads(Object box){BoxAccess a=boxAccess(box.getClass());return a==null?null:Reflect.get(a.quads,box);}
    private PartAccess partAccess(Class<?> c){if(partCache.containsKey(c))return partCache.get(c);PartAccess a=new PartAccess();a.cubes=Reflect.field(c,"field_78804_l","cubeList");a.children=Reflect.field(c,"field_78805_m","childModels");a.show=Reflect.field(c,"field_78806_j","showModel");a.hidden=Reflect.field(c,"field_78807_k","isHidden");a.rpX=Reflect.field(c,"field_78800_c","rotationPointX");a.rpY=Reflect.field(c,"field_78797_d","rotationPointY");a.rpZ=Reflect.field(c,"field_78798_e","rotationPointZ");a.rotX=Reflect.field(c,"field_78795_f","rotateAngleX");a.rotY=Reflect.field(c,"field_78796_g","rotateAngleY");a.rotZ=Reflect.field(c,"field_78808_h","rotateAngleZ");a.offX=Reflect.field(c,"field_82906_o","offsetX");a.offY=Reflect.field(c,"field_82908_p","offsetY");a.offZ=Reflect.field(c,"field_82907_q","offsetZ");if(a.cubes==null)a.cubes=findListField(c);if(a.cubes==null){partCache.put(c,null);return null;}partCache.put(c,a);return a;}
    private BoxAccess boxAccess(Class<?> c){if(boxCache.containsKey(c))return boxCache.get(c);BoxAccess a=new BoxAccess();a.quads=Reflect.field(c,"field_78254_i","quadList");if(a.quads==null)a.quads=findArrayField(c,"TexturedQuad");if(a.quads==null){boxCache.put(c,null);return null;}boxCache.put(c,a);return a;}
    private QuadAccess quadAccess(Class<?> c){if(quadCache.containsKey(c))return quadCache.get(c);QuadAccess a=new QuadAccess();a.vertices=Reflect.field(c,"field_78239_a","vertexPositions");if(a.vertices==null)a.vertices=findArrayField(c,"PositionTextureVertex");if(a.vertices==null){quadCache.put(c,null);return null;}quadCache.put(c,a);return a;}
    private VertexAccess vertexAccess(Class<?> c){if(vertexCache.containsKey(c))return vertexCache.get(c);VertexAccess a=new VertexAccess();a.vec=Reflect.field(c,"field_78243_a","vector3D");a.u=Reflect.field(c,"field_78241_b","texturePositionX");a.v=Reflect.field(c,"field_78242_c","texturePositionY");if(a.vec==null){for(Field f:allFields(c))if(!f.getType().isPrimitive()&&f.getType().getName().endsWith("Vec3")){try{f.setAccessible(true);}catch(Throwable ignored){}a.vec=f;break;}}if(a.u==null||a.v==null){List<Field> fs=new ArrayList<Field>();for(Field f:allFields(c))if(f.getType()==Float.TYPE){try{f.setAccessible(true);}catch(Throwable ignored){}fs.add(f);}if(a.u==null&&fs.size()>0)a.u=fs.get(0);if(a.v==null&&fs.size()>1)a.v=fs.get(1);}if(a.vec==null||a.u==null||a.v==null){vertexCache.put(c,null);return null;}vertexCache.put(c,a);return a;}
    private VecAccess vecAccess(Class<?> c){if(vecCache.containsKey(c))return vecCache.get(c);VecAccess a=new VecAccess();a.x=Reflect.field(c,"field_72450_a","xCoord");a.y=Reflect.field(c,"field_72448_b","yCoord");a.z=Reflect.field(c,"field_72449_c","zCoord");if(a.x==null||a.y==null||a.z==null){List<Field> ds=new ArrayList<Field>();for(Field f:allFields(c))if(f.getType()==Double.TYPE){try{f.setAccessible(true);}catch(Throwable ignored){}ds.add(f);}if(ds.size()>=3){a.x=ds.get(0);a.y=ds.get(1);a.z=ds.get(2);}}if(a.x==null||a.y==null||a.z==null){vecCache.put(c,null);return null;}vecCache.put(c,a);return a;}
    private static Field findArrayField(Class<?> c,String hint){for(Field f:allFields(c))if(f.getType().isArray()&&f.getType().getComponentType().getName().indexOf(hint)>=0){try{f.setAccessible(true);}catch(Throwable ignored){}return f;}return null;}
    private static Field findListField(Class<?> c){for(Field f:allFields(c))if(List.class.isAssignableFrom(f.getType())){try{f.setAccessible(true);}catch(Throwable ignored){}return f;}return null;}
    private static List<Field> allFields(Class<?> c){List<Field> out=new ArrayList<Field>();while(c!=null){try{for(Field f:c.getDeclaredFields())out.add(f);}catch(Throwable ignored){}c=c.getSuperclass();}return out;}
    private static int q(float v){return Math.round(v*4096F);}

    private static final class CompiledVertex{
        final float x,y,z,u,v;final int[] joint;final float[] weight;final byte influenceCount;int skinIndex;
        CompiledVertex(float x,float y,float z,float u,float v,int[] j,float[] w){
            this.x=x;this.y=y;this.z=z;this.u=u;this.v=v;this.joint=j;this.weight=w;
            int n=0;while(n<j.length&&n<w.length&&j[n]>=0&&w[n]>.00001F)n++;
            influenceCount=(byte)n;
        }
    }
    private static final class PositionKey{
        final int x,y,z;
        PositionKey(CompiledVertex v){x=Float.floatToIntBits(v.x);y=Float.floatToIntBits(v.y);z=Float.floatToIntBits(v.z);}
        public int hashCode(){int h=x;h=31*h+y;return 31*h+z;}
        public boolean equals(Object o){if(this==o)return true;if(!(o instanceof PositionKey))return false;PositionKey k=(PositionKey)o;return x==k.x&&y==k.y&&z==k.z;}
    }
    private static final class CompiledMesh{
        final CompiledVertex[] vertices,skinVertices;final float[] skinned,normals;final NativeJbraSkinContext.PartRole role;final float pivotX,pivotY,pivotZ;
        int skinFrame=Integer.MIN_VALUE,normalsFrame=Integer.MIN_VALUE;
        CompiledMesh(CompiledVertex[] v,NativeJbraSkinContext.PartRole r,float x,float y,float z){
            vertices=v;role=r;pivotX=x;pivotY=y;pivotZ=z;
            HashMap<PositionKey,Integer> map=new HashMap<PositionKey,Integer>();ArrayList<CompiledVertex> unique=new ArrayList<CompiledVertex>();
            for(CompiledVertex cv:v){if(cv==null)continue;PositionKey key=new PositionKey(cv);Integer idx=map.get(key);if(idx==null){idx=Integer.valueOf(unique.size());map.put(key,idx);unique.add(cv);}cv.skinIndex=idx.intValue();}
            skinVertices=unique.toArray(new CompiledVertex[unique.size()]);
            skinned=new float[skinVertices.length*3];
            normals=new float[(vertices.length/3)*3];
        }
    }
    private static final class RejectEntry{
        final int scaleBits;final NativeJbraSkinContext.PartRole role;final int generation,nextAuditTick;
        RejectEntry(NativeJbraSkinContext.PartRole role,int scaleBits,int generation,int nextAuditTick){this.role=role;this.scaleBits=scaleBits;this.generation=generation;this.nextAuditTick=nextAuditTick;}
    }
    private static final class CacheEntry{
        final int signature;final CompiledMesh mesh;final NativeJbraSkinContext.PartRole role;final int scaleBits;
        int generation,nextAuditTick,deepWatchdogTick,shallowStamp;
        CacheEntry(int s,CompiledMesh m,NativeJbraSkinContext.PartRole r,int scaleBits,int generation,int nextAuditTick,int deepWatchdogTick,int shallowStamp){signature=s;mesh=m;role=r;this.scaleBits=scaleBits;this.generation=generation;this.nextAuditTick=nextAuditTick;this.deepWatchdogTick=deepWatchdogTick;this.shallowStamp=shallowStamp;}
    }
    private static final class WeightKey{final int role,y,zside;WeightKey(int r,int y,int z){role=r;this.y=y;zside=z;}public int hashCode(){return (31*role+y)*31+zside;}public boolean equals(Object o){if(this==o)return true;if(!(o instanceof WeightKey))return false;WeightKey k=(WeightKey)o;return role==k.role&&y==k.y&&zside==k.zside;}}
    private static final class PovBodyAccess{Field torsoPrimary,torsoFallback,rightLegPrimary,rightLegFallback,leftLegPrimary,leftLegFallback;}
    private static final class PartAccess{Field cubes,children,show,hidden,rpX,rpY,rpZ,rotX,rotY,rotZ,offX,offY,offZ;}private static final class BoxAccess{Field quads;}private static final class QuadAccess{Field vertices;}private static final class VertexAccess{Field vec,u,v;}private static final class VecAccess{Field x,y,z;}
}
