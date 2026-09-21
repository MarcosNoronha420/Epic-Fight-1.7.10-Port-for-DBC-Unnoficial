package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.ClipLibrary;
import com.nicolas.epicfight1710.anim.Mat4;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import com.nicolas.epicfight1710.client.CombatController;
import com.nicolas.epicfight1710.client.DbcAnatomicalRig;
import com.nicolas.epicfight1710.client.DbcRetargetMath;
import com.nicolas.epicfight1710.client.Compat;
import com.nicolas.epicfight1710.combat.WeaponCapability1710;
import com.nicolas.epicfight1710.combat.ResolvedWeaponCapability;

/**
 * 1.7.10 client animator modeled after Epic Fight's base/composite/action layer
 * architecture. It keeps animation time and pose composition separate from the
 * JBRA renderer so one pose can consistently drive DBC body geometry and first
 * person transforms.
 */
public final class LayeredAnimator {
    private final SkeletonMesh mesh;
    private final ClipLibrary clips;
    private final AnimationCatalog catalog;
    private final DbcAnatomicalRig dbcRig;
    private final AnimationLayer baseLayer;
    private final AnimationLayer compositeLayer;
    private final AnimationLayer reactionLayer;
    private final AnimationLayer actionLayer;

    private final AnimationPose basePose;
    private final AnimationPose composedPose;
    private final AnimationPose tempPose;
    /** One authoritative composed hierarchy; WORLD-BODY and third person share it. */
    private final float[][] local,global,skin,bindGlobal;
    // Legacy action-parent delta APIs still expose the base hierarchy, but no live
    // WORLD-BODY/retarget caller consumes it every frame. Build it lazily only if a
    // compatibility caller actually asks for it.
    private float[][] baseLocalLazy,baseGlobalLazy;
    private int poseSerial,baseSerial=Integer.MIN_VALUE;
    private final float[] matrixTemp=new float[16];
    private final float[] viewVector=new float[3];
    private final float[] motionVector=new float[3],deltaVector=new float[3];

    private LivingMotion currentMotion;
    private String observedAction;
    private int lastTick=Integer.MIN_VALUE;
    private boolean lastOnGround=true;
    private int lastAirborne;
    private boolean guardVisual;
    private String automaticCompositeClip;
    private boolean interactionLogged;
    private boolean dbcJbraProfile;
    private boolean profileLogged;
    private boolean attackFacingLogged;
    private boolean airAttackLogged;
    private boolean sourceFistLogged,firstPersonSourcePoseLogged;
    private boolean sourceWeaponLogged;
    private boolean guardPolishLogged;
    private boolean kiAimLogged;
    private boolean epicFlightLogged;
    private LivingMotion lastLoggedMotion;
    private float smoothedSpeed;
    private float smoothedVertical;
    private float smoothedForward;
    private float minAirVertical;
    private boolean motionSamplesReady;
    private int root=-1,torso=-1,chest=-1,head=-1;
    private int thighR=-1,legR=-1,thighL=-1,legL=-1;
    private int shoulderR=-1,armR=-1,handR=-1,toolR=-1,shoulderL=-1,armL=-1,handL=-1,toolL=-1;

    public LayeredAnimator(SkeletonMesh mesh,ClipLibrary clips) {
        this.mesh=mesh;this.clips=clips;this.catalog=new AnimationCatalog(mesh,clips);this.dbcRig=new DbcAnatomicalRig(mesh);
        int n=mesh.jointCount;
        baseLayer=new AnimationLayer(clips,n);compositeLayer=new AnimationLayer(clips,n);reactionLayer=new AnimationLayer(clips,n);actionLayer=new AnimationLayer(clips,n);
        basePose=new AnimationPose(n);composedPose=new AnimationPose(n);tempPose=new AnimationPose(n);
        local=new float[n][16];global=new float[n][16];skin=new float[n][16];bindGlobal=new float[n][16];
        for(int i=0;i<n;i++) {
            if(mesh.parent[i]<0)Mat4.copy(mesh.bindLocal[i],bindGlobal[i]);else Mat4.mul(bindGlobal[mesh.parent[i]],mesh.bindLocal[i],bindGlobal[i]);
            String s=mesh.jointName[i];
            if("Root".equals(s))root=i;else if("Torso".equals(s))torso=i;else if("Chest".equals(s))chest=i;else if("Head".equals(s))head=i;
            else if("Thigh_R".equals(s))thighR=i;else if("Thigh_L".equals(s))thighL=i;else if("Arm_R".equals(s))armR=i;else if("Arm_L".equals(s))armL=i;
            else if("Hand_R".equals(s))handR=i;else if("Hand_L".equals(s))handL=i;else if("Tool_R".equals(s))toolR=i;else if("Tool_L".equals(s))toolL=i;
        }
        root=dbcRig.root;torso=dbcRig.torso;chest=dbcRig.chest;head=dbcRig.head;
        thighR=dbcRig.thighR;legR=dbcRig.legR;thighL=dbcRig.thighL;legL=dbcRig.legL;
        shoulderR=dbcRig.shoulderR;armR=dbcRig.armR;handR=dbcRig.handR;
        shoulderL=dbcRig.shoulderL;armL=dbcRig.armL;handL=dbcRig.handL;
    }

