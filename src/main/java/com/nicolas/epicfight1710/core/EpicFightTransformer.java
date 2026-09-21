package com.nicolas.epicfight1710.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Rendering bridge for the exact DBC/JRMCore/JBRA 1.7.10 pipeline.
 *
 * There are deliberately three independent interception paths:
 *
 *  1) vanilla ModelRenderer method entry (generic compatibility fallback),
 *  2) JRMCore ModelBipedBody call sites (authoritative DBC body path),
 *  3) JBRA's standalone ModelRendererJBRA method entry (custom JBRA geometry).
 *
 * The second path is the important one.  In JRMCore 1.3.51 the real player body
 * is drawn from ModelBipedBody.renderBody(float), which directly invokes
 * ModelRenderer.func_78785_a(float) many times.  Hooking those call sites means
 * a later coremod is free to rewrite vanilla ModelRenderer without deleting our
 * DBC bridge.  This is required by the user's Angelica/Xaero-heavy runtime.
 */
public final class EpicFightTransformer implements IClassTransformer {
    private static final String VANILLA_MODEL="net.minecraft.client.model.ModelRenderer";
    private static final String JRM_BODY="JinRyuu.JRMCore.entity.ModelBipedBody";
    private static final String JBRA_MODEL="JinRyuu.JBRA.ModelRendererJBRA";
    private static final String JBRA_PLAYER="JinRyuu.JBRA.RenderPlayerJBRA";
    private static final String HOOK_OWNER="com/nicolas/epicfight1710/client/ModelRendererSkinHook";
    private static final String CALLSITE_OWNER="com/nicolas/epicfight1710/client/ModelRendererCallsiteHook";
    private static final String FLIGHT_HOOK_OWNER="com/nicolas/epicfight1710/client/DbcFlightRenderHook";
    private static final String ITEM_MOUNT_OWNER="com/nicolas/epicfight1710/client/WeaponItemMountHook";
    private static final String NATIVE_CTX_OWNER="com/nicolas/epicfight1710/client/NativeJbraSkinContext";

    public byte[] transform(String name,String transformedName,byte[] basicClass) {
        if(basicClass==null)return null;
        try {
            if(VANILLA_MODEL.equals(transformedName))return hookModelRenderer(basicClass,"epicfight1710.modelrendererhook","vanilla ModelRenderer");
            if(JBRA_MODEL.equals(transformedName))return hookModelRenderer(basicClass,"epicfight1710.jbrarendererhook","JBRA ModelRendererJBRA");
            if(JBRA_PLAYER.equals(transformedName)) {
                byte[] out=hookJbraFlightTransform(basicClass);
                out=hookJbraFirstPersonCallsites(out);
                out=hookJbraHeldItemMount(out);
                return out;
            }
            if(JRM_BODY.equals(transformedName))return hookJrmBodyCallsites(basicClass);
            return basicClass;
        } catch(Throwable t) {
            System.err.println("[EpicFight1710/Core] Transformer failure in "+transformedName+"; original class preserved.");
            t.printStackTrace();
            return basicClass;
        }
    }

