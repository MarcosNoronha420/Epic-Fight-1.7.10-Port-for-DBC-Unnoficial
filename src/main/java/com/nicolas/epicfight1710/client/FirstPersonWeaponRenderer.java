package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.util.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;

/**
 * Epic Fight 20.9.5 main-hand item layer adapted to Minecraft 1.7.10.
 * Tool_R and RenderItemBase's default correction remain source-owned; only the final
 * item-display API is translated to the legacy ItemRenderer.renderItem path.
 */
final class FirstPersonWeaponRenderer {
    static final FirstPersonWeaponRenderer INSTANCE=new FirstPersonWeaponRenderer();
    private static final float[] TOOL=new float[16],TOOL_CORRECTED=new float[16],TOOL_JBRA=new float[16],SOCKET_DELTA=new float[3],BODY_MODEL_OFFSET=new float[3],CORRECTION=new float[16];
    private static final FloatBuffer GL_MATRIX=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer COLOR=ByteBuffer.allocateDirect(4*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final float[] SAVED_COLOR=new float[4];
    static {
        float[] t=new float[16],r=new float[16];Mat4.identity(t);Mat4.identity(r);
        t[11]=-0.13F;
        double a=Math.toRadians(-90.0);float c=(float)Math.cos(a),s=(float)Math.sin(a);
        r[5]=c;r[6]=-s;r[9]=s;r[10]=c;
        Mat4.mul(t,r,CORRECTION); // RenderItemBase: translate Z -0.13, then rotate X -90.
    }

    private Class<?> playerType,stackType,itemType,itemRendererType,mcType,entityRendererType;
    private Method heldMethod,stackItemMethod,itemFull3DMethod,itemRotateMethod,renderItemMethod;
    private Field mcEntityRendererField,entityItemRendererField;
    private Object cachedItemRenderer;
    private Object heldPlayer,heldStackCache,lastStack,lastItem;
    private int heldTick=Integer.MIN_VALUE;
    private boolean logged,warned;
    private FirstPersonWeaponRenderer(){}

    Object heldStack(Object player){
        if(player==null)return null;
        int tick=Compat.ticks(player);
        if(heldPlayer==player&&heldTick==tick)return heldStackCache;
        try{
            Class<?> c=player.getClass();
            if(playerType!=c||heldMethod==null){playerType=c;heldMethod=Reflect.method(c,0,"func_70694_bm","getCurrentEquippedItem");}
            heldStackCache=heldMethod==null?null:heldMethod.invoke(player);
            heldPlayer=player;heldTick=tick;
            return heldStackCache;
        }catch(Throwable t){heldPlayer=player;heldTick=tick;heldStackCache=null;return null;}
    }
    boolean canRender(Object player,Object stack){
        if(player==null||stack==null)return false;try{Object item=item(stack);return item!=null&&isFull3D(item)&&resolveItemRenderer(player,stack)!=null&&renderItemMethod!=null;}catch(Throwable t){return false;}
    }
    boolean render(Object player,Object stack){
        if(!canRender(player,stack))return false;
        boolean pushed=false;
        try{
            if(!EpicFirstPersonBridge.INSTANCE.pushEpicBodySpace())return false;pushed=true;
            int joint=PoseEngine.INSTANCE.toolR();if(joint<0||!PoseEngine.INSTANCE.globalJointMatrix(joint,TOOL))return false;
            if(JbraWeightedPartRenderer.INSTANCE.rightHandSocketDelta(SOCKET_DELTA)){TOOL[3]+=SOCKET_DELTA[0];TOOL[7]+=SOCKET_DELTA[1];TOOL[11]+=SOCKET_DELTA[2];}
            // Exact source order: correction.mulFront(Tool_R) == Tool_R * correction.
            Mat4.mul(TOOL,CORRECTION,TOOL_CORRECTED);
            upload(TOOL_CORRECTED);

            Object item=item(stack);if(item==null)return false;
            // This is the legacy 1.7.10 THIRD_PERSON_RIGHT_HAND display boundary that
            // replaces modern ItemDisplayContext.THIRD_PERSON_RIGHT_HAND.
            if(shouldRotate(item)){GL11.glRotatef(180.0F,0,0,1);GL11.glTranslatef(0,-0.125F,0);}
            GL11.glTranslatef(0,0.1875F,0);GL11.glScalef(0.625F,-0.625F,0.625F);GL11.glRotatef(-100.0F,1,0,0);GL11.glRotatef(45.0F,0,1,0);
            GL11.glColor4f(1,1,1,1);
            renderItemMethod.invoke(cachedItemRenderer,player,stack,Integer.valueOf(0));
            if(!logged){logged=true;System.out.println("[EpicFight1710] First-person item layer now follows Epic 20.9.5 Tool_R * RenderItemBase(Tz=-0.13,Rx=-90) before the legacy 1.7.10 THIRD_PERSON_RIGHT_HAND display transform.");}
            return true;
        }catch(Throwable t){if(!warned){warned=true;System.err.println("[EpicFight1710] First-person Tool_R item layer failed once.");t.printStackTrace();}return false;}
        finally{if(pushed)EpicFirstPersonBridge.INSTANCE.popEpicBodySpace();}
    }


    /**
     * 2.0.40-RC1 WORLD-BODY item pass. This deliberately does not call
     * RenderPlayerJBRA.renderEquippedItemsJBRA: the 2.0.37-RC1 live log proved that
     * method enters hair/form rendering and can abort inside third-party mixins, leaving
     * GL state corrupted for later GUI rendering. Tool_R is evaluated directly and then
     * converted into the same JBRA model basis used by the weighted body meshes.
     */
    boolean renderWorldBody(Object player,Object stack,FloatBuffer worldBodyMatrix){
        if(player==null||stack==null||worldBodyMatrix==null||!canRender(player,stack))return false;
        int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int oldTex=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean oldBlend=GL11.glIsEnabled(GL11.GL_BLEND);
        boolean oldAlpha=GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean oldCull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean oldTexture=GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean oldDepth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldLighting=GL11.glIsEnabled(GL11.GL_LIGHTING);
        readColor(SAVED_COLOR);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        int modelViewDepth=safeModelViewDepth();
        boolean pushed=false;
        try{
            GL11.glPushMatrix();pushed=true;
            ((Buffer)worldBodyMatrix).rewind();
            GL11.glLoadMatrix(worldBodyMatrix);

            int joint=PoseEngine.INSTANCE.toolR();
            if(joint<0||!PoseEngine.INSTANCE.globalJointMatrix(joint,TOOL))return false;
            if(JbraWeightedPartRenderer.INSTANCE.rightHandSocketDelta(SOCKET_DELTA)){
                TOOL[3]+=SOCKET_DELTA[0];TOOL[7]+=SOCKET_DELTA[1];TOOL[11]+=SOCKET_DELTA[2];
            }
            // Keep the exact Tool_R compatibility adjustment used by the established
            // third-person mount before crossing into JBRA/model coordinates.
            if(JbraWeightedPartRenderer.INSTANCE.frameModelOffset(BODY_MODEL_OFFSET)){
                TOOL[3]-=BODY_MODEL_OFFSET[0];TOOL[7]-=BODY_MODEL_OFFSET[1];TOOL[11]+=BODY_MODEL_OFFSET[2];
            }
            Mat4.mul(TOOL,CORRECTION,TOOL_CORRECTED);
            // Important: this is an affine basis conjugation, not a simple left
            // multiplication. Reuse the already-validated third-person adapter.
            WeaponItemMountHook.epicToJbra(TOOL_CORRECTED,TOOL_JBRA);
            upload(TOOL_JBRA);

            Object item=item(stack);if(item==null)return false;
            if(shouldRotate(item)){GL11.glRotatef(180.0F,0,0,1);GL11.glTranslatef(0,-0.125F,0);}
            GL11.glTranslatef(0,0.1875F,0);
            GL11.glScalef(0.625F,-0.625F,0.625F);
            GL11.glRotatef(-100.0F,1,0,0);
            GL11.glRotatef(45.0F,0,1,0);
            GL11.glColor4f(1,1,1,1);
            renderItemMethod.invoke(cachedItemRenderer,player,stack,Integer.valueOf(0));
            if(!logged){
                logged=true;
                System.out.println("[EpicFight1710] First-person 2.0.40-RC1 direct Tool_R item pass active: no RenderPlayerJBRA equipped-item/hair/layer traversal is entered.");
            }
            return true;
        }catch(Throwable t){
            if(!warned){warned=true;System.err.println("[EpicFight1710] First-person 2.0.40-RC1 direct Tool_R item pass failed once; body rendering remains isolated and GL state is restored by the outer pass.");t.printStackTrace();}
            return false;
        }finally{
            try{if(pushed)restoreModelViewDepth(modelViewDepth);}catch(Throwable ignored){}
            try{GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTex);}catch(Throwable ignored){}
            try{GL11.glColor4f(SAVED_COLOR[0],SAVED_COLOR[1],SAVED_COLOR[2],SAVED_COLOR[3]);}catch(Throwable ignored){}
            try{restore(GL11.GL_BLEND,oldBlend);restore(GL11.GL_ALPHA_TEST,oldAlpha);restore(GL11.GL_CULL_FACE,oldCull);restore(GL11.GL_TEXTURE_2D,oldTexture);restore(GL11.GL_DEPTH_TEST,oldDepth);restore(GL11.GL_LIGHTING,oldLighting);}catch(Throwable ignored){}
            try{if(oldMode!=GL11.GL_MODELVIEW)GL11.glMatrixMode(oldMode);}catch(Throwable ignored){}
        }
    }