    public void reset() {
        baseLayer.off();compositeLayer.off();reactionLayer.off();actionLayer.off();
        currentMotion=null;observedAction=null;lastTick=Integer.MIN_VALUE;lastOnGround=true;lastAirborne=0;guardVisual=false;automaticCompositeClip=null;
        dbcJbraProfile=false;profileLogged=false;attackFacingLogged=false;airAttackLogged=false;sourceFistLogged=false;sourceWeaponLogged=false;guardPolishLogged=false;kiAimLogged=false;epicFlightLogged=false;interactionLogged=false;lastLoggedMotion=null;
        smoothedSpeed=smoothedVertical=smoothedForward=minAirVertical=0.0F;motionSamplesReady=false;
    }

    public void update(Object player,float partial) { updateInternal(player,partial,false,-1); }

    public void updateDbcJbra(Object player,float partial,int renderState) {
        updateInternal(player,partial,true,renderState);
    }

    private void updateInternal(Object player,float partial,boolean dbcProfile,int renderState) {
        if(player==null)return;
        dbcJbraProfile=dbcProfile;
        int tick=CombatController.INSTANCE.tick();
        if(lastTick==Integer.MIN_VALUE){lastTick=tick-1;lastOnGround=Compat.onGround(player);}
        while(lastTick<tick){tickOnce(player);lastTick++;}
        // RenderPlayerJBRA can publish its exact y-state after the client tick. Force
        // the base layer into the authoritative motion immediately rather than
        // waiting another tick and showing one frame of FALL/IDLE in the middle of flight.
        if(dbcJbraProfile)ensureBaseMotion(player,true,partial);
        compose(player,partial);
        if(dbcJbraProfile&&!profileLogged){
            profileLogged=true;
            System.out.println("[EpicFight1710] DBC/JBRA compatibility profile active: frame-rate pose conversions, phase-stable gait, neutral-bind JBRA geometry, hierarchical arm/leg socket retarget, Epic Fight 20.9.5 creative-flight selection/correction, Head-joint body continuity, view-directed fists, and source-driven fist actions.");
        }
    }