    private static byte[] hookModelRenderer(byte[] basicClass,String property,String label) {
        try {
            ClassReader cr=new ClassReader(basicClass);
            ClassNode cn=new ClassNode(Opcodes.ASM5);
            cr.accept(cn,0);
            int injected=0;
            StringBuilder candidates=new StringBuilder();
            for(MethodNode mn:cn.methods) {
                if("(F)V".equals(mn.desc)) {
                    if(candidates.length()>0)candidates.append(" | ");
                    candidates.append(fingerprint(mn));
                }
                if(!isRenderMethod(mn) || alreadyHooked(mn))continue;
                InsnList add=new InsnList();
                LabelNode original=new LabelNode();
                add.add(new VarInsnNode(Opcodes.ALOAD,0));
                add.add(new VarInsnNode(Opcodes.FLOAD,1));
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK_OWNER,"render","(Ljava/lang/Object;F)Z",false));
                add.add(new JumpInsnNode(Opcodes.IFEQ,original));
                add.add(new InsnNode(Opcodes.RETURN));
                add.add(original);
                mn.instructions.insert(add);
                injected++;
            }
            if(injected==0) {
                System.clearProperty(property);
                System.err.println("[EpicFight1710/Core] "+label+" found but no draw method was hooked. (F)V methods="+candidates);
                return basicClass;
            }
            ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.setProperty(property,"true");
            System.setProperty(property+".count",String.valueOf(injected));
            System.out.println("[EpicFight1710/Core] "+label+" skinning entry hook installed ("+injected+" draw method(s)).");
            return cw.toByteArray();
        } catch(Throwable t) {
            System.clearProperty(property);
            System.err.println("[EpicFight1710/Core] Could not instrument "+label+"; class left untouched.");
            t.printStackTrace();
            return basicClass;
        }
    }

    /**
     * Patches only JBRA's native y=2 flight block in preRenderCallback. The exact
     * 1.6.52 renderer translates by (0,-1.5,0), rotates about X by 90 degrees (or
     * pitch+90), then stores ModelBipedDBC.y=2. We redirect only those GL calls
     * and observe the state write. DbcFlightRenderHook now gates that legacy prone
     * pair by the physical sprint/run key: default DBC flight suppresses it for Epic
     * creative-flight presentation, while sprint-only plank keeps translate/rotate
     * and y=2 head compensation paired.
     */
    private static byte[] hookJbraFlightTransform(byte[] basicClass) {
        try {
            ClassReader cr=new ClassReader(basicClass);
            ClassNode cn=new ClassNode(Opcodes.ASM5);
            cr.accept(cn,0);
            int rotates=0,translates=0,blocks=0,stateCallbacks=0;
            for(MethodNode mn:cn.methods) {
                if(mn.desc==null||!mn.desc.endsWith(";F)V"))continue;
                AbstractInsnNode[] nodes=mn.instructions.toArray();
                for(int i=0;i<nodes.length;i++) {
                    AbstractInsnNode n=nodes[i];
                    if(!(n instanceof FieldInsnNode))continue;
                    FieldInsnNode f=(FieldInsnNode)n;
                    if(f.getOpcode()!=Opcodes.PUTSTATIC||!"JinRyuu/JBRA/ModelBipedDBC".equals(f.owner)||!"y".equals(f.name)||!"I".equals(f.desc))continue;
                    AbstractInsnNode pv=previousMeaningful(n.getPrevious());
                    if(pv==null||pv.getOpcode()!=Opcodes.ICONST_2)continue;
                    blocks++;
                    // The original y=2 write remains authoritative DBC flight evidence.
                    // The hook decides whether that state stays paired with native prone
                    // presentation (sprint/run key held) or is normalized for normal
                    // Epic creative flight. Keep this callback immediately after the store.
                    InsnList afterState=new InsnList();
                    afterState.add(new VarInsnNode(Opcodes.ALOAD,1));
                    afterState.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FLIGHT_HOOK_OWNER,"afterFlightStateWrite","(Ljava/lang/Object;)V",false));
                    mn.instructions.insert(n,afterState);
                    stateCallbacks++;
                    // Stay inside this y-state basic region: the previous ModelBipedDBC.y
                    // write belongs to the UI/KO branch and must never be redirected.
                    AbstractInsnNode q=n.getPrevious();int guard=0;
                    while(q!=null&&guard++<90) {
                        if(q instanceof FieldInsnNode) {
                            FieldInsnNode pf=(FieldInsnNode)q;
                            if(pf.getOpcode()==Opcodes.PUTSTATIC&&"JinRyuu/JBRA/ModelBipedDBC".equals(pf.owner)&&"y".equals(pf.name))break;
                        }
                        if(q instanceof MethodInsnNode) {
                            MethodInsnNode call=(MethodInsnNode)q;
                            if(isFlightTranslateCall(call)&&!FLIGHT_HOOK_OWNER.equals(call.owner)) {
                                mn.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,1));
                                call.owner=FLIGHT_HOOK_OWNER;call.name="translate";call.desc="(FFFLjava/lang/Object;)V";call.setOpcode(Opcodes.INVOKESTATIC);call.itf=false;
                                translates++;
                            } else if(isFlightRotateCall(call)&&!FLIGHT_HOOK_OWNER.equals(call.owner)) {
                                mn.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,1));
                                call.owner=FLIGHT_HOOK_OWNER;call.name="rotate";call.desc="(FFFFLjava/lang/Object;)V";call.setOpcode(Opcodes.INVOKESTATIC);call.itf=false;
                                rotates++;
                            }
                        }
                        q=q.getPrevious();
                    }
                }
            }
            if(blocks==0||rotates<1||translates<1||stateCallbacks<1) {
                System.clearProperty("epicfight1710.jbraflightstylehook");
                System.err.println("[EpicFight1710/Core] RenderPlayerJBRA found but the native y=2 flight transform was not matched safely (blocks="+blocks+", rotate="+rotates+", translate="+translates+").");
                return basicClass;
            }
            ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.setProperty("epicfight1710.jbraflightstylehook","true");
            System.out.println("[EpicFight1710/Core] JBRA y=2 flight presentation hook installed ("+rotates+" rotate + "+translates+" translate + "+stateCallbacks+" post-state callback(s)); fast prone and slow-hover head state are separated without changing flight evidence.");
            return cw.toByteArray();
        } catch(Throwable t) {
            System.clearProperty("epicfight1710.jbraflightstylehook");
            System.err.println("[EpicFight1710/Core] Could not instrument JBRA flight transform; native renderer preserved.");
            t.printStackTrace();
            return basicClass;
        }
    }

    private static boolean isFlightTranslateCall(MethodInsnNode m) {
        if(m==null||!"(FFF)V".equals(m.desc)||m.name==null)return false;
        String n=m.name.toLowerCase(java.util.Locale.ROOT),o=m.owner==null?"":m.owner.toLowerCase(java.util.Locale.ROOT);
        return n.indexOf("translate")>=0&&(o.indexOf("gl")>=0||o.indexOf("render")>=0);
    }

    private static boolean isFlightRotateCall(MethodInsnNode m) {
        if(m==null||!"(FFFF)V".equals(m.desc)||m.name==null)return false;
        String n=m.name.toLowerCase(java.util.Locale.ROOT),o=m.owner==null?"":m.owner.toLowerCase(java.util.Locale.ROOT);
        return n.indexOf("rotate")>=0&&(o.indexOf("gl")>=0||o.indexOf("render")>=0);
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode n) {
        while(n!=null&&n.getOpcode()<0)n=n.getPrevious();
        return n;
    }

    /**
     * Replaces ModelRenderer.render(float) INVOKEVIRTUAL instructions inside the
     * exact JRMCore body renderer with a static wrapper.  The operand stack before
     * the original call is [part, scale]; adding one LDC gives
     * [part, scale, originalMethodName], exactly matching our bridge descriptor.
     * No new branch is introduced into JRMCore bytecode.
     */
    private static byte[] hookJrmBodyCallsites(byte[] basicClass) {
        try {
            ClassReader cr=new ClassReader(basicClass);
            ClassNode cn=new ClassNode(Opcodes.ASM5);
            cr.accept(cn,0);
            int replaced=0;
            int methods=0;
            for(MethodNode mn:cn.methods) {
                int inMethod=0;
                for(AbstractInsnNode n:mn.instructions.toArray()) {
                    if(!(n instanceof MethodInsnNode))continue;
                    MethodInsnNode call=(MethodInsnNode)n;
                    if(!isVanillaModelRendererDrawCall(call))continue;
                    String originalName=call.name;
                    mn.instructions.insertBefore(call,new LdcInsnNode(originalName));
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner=CALLSITE_OWNER;
                    call.name="render";
                    call.desc="(Ljava/lang/Object;FLjava/lang/String;)V";
                    call.itf=false;
                    replaced++;inMethod++;
                }
                if(inMethod>0)methods++;
            }
            if(replaced==0) {
                System.clearProperty("epicfight1710.jrmrenderbridge");
                System.err.println("[EpicFight1710/Core] JRMCore ModelBipedBody found, but no ModelRenderer draw call sites matched.");
                return basicClass;
            }
            ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.setProperty("epicfight1710.jrmrenderbridge","true");
            System.setProperty("epicfight1710.jrmrenderbridge.count",String.valueOf(replaced));
            System.out.println("[EpicFight1710/Core] JRMCore ModelBipedBody direct render bridge installed: "+replaced+" ModelRenderer call site(s) across "+methods+" method(s). This path survives later ModelRenderer transformers.");
            return cw.toByteArray();
        } catch(Throwable t) {
            System.clearProperty("epicfight1710.jrmrenderbridge");
            System.err.println("[EpicFight1710/Core] Could not instrument JRMCore ModelBipedBody call sites; original class preserved.");
            t.printStackTrace();
            return basicClass;
        }
    }

    /**
     * Directly instruments JBRA 1.6.52's own first-person method. This mirrors the
     * proven JRMCore body-callsite strategy: later Xaero/Angelica transformers may
     * rewrite vanilla ModelRenderer, but they cannot erase the call from
     * RenderPlayerJBRA.func_82441_a to our stable wrapper.
     */
    private static byte[] hookJbraFirstPersonCallsites(byte[] basicClass) {
        try {
            ClassReader cr=new ClassReader(basicClass);
            ClassNode cn=new ClassNode(Opcodes.ASM5);
            cr.accept(cn,0);
            int replaced=0;
            int returnHooks=0;
            for(MethodNode mn:cn.methods) {
                if(!isJbraFirstPersonMethod(mn))continue;
                for(AbstractInsnNode n:mn.instructions.toArray()) {
                    if(n instanceof MethodInsnNode) {
                        MethodInsnNode call=(MethodInsnNode)n;
                        if(isVanillaModelRendererDrawCall(call)) {
                            String originalName=call.name;
                            mn.instructions.insertBefore(call,new LdcInsnNode(originalName));
                            call.setOpcode(Opcodes.INVOKESTATIC);
                            call.owner=CALLSITE_OWNER;
                            call.name="render";
                            call.desc="(Ljava/lang/Object;FLjava/lang/String;)V";
                            call.itf=false;
                            replaced++;
                        }
                    }
                    if(n.getOpcode()==Opcodes.RETURN) {
                        mn.instructions.insertBefore(n,new MethodInsnNode(Opcodes.INVOKESTATIC,NATIVE_CTX_OWNER,"onJbraFirstPersonReturn","()V",false));
                        returnHooks++;
                    }
                }
            }
            if(replaced==0||returnHooks==0) {
                System.clearProperty("epicfight1710.jbrafirstpersonbridge");
                System.err.println("[EpicFight1710/Core] JBRA first-person method found, but its draw/RETURN lifecycle could not be matched safely (draws="+replaced+", returns="+returnHooks+").");
                return basicClass;
            }
            ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.setProperty("epicfight1710.jbrafirstpersonbridge","true");
            System.setProperty("epicfight1710.jbrafirstpersonbridge.count",String.valueOf(replaced));
            System.setProperty("epicfight1710.jbrafirstpersonreturn.count",String.valueOf(returnHooks));
            System.out.println("[EpicFight1710/Core] JBRA first-person direct render bridge installed: "+replaced+" ModelRenderer draw call site(s) + "+returnHooks+" exact RETURN lifecycle hook(s) in func_82441_a. This path survives later ModelRenderer transformers.");
            return cw.toByteArray();
        } catch(Throwable t) {
            System.clearProperty("epicfight1710.jbrafirstpersonbridge");
            System.err.println("[EpicFight1710/Core] Could not instrument JBRA first-person render call sites; original class preserved.");
            t.printStackTrace();
            return basicClass;
        }
    }

    private static boolean isJbraFirstPersonMethod(MethodNode mn) {
        if(mn==null||mn.desc==null)return false;
        boolean name="func_82441_a".equals(mn.name)||"renderFirstPersonArm".equals(mn.name);
        if(!name)return false;
        return "(Lnet/minecraft/entity/player/EntityPlayer;)V".equals(mn.desc)
                || "(Lyz;)V".equals(mn.desc);
    }

    /**
     * Port of Epic Fight RenderItemBase's Tool_R attachment point into JBRA's
     * existing 1.7.10 equipped-item renderer. Only the two RA.postRender calls
     * that begin JBRA's held-item block are redirected; all native item-type
     * transforms/render passes remain untouched after the new mount matrix.
     */
    private static byte[] hookJbraHeldItemMount(byte[] basicClass) {
        try {
            ClassReader cr=new ClassReader(basicClass);
            ClassNode cn=new ClassNode(Opcodes.ASM5);
            cr.accept(cn,0);
            int replaced=0;
            for(MethodNode mn:cn.methods) {
                if(!isJbraSpecialsMethod(mn))continue;
                for(AbstractInsnNode n:mn.instructions.toArray()) {
                    if(!(n instanceof MethodInsnNode))continue;
                    MethodInsnNode call=(MethodInsnNode)n;
                    if(!isModelRendererPostRender(call)||!hasRightArmFieldBefore(call,5))continue;
                    String originalName=call.name;
                    mn.instructions.insertBefore(call,new LdcInsnNode(originalName));
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner=ITEM_MOUNT_OWNER;
                    call.name="postRender";
                    call.desc="(Ljava/lang/Object;FLjava/lang/String;)V";
                    call.itf=false;
                    replaced++;
                }
            }
            if(replaced==0) {
                System.clearProperty("epicfight1710.jbraitemmountbridge");
                System.err.println("[EpicFight1710/Core] JBRA equipped-item method found, but no RA.postRender item-mount call sites matched.");
                return basicClass;
            }
            ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            System.setProperty("epicfight1710.jbraitemmountbridge","true");
            System.setProperty("epicfight1710.jbraitemmountbridge.count",String.valueOf(replaced));
            System.out.println("[EpicFight1710/Core] JBRA Tool_R held-item mount bridge installed: "+replaced+" RA.postRender call site(s); native 1.7.10 item rendering remains downstream.");
            return cw.toByteArray();
        } catch(Throwable t) {
            System.clearProperty("epicfight1710.jbraitemmountbridge");
            System.err.println("[EpicFight1710/Core] Could not instrument JBRA held-item mount; original class preserved.");
            t.printStackTrace();
            return basicClass;
        }
    }

    private static boolean isJbraSpecialsMethod(MethodNode mn) {
        if(mn==null||mn.desc==null)return false;
        boolean name="func_77029_c".equals(mn.name)||"renderEquippedItems".equals(mn.name);
        if(!name)return false;
        return mn.desc.indexOf("AbstractClientPlayer")>=0&&mn.desc.endsWith(";F)V");
    }

    private static boolean isModelRendererPostRender(MethodInsnNode m) {
        if(m==null||!"(F)V".equals(m.desc))return false;
        String owner=m.owner==null?"":m.owner;
        if(!("net/minecraft/client/model/ModelRenderer".equals(owner)||"bix".equals(owner)||owner.endsWith("/ModelRenderer")))return false;
        return "func_78794_c".equals(m.name)||"postRender".equals(m.name);
    }

    private static boolean hasRightArmFieldBefore(AbstractInsnNode call,int meaningfulBudget) {
        AbstractInsnNode q=call==null?null:call.getPrevious();
        int seen=0;
        while(q!=null&&seen++<meaningfulBudget) {
            if(q.getOpcode()<0){q=q.getPrevious();seen--;continue;}
            if(q instanceof FieldInsnNode) {
                FieldInsnNode f=(FieldInsnNode)q;
                if(f.getOpcode()==Opcodes.GETFIELD&&"JinRyuu/JBRA/ModelBipedDBC".equals(f.owner)&&"RA".equals(f.name))return true;
            }
            q=q.getPrevious();
        }
        return false;
    }

    static boolean isVanillaModelRendererDrawCall(MethodInsnNode m) {
        if(m==null || !"(F)V".equals(m.desc))return false;
        String owner=m.owner==null?"":m.owner;
        boolean modelOwner="net/minecraft/client/model/ModelRenderer".equals(owner)
                || "bix".equals(owner)
                || owner.endsWith("/ModelRenderer");
        if(!modelOwner)return false;
        String n=m.name;
        return "func_78785_a".equals(n)||"render".equals(n)||"a".equals(n);
    }

    private static boolean isRenderMethod(MethodNode mn) {
        if(!"(F)V".equals(mn.desc))return false;
        for(AbstractInsnNode n:mn.instructions.toArray()) if(n instanceof MethodInsnNode) {
            MethodInsnNode m=(MethodInsnNode)n;
            if(isDisplayListCall(m))return true;
        }
        String n=mn.name;
        return "func_78785_a".equals(n)||"render".equals(n)||"func_78791_b".equals(n)||"renderWithRotation".equals(n);
    }

    private static boolean isDisplayListCall(MethodInsnNode m) {
        if(m==null || m.name==null)return false;
        String n=m.name.toLowerCase(java.util.Locale.ROOT);
        if(("glcalllist".equals(n)||n.endsWith("calllist")) && "(I)V".equals(m.desc))return true;
        String owner=m.owner==null?"":m.owner.toLowerCase(java.util.Locale.ROOT);
        return "(I)V".equals(m.desc) && n.contains("call") && n.contains("list")
                && (owner.contains("gl")||owner.contains("render"));
    }

    private static String fingerprint(MethodNode mn) {
        StringBuilder b=new StringBuilder(mn.name).append(mn.desc).append(" calls=");
        int shown=0;
        for(AbstractInsnNode n:mn.instructions.toArray()) if(n instanceof MethodInsnNode) {
            MethodInsnNode m=(MethodInsnNode)n;
            if(shown++>0)b.append(';');
            b.append(m.owner).append('.').append(m.name).append(m.desc);
            if(shown>=8){ b.append(";..."); break; }
        }
        if(shown==0)b.append("<none>");
        return b.toString();
    }

    private static boolean alreadyHooked(MethodNode mn) {
        for(AbstractInsnNode n:mn.instructions.toArray()) if(n instanceof MethodInsnNode) {
            MethodInsnNode m=(MethodInsnNode)n;
            if(HOOK_OWNER.equals(m.owner)&&"render".equals(m.name))return true;
        }
        return false;
    }
}
