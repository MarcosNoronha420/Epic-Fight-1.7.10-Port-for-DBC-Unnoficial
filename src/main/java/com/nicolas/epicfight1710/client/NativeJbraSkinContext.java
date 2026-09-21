package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import com.nicolas.epicfight1710.anim.Mat4;
import java.util.IdentityHashMap;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;

/**
 * Render context that keeps JBRA/DBC completely native and replaces only the rigid
 * ModelRenderer cube draw for the local player's body pieces.
 *
 * This is deliberately different from 0.3 and 0.4.x:
 * - no DBC model is hidden;
 * - no account skin is bound by us;
 * - no second player mesh is rendered at world coordinates;
 * - only the exact JRMCore body draw call-sites are transformed; JBRA texture/race
 *   logic remains untouched;
 * - JBRA still decides every texture, tint, race/muscle/clothing pass and hair draw.
 *
 * ModelRendererSkinHook sees the exact ModelRenderer objects owned by JBRA and skins
 * their original cubes/UVs through the Epic Fight armature while the native renderer
 * is already in the correct player matrix and has the correct texture bound.
 */
public final class NativeJbraSkinContext {
    public static final NativeJbraSkinContext INSTANCE=new NativeJbraSkinContext();

    enum PartRole { HEAD, TORSO, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG, GENERIC }