    private void tickOnce(Object player) {
        CombatController combat=CombatController.INSTANCE;
        int air=combat.airborneTicks();
        updateMotionSamples(player);
        ensureBaseMotion(player,false,1.0F);

        String actionName=combat.attackClip();
        if(actionName==null) {
            observedAction=null;
            if(actionLayer.enabled()&&actionLayer.ended())actionLayer.off();
        } else if(!actionName.equals(observedAction)) {
            observedAction=actionName;
            AnimationDefinition def=catalog.get(actionName);
            if(def!=null&&clips.get(def.clip)!=null) {
                actionLayer.play(def,Math.max(0.0F,combat.attackAge(0.0F)),1.0F);
            }
        }

        // JRMCore remains authoritative for defense. Outside guard, mirror safe
        // vanilla item-use states into the corresponding Epic source upper-body
        // composites. This broadens the port without inventing any pose or gameplay.
        boolean wantsGuard=combat.blocking();
        int dbcKiAttack=Compat.dbcKiAttackAnimation(player);
        boolean wantsKiAim=dbcKiAttack>0;
        ResolvedWeaponCapability capability=combat.activeCapability(player);
        String desiredComposite;
        if(wantsKiAim){
            desiredComposite=sourceKiAimComposite(player);
            if(!kiAimLogged){kiAimLogged=true;System.out.println("[EpicFight1710] Exact DBC Ki-shoot state -> Epic source aim layer active: ExtendedPlayer#getAnimKiShoot="+dbcKiAttack+" drives crossbow_aim_(down/mid/up) while DBC remains authoritative for charge, projectile and damage.");}
        }else if(wantsGuard){
            desiredComposite=capability.livingMotion(WeaponCapability1710.MOTION_BLOCK);
            // 2.0 external weapon_types.json files can predate the fist guard entry.
            // Preserve the previously accepted unarmed two-arm visual even with an old
            // hot-reload file instead of requiring the user to delete config manually.
            if(desiredComposite==null&&capability.isFist())desiredComposite="guard_dualsword";
        }else desiredComposite=sourceInteractionComposite(player);
        if(desiredComposite==null)desiredComposite=sourceWeaponComposite(capability);
        if(desiredComposite!=null&&!desiredComposite.equals(automaticCompositeClip)) {
            AnimationDefinition d=catalog.get(desiredComposite);
            if(d!=null&&clips.get(d.clip)!=null) {
                float startTime=compositePhaseStart(d);
                compositeLayer.play(d,startTime,1.0F);
                automaticCompositeClip=desiredComposite;
                if(!wantsGuard&&!interactionLogged){interactionLogged=true;System.out.println("[EpicFight1710] Epic source ItemCapability -> WeaponType living-motion routing active: weapon hold/walk/run composites are resolved from the current capability, locomotion clips preserve gait phase, and no procedural stance is generated.");}
            }
        } else if(desiredComposite==null&&automaticCompositeClip!=null) {
            compositeLayer.requestStop(.10F);
            automaticCompositeClip=null;
        }
        guardVisual=wantsGuard;

        String guardReaction=combat.pollGuardReaction();
        if(guardReaction!=null){
            AnimationDefinition hit=catalog.get(guardReaction);
            if(hit!=null&&clips.get(hit.clip)!=null)reactionLayer.play(hit,0.0F,1.0F);
        }

        boolean ground=Compat.onGround(player);
        if(!ground)minAirVertical=Math.min(minAirVertical,Compat.verticalSpeed(player));
        if(ground&&!lastOnGround&&lastAirborne>=5&&actionName==null) {
            AnimationDefinition landing=catalog.living(LivingMotion.LANDING);
            if(landing!=null&&clips.get(landing.clip)!=null) {
                float impact=Math.max(-minAirVertical,0.0F);
                float weight=clamp(.52F+impact*1.6F,.52F,.88F);
                reactionLayer.play(landing,0.0F,weight);
            }
        }
        if(ground)minAirVertical=0.0F;
        lastOnGround=ground;lastAirborne=air;

        baseLayer.tick(.05F,dbcJbraProfile?LivingMotionResolver.speedMultiplier(baseLayer.definition(),player,smoothedSpeed):LivingMotionResolver.speedMultiplier(baseLayer.definition(),player));
        float compositeSpeed=dbcJbraProfile?LivingMotionResolver.speedMultiplier(compositeLayer.definition(),player,smoothedSpeed):LivingMotionResolver.speedMultiplier(compositeLayer.definition(),player);
        compositeLayer.tick(.05F,compositeSpeed);reactionLayer.tick(.05F,1.0F);actionLayer.tick(.05F,combat.actionPlaybackSpeed());
        if(reactionLayer.enabled()&&reactionLayer.ended())reactionLayer.off();
        if(actionLayer.enabled()&&actionName==null&&actionLayer.ended())actionLayer.off();
    }

