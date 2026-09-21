package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.*;
import com.nicolas.epicfight1710.combat.*;
import java.lang.reflect.*;
import java.util.*;

/** LOCAL renderer equivalence only. No fixture of native DBC forms/preRender is
 * available; synthetic matrices do not establish native world/fast-flight parity.
 */
public final class DbcSpatialMathTest {
    private static int checks;
    private static final Object oldRenderer=BaselineJbraWeightedPartRenderer.INSTANCE;
    private static final Object renderer=JbraWeightedPartRenderer.INSTANCE;
    private static void check(boolean value,String msg){checks++;if(!value)throw new AssertionError(msg);}
    private static void bits(float[] a,float[] b,String msg){
        check(a.length==b.length,msg);for(int i=0;i<a.length;i++)check(Float.floatToIntBits(a[i])==Float.floatToIntBits(b[i]),msg+"["+i+"] "+a[i]+" != "+b[i]);
    }
    private static Object call(Object target,String name,Object... args)throws Exception{
        Class<?> c=target instanceof Class?(Class<?>)target:target.getClass();
        for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterTypes().length==args.length){
            m.setAccessible(true);return m.invoke(target instanceof Class?null:target,args);
        }
        throw new NoSuchMethodException(name);
    }
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    public static final class Part {
        public float rotationPointX,rotationPointY,rotationPointZ,offsetX,offsetY,offsetZ,rotateAngleX,rotateAngleY,rotateAngleZ;
        public boolean showModel=true,isHidden;
        public List<Object> cubeList=new ArrayList<Object>(),childModels=new ArrayList<Object>();
    }
    private static float[] treeTransform(Object r,float scale,float yaw)throws Exception{
        return (float[])call(r,"transform",.125F*scale,-.25F*scale,.375F*scale,.3F,yaw,-.2F);
    }
    private static void bindAndScale()throws Exception{
        for(float scale:new float[]{.03125F,.0625F,.09375F,.125F})for(int yaw:new int[]{0,90,180,-90}){
            float angle=(float)Math.toRadians(yaw);
            float[] before=treeTransform(oldRenderer,scale,angle);
            bits(before,treeTransform(renderer,scale,angle),"real renderer node transform");
            bits(before,DbcSpatialMath.nodeTransform(.125F*scale,-.25F*scale,.375F*scale,.3F,angle,-.2F),"pure node transform");
            for(NativeJbraSkinContext.PartRole role:new NativeJbraSkinContext.PartRole[]{
                NativeJbraSkinContext.PartRole.RIGHT_ARM,NativeJbraSkinContext.PartRole.LEFT_ARM,
                NativeJbraSkinContext.PartRole.RIGHT_LEG,NativeJbraSkinContext.PartRole.LEFT_LEG}){
                for(boolean child:new boolean[]{false,true}){
                    Part p=new Part();p.rotationPointX=-5;p.rotationPointY=2;p.offsetX=.03125F;
                    if(child)p.childModels.add(new Part());
                    Object oldMesh=call(oldRenderer,"compileTree",p,role,scale),newMesh=call(renderer,"compileTree",p,role,scale);
                    float[] oldPivot={(Float)field(oldMesh,"pivotX"),(Float)field(oldMesh,"pivotY"),(Float)field(oldMesh,"pivotZ")};
                    float[] newPivot={(Float)field(newMesh,"pivotX"),(Float)field(newMesh,"pivotY"),(Float)field(newMesh,"pivotZ")};
                    bits(oldPivot,newPivot,"real compiled neutral pivot");
                }
            }
        }
        for(float x:new float[]{-0.0F,0,.25F,-.25F,1.5F,-1.5F,100}){
            float[] converted=new float[3];DbcSpatialMath.convertPoint(x,x,x,converted);
            bits(new float[]{-x,1.5F-x,x},converted,"vertex basis expression");
        }
        System.out.println("PASS local bind/pivot extraction; four model pixel scales, yaw 0/90/180/-90; synthetic ModelRenderer trees");
    }
    private static float[] skinPoint(Object r,int joint,float[][] skin)throws Exception{
        Class<?> cv=Class.forName(r.getClass().getName()+"$CompiledVertex");
        Constructor<?> ctor=cv.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object vertex=ctor.newInstance(.1F,1.1F,.2F,0F,0F,new int[]{joint},new float[]{1});
        float[] out=new float[3];
        call(r,"skin",vertex,out,NativeJbraSkinContext.PartRole.RIGHT_ARM,skin,
            -1,null,-1,null,-1,null,-1,null,-1,null,-1,null);
        return out;
    }
    private static void poses()throws Exception{
        RuntimeAssets.load();SkeletonMesh mesh=RuntimeAssets.MESH;ClipLibrary clips=RuntimeAssets.CLIPS;
        CombatPoseState state=new CombatPoseState();Object player=new Object(),world=new Object();state.observeContext(player,world,1);
        CombatPoseEvaluator e=new CombatPoseEvaluator(mesh,clips);int samples=0;
        BaselineHeadMath headOracle=new BaselineHeadMath();
        for(CombatPoseEvaluator.Profile profile:CombatPoseEvaluator.Profile.values()){
            CombatPoseEvaluator.Plan plan=new CombatPoseEvaluator.Plan(profile,1,null,true);
            for(String clip:clips.names()){
                state.startAction(clip);CombatPoseState.Frame frame=state.capture(0,clips.get(clip).duration,1);
                for(float t:new float[]{0,clips.get(clip).duration*.5F,clips.get(clip).duration}){
                    CombatPoseSnapshot s=e.evaluate(frame,plan,t);
                    for(String name:new String[]{"Hand_R","Hand_L","Tool_R","Tool_L"}){
                        int j=Arrays.asList(mesh.jointName).indexOf(name);float[] g=new float[16];check(s.copyGlobalJointMatrix(j,g),"valid joint");
                        float[] old=new float[16],now=new float[16],pure=new float[16];
                        BaselineWeaponItemMountHook.epicToJbra(g,old);WeaponItemMountHook.epicToJbra(g,now);DbcSpatialMath.jointFrameToModel(g,pure);
                        bits(old,now,"mount basis delegation "+name);bits(old,pure,"pure global joint basis "+name);
                        float[] alias=g.clone();DbcSpatialMath.jointFrameToModel(alias,alias);bits(old,alias,"in-place conversion");
                        // Item correction/cancellation must remain exactly where it was.
                        BaselineWeaponItemMountHook.composeLegacyMountForAudit(g,old);WeaponItemMountHook.composeLegacyMountForAudit(g,now);bits(old,now,"complete legacy mount math");
                        float[] skin=new float[16];Mat4.mul(g,mesh.invBindGlobal[j],skin);
                        headOracle.convert(skin,new float[16],old);DbcSpatialMath.deformationToModel(skin,new float[16],now);bits(old,now,"native head conjugation");
                        float[][] matrices=new float[mesh.jointCount][16];for(int k=0;k<matrices.length;k++)Mat4.identity(matrices[k]);matrices[j]=skin;
                        bits(skinPoint(oldRenderer,j,matrices),skinPoint(renderer,j,matrices),"real renderer skin/emission basis");
                        // Existing pure socket kernel: preserve limits and aliasing.
                        float[] parent=DbcSpatialMath.nodeTransform(.01F,-.02F,.03F,.3F,-.4F,.1F);
                        BaselineDbcRetargetMath.socketCorrectMatrix(parent,skin,.3125F,1.375F,0,.25F,old);
                        DbcRetargetMath.socketCorrectMatrix(parent,skin,.3125F,1.375F,0,.25F,now);bits(old,now,"shared socket kernel");
                    }
                    samples++;
                }
            }
        }
        // Pure output uses the captured source, not the mutable visual cache. Test
        // the actual renderer math 0/1/10 times without pretending these are GL draws.
        state.startAction("creative_fly_forward");CombatPoseState.Frame f=state.capture(0,.2F,3);
        CombatPoseEvaluator.Plan p=new CombatPoseEvaluator.Plan(CombatPoseEvaluator.Profile.DBC_ARMATURE,1,null,true);
        CombatPoseSnapshot snapshot=e.evaluate(f,p,.1F);float[] global=new float[16];snapshot.copyGlobalJointMatrix(13,global);
        float[] expected=new float[16];DbcSpatialMath.jointFrameToModel(global,expected);
        for(int renderMathCalls:new int[]{0,1,10}){
            for(int n=0;n<renderMathCalls;n++)treeTransform(renderer,1,n);
            float[] actual=new float[16];DbcSpatialMath.jointFrameToModel(global,actual);bits(expected,actual,"render-math call independence");
        }
        state.observeContext(new Object(),world,1);check(!snapshot.copyGlobalJointMatrix(13,global),"player invalidates spatial input");
        state.startAction("creative_fly_forward");snapshot=e.evaluate(state.capture(0,.2F,3),p,.1F);
        state.observeContext(player,new Object(),1);check(!snapshot.copyGlobalJointMatrix(13,global),"world invalidates spatial input");
        System.out.println("PASS "+samples+" source/DBC poses, Hand_R/L and Tool_R/L, unchanged mount/head/socket kernels and renderer skin method");
        System.out.println("PASS local transform independence from 0/1/10 renderer-math calls; player/world invalidation");
    }
    public static void main(String[] args)throws Exception{
        bindAndScale();poses();
        // Phase 1 uses actual compose calls and switched camera fixtures; re-run
        // here against the real newly compiled render classes (not its renderer double).
        CombatPoseSnapshotTest.main(new String[0]);
        System.out.println("PASS DbcSpatialMathTest: "+checks+" assertions; native world/forms/fast-flight validation BLOCKED (no authoritative fixtures)");
    }
}
