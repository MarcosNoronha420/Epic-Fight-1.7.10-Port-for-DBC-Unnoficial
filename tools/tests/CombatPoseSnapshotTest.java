package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.*;
import com.nicolas.epicfight1710.anim.runtime.*;
import com.nicolas.epicfight1710.combat.*;
import java.lang.reflect.*;
import java.util.Arrays;

/** Real core classes with test-only native boundaries; no Minecraft or GL context.
 * Render counts below mean actual LayeredAnimator compose calls, not GPU draws.
 */
public final class CombatPoseSnapshotTest {
    private static SkeletonMesh mesh;
    private static ClipLibrary clips;
    private static AnimationCatalog catalog;
    private static final FakeMinecraft minecraft=new FakeMinecraft();
    private static int assertions;
    public static final class FakeSettings { public int thirdPersonView; }
    public static final class FakeMinecraft {
        public FakePlayer thePlayer=new FakePlayer();
        public FakeSettings gameSettings=new FakeSettings();
    }
    public static final class FakePlayer {
        public Object worldObj=new Object();
        public double posX,posY,posZ,prevPosX,prevPosY,prevPosZ,motionX,motionY,motionZ;
        public float rotationYaw,prevRotationYaw,rotationPitch,prevRotationPitch,renderYawOffset,prevRenderYawOffset;
        public boolean onGround=true;
        public int ticksExisted;
    }
    private static void check(boolean v,String msg){assertions++;if(!v)throw new AssertionError(msg);}
    private static void near(double a,double b,String msg){check(!Double.isNaN(a)&&!Double.isInfinite(a)&&Math.abs(a-b)<2e-5,msg+": "+a+" / "+b);}
    private static Object get(Object o,String name)throws Exception{
        Field f=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredField(name);f.setAccessible(true);
        return f.get(o instanceof Class?null:o);
    }
    private static void set(Object o,String name,Object v)throws Exception{
        Field f=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredField(name);f.setAccessible(true);f.set(o instanceof Class?null:o,v);
    }
    private static void compose(Object animator,float partial)throws Exception{
        Method m=animator.getClass().getDeclaredMethod("compose",Object.class,float.class);m.setAccessible(true);m.invoke(animator,minecraft.thePlayer,partial);
    }
    private static float[][] globals(CombatPoseSnapshot s){
        check(s.isValid(),"valid snapshot: "+s.invalidReason());float[][] out=new float[s.jointCount()][16];
        for(int j=0;j<out.length;j++)check(s.copyGlobalJointMatrix(j,out[j]),"read global");return out;
    }
    private static void equal(float[][] a,float[][] b,String why){
        check(a.length==b.length,why+" length");for(int j=0;j<a.length;j++)for(int k=0;k<16;k++)
            check(Float.floatToIntBits(a[j][k])==Float.floatToIntBits(b[j][k]),why+" joint "+j+" entry "+k);
    }
    private static CombatPoseState state(String clip){
        CombatPoseState s=new CombatPoseState();s.observeContext(minecraft.thePlayer,minecraft.thePlayer.worldObj,7);s.startAction(clip);return s;
    }
    private static CombatPoseEvaluator.Plan plan(CombatPoseEvaluator.Profile profile){
        return new CombatPoseEvaluator.Plan(profile,1,JointMask.basicAttack(mesh),true,
            new CombatPoseEvaluator.Layer("walk",.2F,.3F,true,1,null,false));
    }
    private static void init()throws Exception{
        RuntimeAssets.load();mesh=RuntimeAssets.MESH;clips=RuntimeAssets.CLIPS;catalog=new AnimationCatalog(mesh,clips);
        // Only inject the reflection boundary. Compat/controller/guard are real.
        set(Compat.class,"initialized",true);set(Compat.class,"minecraft",minecraft);
        set(Compat.class,"mcPlayer",FakeMinecraft.class.getField("thePlayer"));
        set(Compat.class,"mcSettings",FakeMinecraft.class.getField("gameSettings"));
        Method init=Compat.class.getDeclaredMethod("initPlayer",Object.class);init.setAccessible(true);init.invoke(null,minecraft.thePlayer);
        check(Compat.player()==minecraft.thePlayer,"actual Compat player bridge");
        check(Compat.worldIdentity(minecraft.thePlayer)==minecraft.thePlayer.worldObj,"world identity bridge");
    }
    private static void isolation()throws Exception{
        CombatPoseState state=state("fist_auto1");CombatPoseState.Frame frame=state.capture(.1F,.2F,10);
        CombatPoseEvaluator evaluator=new CombatPoseEvaluator(mesh,clips);
        CombatPoseEvaluator.Plan generic=plan(CombatPoseEvaluator.Profile.SOURCE_ARMATURE),dbc=plan(CombatPoseEvaluator.Profile.DBC_ARMATURE);
        CombatPoseSnapshot noRender=evaluator.evaluate(frame,generic,.15F);
        float[][] expected=globals(noRender);check(evaluator.completeEvaluations()==1,"no render required");
        float[][] expectedDbc=globals(evaluator.evaluate(frame,dbc,.15F));
        LayeredAnimator visual=new LayeredAnimator(mesh,clips);
        AnimationLayer base=(AnimationLayer)get(visual,"baseLayer"),action=(AnimationLayer)get(visual,"actionLayer");
        base.play(catalog.get("walk"),.2F,0);action.play(catalog.get("fist_auto1"),.1F,0);
        base.tick(.05F,1);action.tick(.05F,1);set(visual,"currentMotion",LivingMotion.WALK);
        CombatGuardRuntime guard=(CombatGuardRuntime)get(CombatController.INSTANCE,"guardRuntime");
        set(guard,"pendingReaction","guard_dualsword_hit");
        AnimationPlayer player=(AnimationPlayer)get(action,"player");
        for(int renders:new int[]{0,1,10})for(int camera:new int[]{0,1,2}){
            Compat.setThirdPerson(camera);check(Compat.getThirdPerson()==camera,"actual camera boundary");
            for(int r=0;r<renders;r++){
                set(visual,"dbcJbraProfile",r%2==0);compose(visual,(r+1F)/(renders+1F));
            }
            float previous=player.prevElapsed(),current=player.elapsed();Object fresh=get(action,"freshEntry");
            float[][] visualBefore=copy(visual.globalMatrices());int serial=visual.poseSerial();
            // Force a cache MISS as well as hits after arbitrary presentation work.
            equal(expected,globals(new CombatPoseEvaluator(mesh,clips).evaluate(frame,generic,.15F)),"render/camera independence fresh evaluation");
            equal(expected,globals(evaluator.evaluate(frame,generic,.15F)),"render/camera independence cached");
            equal(expectedDbc,globals(new CombatPoseEvaluator(mesh,clips).evaluate(frame,dbc,.15F)),"DBC render/camera independence fresh evaluation");
            equal(expectedDbc,globals(evaluator.evaluate(frame,dbc,.15F)),"DBC render/camera independence cached");
            check(player.elapsed()==current&&player.prevElapsed()==previous,"no AnimationPlayer advance");
            check(get(action,"freshEntry").equals(fresh),"freshEntry preserved");
            check(visual.poseSerial()==serial,"visual serial preserved");equal(visualBefore,visual.globalMatrices(),"visual cache preserved");
            check("guard_dualsword_hit".equals(get(guard,"pendingReaction")),"guard reaction untouched");
        }
        check("guard_dualsword_hit".equals(guard.pollReaction())&&guard.pollReaction()==null,"real guard consumes once after evaluation");
        CombatPoseSnapshot dbcResult=evaluator.evaluate(frame,dbc,.15F);
        check(!Arrays.deepEquals(expected,globals(dbcResult)),"fixture distinguishes generic/DBC profiles");
        for(int target=0;target<100;target++){
            check(evaluator.evaluate(frame,generic,.15F)==noRender,"one shared generic sample per target");
            check(evaluator.evaluate(frame,dbc,.15F)==dbcResult,"interleaved DBC cache identity");
        }
        check(evaluator.completeEvaluations()==2,"100 targets must not repeat complete pose evaluation");
        check(evaluator.cacheHits()>=200,"cache hit counter");
        float[] changed=new float[16];noRender.copyGlobalJointMatrix(0,changed);changed[0]=99;
        equal(expected,globals(noRender),"snapshot buffers cannot be mutated by caller");
        check(!evaluator.evaluate(frame,generic,Float.NaN).isValid(),"NaN sample rejected");
        check(!evaluator.evaluate(frame,generic,.3F).isValid(),"outside interval rejected");
        CombatPoseEvaluator bounded=new CombatPoseEvaluator(mesh,clips);
        for(int n=0;n<128;n++)check(bounded.evaluate(frame,generic,.1F+.1F*n/128).isValid(),"sample budget entry");
        check(!bounded.evaluate(frame,generic,.2F).isValid(),"sample budget fails explicitly");
        check(bounded.evaluate(frame,generic,.1F).isValid()&&bounded.completeEvaluations()==128,"no eviction/recomputation by target");
        System.out.println("PASS render counts 0/1/10, cameras 0/1/2, clocks/layers/guard/cache isolation, 100 targets: 2 complete poses (generic + DBC)");
    }
    private static float[][] copy(float[][] input){float[][] out=new float[input.length][];for(int j=0;j<out.length;j++)out[j]=input[j].clone();return out;}
    private static void lifecycle()throws Exception{
        CombatPoseState s=state("fist_auto1");CombatPoseEvaluator e=new CombatPoseEvaluator(mesh,clips);
        CombatPoseEvaluator.Plan p=plan(CombatPoseEvaluator.Profile.SOURCE_ARMATURE);
        CombatPoseState.Frame f=s.capture(.1F,.2F,3);CombatPoseSnapshot old=e.evaluate(f,p,.15F);
        check(s.capture(.1F,.2F,3)==f,"identical capture reused");
        s.startAction("fist_auto1");CombatPoseState.Frame next=s.capture(0,.05F,3);
        check(next.actionSerial==f.actionSerial+1,"same clip distinct execution serial");check(!old.isValid(),"old execution invalidated");
        check(!old.copyGlobalJointMatrix(0,new float[16]),"expired read rejected");
        s.observeContext(minecraft.thePlayer,minecraft.thePlayer.worldObj,8);check(!next.isValid(),"tick invalidates");
        next=s.capture(.05F,.1F,3);s.capture(.05F,.1F,4);check(!next.isValid(),"profile revision invalidates");
        next=s.capture(.05F,.1F,4);s.capture(.1F,.15F,4);check(!next.isValid(),"age interval invalidates");
        next=s.capture(.1F,.15F,4);s.observeContext(new FakePlayer(),minecraft.thePlayer.worldObj,8);
        check(!next.isValid()&&s.capture(0,0,4)==null,"player swap invalidates and requires new action");
        s.startAction("fist_auto1");next=s.capture(0,0,4);s.observeContext(minecraft.thePlayer,new Object(),8);
        check(!next.isValid(),"world swap invalidates");s.startAction("fist_auto1");next=s.capture(0,0,4);s.clearAction();check(!next.isValid(),"clear invalidates");
        // Exercise actual action lifecycle integration, not only its metadata class.
        Class<?> type=Class.forName("com.nicolas.epicfight1710.client.CombatController$ActionType");
        Method start=CombatController.class.getDeclaredMethod("startClip",String.class,type,boolean.class);start.setAccessible(true);
        Object attack=type.getEnumConstants()[1];start.invoke(CombatController.INSTANCE,"fist_auto1",attack,false);
        CombatPoseState.Frame first=CombatController.INSTANCE.captureCombatPoseFrame(9);
        start.invoke(CombatController.INSTANCE,"fist_auto1",attack,false);
        CombatPoseState.Frame second=CombatController.INSTANCE.captureCombatPoseFrame(9);
        check(first!=null&&second.actionSerial>first.actionSerial&&!first.isValid(),"controller same clip execution integration");
        minecraft.thePlayer.worldObj=new Object();check(CombatController.INSTANCE.captureCombatPoseFrame(9)==null&&!second.isValid(),"controller detects world swap at capture");
        System.out.println("PASS action serial, player/world/tick/age/profile lifecycle and real controller bridge");
    }
    private static void hierarchyAndWorld(){
        CombatPoseState s=state("fist_auto1");CombatPoseEvaluator e=new CombatPoseEvaluator(mesh,clips);
        CombatPoseEvaluator.Plan p=new CombatPoseEvaluator.Plan(CombatPoseEvaluator.Profile.SOURCE_ARMATURE,1,null,false);
        int poses=0;
        for(String name:clips.names()){
            s.startAction(name);float duration=clips.get(name).duration;CombatPoseState.Frame f=s.capture(0,duration,1);
            for(float time:new float[]{0,duration*.25F,duration*.5F,duration}){
                CombatPoseSnapshot snap=e.evaluate(f,p,time);float[][] g=globals(snap);
                for(int j=0;j<mesh.jointCount;j++){
                    float[] local=new float[16],expected=new float[16];check(snap.copyLocalJointMatrix(j,local),"local matrix");
                    if(mesh.parent[j]<0)Mat4.copy(local,expected);else Mat4.mul(g[mesh.parent[j]],local,expected);
                    for(int k=0;k<16;k++)near(g[j][k],expected[k],"finite hierarchy");
                    // Independent raw-clip path used by the approved golden test.
                    float[] trs=new float[10],delta=new float[16],rawLocal=new float[16];
                    clips.get(name).sampleTRS(j,time,false,trs);Mat4.fromTRS(trs,delta);Mat4.mul(mesh.bindLocal[j],delta,rawLocal);
                    for(int k=0;k<16;k++)near(local[k],rawLocal[k],"authored clip sample");
                }
                poses++;
            }
        }
        s.startAction("fist_auto1");CombatPoseSnapshot snap=e.evaluate(s.capture(0,.2F,1),p,.1F);
        float[][] g=globals(snap),skin=new float[mesh.jointCount][16];ArmaturePoseMath.skinning(mesh,g,skin);
        check(!Arrays.deepEquals(g,skin),"global joint matrices are not skin matrices");
        float[] affine={0,0,2,3, 0,3,0,4, -4,0,0,5, 0,0,0,1};
        ModelToWorld transform=new ModelToWorld(30000000.125,64.5,-30000000.25,affine);
        affine[3]=999; // transform must own its matrix.
        double[] world=new double[16];check(snap.copyWorldJointMatrix(0,transform,world),"world matrix");
        near(world[3],30000003.125+2.0*g[0][11],"world x / double precision");
        near(world[7],68.5+3.0*g[0][7],"world y / scale and pivot");
        near(world[11],-29999995.25-4.0*g[0][3],"world z / rotation");
        for(int k=0;k<3;k++){near(world[k],2.0*g[0][8+k],"world basis X");near(world[4+k],3.0*g[0][4+k],"world basis Y");near(world[8+k],-4.0*g[0][k],"world basis Z");}
        boolean rejected=false;try{new ModelToWorld(0,0,0,new float[16]);}catch(IllegalArgumentException ex){rejected=true;}
        check(rejected,"invalid affine rejected");
        System.out.println("PASS "+poses+" finite combat hierarchies; globals versus skin; explicit affine model-to-world transform");
    }
    private static void extractionParity()throws Exception{
        // Both are the actual full compose methods. Oracle is generated by git show
        // from approved commit, not a rewritten model of the new implementation.
        LayeredAnimator now=new LayeredAnimator(mesh,clips);
        BaselineLayeredAnimator before=new BaselineLayeredAnimator(mesh,clips);
        int samples=0;
        for(boolean dbc:new boolean[]{false,true})for(int camera:new int[]{0,1,2}){
            Compat.setThirdPerson(camera);
            for(String name:clips.names())for(float time:new float[]{0,.1F,.2F}){
                for(Object a:new Object[]{before,now}){
                    set(a,"dbcJbraProfile",dbc);set(a,"currentMotion",LivingMotion.WALK);
                    AnimationLayer base=(AnimationLayer)get(a,"baseLayer"),action=(AnimationLayer)get(a,"actionLayer");
                    base.off();action.off();base.play(catalog.get("walk"),.2F,0);action.play(catalog.get(name),time,0);
                    base.tick(.05F,1);action.tick(.05F,1);compose(a,.5F);
                }
                equal(before.globalMatrices(),now.globalMatrices(),"baseline composed globals "+name);
                equal(before.skinMatrices(),now.skinMatrices(),"baseline composed skin "+name);samples++;
            }
        }
        System.out.println("PASS bit-identical approved-baseline composition + skin: "+samples+" samples, 242 clips, generic/DBC, cameras 0/1/2");
        String[] bases={"walk","creative_fly_forward","creative_fly_backward","walk"};
        LivingMotion[] motions={LivingMotion.WALK,LivingMotion.FLY_FORWARD,LivingMotion.FLY_BACKWARD,LivingMotion.WALK};
        int transitions=0;
        for(int camera:new int[]{0,1,2}){
            Compat.setThirdPerson(camera);minecraft.thePlayer.rotationPitch=35;minecraft.thePlayer.rotationYaw=80;
            for(Object a:new Object[]{before,now}){
                a.getClass().getMethod("reset").invoke(a);set(a,"dbcJbraProfile",true);
                ((AnimationLayer)get(a,"compositeLayer")).play(catalog.get("guard_sword"),0,0);
                ((AnimationLayer)get(a,"reactionLayer")).play(catalog.get("landing"),0,0);
                ((AnimationLayer)get(a,"actionLayer")).play(catalog.get("fist_auto1"),0,0);
            }
            for(int stage=0;stage<bases.length;stage++){
                for(Object a:new Object[]{before,now}){
                    set(a,"currentMotion",motions[stage]);
                    ((AnimationLayer)get(a,"baseLayer")).play(catalog.get(bases[stage]),0,.5F);
                }
                for(int tick=0;tick<5;tick++){
                    for(Object a:new Object[]{before,now})for(String layer:new String[]{"baseLayer","compositeLayer","reactionLayer","actionLayer"})
                        ((AnimationLayer)get(a,layer)).tick(.05F,1);
                    for(float partial:new float[]{0,.5F,1}){
                        compose(before,partial);compose(now,partial);
                        equal(before.globalMatrices(),now.globalMatrices(),"flight/guard/landing/transition globals");
                        equal(before.skinMatrices(),now.skinMatrices(),"flight/guard/landing/transition skin");transitions++;
                    }
                }
            }
        }
        System.out.println("PASS bit-identical baseline flight entry/exit + guard/landing/transition composition: "+transitions+" samples");
    }
    public static void main(String[] args)throws Exception{
        init();isolation();lifecycle();hierarchyAndWorld();extractionParity();
        System.out.println("PASS CombatPoseSnapshotTest: "+assertions+" assertions; no native rendering performed");
    }
}