    private Object item(Object stack)throws Exception{
        if(stack==lastStack)return lastItem;
        Class<?> c=stack.getClass();
        if(stackType!=c||stackItemMethod==null){stackType=c;stackItemMethod=Reflect.method(c,0,"func_77973_b","getItem");}
        lastStack=stack;lastItem=stackItemMethod==null?null:stackItemMethod.invoke(stack);
        return lastItem;
    }
    private boolean isFull3D(Object item)throws Exception{
        Class<?> c=item.getClass();if(itemType!=c||itemFull3DMethod==null){itemType=c;itemFull3DMethod=Reflect.method(c,0,"func_77662_d","isFull3D");itemRotateMethod=Reflect.method(c,0,"func_77629_n_","shouldRotateAroundWhenRendering");}
        Object v=itemFull3DMethod==null?null:itemFull3DMethod.invoke(item);return Boolean.TRUE.equals(v);
    }
    private boolean shouldRotate(Object item){try{if(itemRotateMethod==null)isFull3D(item);return itemRotateMethod!=null&&Boolean.TRUE.equals(itemRotateMethod.invoke(item));}catch(Throwable t){return false;}}

    private Object resolveItemRenderer(Object player,Object stack)throws Exception{
        Object mc=Compat.minecraft();if(mc==null)return null;Class<?> mcc=mc.getClass();if(mcType!=mcc||mcEntityRendererField==null){mcType=mcc;mcEntityRendererField=Reflect.field(mcc,"field_71460_t","entityRenderer");}
        Object er=Reflect.get(mcEntityRendererField,mc);if(er==null)return null;Class<?> erc=er.getClass();if(entityRendererType!=erc||entityItemRendererField==null){entityRendererType=erc;entityItemRendererField=Reflect.field(erc,"field_78516_c","itemRenderer");if(entityItemRendererField==null){Class<?> k=erc;while(k!=null&&entityItemRendererField==null){for(Field f:k.getDeclaredFields()){if(Modifier.isStatic(f.getModifiers()))continue;String n=f.getType().getName();if(n.endsWith("ItemRenderer")||n.indexOf("client.renderer.ItemRenderer")>=0){try{f.setAccessible(true);}catch(Throwable ignored){}entityItemRendererField=f;break;}}k=k.getSuperclass();}}}
        Object ir=Reflect.get(entityItemRendererField,er);if(ir==null)return null;Class<?> c=ir.getClass();if(itemRendererType!=c||renderItemMethod==null){itemRendererType=c;renderItemMethod=Reflect.method(c,3,"func_78443_a","renderItem");}cachedItemRenderer=ir;return ir;
    }
    private static void readColor(float[] out){
        ((Buffer)COLOR).clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR,COLOR);
        out[0]=COLOR.get(0);out[1]=COLOR.get(1);out[2]=COLOR.get(2);out[3]=COLOR.get(3);
    }
    private static void restore(int cap,boolean enabled){if(enabled)GL11.glEnable(cap);else GL11.glDisable(cap);}
    private static int safeModelViewDepth(){try{return GL11.glGetInteger(2979);}catch(Throwable ignored){return -1;}}
    private static void restoreModelViewDepth(int target){
        if(target<0){try{GL11.glPopMatrix();}catch(Throwable ignored){}return;}
        try{int now=GL11.glGetInteger(2979);while(now>target){GL11.glPopMatrix();now--;}}catch(Throwable ignored){}
    }

    private static void upload(float[] m){
        ((Buffer)GL_MATRIX).clear();GL_MATRIX.put(m[0]).put(m[4]).put(m[8]).put(0);GL_MATRIX.put(m[1]).put(m[5]).put(m[9]).put(0);GL_MATRIX.put(m[2]).put(m[6]).put(m[10]).put(0);GL_MATRIX.put(m[3]).put(m[7]).put(m[11]).put(1);((Buffer)GL_MATRIX).flip();GL11.glMultMatrix(GL_MATRIX);
    }
}