    private void compose(Object player,float partial) {
        final boolean sourceFirstPerson=dbcJbraProfile&&Compat.getThirdPerson()==0;
        baseLayer.sample(partial,basePose);
        if(dbcJbraProfile) {
            boolean creativeFlight=isCreativeFlight(currentMotion);
            // JBRA owns the whole-player prone presentation. Keep Epic's authored
            // creative-flight limb choreography, but never rotate only the skinned
            // torso/limbs around Epic's modern Root: that splits the native JBRA
            // head/hair/body around incompatible bind origins.
            sanitizeDbcLayerPose(basePose,false,false);
            if(creativeFlight&&!epicFlightLogged) {
                epicFlightLogged=true;
                System.out.println("[EpicFight1710] DBC creative-flight adapter active: Epic 20.9.5 owns authored idle/forward/back joint choreography; no cross-arm mirroring is applied. JBRA prone presentation is sprint-key gated by DbcFlightRenderHook.");
            }
            stabilizeDbcLivingPose(basePose,currentMotion,partial);
        }
        composedPose.copyFrom(basePose);

        if(compositeLayer.enabled()) {
            compositeLayer.sample(partial,tempPose);
            AnimationDefinition compositeDef=compositeLayer.definition();
            if(dbcJbraProfile) {
                sanitizeDbcLayerPose(tempPose,false,false);
            }
            // Epic Fight 20.9.5 guard_*.json is authoritative here. Sword,
            // uchigatana, longsword, greatsword and dualsword guard clips declare
            // COMPOSITE_LAYER + HIGHEST + root_upper_joints. The base WALK/RUN/SNEAK
            // therefore keeps driving the lower body while BLOCK owns only the upper
            // body. 2.0.11 incorrectly promoted guard to full-body and visually froze
            // locomotion while defending.
            composedPose.overlay(tempPose,compositeLayer.effectiveWeight(partial),compositeDef.mask);
            if(dbcJbraProfile&&guardVisual&&compositeDef!=null&&compositeDef.clip!=null
                    &&compositeDef.clip.startsWith("guard_")&&compositeDef.clip.indexOf("_hit")<0&&!guardPolishLogged) {
                guardPolishLogged=true;
                System.out.println("[EpicFight1710] Epic 20.9.5 BLOCK data parity active: guard_* stays COMPOSITE_LAYER/root_upper_joints over live WALK/RUN/SNEAK lower-body locomotion; no procedural guard pose is generated.");
            }
        }
        if(reactionLayer.enabled()) {
            reactionLayer.sample(partial,tempPose);
            if(dbcJbraProfile) {
                sanitizeDbcLayerPose(tempPose,false,false);
                AnimationDefinition rd=reactionLayer.definition();
                if(rd!=null&&"landing".equals(rd.clip))applySafeLandingPose(tempPose,reactionLayer.interpolatedElapsed(partial),reactionLayer.duration());
            }
            composedPose.overlay(tempPose,reactionLayer.effectiveWeight(partial),reactionLayer.definition().mask);
        }
        if(actionLayer.enabled()) {
            // Source-driven action path.  Fist attacks are sampled directly from the
            // converted Epic Fight 20.9.5 fist_auto clips.  We adapt only the rig-space
            // pieces DBC/JBRA cannot consume (local translation/scale and Root pitch/roll).
            actionLayer.sample(partial,tempPose);
            if(dbcJbraProfile) {
                boolean fist=isFistAction();
                boolean weapon=isWeaponAction();
                // Epic FirstPersonRenderer consumes the same final Animator pose as
                // third person. Camera perspective must never select a second pose
                // sanitization pipeline. Keep the single DBC/JBRA compatibility
                // boundary here; only source-defined modifiers may branch on
                // PlayerPatch.isFirstPerson().
                sanitizeDbcLayerPose(tempPose,fist||weapon,false);
                if(fist||weapon) {
                    // Exact Animations.ReusableSources.COMBO_ATTACK_DIRECTION_MODIFIER:
                    // PlayerPatch.isFirstPerson() returns before pitch/body-yaw twisting.
                    // Running our bounded third-person modifier in first person made a
                    // 180-degree camera turn keep punching toward the old body heading.
                    if(!sourceFirstPerson)applyEpicComboAttackDirectionModifier(tempPose,player,partial);
                    else if(!firstPersonSourcePoseLogged){
                        firstPersonSourcePoseLogged=true;
                        System.out.println("[EpicFight1710] Epic 20.9.5 first-person pose parity active: first and third person consume the same DBC-compatible final Animator pose; only COMBO_ATTACK_DIRECTION_MODIFIER is skipped when PlayerPatch.isFirstPerson(), matching the source.");
                    }
                    if(fist&&!sourceFistLogged) {
                        sourceFistLogged=true;
                        System.out.println("[EpicFight1710] Epic Fight 20.9.5 source fist active: fist_auto / relentless_combo is sampled from the authored clip and both camera modes share the same DBC/JBRA compatibility boundary.");
                    }
                    if(weapon&&!sourceWeaponLogged) {
                        sourceWeaponLogged=true;
                        System.out.println("[EpicFight1710] Epic Fight 20.9.5 source weapon action active: sword/katana/longsword/greatsword/axe/dagger/spear clips drive the same DBC/JBRA armature; no procedural weapon swing is generated.");
                    }
                }
            }
            JointMask mask=actionLayer.definition().mask;
            composedPose.overlay(tempPose,actionLayer.effectiveWeight(partial),mask);
            if(dbcJbraProfile&&isFistAction()&&!Compat.onGround(player)&&!airAttackLogged) {
                airAttackLogged=true;
                System.out.println("[EpicFight1710] Airborne fist uses Epic BASIC_ATTACK upper-body mask over the existing jump/fall/flight lower body.");
            }
        }

        if(root>=0) {
            if(dbcJbraProfile) {
                // DBC owns world translation/physics and JBRA owns the whole-player
                // prone presentation. Epic's DBC profile never adds a second Root
                // translation/flight rotation here; source fist yaw remains inside the
                // upper-body action composition rather than moving the entity root.
            } else {
                // Generic fallback has no authoritative root-motion patch, so at minimum
                // never render horizontal displacement away from the entity hitbox.
                basePose.joint(root)[0]=0;basePose.joint(root)[2]=0;
                composedPose.joint(root)[0]=0;composedPose.joint(root)[2]=0;
            }
        }

        ArmaturePoseMath.globals(mesh,composedPose,local,global,matrixTemp);
        if(++poseSerial==Integer.MIN_VALUE)poseSerial=1;

        if(dbcJbraProfile) {
            // Source-parity rule for 2.0.7: do not move Chest after armature evaluation
            // to chase an independently rendered native head. The concrete JBRA skull
            // is now retargeted rigidly to Epic's Head joint, so normal armature
            // hierarchy (Torso -> Chest -> Head) owns body continuity exactly once.
            // Leg sockets are still retargeted at skin time against neutral JBRA pivots.
        }
        ArmaturePoseMath.skinning(mesh,global,skin);

        // WORLD-BODY first person (2.0.34+) consumes this same final DBC-compatible
        // armature pose. A parallel first-person pose hierarchy remained from the
        // rejected pre-WORLD-BODY renderer even though no live caller consumed it.
        // One authoritative matrix set avoids duplicate pose composition and joint
        // hierarchy evaluation on every Battle Mode display frame.
    }

