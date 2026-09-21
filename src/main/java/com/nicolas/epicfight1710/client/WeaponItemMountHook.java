package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.combat.WeaponCapabilityRegistry;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.opengl.GL11;

/**
 * 1.7.10 adapter for Epic Fight's Tool_R held-item joint.
 *
 * Epic Fight 20.9.5 fully owns the modern ItemInHandRenderer and therefore composes
 * Tool_R with RenderItemBase's modern item correction before issuing the item draw.
 * JBRA 1.6.52 has a different contract: RA.postRender is only the arm mount, then its
 * legacy equipped-item path applies the hand translation and the 1.7.10 item-type
 * transforms itself. Applying BOTH correction stacks (the 1.8 behavior) is a double
 * transform and is the reason the user's sword remained detached even though Tool_R
 * was executing successfully.
 *
 * This bridge now ports the source *joint ownership* exactly and adapts only the API
 * boundary: replace RA.postRender by animated Tool_R, pre-cancel JBRA's immediately
 * following fixed arm->hand translation, then let the native 1.7.10 item renderer do
 * its own display transform/color/custom-renderer work once.
 */
public final class WeaponItemMountHook {
    private static final Map<Class<?>,Map<String,Method>> CACHE=new WeakHashMap<Class<?>,Map<String,Method>>();
    private static final float[] TOOL=new float[16];
    private static final float[] TOOL_CORRECTED=new float[16];
    private static final float[] ITEM_CORRECTION=new float[16];
    private static final float[] LEGACY_HAND_CANCEL=new float[16];
    private static final float[] TOOL_JBRA=new float[16];
    private static final float[] TOTAL=new float[16];
    private static final float[] SOCKET_DELTA=new float[3];
    private static final float[] BODY_MODEL_OFFSET=new float[3];
    private static final FloatBuffer GL_MATRIX=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static boolean logged,warned;

    static {
        Mat4.identity(LEGACY_HAND_CANCEL);
        // JBRA applies T(-0.0625,+0.4375,+0.0625) immediately after RA.postRender.
        // Since Tool_R is already the authored hand/tool socket, pre-compose the exact
        // inverse so that legacy translation cancels instead of moving the sword twice.
        LEGACY_HAND_CANCEL[3]=+0.0625F;
        LEGACY_HAND_CANCEL[7]=-0.4375F;
        LEGACY_HAND_CANCEL[11]=-0.0625F;
        // Epic Fight 20.9.5 RenderItemBase default main-hand correction:
        // T(0,0,-0.13) then Rx(-90deg). mulFront(Tool_R) means Tool_R*Correction.
        float[] t=new float[16],r=new float[16];Mat4.identity(t);Mat4.identity(r);
        t[11]=-0.13F;float c=0.0F,s=-1.0F;r[5]=c;r[6]=-s;r[9]=s;r[10]=c;
        Mat4.mul(t,r,ITEM_CORRECTION);
    }

    private WeaponItemMountHook() {}

    public static void postRender(Object part,float scale,String originalMethodName) {
        if(part==null)return;
        try {
            if(shouldUseEpicMount() && applyEpicMount())return;
        } catch(Throwable t) {
            if(!warned){warned=true;System.err.println("[EpicFight1710] Tool_R item mount failed once; JBRA's native RA.postRender fallback is retained.");t.printStackTrace();}
        }
        invokeOriginal(part,scale,originalMethodName);
    }

    private static boolean shouldUseEpicMount() {
        if(!CombatController.INSTANCE.battleMode()||!NativeJbraSkinContext.INSTANCE.active())return false;
        Object player=Compat.player();
        return player!=null&&WeaponCapabilityRegistry.resolve(player).armed();
    }

