package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;

/**
 * 2.0.40-RC1 first-person WORLD-BODY transform-tree fast path.
 *
 * 2.0.34-2.0.36 proved the correct visual contract: render the real local body in
 * the already-live world camera from RenderHandEvent and move only that POV body
 * down by 0.075 block. The performance regression came from reaching that result
 * through RenderManager.renderEntityStatic(player), which recursively paid the full
 * RenderPlayerJBRA player pipeline every render frame.
 *
 * This class keeps the exact world-camera/body relation but uses JBRA's existing
 * first-person arm routine only as a low-frequency texture/tint/model-state probe.
 * The probe is executed at most once per player game tick with color/depth writes
 * disabled. Its ModelRenderer calls are swallowed by ModelRendererSkinHook and only
 * the currently-bound texture/color state is captured. Every render frame then
 * replays those cached passes through JRMCore ModelBipedBody.renderBody(), but intercepts
 * every ModelRenderer before native geometry traversal. This preserves JRMCore's exact
 * race/body outer transforms while JbraWeightedPartRenderer emits the cached weighted
 * geometry with the current Epic Armature. Head/face/hair/headwear never enter the POV
 * geometry traversal.
 */
public final class FirstPersonBodyRenderer1710 {
    public static final FirstPersonBodyRenderer1710 INSTANCE = new FirstPersonBodyRenderer1710();

    private static final int GL_MODELVIEW = 5888;
    private static final int GL_MODELVIEW_MATRIX = 2982;
    private static final int GL_MATRIX_MODE = 2976;
    private static final int GL_CURRENT_COLOR = 2816;
    private static final int GL_ALPHA_TEST = 3008;
    private static final int GL_BLEND = 3042;
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_TEXTURE_BINDING_2D = 32873;
    private static final float MODEL_SCALE = 0.0625F;
    private static final float POV_BODY_Y = -0.075F;
    // One legacy model pixel. Keep the real world camera fixed and move only the POV
    // body slightly backward along the horizontal view yaw, equivalent to placing the
    // eyes a little farther forward in the head without touching FOV/near plane/arms.
    private static final float POV_BODY_BACK = 0.0625F;
    private static final float VANILLA_MODEL_Y = -1.5078125F;
    private static final int MAX_CAPTURED_PASSES = 16;