    private String sourceKiAimComposite(Object player) {
        float pitch=Compat.pitch(player);
        if(pitch<-25.0F&&clips.get("crossbow_aim_up")!=null)return "crossbow_aim_up";
        if(pitch>25.0F&&clips.get("crossbow_aim_down")!=null)return "crossbow_aim_down";
        return clips.get("crossbow_aim_mid")!=null?"crossbow_aim_mid":null;
    }

    private String sourceInteractionComposite(Object player) {
        String use=Compat.itemUseAction(player);
        if(use==null)return null;
        if(use.indexOf("eat")>=0)return clips.get("eat_mainhand")!=null?"eat_mainhand":null;
        if(use.indexOf("drink")>=0)return clips.get("drink_mainhand")!=null?"drink_mainhand":null;
        if(use.indexOf("bow")>=0) {
            float p=Compat.pitch(player);
            String n=p<-25.0F?"bow_aim_up":(p>25.0F?"bow_aim_down":"bow_aim_mid");
            return clips.get(n)!=null?n:null;
        }
        return null;
    }

    private String sourceWeaponComposite(ResolvedWeaponCapability capability) {
        if(capability==null||!capability.armed())return null;
        String n=null;
        if(currentMotion==LivingMotion.RUN)n=capability.livingMotion(WeaponCapability1710.MOTION_RUN);
        else if(currentMotion==LivingMotion.WALK)n=capability.livingMotion(WeaponCapability1710.MOTION_WALK);
        if(n==null)n=capability.livingMotion(WeaponCapability1710.MOTION_IDLE);
        return n!=null&&clips.get(n)!=null?n:null;
    }

    /** Preserve normalized locomotion phase when a weapon living modifier changes
     * WALK <-> RUN. Epic Fight treats these as living-motion modifiers layered over
     * the same locomotion state; restarting at frame zero makes the upper body hitch. */
    private float compositePhaseStart(AnimationDefinition next) {
        if(next==null||!compositeLayer.enabled()||compositeLayer.definition()==null)return 0.0F;
        AnimationDefinition cur=compositeLayer.definition();
        if(!isLocomotionSpeed(cur.speedMode)||!isLocomotionSpeed(next.speedMode)||compositeLayer.duration()<=.0001F)return 0.0F;
        float phase=compositeLayer.elapsed()/compositeLayer.duration();
        phase=phase-(float)Math.floor(phase);
        com.nicolas.epicfight1710.anim.Clip nextClip=clips.get(next.clip);
        return nextClip==null||nextClip.duration<=.0001F?0.0F:phase*nextClip.duration;
    }

    private static boolean isLocomotionSpeed(AnimationDefinition.SpeedMode mode){return mode==AnimationDefinition.SpeedMode.WALK||mode==AnimationDefinition.SpeedMode.RUN;}