    // The stable Mapping already owns an IdentityHashMap of the model's parts.
    // Older builds copied ~108 entries into a fresh per-frame context and cleared them
    // again after every player draw. Keep the mapping by reference and reserve a tiny
    // overlay only for truly frame-local aliases/legacy first-person peers.
    private IdentityHashMap<Object,PartRole> baseParts;
    private final IdentityHashMap<Object,PartRole> frameParts=new IdentityHashMap<Object,PartRole>();
    private Object activePlayer;
    private Object activeRenderer;
    private Object activeMainModel;
    // JBRA owns the complete native skull/face/hair geometry, but Epic still owns
    // the articulated body pose.  JRMCore renders the head under its own scale/parent
    // matrix, so the correct bridge is to make that native head ROOT inherit the
    // Epic Chest deformation rigidly.  Do not translate the torso toward the head.
    private Object activeMainHead;
    private Class<?> mainHeadModelType;
    private Field mainHeadField;
    private Class<?> nativeHeadPartType;
    private Field nativeHeadRpX,nativeHeadRpY,nativeHeadRpZ;
    private final FloatBuffer headPoseBuffer=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] epicToJbra={
        -1,0,0,0,
         0,-1,0,1.5F,
         0,0,1,0,
         0,0,0,1
    };
    private final float[] headPoseTmp=new float[16];
    private final float[] headPoseJbra=new float[16];
    private boolean nativeHeadPoseLogged,nativeHeadPoseWarned;
    private boolean logged;
    private boolean warnedHook;
    private boolean warnedModel;
    private int renderedPartsThisFrame;
    private boolean hookTrafficLogged;
    private float activePartial;
    private int activeRenderState=-1;
    private boolean renderStateSynchronized;
    private boolean stateSyncLogged;
    private boolean specialStateLogged;
    private boolean lateAliasLogged;
    private boolean firstPerson;
    // 1 = custom Epic-style camera/body pass, 2 = swallow only the later native
    // JBRA arm that ItemRenderer would otherwise draw a second time.
    private int firstPersonPhase;
    private Object suppressPairedArm;
    private boolean firstPersonPairLogged;
    private boolean firstPersonSourceMaskLogged;
    private boolean firstPersonNativeDuplicateLogged;
    private boolean firstPersonWeightedFailureLogged;
    private boolean firstPersonRightArmSeen;
    private boolean firstPersonLeftArmSeen;
    private int firstPersonArmDraws;
    private int firstPersonSuppressedGeometry;
    // Depth is non-zero only while EpicFirstPersonBridge itself is invoking
    // RenderPlayerJBRA.func_82441_a for the source-parity custom pass. The ASM
    // RETURN hook sees that call too, so it must not close the context until the
    // later native ItemRenderer/JBRA invocation (phase 2) has returned.
    private int firstPersonCustomRendererDepth;
    private boolean firstPersonLifecycleLogged;
    // Coremod bridge properties are fixed before the first world render. Cache them
    // once instead of parsing four System properties on every RenderPlayerEvent.Pre.
    private boolean bridgeFlagsReady,directJrmBridge,directFpBridge,vanillaModelHook,jbraModelHook;

    private NativeJbraSkinContext() {}

    private void ensureBridgeFlags(){
        if(bridgeFlagsReady)return;
        directJrmBridge="true".equals(System.getProperty("epicfight1710.jrmrenderbridge"));
        directFpBridge="true".equals(System.getProperty("epicfight1710.jbrafirstpersonbridge"));
        vanillaModelHook="true".equals(System.getProperty("epicfight1710.modelrendererhook"));
        jbraModelHook="true".equals(System.getProperty("epicfight1710.jbrarendererhook"));
        bridgeFlagsReady=true;
    }

    public boolean begin(Object player,Object renderer,float partial) {
        return beginInternal(player,renderer,partial,false);
    }

    public boolean beginFirstPerson(Object player,Object renderer,float partial) {
        return beginInternal(player,renderer,partial,true);
    }

    private boolean beginInternal(Object player,Object renderer,float partial,boolean fp) {
        end();
        if(player==null || renderer==null || !isJbraRenderer(renderer))return false;
        ensureBridgeFlags();
        boolean directJrm=directJrmBridge,directFp=directFpBridge,vanillaHook=vanillaModelHook,jbraHook=jbraModelHook;
        // First person is deliberately stricter than third person. 1.6.0 relied on
        // the globally transformed ModelRenderer entry point; the user's live client
        // proved that this was not a trustworthy Epic first-person contract once
        // Xaero/Angelica/JBRA had all rewritten the render path. 1.7.0 therefore only
        // takes ownership when the exact RenderPlayerJBRA.func_82441_a call sites were
        // instrumented by the coremod. If that direct bridge is missing, return false
        // and preserve the untouched native hand rather than claiming parity through
        // a fragile fallback.
        if(fp&&!directFp) {
            if(!warnedHook) {
                warnedHook=true;
                System.err.println("[EpicFight1710] Direct JBRA first-person call-site bridge is unavailable; keeping the untouched native hand instead of using the rejected ModelRenderer fallback.");
            }
            return false;
        }
        if(!fp&&!directJrm && !vanillaHook && !jbraHook) {
            if(!warnedHook) {
                warnedHook=true;
                System.err.println("[EpicFight1710] No JBRA/JRMCore render bridge is available; keeping the untouched DBC renderer instead of drawing a broken fallback skin.");
            }
            return false;
        }
        try {
            JbraModelAdapter.Mapping mapping=JbraModelAdapter.INSTANCE.map(renderer);
            if(mapping==null||mapping.mainModel==null)return false;
            Object main=mapping.mainModel;
            baseParts=mapping.parts;
            frameParts.clear();
            if(baseParts==null||baseParts.size()<5) {
                baseParts=null;JbraModelAdapter.INSTANCE.invalidate(main);
                mapping=JbraModelAdapter.INSTANCE.map(renderer);
                if(mapping!=null)baseParts=mapping.parts;
            }
            if(baseParts==null||baseParts.size()<5) {
                baseParts=null;frameParts.clear();
                if(!warnedModel) {
                    warnedModel=true;
                    System.err.println("[EpicFight1710] JBRA model parts could not be mapped safely; native DBC renderer kept untouched.");
                }
                return false;
            }

            // RenderPlayerEvent.Pre may run before RenderPlayerJBRA has assigned its
            // static y-state for this exact player. Never publish that pre-state as
            // authoritative: it may still belong to the previous entity render.
            int preState=DbcClientState.INSTANCE.stableRenderState(player);
            PoseEngine.INSTANCE.updateDbcJbra(player,partial,preState);
            renderedPartsThisFrame=0;
            activePlayer=player;
            activeRenderer=renderer;
            JbraWeightedPartRenderer.INSTANCE.beginFrame();
            activeMainModel=main;
            if(mainHeadModelType!=main.getClass()) {
                mainHeadModelType=main.getClass();
                mainHeadField=Reflect.field(mainHeadModelType,"field_78116_c","bipedHead");
            }
            activeMainHead=Reflect.get(mainHeadField,main);
            activePartial=partial;
            activeRenderState=preState;
            renderStateSynchronized=false;
            firstPerson=fp;
            firstPersonPhase=fp?1:0;
            suppressPairedArm=null;
            firstPersonCustomRendererDepth=0;
            firstPersonRightArmSeen=false;
            firstPersonLeftArmSeen=false;
            firstPersonArmDraws=0;
            firstPersonSuppressedGeometry=0;
            if(!logged) {
                logged=true;
                int[] counts=new int[PartRole.values().length];
                for(PartRole r:baseParts.values())counts[r.ordinal()]++;
                System.out.println("[EpicFight1710] Native JBRA geometry skinning active: main="+main.getClass().getName()+", ModelRenderer parts="+baseParts.size()+" (head="+counts[PartRole.HEAD.ordinal()]+", torso="+counts[PartRole.TORSO.ordinal()]+", arms="+(counts[PartRole.RIGHT_ARM.ordinal()]+counts[PartRole.LEFT_ARM.ordinal()])+", legs="+(counts[PartRole.RIGHT_LEG.ordinal()]+counts[PartRole.LEFT_LEG.ordinal()])+", generic="+counts[PartRole.GENERIC.ordinal()]+").");
                System.out.println("[EpicFight1710] JBRA owns textures/race/forms/layers and the complete native head/face/hair geometry; Epic Fight owns the articulated body pose and the rigid Chest->head parent socket. No replacement player skin is rendered.");
                System.out.println("[EpicFight1710] Native-head ownership guard active: skull/headwear/hair/ears remain one JBRA tree; the tree inherits one rigid Epic Chest deformation instead of making the torso chase a separately rendered head.");
                System.out.println("[EpicFight1710] Active render bridge: "+(fp?"JBRA first-person direct call-site (authoritative)":(directJrm?"JRMCore call-site (authoritative)":(jbraHook?"JBRA ModelRendererJBRA entry":"vanilla ModelRenderer fallback")))+".");
                if(Boolean.getBoolean("epicfight1710.debugparts")&&mapping.diagnostics.length()>0)System.out.println("[EpicFight1710] Cached JBRA part map: "+mapping.diagnostics);
            }
            return true;
        } catch(Throwable t) {
            baseParts=null;frameParts.clear(); activePlayer=null; activeRenderer=null; activeMainModel=null; activeMainHead=null;
            if(!warnedModel) {
                warnedModel=true;
                System.err.println("[EpicFight1710] Could not start native JBRA geometry skinning; renderer left untouched.");
                t.printStackTrace();
            }
            return false;
        }
    }

    public void end() {
        if(activePlayer!=null && renderedPartsThisFrame>0) {
            if(!hookTrafficLogged) {
                hookTrafficLogged=true;
                System.out.println("[EpicFight1710] Skeletal render bridge reached live JBRA body geometry: handledParts="+renderedPartsThisFrame+" in first animated frame.");
            }
        }
        baseParts=null;
        frameParts.clear();
        activePlayer=null;
        activeRenderer=null;
        activeMainModel=null;
        activeMainHead=null;
        activeRenderState=-1;
        renderStateSynchronized=false;
        firstPerson=false;
        firstPersonPhase=0;
        suppressPairedArm=null;
        firstPersonCustomRendererDepth=0;
        firstPersonRightArmSeen=false;
        firstPersonLeftArmSeen=false;
        firstPersonArmDraws=0;
        firstPersonSuppressedGeometry=0;
    }

    /** Called immediately around the reflective JBRA invocation owned by the Epic
     * custom pass. The transformed method RETURN executes before reflection returns,
     * therefore this depth flag distinguishes it from the later native hand pass. */
    void beginFirstPersonCustomRendererCall(){
        if(firstPerson&&activePlayer!=null)firstPersonCustomRendererDepth++;
    }
    void endFirstPersonCustomRendererCall(){
        if(firstPersonCustomRendererDepth>0)firstPersonCustomRendererDepth--;
    }

    /**
     * ASM lifecycle callback injected at every normal RETURN of
     * RenderPlayerJBRA.func_82441_a(EntityPlayer).
     *
     * Phase 1 is our explicit Epic-style two-arm draw and stays alive so the normal
     * RenderHand pipeline can continue. Phase 2 is Minecraft/JBRA's later native
     * hand invocation: its rigid arm geometry has already been suppressed, and this
     * RETURN is the exact safe boundary at which the first-person skinning context
     * must be released.
     */
    public static void onJbraFirstPersonReturn(){
        NativeJbraSkinContext c=INSTANCE;
        if(!c.firstPerson||c.activePlayer==null)return;
        if(c.firstPersonCustomRendererDepth>0)return;
        if(c.firstPersonPhase==2){
            if(!c.firstPersonLifecycleLogged){
                c.firstPersonLifecycleLogged=true;
                System.out.println("[EpicFight1710] JBRA first-person lifecycle boundary active: custom source-parity call stays scoped through phase 1; the later native hand method RETURN closes phase 2 exactly, preventing first-person state leakage into later renders.");
            }
            c.end();
        }
    }

    /** Marks the successful custom two-arm draw; subsequent native hand-arm calls
     * in the same RenderHand pipeline are swallowed without canceling held items. */
    void finishFirstPersonCustomPass(){
        if(firstPerson&&activePlayer!=null){
            if(!firstPersonSourceMaskLogged){
                firstPersonSourceMaskLogged=true;
                System.out.println("[EpicFight1710] First-person Epic-source pass summary: RA="+firstPersonRightArmSeen+", LA="+firstPersonLeftArmSeen+", emittedArmDraws="+firstPersonArmDraws+", suppressedNativeDraws="+firstPersonSuppressedGeometry+". Every authoritative JBRA right-arm texture pass emits the complete Epic RA+LA pair in the same source-style body/camera matrix.");
            }
            firstPersonPhase=2;suppressPairedArm=null;
        }
    }

    public boolean activeFor(Object player){return activePlayer!=null && activePlayer==player;}
    public boolean active(){return activePlayer!=null;}
    PartRole mappedRole(Object part){return roleOf(part);}
    private PartRole roleOf(Object part){
        if(part==null)return null;
        PartRole r=frameParts.get(part);
        return r!=null?r:(baseParts==null?null:baseParts.get(part));
    }

    boolean renderPart(Object part,float scale) {
        if(activePlayer==null || part==null)return false;

        // Vanish Dash sets the real player invisible, but our redirected JBRA body/
        // clothing draws bypass parts of vanilla's invisibility arbitration. Swallow
        // every intercepted body/wearable ModelRenderer for the dashing player so
        // armor/clothes cannot remain floating after the body vanishes. Native JBRA
        // hair/accessories still obey Entity.invisible on their untouched paths.
        if(DashController.INSTANCE.activeFor(activePlayer))return true;

        if(!firstPerson)synchronizeAuthoritativeRenderState();
        // JBRA y=3 is KO and y=4..7 are JRMCore/UI-owned special body states.
        // Those states already apply their own renderer-space rotations/translations.
        // Stacking an Epic skeletal pose on top is exactly the kind of double-transform
        // that made earlier builds look broken. Yield the entire body part to JBRA for
        // these explicit states; normal (1) and flight (2) remain Epic-retargeted.
        if(activeRenderState>=3&&activeRenderState<=7) {
            if(!specialStateLogged) {
                specialStateLogged=true;
                System.out.println("[EpicFight1710] JBRA special-state arbitration active: KO/UI states y=3..7 stay native to prevent double transforms; normal/flight states remain Epic-retargeted.");
            }
            return false;
        }
        PartRole role=roleOf(part);
        if(role==null) {
            // Armor/clothing renderPassModel can be assigned after RenderPlayerEvent.Pre,
            // and JRMCore rewrites RA/LA/RL/LL/B* aliases during setRotationAngles.
            // Resolve only the exact ModelRenderer currently being drawn using cached
            // Field metadata; do not rescan renderer/model hierarchies in the hot path.
            role=JbraModelAdapter.INSTANCE.resolveLivePart(activeRenderer,activeMainModel,part);
            if(role!=null&&role!=PartRole.GENERIC&&role!=PartRole.HEAD) {
                // resolveLivePart persists the complete late model snapshot, including
                // safe same-role wearable descendants. Merge it into this frame before
                // the weighted tree compiler inspects childModels; otherwise a late
                // sleeve child would still look "unknown" and force native fallback.
                // resolveLivePart persists positive late aliases into the Mapping
                // referenced by baseParts. No per-frame map merge/copy is needed.
                if(!lateAliasLogged) {
                    lateAliasLogged=true;
                    System.out.println("[EpicFight1710] Late DBC wearable subtree retarget active: renderPassModel/modelArmor and live RA/LA/RL/LL/B* aliases are promoted at draw time, including same-role child ModelRenderers, from cached reflection metadata.");
                }
            }
        }
        if(firstPerson) {
            /*
             * 2.0.5: the JBRA method is used only as a texture/tint/pass sequencer.
             * Its first real RIGHT_ARM call supplies the canonical pre-ModelRenderer
             * ModelView. Every subsequent skin/clothing overlay is forced onto that
             * SAME matrix, then the current right part and its same-model LEFT peer
             * are rendered from the full Epic model-space pose.
             *
             * This is intentionally source-like: one arm pair, many texture passes.
             * The 2.0.4 mistake was treating the two RA calls seen in the live log as
             * two spatial anchors, which visibly separated bare skin from armwear.
             */
            if(firstPersonPhase==2) {
                firstPersonSuppressedGeometry++;
                if(!firstPersonNativeDuplicateLogged){
                    firstPersonNativeDuplicateLogged=true;
                    System.out.println("[EpicFight1710] First-person post-owned duplicate suppression active: the later native hand pass is fully swallowed after the canonical Epic arm pair has been drawn.");
                }
                return true;
            }
            if(firstPersonPhase!=1) {
                firstPersonSuppressedGeometry++;
                return true;
            }
            if(DashController.INSTANCE.active())return true;

            // LEFT_ARM calls from JBRA are not independent draws in the owned pass.
            // They are already emitted as the peer of each authoritative RIGHT_ARM
            // texture pass below, so accepting them again would duplicate geometry.
            if(role==PartRole.LEFT_ARM) {
                firstPersonSuppressedGeometry++;
                return true;
            }
            if(role!=PartRole.RIGHT_ARM) {
                firstPersonSuppressedGeometry++;
                return true;
            }

            if(!EpicFirstPersonBridge.INSTANCE.pushBodySpace()) {
                if(!firstPersonWeightedFailureLogged){firstPersonWeightedFailureLogged=true;System.err.println("[EpicFight1710] First-person canonical ModelView unavailable; suppressing unsafe native fallback geometry.");}
                return true;
            }
            try {
                boolean rightHandled=JbraWeightedPartRenderer.INSTANCE.render(part,scale,PartRole.RIGHT_ARM);
                if(!rightHandled) {
                    if(!firstPersonWeightedFailureLogged){firstPersonWeightedFailureLogged=true;System.err.println("[EpicFight1710] Canonical first-person RIGHT_ARM pass could not compile; that texture pass stays masked instead of leaking rigid geometry.");}
                    return true;
                }
                renderedPartsThisFrame++;firstPersonArmDraws++;firstPersonRightArmSeen=true;

                Object left=JbraModelAdapter.INSTANCE.pairedArm(activeRenderer,activeMainModel,part,PartRole.LEFT_ARM);
                if(left!=null) {
                    PartRole lr=roleOf(left);
                    if(lr==null||lr==PartRole.GENERIC)frameParts.put(left,PartRole.LEFT_ARM);
                    boolean leftHandled=JbraWeightedPartRenderer.INSTANCE.render(left,scale,PartRole.LEFT_ARM);
                    if(leftHandled){renderedPartsThisFrame++;firstPersonArmDraws++;firstPersonLeftArmSeen=true;}
                    else if(!firstPersonWeightedFailureLogged){
                        firstPersonWeightedFailureLogged=true;
                        System.err.println("[EpicFight1710] Canonical first-person LEFT_ARM peer could not compile; right arm remains valid but the missing peer is not replaced by native geometry.");
                    }
                } else if(!firstPersonWeightedFailureLogged) {
                    firstPersonWeightedFailureLogged=true;
                    System.err.println("[EpicFight1710] Canonical first-person LEFT_ARM peer was not found for one JBRA pass; no unsafe synthetic geometry is emitted.");
                }

                if(!firstPersonPairLogged){
                    firstPersonPairLogged=true;
                    System.out.println("[EpicFight1710] First-person Epic-source arm pair active: each authoritative JBRA RIGHT_ARM texture/tint pass redraws one Epic RA + its same-model LA peer under the exact 20.9.5 body/camera contract. Skin, glove and clothing share the same armature matrices.");
                }
                return true;
            } finally { EpicFirstPersonBridge.INSTANCE.popBodySpace(); }
        }
        if(role==PartRole.HEAD)return false;
        if(role==null || role==PartRole.GENERIC)return false;
        // Unknown cosmetics/accessories are safer left entirely to JBRA. Only parts
        // with a confident body anchor are deformed by the Epic skeleton.
        boolean handled=JbraWeightedPartRenderer.INSTANCE.render(part,scale,role);
        if(handled) renderedPartsThisFrame++;
        return handled;
    }

    /**
     * Called only by the direct JRMCore call-site bridge, immediately around the
     * original native ModelRenderer head draw.  The native head keeps its own local
     * yaw/pitch, hair, face and form geometry; only the parent-space deformation that
     * the Epic Chest gives to the neck is inherited.
     */
    boolean pushNativeHeadParentPose(Object part,float scale) {
        if(firstPerson||activePlayer==null||part==null||roleOf(part)!=PartRole.HEAD)return false;
        try {
            int chest=PoseEngine.INSTANCE.chest();
            float[][] skin=PoseEngine.INSTANCE.skinMatrices();
            if(chest<0||skin==null||chest>=skin.length||skin[chest]==null||skin[chest].length<16)return false;
            // C maps Epic body coordinates to the JBRA model basis:
            //   (-x, 1.5-y, +z).  C is its own inverse, so D_jbra=C*D_epic*C.
            Mat4.mul(epicToJbra,skin[chest],headPoseTmp);
            Mat4.mul(headPoseTmp,epicToJbra,headPoseJbra);
            ((Buffer)headPoseBuffer).clear();
            // LWJGL/OpenGL consumes column-major floats; Mat4 is row-major.
            for(int c=0;c<4;c++)for(int r=0;r<4;r++)headPoseBuffer.put(headPoseJbra[r*4+c]);
            ((Buffer)headPoseBuffer).flip();
            GL11.glPushMatrix();
            GL11.glMultMatrix(headPoseBuffer);
            // Epic owns body-space locomotion/sneak. JRMCore also mutates the native
            // head rotationPoint for those states; cancel only that positional root
            // animation here so it is not applied a second time. Native yaw/pitch
            // angles are deliberately untouched. ModelBipedBody's head bind pivot is
            // (0,0,0) for the live player model used by JBRA.
            if(part==activeMainHead){
                ensureNativeHeadPivotFields(part.getClass());
                float rx=Reflect.getFloat(nativeHeadRpX,part,0.0F),ry=Reflect.getFloat(nativeHeadRpY,part,0.0F),rz=Reflect.getFloat(nativeHeadRpZ,part,0.0F);
                if(rx!=0.0F||ry!=0.0F||rz!=0.0F)GL11.glTranslatef(-rx*scale,-ry*scale,-rz*scale);
            }
            if(!nativeHeadPoseLogged){
                nativeHeadPoseLogged=true;
                System.out.println("[EpicFight1710] Native JBRA head parent now inherits Epic Chest deformation as one rigid tree: head/face/hair stay native, torso is never translated to chase the skull, and native head look rotation remains local.");
            }
            return true;
        } catch(Throwable t) {
            if(!nativeHeadPoseWarned){nativeHeadPoseWarned=true;System.err.println("[EpicFight1710] Native head parent retarget failed; falling back to the untouched JBRA head for this draw.");t.printStackTrace();}
            return false;
        }
    }

    private void ensureNativeHeadPivotFields(Class<?> c){
        if(nativeHeadPartType==c&&nativeHeadRpX!=null)return;
        nativeHeadPartType=c;
        nativeHeadRpX=Reflect.field(c,"field_78800_c","rotationPointX");
        nativeHeadRpY=Reflect.field(c,"field_78797_d","rotationPointY");
        nativeHeadRpZ=Reflect.field(c,"field_78798_e","rotationPointZ");
    }

    void popNativeHeadParentPose() { GL11.glPopMatrix(); }

    private void synchronizeAuthoritativeRenderState() {
        if(renderStateSynchronized||activePlayer==null)return;
        renderStateSynchronized=true;
        int exact=DbcClientState.INSTANCE.renderState(activeMainModel);
        DbcClientState.INSTANCE.noteRenderState(activePlayer,exact,CombatController.INSTANCE.tick());
        // RenderPlayerEvent.Pre can fire before RenderPlayerJBRA publishes its final
        // ModelBipedDBC.y for this pass. Recompose once here, at the first actual
        // JRMCore body call-site, where the state is authoritative.
        if(exact!=activeRenderState) {
            activeRenderState=exact;
            PoseEngine.INSTANCE.updateDbcJbra(activePlayer,activePartial,exact);
        }
        if(!stateSyncLogged) {
            stateSyncLogged=true;
            System.out.println("[EpicFight1710] Per-player JBRA render-state synchronization active: ModelBipedDBC.y="+exact+" is accepted only inside this player's real body draw; static cross-entity flight contamination is ignored.");
        }
    }
    static boolean isJbraRenderer(Object renderer) {
        if(renderer==null)return false;
        String n=renderer.getClass().getName();
        return n.startsWith("JinRyuu.JBRA.") || n.indexOf("RenderPlayerJBRA")>=0;
    }

    static PartRole roleFor(String raw) {
        if(raw==null)return PartRole.GENERIC;
        String n=raw.toLowerCase();
        // JBRA owns the complete head/face/hair presentation. Do not split a named
        // headwear/hair/ear layer away from the native head pipeline merely because
        // its field name also contains "head". HEAD remains identifiable for safety
        // diagnostics, but renderPart always yields it back to JBRA.
        if(n.indexOf("hair")>=0||n.indexOf("ear")>=0)return PartRole.GENERIC;
        if("field_78116_c".equals(raw)||"field_78114_d".equals(raw)||"field_78121_j".equals(raw)||n.indexOf("head")>=0)return PartRole.HEAD;
        if("field_78112_f".equals(raw)||"ra".equals(n)||(n.indexOf("right")>=0&&n.indexOf("arm")>=0)||"rightarm".equals(n)||"brightarm".equals(n))return PartRole.RIGHT_ARM;
        if("field_78113_g".equals(raw)||"la".equals(n)||(n.indexOf("left")>=0&&n.indexOf("arm")>=0)||"leftarm".equals(n)||"bleftarm".equals(n))return PartRole.LEFT_ARM;
        if("field_78123_h".equals(raw)||"rl".equals(n)||(n.indexOf("right")>=0&&n.indexOf("leg")>=0)||"rightleg".equals(n))return PartRole.RIGHT_LEG;
        if("field_78124_i".equals(raw)||"ll".equals(n)||(n.indexOf("left")>=0&&n.indexOf("leg")>=0)||"leftleg".equals(n))return PartRole.LEFT_LEG;
        // JRMCore ModelBipedBody uses B/B1/B2/... as live torso aliases for armor and
        // clothing passes. Match only B followed by digits so Brightarm/Bbreast keep
        // their more specific anatomical classifications above.
        boolean dbcTorsoAlias=raw.length()>=1&&raw.charAt(0)=='B';
        if(dbcTorsoAlias)for(int i=1;i<raw.length();i++)if(raw.charAt(i)<'0'||raw.charAt(i)>'9'){dbcTorsoAlias=false;break;}
        if("field_78115_e".equals(raw)||dbcTorsoAlias||n.indexOf("body")>=0||n.indexOf("breast")>=0||n.indexOf("chest")>=0||n.indexOf("waist")>=0||n.indexOf("hip")>=0||n.indexOf("bottom")>=0||n.indexOf("skirt")>=0)return PartRole.TORSO;
        return PartRole.GENERIC;
    }

}