    private final FloatBuffer cameraMatrix = directMatrix();
    private final FloatBuffer worldBodyMatrix = directMatrix();
    private final FloatBuffer preRenderMatrix = directMatrix();
    private final FloatBuffer colorScratch = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] savedColor = new float[4];
    private final float[] probeColor = new float[4];

    private final ArrayList<BodyPass> passes = new ArrayList<BodyPass>();
    private final IdentityHashMap<Object,Object> probeOwnerCache = new IdentityHashMap<Object,Object>();
    private final Map<Class<?>, ModelAccess> modelAccessCache = new HashMap<Class<?>, ModelAccess>();
    private final Map<Class<?>, Field[]> rendererModelFields = new HashMap<Class<?>, Field[]>();
    private final Map<Class<?>, MethodHandle> bodyRenderHandles = new HashMap<Class<?>, MethodHandle>();

    // Dedicated POV mapping cache. Keep the validated 2.0.36 NativeJbraSkinContext
    // bytecode completely untouched: the fast pass owns its low-frequency identity
    // map and only consults JbraModelAdapter once per game tick / renderer change.
    private JbraModelAdapter.Mapping fastMapping;
    private Object fastMappingRenderer;
    private int fastMappingTick = Integer.MIN_VALUE;

    private Object currentPlayer;
    private Object currentRenderer;
    private Object currentMainModel;
    private float currentPartial;
    private boolean active;
    private boolean probing;
    private boolean bodyTraversal;
    private boolean bodyTraversalArmsOnly;
    private boolean bodyTraversalValidate;
    private boolean bodyTraversalHandled;
    private Object currentTraversalModel;
    private boolean worldBodyReady;
    private boolean emittedThisFrame;
    private boolean loggedFastPath;
    private boolean loggedProbeCache;
    private boolean loggedRenderPlayerYContract;
    private boolean warned;

    private Class<?> rendererType;
    private MethodHandle renderFirstPersonArm;
    private MethodHandle preRenderCallback;
    private int lastProbeTick = Integer.MIN_VALUE;
    private int preRenderTick = Integer.MIN_VALUE;
    private Object preRenderOwner;
    private int lastValidatedTick = Integer.MIN_VALUE;

    private Object lastProbePlayer;
    private Object lastProbeRenderer;
    private Object lastProbeMainModel;

    private FirstPersonBodyRenderer1710() {}

    private static FloatBuffer directMatrix() {
        return ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public static boolean active() {
        return INSTANCE.active;
    }

    /** Narrow signal used by Tool_R while the dedicated POV item pass is executing. */
    static boolean fastContextActive() {
        return INSTANCE.active;
    }

    /**
     * Identity-role lookup for tick-rate fast-mesh validation. This deliberately lives
     * outside NativeJbraSkinContext so the already validated 2.0.36 native body/head
     * bridge can remain byte-for-byte untouched in the release JAR.
     */
    static NativeJbraSkinContext.PartRole fastMappedRole(Object part) {
        FirstPersonBodyRenderer1710 self = INSTANCE;
        if (!self.active || part == null || self.fastMapping == null) return null;
        NativeJbraSkinContext.PartRole role = self.fastMapping.parts.get(part);
        Object model = self.currentTraversalModel != null ? self.currentTraversalModel : self.currentMainModel;
        if (role == null && self.currentRenderer != null && model != null) {
            role = JbraModelAdapter.INSTANCE.resolveLivePart(self.currentRenderer, model, part);
        }
        return role;
    }

    /** 2.0.36 compatibility entry; supplementary head scanning no longer exists. */
    public static boolean shouldSuppressPart(Object part) {
        return false;
    }

    /**
     * Called by ModelRendererSkinHook before normal JBRA skeletal dispatch.
     *  0 = not owned by the POV fast path
     * +1 = swallow this native ModelRenderer call (probe or transform-tree traversal owns it).
     */
    static int modelRendererHook(Object part, float scale) {
        FirstPersonBodyRenderer1710 self = INSTANCE;
        if (!self.active) return 0;
        if (self.probing) {
            try {
                self.captureProbePart(part, scale);
            } catch (Throwable t) {
                self.warnOnce("first-person pass capture", t);
            }
            return 1;
        }
        if (self.bodyTraversal) {
            try {
                NativeJbraSkinContext.PartRole role = fastMappedRole(part);
                // renderBody() is retained only as the authoritative JRMCore transform
                // tree. HEAD/unknown geometry is swallowed before ModelRenderer walks
                // cubes/children, so face/hair/headwear never enter the POV draw.
                if (role == null || role == NativeJbraSkinContext.PartRole.GENERIC || role == NativeJbraSkinContext.PartRole.HEAD) {
                    return 1;
                }
                if (self.bodyTraversalArmsOnly
                        && role != NativeJbraSkinContext.PartRole.RIGHT_ARM
                        && role != NativeJbraSkinContext.PartRole.LEFT_ARM) {
                    return 1;
                }
                boolean handled = JbraWeightedPartRenderer.INSTANCE.renderFast(part, scale, role, self.bodyTraversalValidate);
                if (handled) self.bodyTraversalHandled = true;
                // Never leak rigid/native geometry into this owned POV traversal. A
                // failed weighted part is omitted for this frame rather than doubled.
                return 1;
            } catch (Throwable t) {
                self.warnOnce("first-person body-transform traversal", t);
                return 1;
            }
        }
        return 0;
    }

    public boolean render(Object player, float partialTicks) {
        if (player == null || active) return false;
        // Full WORLD-BODY respected the player's invisibility bit. Preserve that
        // contract without entering JBRA: cancel the native hand while invisible.
        if (Compat.invisible(player)) return true;
        // Sleeping owns a special vanilla/JBRA camera/body transform; use native hand
        // fallback rather than guessing a world-body matrix in this rare state.
        if (Compat.sleeping(player)) return false;
        Object renderer = Compat.rendererFor(player);
        if (!NativeJbraSkinContext.isJbraRenderer(renderer)) return false;

        boolean lightmap = false;
        boolean standard = false;
        try {
            ensureRenderer(renderer, player);
            if (renderFirstPersonArm == null) return false;

            currentPlayer = player;
            currentRenderer = renderer;
            currentPartial = clamp01(partialTicks);
            int tick = Compat.ticks(player);
            if (!prepareFastMapping(renderer, tick)) return false;
            currentMainModel = fastMapping.mainModel;
            active = true;
            emittedThisFrame = false;

            captureIncomingCamera();

            // Preserve 2.0.36 lighting behavior without entering RenderManager.
            if (Lighting.INSTANCE.enable(currentPartial)) lightmap = true;
            if (Lighting.INSTANCE.enableStandard()) standard = true;

            boolean identityChanged = player != lastProbePlayer || renderer != lastProbeRenderer || currentMainModel != lastProbeMainModel;
            // ModelRenderer -> owning body-model identity is structural, not frame state.
            // 2.0.40 rebuilt this ownership reflection cache every probe tick even when
            // the exact same JBRA model was alive. Keep it until the renderer/main-model
            // identity changes; dynamic form parts simply add their own identities.
            if (identityChanged) probeOwnerCache.clear();
            boolean probedThisFrame = false;
            if (identityChanged || tick != lastProbeTick || passes.isEmpty()) {
                if (!probeAppearanceState(player, renderer)) return false;
                probedThisFrame = true;
                lastProbeTick = tick;
                lastProbePlayer = player;
                lastProbeRenderer = renderer;
                lastProbeMainModel = currentMainModel;
                preRenderTick = Integer.MIN_VALUE; // renderer static race/body state was refreshed by the probe
            }

            // Mirror NativeJbraSkinContext's authoritative y-state synchronization, but
            // only on the 20 TPS probe boundary rather than at display-frame frequency.
            if (probedThisFrame) {
                int exactState = DbcClientState.INSTANCE.renderState(currentMainModel);
                DbcClientState.INSTANCE.noteRenderState(player, exactState, CombatController.INSTANCE.tick());
            }
            int renderState = DbcClientState.INSTANCE.stableRenderState(player);
            PoseEngine.INSTANCE.updateDbcJbra(player, currentPartial, renderState);
            // NativeJbraSkinContext normally clears per-frame socket/retarget scratch.
            // The dedicated POV path bypasses that context, so it must establish the
            // same clean frame boundary explicitly before any weighted part is emitted.
            JbraWeightedPartRenderer.INSTANCE.beginFrame();
            // y=3..7 are DBC/JRM special renderer-space states (KO/UI transforms).
            // Third person already yields these states to JBRA. Do the same here and
            // keep the fast path limited to the normal/flight states it can reproduce.
            if (renderState >= 3 && renderState <= 7) return false;
            prepareWorldBodyMatrix(player, renderer, currentPartial);
            replayCapturedBodyPasses(tick);

            // Never call RenderPlayerJBRA.renderEquippedItemsJBRA here. The 2.0.37-RC1
            // live test proved that method traverses hair/form layers and can throw from
            // foreign mixins, corrupting GL state for the pause GUI. Render only the
            // supported main-hand item directly from Epic Tool_R.
            if (emittedThisFrame) {
                Object stack = FirstPersonWeaponRenderer.INSTANCE.heldStack(player);
                if (stack != null) {
                    FirstPersonWeaponRenderer.INSTANCE.renderWorldBody(player, stack, worldBodyMatrix);
                }
            }

            if (emittedThisFrame && !loggedFastPath) {
                loggedFastPath = true;
                System.out.println("[EpicFight1710] First-person 2.0.40-RC1 WORLD-BODY transform-tree fast path active: RenderManager/renderEntityStatic is bypassed; JRMCore renderBody() supplies only the authoritative per-part race/body matrices while ModelRenderer geometry is intercepted before traversal and replaced by cached Epic-weighted meshes at Y=-0.075 with a 0.0625-block horizontal body-back/eye-forward calibration. Head/face/hair/headwear and RenderPlayerJBRA layers never enter the POV draw.");
            }
            return emittedThisFrame;
        } catch (Throwable t) {
            warnOnce("first-person 2.0.40-RC1 WORLD-BODY transform-tree fast path", t);
            return false;
        } finally {
            probing = false;
            bodyTraversal = false;
            bodyTraversalArmsOnly = false;
            bodyTraversalValidate = false;
            bodyTraversalHandled = false;
            currentTraversalModel = null;
            active = false;
            worldBodyReady = false;
            currentPlayer = null;
            currentRenderer = null;
            currentMainModel = null;
            try { if (standard) Lighting.INSTANCE.disableStandard(); } catch (Throwable ignored) {}
            try { if (lightmap) Lighting.INSTANCE.disable(currentPartial); } catch (Throwable ignored) {}
            // Probe code deliberately uses color/depth masks as a hard invisibility wall.
            // Always restore the normal RenderHand boundary even after a foreign JBRA error.
            try { GL11.glColorMask(true, true, true, true); } catch (Throwable ignored) {}
            try { GL11.glDepthMask(true); } catch (Throwable ignored) {}
        }
    }

    /** Refresh structural JBRA identity information at game-tick frequency only. */
    private boolean prepareFastMapping(Object renderer, int tick) {
        if (renderer == null) return false;
        if (fastMapping == null || fastMappingRenderer != renderer || fastMappingTick != tick) {
            JbraModelAdapter.Mapping mapping = JbraModelAdapter.INSTANCE.map(renderer);
            if (mapping == null || mapping.mainModel == null || mapping.parts.size() < 5) {
                if (mapping != null && mapping.mainModel != null) {
                    JbraModelAdapter.INSTANCE.invalidate(mapping.mainModel);
                    mapping = JbraModelAdapter.INSTANCE.map(renderer);
                }
            }
            if (mapping == null || mapping.mainModel == null || mapping.parts.size() < 5) return false;
            fastMapping = mapping;
            fastMappingRenderer = renderer;
            fastMappingTick = tick;
        }
        return true;
    }

    /**
     * Execute only JBRA's existing first-person arm routine as a texture/tint/model-state
     * sequencer. ModelRendererSkinHook swallows every native geometry call while probing.
     */
    private boolean probeAppearanceState(Object player, Object renderer) throws Throwable {
        passes.clear();
        probing = true;

        int oldMode = GL11.glGetInteger(GL_MATRIX_MODE);
        int oldTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean oldBlend = GL11.glIsEnabled(GL_BLEND);
        boolean oldAlpha = GL11.glIsEnabled(GL_ALPHA_TEST);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        readCurrentColor(savedColor);

        GL11.glMatrixMode(GL_MODELVIEW);
        int modelViewDepth = safeModelViewDepth();
        GL11.glPushMatrix();
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        try {
            renderFirstPersonArm.invokeExact((Object)renderer, (Object)player);
        } finally {
            probing = false;
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            restoreModelViewDepth(modelViewDepth);
            restoreCapability(GL_BLEND, oldBlend);
            restoreCapability(GL_ALPHA_TEST, oldAlpha);
            restoreCapability(GL11.GL_CULL_FACE, oldCull);
            GL11.glBindTexture(GL_TEXTURE_2D, oldTex);
            GL11.glColor4f(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
            if (oldMode != GL_MODELVIEW) GL11.glMatrixMode(oldMode);
        }

        if (passes.isEmpty()) return false;
        if (!loggedProbeCache) {
            loggedProbeCache = true;
            System.out.println("[EpicFight1710] First-person 2.0.40-RC1 appearance-pass cache active: captured " + passes.size() + " JBRA texture/tint pass(es) from the lightweight first-person sequencer; probe frequency is capped to one run per game tick instead of one full player render per display frame.");
        }
        return true;
    }

    /** Called for every ModelRenderer reached by the invisible appearance probe. */
    private void captureProbePart(Object part, float scale) {
        if (part == null || passes.size() >= MAX_CAPTURED_PASSES) return;
        Object owner = probeOwnerCache.get(part);
        if (owner == null) {
            owner = findOwningRightArmModel(currentRenderer, currentMainModel, part);
            if (owner == null) return;
            if (probeOwnerCache.size() > 512) probeOwnerCache.clear();
            probeOwnerCache.put(part, owner);
        }

        int tex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        readCurrentColor(probeColor);
        boolean blend = GL11.glIsEnabled(GL_BLEND);
        boolean alpha = GL11.glIsEnabled(GL_ALPHA_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        float s = scale > 0.00001F ? scale : MODEL_SCALE;

        if (!passes.isEmpty()) {
            BodyPass last = passes.get(passes.size() - 1);
            if (last.same(owner, tex, probeColor, blend, alpha, cull, s)) return;
        }
        passes.add(new BodyPass(owner, tex, probeColor[0], probeColor[1], probeColor[2], probeColor[3], blend, alpha, cull, s));
    }

    private void replayCapturedBodyPasses(int tick) {
        if (!worldBodyReady || passes.isEmpty()) return;

        int oldMode = GL11.glGetInteger(GL_MATRIX_MODE);
        int oldTex = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean oldBlend = GL11.glIsEnabled(GL_BLEND);
        boolean oldAlpha = GL11.glIsEnabled(GL_ALPHA_TEST);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        readCurrentColor(savedColor);

        boolean validationTick = tick != lastValidatedTick;
        boolean any = false;
        GL11.glMatrixMode(GL_MODELVIEW);
        int modelViewDepth = safeModelViewDepth();
        GL11.glPushMatrix();
        try {
            ((Buffer)worldBodyMatrix).rewind();
            GL11.glLoadMatrix(worldBodyMatrix);
            for (int i = 0; i < passes.size(); i++) {
                BodyPass p = passes.get(i);
                GL11.glBindTexture(GL_TEXTURE_2D, p.textureId);
                GL11.glColor4f(p.r, p.g, p.b, p.a);
                restoreCapability(GL_BLEND, p.blend);
                restoreCapability(GL_ALPHA_TEST, p.alpha);
                // Full player rendering keeps body surfaces effectively two-sided while
                // animations can approach the camera. Preserve the captured pass state.
                restoreCapability(GL11.GL_CULL_FACE, p.cull);
                // func_82441_a is an ARM appearance sequencer. The first captured pass
                // for a concrete JBRA model is its baseline skin/form texture, so replay
                // that pass over the whole body. Later passes from the same owner are
                // arm-specific overlays and must not be smeared across torso/legs.
                boolean fullBody = firstPassForOwner(i, p.model);
                boolean validatePass = validationTick && fullBody;
                // Critical RC2 correction: do NOT emit B/RA/LA/RL/LL under one flat
                // matrix. ModelBipedBody.renderBody() applies race/body dependent outer
                // scale/translation separately to head, torso, arms and legs. We keep
                // only that transform tree and intercept every ModelRenderer draw before
                // native geometry traversal, so the weighted cached mesh is emitted at
                // exactly the matrix the real JRMCore body would have used.
                if (renderModelBodyTransformTree(p.model, p.scale, !fullBody, validatePass)) any = true;
            }
        } finally {
            restoreModelViewDepth(modelViewDepth);
            GL11.glBindTexture(GL_TEXTURE_2D, oldTex);
            GL11.glColor4f(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
            restoreCapability(GL_BLEND, oldBlend);
            restoreCapability(GL_ALPHA_TEST, oldAlpha);
            restoreCapability(GL11.GL_CULL_FACE, oldCull);
            if (oldMode != GL_MODELVIEW) GL11.glMatrixMode(oldMode);
        }
        if (tick != lastValidatedTick) lastValidatedTick = tick;
        emittedThisFrame |= any;
    }

    private boolean firstPassForOwner(int index, Object model) {
        if (model == null) return false;
        for (int i = 0; i < index; i++) if (passes.get(i).model == model) return false;
        return true;
    }

    private boolean renderModelBodyTransformTree(Object model, float scale, boolean armsOnly, boolean validate) {
        if (model == null) return false;
        MethodHandle renderBody = bodyRenderHandle(model.getClass());
        if (renderBody == null) return false;
        bodyTraversal = true;
        bodyTraversalArmsOnly = armsOnly;
        bodyTraversalValidate = validate;
        bodyTraversalHandled = false;
        currentTraversalModel = model;
        try {
            // ModelBipedBody.render(Entity,...) normally stores the current Entity before
            // calling renderBody().  The fast path calls renderBody() directly, so restore
            // that native precondition explicitly.  Some race/body branches consult it.
            ModelAccess access = modelAccess(model.getClass());
            if (access != null && access.entity != null && currentPlayer != null) {
                try { access.entity.set(model, currentPlayer); } catch (Throwable ignored) {}
            }
            renderBody.invokeExact((Object)model, scale);
            return bodyTraversalHandled;
        } catch (Throwable t) {
            warnOnce("first-person JRMCore renderBody transform tree", t);
            return false;
        } finally {
            bodyTraversal = false;
            bodyTraversalArmsOnly = false;
            bodyTraversalValidate = false;
            currentTraversalModel = null;
        }
    }

    private MethodHandle bodyRenderHandle(Class<?> type) {
        if (type == null) return null;
        if (bodyRenderHandles.containsKey(type)) return bodyRenderHandles.get(type);
        Method found = null;
        for (Class<?> c = type; c != null && found == null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("renderBody", Float.TYPE);
                m.setAccessible(true);
                found = m;
            } catch (Throwable ignored) {}
        }
        MethodHandle handle=adapt(found,MethodType.methodType(Void.TYPE,Object.class,Float.TYPE));
        bodyRenderHandles.put(type,handle);
        return handle;
    }

    private static MethodHandle adapt(Method method,MethodType type) {
        if(method==null)return null;
        try{return MethodHandles.lookup().unreflect(method).asType(type);}
        catch(Throwable ignored){return null;}
    }

    private void captureIncomingCamera() {
        int oldMode = GL11.glGetInteger(GL_MATRIX_MODE);
        GL11.glMatrixMode(GL_MODELVIEW);
        ((Buffer)cameraMatrix).clear();
        GL11.glGetFloat(GL_MODELVIEW_MATRIX, cameraMatrix);
        ((Buffer)cameraMatrix).rewind();
        if (oldMode != GL_MODELVIEW) GL11.glMatrixMode(oldMode);
    }

    /** Build the same outer RenderLiving matrix the 2.0.36 full player render used. */
    private void prepareWorldBodyMatrix(Object player, Object renderer, float partial) throws Throwable {
        int oldMode = GL11.glGetInteger(GL_MATRIX_MODE);
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            ((Buffer)cameraMatrix).rewind();
            GL11.glLoadMatrix(cameraMatrix);
            GL11.glTranslatef(0.0F, POV_BODY_Y, 0.0F);

            double ix = Compat.px(player) + (Compat.x(player) - Compat.px(player)) * partial - Compat.renderX();
            double iy = Compat.py(player) + (Compat.y(player) - Compat.py(player)) * partial - Compat.renderY();
            double iz = Compat.pz(player) + (Compat.z(player) - Compat.pz(player)) * partial - Compat.renderZ();

            // EXACT vanilla/Forge 1.7.10 RenderPlayer contract:
            //   d3 = renderY - player.yOffset
            // before RendererLivingEntity receives the player coordinates.
            // 2.0.37/2.0.38 accidentally fed raw Entity.posY into the manual fast path,
            // lifting the entire real body by roughly one legacy player eye/body offset.
            // This is not a visual tuning constant: it reproduces RenderPlayer.doRender.
            float renderPlayerYOffset = Compat.yOffset(player);
            iy -= (double)renderPlayerYOffset;

            // 2.0.39 restored the exact vertical RenderPlayer contract and proved the
            // WORLD-BODY is structurally aligned again. Live acceptance still showed a
            // thin neck/upper-back strip at the bottom of first person: the camera is
            // visually a little too far behind inside the head. Do NOT move individual
            // arms or the actual world camera. Instead move the POV-only body backward
            // by one legacy model pixel along interpolated view yaw. This is horizontal
            // only, so looking up/down does not make the body climb/fall in camera space.
            float vy0 = Compat.prevYaw(player);
            float vy1 = Compat.yaw(player);
            float viewYaw = vy0 + wrapDegrees(vy1 - vy0) * partial;
            double viewRad = Math.toRadians((double)viewYaw);
            ix += Math.sin(viewRad) * (double)POV_BODY_BACK;
            iz -= Math.cos(viewRad) * (double)POV_BODY_BACK;

            GL11.glTranslatef((float)ix, (float)iy, (float)iz);
            if (!loggedRenderPlayerYContract) {
                loggedRenderPlayerYContract = true;
                System.out.println("[EpicFight1710] First-person 2.0.40-RC1 exact RenderPlayer Y contract active: -Entity.yOffset=" + renderPlayerYOffset + " is applied before the RendererLiving model basis; POV-only offsets: Y=" + POV_BODY_Y + ", back=" + POV_BODY_BACK + ".");
            }

            float by0 = Compat.prevBodyYaw(player);
            float by1 = Compat.bodyYaw(player);
            float bodyYaw = by0 + wrapDegrees(by1 - by0) * partial;
            GL11.glRotatef(180.0F - bodyYaw, 0.0F, 1.0F, 0.0F);

            // Vanilla RenderLivingBase model basis before model.render(...).
            GL11.glScalef(-1.0F, -1.0F, 1.0F);
            applyCachedPreRender(renderer, player, partial);
            GL11.glTranslatef(0.0F, VANILLA_MODEL_Y, 0.0F);

            ((Buffer)worldBodyMatrix).clear();
            GL11.glGetFloat(GL_MODELVIEW_MATRIX, worldBodyMatrix);
            ((Buffer)worldBodyMatrix).rewind();
            worldBodyReady = true;
        } finally {
            GL11.glPopMatrix();
            if (oldMode != GL_MODELVIEW) GL11.glMatrixMode(oldMode);
        }
    }

    /**
     * RenderPlayerJBRA's preRenderCallback contains DBC race/body size scaling. It is
     * intentionally evaluated at most once per game tick and the resulting affine matrix
     * is reused for all display frames in that tick.
     */
    private void applyCachedPreRender(Object renderer, Object player, float partial) throws Throwable {
        int tick = Compat.ticks(player);
        if (preRenderTick != tick || preRenderOwner != renderer) {
            preRenderTick = tick;
            preRenderOwner = renderer;
            int oldMode = GL11.glGetInteger(GL_MATRIX_MODE);
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                GL11.glLoadIdentity();
                if (preRenderCallback != null) preRenderCallback.invokeExact((Object)renderer, (Object)player, partial);
                ((Buffer)preRenderMatrix).clear();
                GL11.glGetFloat(GL_MODELVIEW_MATRIX, preRenderMatrix);
                ((Buffer)preRenderMatrix).rewind();
            } finally {
                GL11.glPopMatrix();
                if (oldMode != GL_MODELVIEW) GL11.glMatrixMode(oldMode);
            }
        }
        ((Buffer)preRenderMatrix).rewind();
        GL11.glMultMatrix(preRenderMatrix);
    }

    private void ensureRenderer(Object renderer, Object player) {
        Class<?> c = renderer.getClass();
        if (rendererType == c && renderFirstPersonArm != null) return;
        rendererType = c;
        Method arm=findCompatible(c, 1, player, "func_82441_a", "renderFirstPersonArm");
        Method pre=findCompatible(c, 2, player, "func_77041_b", "preRenderCallback");
        renderFirstPersonArm=adapt(arm,MethodType.methodType(Void.TYPE,Object.class,Object.class));
        preRenderCallback=adapt(pre,MethodType.methodType(Void.TYPE,Object.class,Object.class,Float.TYPE));
        preRenderTick = Integer.MIN_VALUE;
        preRenderOwner = null;
    }

    private static Method findCompatible(Class<?> type, int params, Object player, String... names) {
        Class<?> c = type;
        while (c != null) {
            Method[] ms;
            try { ms = c.getDeclaredMethods(); } catch (Throwable t) { ms = new Method[0]; }
            for (String name : names) {
                for (Method m : ms) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != params) continue;
                    if (params >= 1 && player != null && !p[0].isAssignableFrom(player.getClass())) continue;
                    if (params == 2 && p[1] != Float.TYPE) continue;
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private Object findOwningRightArmModel(Object renderer, Object main, Object part) {
        if (part == null) return null;
        if (ownsRightArm(main, part)) return main;
        if (renderer == null) return null;
        Field[] fields = rendererModelFields(renderer.getClass());
        for (int i = 0; i < fields.length; i++) {
            Object model = Reflect.get(fields[i], renderer);
            if (model != null && model != main && ownsRightArm(model, part)) return model;
        }
        return null;
    }

    private boolean ownsRightArm(Object model, Object part) {
        if (model == null || part == null) return false;
        ModelAccess a = modelAccess(model.getClass());
        if (a == null) return false;
        return part == Reflect.get(a.rightArmAlias, model)
                || part == Reflect.get(a.rightArm, model)
                || part == Reflect.get(a.vanillaRightArm, model);
    }

    private Field[] rendererModelFields(Class<?> type) {
        Field[] cached = rendererModelFields.get(type);
        if (cached != null) return cached;
        ArrayList<Field> list = new ArrayList<Field>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Field[] fs;
            try { fs = c.getDeclaredFields(); } catch (Throwable t) { fs = new Field[0]; }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getType().getName();
                if (n.indexOf("Model") < 0 || n.indexOf("ModelRenderer") >= 0) continue;
                try { f.setAccessible(true); } catch (Throwable ignored) {}
                list.add(f);
            }
        }
        Field[] out = list.toArray(new Field[list.size()]);
        rendererModelFields.put(type, out);
        return out;
    }

    private ModelAccess modelAccess(Class<?> c) {
        if (c == null) return null;
        if (modelAccessCache.containsKey(c)) return modelAccessCache.get(c);
        ModelAccess a = new ModelAccess();
        a.entity = Reflect.field(c, "Entity", "entity");
        a.bodyAlias = Reflect.field(c, "B");
        a.body = Reflect.field(c, "body");
        a.vanillaBody = Reflect.field(c, "field_78115_e", "bipedBody");
        a.rightArmAlias = Reflect.field(c, "RA");
        a.rightArm = Reflect.field(c, "rightarm", "Brightarm");
        a.vanillaRightArm = Reflect.field(c, "field_78112_f", "bipedRightArm");
        a.leftArmAlias = Reflect.field(c, "LA");
        a.leftArm = Reflect.field(c, "leftarm", "Bleftarm");
        a.vanillaLeftArm = Reflect.field(c, "field_78113_g", "bipedLeftArm");
        a.rightLegAlias = Reflect.field(c, "RL");
        a.rightLeg = Reflect.field(c, "rightleg");
        a.vanillaRightLeg = Reflect.field(c, "field_78123_h", "bipedRightLeg");
        a.leftLegAlias = Reflect.field(c, "LL");
        a.leftLeg = Reflect.field(c, "leftleg");
        a.vanillaLeftLeg = Reflect.field(c, "field_78124_i", "bipedLeftLeg");
        if (a.bodyAlias == null && a.body == null && a.vanillaBody == null && a.rightArmAlias == null && a.vanillaRightArm == null) {
            modelAccessCache.put(c, null);
            return null;
        }
        modelAccessCache.put(c, a);
        return a;
    }

    private static Object first(Object owner, Field... fields) {
        if (owner == null || fields == null) return null;
        for (int i = 0; i < fields.length; i++) {
            Object v = Reflect.get(fields[i], owner);
            if (v != null) return v;
        }
        return null;
    }

    private void readCurrentColor(float[] out) {
        if (out == null || out.length < 4) return;
        ((Buffer)colorScratch).clear();
        GL11.glGetFloat(GL_CURRENT_COLOR, colorScratch);
        out[0] = colorScratch.get(0);
        out[1] = colorScratch.get(1);
        out[2] = colorScratch.get(2);
        out[3] = colorScratch.get(3);
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
    }

    /** Hardens the RenderHand boundary against foreign render code that throws after
     * pushing MODELVIEW. A leaked stack frame can poison every later GUI draw. */
    private static int safeModelViewDepth() {
        try { return GL11.glGetInteger(2979); } catch (Throwable ignored) { return -1; } // GL_MODELVIEW_STACK_DEPTH
    }

    private static void restoreModelViewDepth(int target) {
        if (target < 0) {
            try { GL11.glPopMatrix(); } catch (Throwable ignored) {}
            return;
        }
        try {
            int now = GL11.glGetInteger(2979);
            while (now > target) { GL11.glPopMatrix(); now--; }
        } catch (Throwable ignored) {}
    }

    private static float clamp01(float x) {
        return x < 0.0F ? 0.0F : (x > 1.0F ? 1.0F : x);
    }

    private static float wrapDegrees(float v) {
        while (v <= -180.0F) v += 360.0F;
        while (v > 180.0F) v -= 360.0F;
        return v;
    }

    private void warnOnce(String where, Throwable t) {
        if (!warned) {
            warned = true;
            System.err.println("[EpicFight1710] " + where + " failed once; native hand fallback remains available for that frame.");
            if (t != null) t.printStackTrace();
        }
    }

    private static final class ModelAccess {
        Field entity;
        Field bodyAlias, body, vanillaBody;
        Field rightArmAlias, rightArm, vanillaRightArm;
        Field leftArmAlias, leftArm, vanillaLeftArm;
        Field rightLegAlias, rightLeg, vanillaRightLeg;
        Field leftLegAlias, leftLeg, vanillaLeftLeg;
    }

    private static final class BodyPass {
        final Object model;
        final int textureId;
        final float r, g, b, a;
        final boolean blend, alpha, cull;
        final float scale;
        BodyPass(Object model, int textureId, float r, float g, float b, float a,
                 boolean blend, boolean alpha, boolean cull, float scale) {
            this.model = model;
            this.textureId = textureId;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.blend = blend; this.alpha = alpha; this.cull = cull;
            this.scale = scale;
        }
        boolean same(Object m, int tex, float[] c, boolean bl, boolean al, boolean cu, float s) {
            return model == m && textureId == tex && blend == bl && alpha == al && cull == cu
                    && Math.abs(scale - s) < 0.000001F
                    && Math.abs(r - c[0]) < 0.0001F && Math.abs(g - c[1]) < 0.0001F
                    && Math.abs(b - c[2]) < 0.0001F && Math.abs(a - c[3]) < 0.0001F;
        }
    }

    /** Lighting reflection is isolated and cached; it never scans renderer/model fields. */
    private static final class Lighting {
        static final Lighting INSTANCE = new Lighting();
        private Object entityRenderer;
        private Method enableLightmap, disableLightmap, enableStandard, disableStandard;
        private boolean initialized;

        boolean enable(float partial) {
            init();
            try {
                if (entityRenderer != null && enableLightmap != null) {
                    enableLightmap.invoke(entityRenderer, Double.valueOf((double)partial));
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
        void disable(float partial) {
            try { if (entityRenderer != null && disableLightmap != null) disableLightmap.invoke(entityRenderer, Double.valueOf((double)partial)); }
            catch (Throwable ignored) {}
        }
        boolean enableStandard() {
            init();
            try { if (enableStandard != null) { enableStandard.invoke(null); return true; } }
            catch (Throwable ignored) {}
            return false;
        }
        void disableStandard() {
            try { if (disableStandard != null) disableStandard.invoke(null); } catch (Throwable ignored) {}
        }
        private void init() {
            if (initialized) return;
            initialized = true;
            try {
                Object mc = Compat.minecraft();
                if (mc != null) {
                    Field f = Reflect.field(mc.getClass(), "field_71460_t", "entityRenderer");
                    entityRenderer = Reflect.get(f, mc);
                    if (entityRenderer != null) {
                        enableLightmap = findDoubleVoid(entityRenderer.getClass(), "enableLightmap", "func_78463_b");
                        disableLightmap = findDoubleVoid(entityRenderer.getClass(), "disableLightmap", "func_78483_a");
                    }
                }
            } catch (Throwable ignored) {}
            try {
                Class<?> rh = Class.forName("net.minecraft.client.renderer.RenderHelper");
                enableStandard = findStaticNoArg(rh, "enableStandardItemLighting", "func_74519_b");
                disableStandard = findStaticNoArg(rh, "disableStandardItemLighting", "func_74518_a");
            } catch (Throwable ignored) {}
        }
        private static Method findDoubleVoid(Class<?> c, String... names) {
            for (String n : names) {
                for (Class<?> x = c; x != null; x = x.getSuperclass()) {
                    try { Method m = x.getDeclaredMethod(n, Double.TYPE); m.setAccessible(true); return m; } catch (Throwable ignored) {}
                }
            }
            return null;
        }
        private static Method findStaticNoArg(Class<?> c, String... names) {
            for (String n : names) {
                for (Class<?> x = c; x != null; x = x.getSuperclass()) {
                    try {
                        Method m = x.getDeclaredMethod(n);
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        m.setAccessible(true);
                        return m;
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }
    }
}