    private void ensureBaseMotion(Object player,boolean renderSync,float snapshotPartial) {
        CombatController combat=CombatController.INSTANCE;
        int flightMode=dbcJbraProfile?Compat.dbcFlightVisualMode(player):(Compat.dbcFlying(player)?2:0);
        LivingMotion motion=dbcJbraProfile?resolveDbcMotion(player,combat.airborneTicks(),flightMode,snapshotPartial):LivingMotionResolver.resolve(player,combat.airborneTicks(),flightMode>0);
        AnimationDefinition base=catalog.living(motion);

        // Do not substitute compatibility idle/walk poses for source living motions.
        // DBC/JBRA keeps bind lengths/world placement while Epic's real local joint
        // rotations drive sneak, basic flight and directional cruise.
        if(base==null||clips.get(base.clip)==null)base=catalog.living(LivingMotion.IDLE);
        String current=baseLayer.enabled()&&baseLayer.definition()!=null?baseLayer.definition().clip:null;
        if(currentMotion!=motion||!baseLayer.enabled()||current==null||!current.equals(base.clip)) {
            // WALK <-> RUN are two views of the same cyclic gait. Preserve normalized
            // foot phase when switching so the transition does not restart both feet at
            // frame zero and create a visible hitch. Other state changes start normally.
            float startTime=0.0F;
            if(baseLayer.enabled()&&isGroundGait(currentMotion)&&isGroundGait(motion)&&baseLayer.duration()>.0001F) {
                float phase=baseLayer.elapsed()/baseLayer.duration();
                phase=phase-(float)Math.floor(phase);
                com.nicolas.epicfight1710.anim.Clip nextClip=clips.get(base.clip);
                if(nextClip!=null&&nextClip.duration>.0001F)startTime=phase*nextClip.duration;
            }
            currentMotion=motion;baseLayer.play(base,startTime,snapshotPartial);
            if(dbcJbraProfile&&motion!=lastLoggedMotion) {
                lastLoggedMotion=motion;
                // Production builds must not synchronously write a log line for every
                // WALK/IDLE/JUMP edge. On legacy launchers stdout is synchronized and the
                // I/O itself can show up as a frametime spike during rapid locomotion.
                if(Boolean.getBoolean("epicfight1710.debugmotions"))
                    System.out.println("[EpicFight1710] DBC living motion -> "+motion+" clip="+base.clip+
                            " speed="+round3(smoothedSpeed)+" sprint="+Compat.sprinting(player)+
                            " sprintKey="+Compat.sprintKeyHeld(player)+" flightMode="+flightMode+".");
            }
        }
    }

    private LivingMotion resolveDbcMotion(Object player,int airborneTicks,int flightMode,float partial) {
        if(flightMode>0) {
            // Port Epic Fight 20.9.5's creative-flight SelectiveAnimation literally:
            // DBC supplies physics/state only; horizontal movement chooses idle vs fly,
            // and lookVector dot velocity chooses the forward/backward child. HOVER vs
            // CRUISE never selects presentation anymore.
            Compat.deltaVector(player,deltaVector);
            Compat.motionVector(player,motionVector);
            Compat.viewVector(player,partial,viewVector);
            return EpicCreativeFlightPolicy.selectStable(currentMotion,deltaVector[0],deltaVector[2],motionVector[0],motionVector[1],motionVector[2],viewVector[0],viewVector[1],viewVector[2]);
        }
        LivingMotion special=LivingMotionResolver.resolve(player,airborneTicks,false);
        if(special==LivingMotion.DEATH||special==LivingMotion.SLEEP||special==LivingMotion.MOUNT||special==LivingMotion.CLIMB||special==LivingMotion.SWIM||special==LivingMotion.FLOAT)return special;
        if(Compat.onGround(player)) {
            // State ownership must follow the CURRENT movement sample. 2.0.17 fed the
            // 0.82/0.18 EMA into DbcLocomotionPolicy, so after the player stopped the
            // residual smoothedSpeed could keep WALK/RUN selected for several seconds.
            // Preserve smoothing for playback-rate/gait quality only; locomotion state
            // itself gets an immediate stationary gate from the live horizontal speed.
            boolean sneaking=Compat.sneaking(player);
            float liveSpeed=Compat.speed(player);
            if(!sneaking&&liveSpeed<=0.0010F)return LivingMotion.IDLE;
            // Epic Fight distinguishes WALK and RUN by locomotion state, not by one
            // noisy position-delta spike. DBC can alternate ~0.04 and ~0.19 block/tick
            // while sprint=false, so RUN remains sprint-only once actual motion exists.
            return DbcLocomotionPolicy.ground(sneaking,Compat.sprinting(player),liveSpeed,currentMotion);
        }
        // User acceptance rule for the DBC/JBRA port: no standalone FALL animation.
        // If DBC flight is genuinely off while airborne, keep the source Epic JUMP
        // rather than injecting the rejected folded/sitting fall pose. Active DBC
        // flight is handled above and never reaches this branch.
        return LivingMotion.JUMP;
    }

    private static boolean isGroundGait(LivingMotion m) {
        return m==LivingMotion.WALK||m==LivingMotion.RUN;
    }

    private void updateMotionSamples(Object player) {
        float s=Compat.speed(player),v=Compat.verticalSpeed(player),f=Compat.forwardMotion(player);
        if(!motionSamplesReady){smoothedSpeed=s;smoothedVertical=v;smoothedForward=f;motionSamplesReady=true;return;}
        smoothedSpeed=smoothedSpeed*.82F+s*.18F;
        smoothedVertical=smoothedVertical*.72F+v*.28F;
        smoothedForward=smoothedForward*.80F+f*.20F;
    }