    private static boolean applyEpicMount() {
        int joint=PoseEngine.INSTANCE.toolR();
        if(joint<0||!PoseEngine.INSTANCE.globalJointMatrix(joint,TOOL))return false;

        // Carry the exact translation-only DBC socket compatibility correction that
        // the weighted arm renderer applied to Hand_R. No source Tool_R quaternion is
        // edited; this only reconnects two rigs whose bind pivots differ.
        if(JbraWeightedPartRenderer.INSTANCE.rightHandSocketDelta(SOCKET_DELTA)) {
            TOOL[3]+=SOCKET_DELTA[0]; TOOL[7]+=SOCKET_DELTA[1]; TOOL[11]+=SOCKET_DELTA[2];
        }

        // The 2.0.10 neck socket is absorbed fully by both arms so shoulders remain
        // connected to the native head/upper torso. Carry that same translation into
        // Tool_R; hips/legs intentionally do not receive it. Model->Epic vector basis
        // is (-x,-y,+z).
        if(JbraWeightedPartRenderer.INSTANCE.frameModelOffset(BODY_MODEL_OFFSET)) {
            TOOL[3]-=BODY_MODEL_OFFSET[0]; TOOL[7]-=BODY_MODEL_OFFSET[1]; TOOL[11]+=BODY_MODEL_OFFSET[2];
        }

        // Preserve the original 20.9.5 item-layer correction before crossing the
        // version boundary to JBRA/1.7.10. Source order is Tool_R * correction.
        Mat4.mul(TOOL,ITEM_CORRECTION,TOOL_CORRECTED);

        // Convert the authored Tool_R+item correction into JBRA/model space FIRST.
        // 1.9.0 multiplied LEGACY_HAND_CANCEL while TOOL was still in Epic armature
        // coordinates, even though that translation is expressed in JBRA's local
        // RA/item mount basis. Translation is basis-dependent: C(A*T(d_jbra)) is not
        // C(A)*T(d_jbra). During a swing the Tool_R rotation therefore rotated the
        // wrong-space cancellation vector, which is why swords could be correct-ish
        // at idle yet detach dramatically through the authored attack arc.
        epicToJbra(TOOL_CORRECTED,TOOL_JBRA);

        // Version adapter: unlike 1.20 RenderItemBase, JBRA will still run its own
        // downstream 1.7.10 item transform. Cancel only the fixed RA->hand translation
        // in the SAME JBRA local basis in which the following native glTranslatef is
        // applied. Algebraically: ToolJbra * T^-1 * T == ToolJbra for every rotation.
        Mat4.mul(TOOL_JBRA,LEGACY_HAND_CANCEL,TOTAL);

        ((Buffer)GL_MATRIX).clear();
        GL_MATRIX.put(TOTAL[0]).put(TOTAL[4]).put(TOTAL[8]).put(0.0F);
        GL_MATRIX.put(TOTAL[1]).put(TOTAL[5]).put(TOTAL[9]).put(0.0F);
        GL_MATRIX.put(TOTAL[2]).put(TOTAL[6]).put(TOTAL[10]).put(0.0F);
        GL_MATRIX.put(TOTAL[3]).put(TOTAL[7]).put(TOTAL[11]).put(1.0F);
        ((Buffer)GL_MATRIX).flip();
        GL11.glMultMatrix(GL_MATRIX);
        if(!logged){
            logged=true;
            System.out.println("[EpicFight1710] Tool_R item mount follows Epic 20.9.5 Tool_R * RenderItemBase(Tz=-0.13,Rx=-90) first, then converts to JBRA mount space and cancels only the legacy RA->hand translation before the 1.7.10 item display transform.");
        }
        return true;
    }

    /** Exact affine basis conversion used by the weighted JBRA body geometry.
     * Epic -> JBRA: p_j = R p_e + q, R=diag(-1,-1,+1), q=(0,1.5,0).
     * For a transform A this is C(A)=[R A3 R, R t + q]. */
    static void epicToJbra(float[] epic,float[] out) {
        out[0]= epic[0]; out[1]= epic[1]; out[2]=-epic[2];  out[3]=-epic[3];
        out[4]= epic[4]; out[5]= epic[5]; out[6]=-epic[6];  out[7]=1.5F-epic[7];
        out[8]=-epic[8]; out[9]=-epic[9]; out[10]=epic[10]; out[11]=epic[11];
        out[12]=out[13]=out[14]=0.0F;out[15]=1.0F;
    }

    /** Package-private pure-math hook for release audits; no GL or game state. */
    static void composeLegacyMountForAudit(float[] epicTool,float[] out) {
        float[] corrected=new float[16],jbra=new float[16];
        Mat4.mul(epicTool,ITEM_CORRECTION,corrected);
        epicToJbra(corrected,jbra);
        Mat4.mul(jbra,LEGACY_HAND_CANCEL,out);
    }

    private static void invokeOriginal(Object part,float scale,String name) {
        try {
            Method m=method(part.getClass(),name);
            if(m==null)throw new NoSuchMethodException(part.getClass().getName()+"."+name+"(float)");
            m.invoke(part,Float.valueOf(scale));
        } catch(Throwable t) {
            if(!warned){warned=true;System.err.println("[EpicFight1710] JBRA item-mount bridge could not invoke native postRender fallback; further identical errors are suppressed.");t.printStackTrace();}
        }
    }

    private static Method method(Class<?> type,String name) {
        synchronized(CACHE) {
            Map<String,Method> byName=CACHE.get(type);
            if(byName==null){byName=new HashMap<String,Method>();CACHE.put(type,byName);}
            if(byName.containsKey(name))return byName.get(name);
            Method found=find(type,name);byName.put(name,found);return found;
        }
    }

    private static Method find(Class<?> type,String name) {
        Class<?> c=type;
        while(c!=null){try{Method m=c.getDeclaredMethod(name,Float.TYPE);m.setAccessible(true);return m;}catch(Throwable ignored){}c=c.getSuperclass();}
        String[] aliases={"func_78794_c","postRender"};
        for(String alias:aliases)if(!alias.equals(name)){try{Method m=type.getMethod(alias,Float.TYPE);m.setAccessible(true);return m;}catch(Throwable ignored){}}
        return null;
    }
}