    /**
     * Bind-relative DBC compatibility for real Epic Fight animation samples. Joint
     * rotations remain authored Epic 20.9.5 data, while modern local translation/scale
     * are removed because JBRA has different proportions and bind lengths. Normal DBC
     * locomotion and creative flight keep Root identity because DBC/JBRA owns world and
     * whole-player flight presentation. Fist actions may retain source Root yaw only;
     * translation, pitch and roll remain stripped.
     */
    private void sanitizeDbcLayerPose(AnimationPose pose,boolean keepRootYaw,boolean keepRootRotation) {
        ArmaturePoseMath.sanitizeDbc(mesh,root,pose,keepRootYaw,keepRootRotation);
    }

    private static boolean isCreativeFlight(LivingMotion motion) {
        return motion==LivingMotion.CREATIVE_IDLE||motion==LivingMotion.FLY_FORWARD||motion==LivingMotion.FLY_BACKWARD;
    }

    /** Exact pose-modifier semantics from Epic Fight 20.9.5 Animations.ReusableSources. */

    private void applyEpicComboAttackDirectionModifier(AnimationPose pose,Object player,float partial) {
        if(pose==null||player==null||chest<0||!pose.has(chest))return;
        float p0=Compat.prevPitch(player),p1=Compat.pitch(player);
        float cameraPitch=p0+(p1-p0)*clamp(partial,0.0F,1.0F);
        float x=-cameraPitch;
        float attackPitch=(x>=0.0F?1.0F:-1.0F)*.03333F*x*x;
        attackPitch=clamp(attackPitch,-30.0F,30.0F);
        appendXRotation(pose,chest,-attackPitch);

        float aimYaw=clamp(Compat.aimYawDelta(player,partial),-70.0F,70.0F);
        appendYRotation(pose,torso,aimYaw*.35F);
        appendYRotation(pose,chest,aimYaw*.65F);
        if(!attackFacingLogged) {
            attackFacingLogged=true;
            System.out.println("[EpicFight1710] View-directed attack modifier active: Epic pitch is preserved and the bounded face-vs-body yaw delta is distributed through Torso/Chest; entity Root/world facing is unchanged.");
        }
    }

    private boolean isFistAction() {
        AnimationDefinition d=actionLayer.definition();
        return d!=null&&d.clip!=null&&(d.clip.startsWith("fist_")||"relentless_combo".equals(d.clip));
    }

    private boolean isWeaponAction() {
        AnimationDefinition d=actionLayer.definition();if(d==null||d.clip==null)return false;
        String n=d.clip;
        return n.startsWith("sword_")||n.startsWith("uchigatana_")||n.startsWith("longsword_")||n.startsWith("greatsword_")||n.startsWith("axe_")||n.startsWith("dagger_")||n.startsWith("spear_");
    }

    private static void normalizeQuaternion(float[] t) {
        ArmaturePoseMath.normalizeQuaternion(t);
    }

    private void stabilizeDbcLivingPose(AnimationPose pose,LivingMotion motion,float partial) {
        if(pose==null||motion==null)return;
        // Do not mirror either flight arm. Epic Fight's creative_idle/forward/backward
        // clips are intentionally asymmetric (especially creative_fly_backward). The
        // 1.1.1 mirror hack overwrote real source choreography and could manufacture
        // the very side-arm pose it was intended to hide. Bind/socket differences are
        // corrected later by JbraWeightedPartRenderer; source joint rotations stay exact.
    }

    private void applySafeLandingPose(AnimationPose pose,float elapsed,float duration) {
        float phase=clamp(elapsed/Math.max(.001F,duration),0.0F,1.0F);
        float impact=phase<.22F?smooth01(phase/.22F):1.0F-smooth01((phase-.22F)/.78F);
        // Symmetric impact crouch. The source landing's 130-150 degree left-thigh
        // rotation is deliberately not retargeted to DBC because it is the leg-pop
        // seen in the user's real-client capture.
        setXRotation(pose,thighR,24.0F*impact);
        setXRotation(pose,thighL,24.0F*impact);
        setXRotation(pose,legR,-38.0F*impact);
        setXRotation(pose,legL,-38.0F*impact);
        setXRotation(pose,torso,8.0F*impact);
        setXRotation(pose,chest,-7.0F*impact);
    }

    /** qDelta * qExisting, matching Epic's frontResult/prepend pose modifiers. */
    private void appendXRotation(AnimationPose pose,int joint,float degrees) {
        if(joint<0||!pose.has(joint)||Math.abs(degrees)<.0001F)return;
        float[] t=pose.joint(joint);normalizeQuaternion(t);
        float half=(float)Math.toRadians(degrees)*.5F,s=(float)Math.sin(half),c=(float)Math.cos(half);
        float x=t[3],y=t[4],z=t[5],w=t[6];
        // qExisting * qX: preserve authored strike twist while pitching the whole
        // shoulder/chest chain toward the crosshair.
        t[3]=w*s+x*c; t[4]=y*c+z*s; t[5]=z*c-y*s; t[6]=w*c-x*s;
        normalizeQuaternion(t);
    }

    private void appendYRotation(AnimationPose pose,int joint,float degrees) {
        if(joint<0||!pose.has(joint)||Math.abs(degrees)<.0001F)return;
        float[] t=pose.joint(joint);normalizeQuaternion(t);
        float half=(float)Math.toRadians(degrees)*.5F,s=(float)Math.sin(half),c=(float)Math.cos(half);
        float x=t[3],y=t[4],z=t[5],w=t[6];
        // qExisting * qY
        t[3]=x*c-z*s; t[4]=w*s+y*c; t[5]=x*s+z*c; t[6]=w*c-y*s;
        normalizeQuaternion(t);
    }

    private void setXRotation(AnimationPose pose,int joint,float degrees) {
        if(joint<0)return;
        if(!pose.has(joint))pose.setIdentity(joint);
        setXRotation(pose.joint(joint),degrees);
    }

    private static void setXRotation(float[] t,float degrees) {
        float half=(float)Math.toRadians(degrees)*.5F;
        t[0]=t[1]=t[2]=0.0F;
        t[3]=(float)Math.sin(half);t[4]=0.0F;t[5]=0.0F;t[6]=(float)Math.cos(half);
        t[7]=t[8]=t[9]=1.0F;
    }

    private static float smooth01(float x){x=clamp(x,0.0F,1.0F);return x*x*(3.0F-2.0F*x);}

    private void makeBoundLocal(int joint,float[] delta,float[] out){Mat4.fromTRS(delta,matrixTemp);Mat4.mul(mesh.bindLocal[joint],matrixTemp,out);}

    public void playComposite(String name){AnimationDefinition d=catalog.get(name);if(d!=null&&d.layer==AnimationDefinition.LayerType.COMPOSITE){guardVisual=false;automaticCompositeClip=null;compositeLayer.play(d,0.0F,1.0F);}}
    public void stopComposite(){guardVisual=false;automaticCompositeClip=null;compositeLayer.requestStop(.10F);}

    public AnimationCatalog catalog(){return catalog;}
    public float[][] skinMatrices(){return skin;}
    /** Monotonic identity of the final composed pose, for render-side geometry caches. */
    public int poseSerial(){return poseSerial;}
    /** Compatibility alias: WORLD-BODY intentionally consumes the same final matrices. */
    public float[][] firstPersonSkinMatrices(){return skin;}
    public float[][] globalMatrices(){return global;}
    public float[][] baseGlobalMatrices(){
        if(baseGlobalLazy==null){
            baseLocalLazy=new float[mesh.jointCount][16];
            baseGlobalLazy=new float[mesh.jointCount][16];
        }
        if(baseSerial!=poseSerial){
            for(int j=0;j<mesh.jointCount;j++){
                makeBoundLocal(j,basePose.has(j)?basePose.joint(j):IDENTITY,baseLocalLazy[j]);
                int p=mesh.parent[j];
                if(p<0)Mat4.copy(baseLocalLazy[j],baseGlobalLazy[j]);
                else Mat4.mul(baseGlobalLazy[p],baseLocalLazy[j],baseGlobalLazy[j]);
            }
            baseSerial=poseSerial;
        }
        return baseGlobalLazy;
    }
    public float[][] bindGlobalMatrices(){return bindGlobal;}
    public boolean actionActive(){return actionLayer.enabled();}
    public boolean guardActive(){return guardVisual||CombatController.INSTANCE.blocking();}
    public String compositeClip(){return compositeLayer.enabled()&&compositeLayer.definition()!=null?compositeLayer.definition().clip:null;}
    public LivingMotion currentMotion(){return currentMotion;}
    public int root(){return root;}public int torso(){return torso;}public int chest(){return chest;}public int head(){return head;}public int thighR(){return thighR;}public int thighL(){return thighL;}public int shoulderR(){return shoulderR;}public int shoulderL(){return shoulderL;}public int armR(){return armR;}public int armL(){return armL;}public int handR(){return handR;}public int handL(){return handL;}public int toolR(){return toolR;}public int toolL(){return toolL;}

    private static float round3(float v){return Math.round(v*1000.0F)/1000.0F;}
    private static float clamp(float v,float a,float b){return v<a?a:(v>b?b:v);}
    private static final float[] IDENTITY={0,0,0,0,0,0,1,1,1,1};
}
