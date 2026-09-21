package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.Clip;
import com.nicolas.epicfight1710.anim.ClipLibrary;
import com.nicolas.epicfight1710.anim.SkeletonMesh;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime metadata for the converted Epic Fight clips.  Animation geometry comes
 * from the original 20.9.5 assets; this class supplies the 1.7.10 layer semantics.
 */
public final class AnimationCatalog {
    private final Map<String,AnimationDefinition> byName=new HashMap<String,AnimationDefinition>();
    private final Map<LivingMotion,AnimationDefinition> living=new HashMap<LivingMotion,AnimationDefinition>();
    public final JointMask full,upper,arms,head,basicAttack,landingMask;

    public AnimationCatalog(SkeletonMesh mesh,ClipLibrary clips) {
        full=JointMask.full(mesh);upper=JointMask.upperBody(mesh);arms=JointMask.arms(mesh);head=JointMask.head(mesh);
        basicAttack=JointMask.basicAttack(mesh);landingMask=JointMask.landingBody(mesh);

        living(LivingMotion.IDLE,"idle",true,.070F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.WALK,"walk",true,.065F,AnimationDefinition.SpeedMode.WALK);
        living(LivingMotion.RUN,"run",true,.060F,AnimationDefinition.SpeedMode.RUN);
        living(LivingMotion.SNEAK,"sneak",true,.070F,AnimationDefinition.SpeedMode.SNEAK);
        living(LivingMotion.JUMP,"jump",false,.055F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.FALL,"fall",true,.060F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.FLOAT,"float",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        AnimationDefinition landingDef=new AnimationDefinition("landing",false,.10F,.14F,AnimationDefinition.LayerType.REACTION,AnimationDefinition.Priority.MIDDLE,AnimationDefinition.SpeedMode.NORMAL,landingMask,true);
        living.put(LivingMotion.LANDING,landingDef);byName.put("landing",landingDef);
        living(LivingMotion.FLY,"fly",true,.16F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.CREATIVE_IDLE,exists(clips,"creative_idle")?"creative_idle":"fly",true,.150F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.FLY_FORWARD,exists(clips,"creative_fly_forward")?"creative_fly_forward":"fly_forward",true,.150F,AnimationDefinition.SpeedMode.FLY);
        living(LivingMotion.FLY_BACKWARD,exists(clips,"creative_fly_backward")?"creative_fly_backward":"fly_backward",true,.150F,AnimationDefinition.SpeedMode.FLY);
        living(LivingMotion.CLIMB,"climb",true,.10F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.SWIM,"swim",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.MOUNT,"mount",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.SIT,"sit",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.SLEEP,"sleep",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.KNEEL,"kneel",true,.12F,AnimationDefinition.SpeedMode.NORMAL);
        living(LivingMotion.DEATH,"death",false,.05F,AnimationDefinition.SpeedMode.NORMAL);

        // DBC/JBRA compatibility no longer substitutes idle/walk for Epic sneak or
        // idle/procedural poses for flight. The real source living clips are sampled;
        // rig incompatibilities are handled in retarget space instead of replacing
        // the authored animation.

        // Epic Fight 20.9.5 registrations: BasicAttackAnimation uses a 0.08 s
        // convert/link time for fist_auto1/2/3 and BASIC_ATTACK_MASK.  Dash uses
        // 0.06 s.  Do not replace these authored clips with a procedural punch.
        action("fist_auto1",.080F,.001F,basicAttack);
        action("fist_auto2",.080F,.001F,basicAttack);
        action("fist_auto3",.080F,.001F,basicAttack);
        action("fist_dash",.060F,.001F,basicAttack);
        action("fist_airslash",.060F,.001F,basicAttack);
        // Epic Fight 20.9.5 FIST innate: AttackAnimation(convert=.05) with eight
        // source hit phases. It is a full-body authored skill, not a procedural fist.
        action("relentless_combo",.050F,.001F,full);

        // Weapon BasicAttack/DashAttack convert times are transcribed from the real
        // Epic Fight 20.9.5 Animations registrations.  Explicit registration here
        // prevents the old family fallback (.08 for everything) from changing the
        // authored link timing of heavy/light weapon classes.
        sourceWeaponAction("sword_auto1",.10F);sourceWeaponAction("sword_auto2",.10F);sourceWeaponAction("sword_auto3",.10F);sourceWeaponAction("sword_dash",.10F);
        sourceWeaponAction("uchigatana_auto1",.05F);sourceWeaponAction("uchigatana_auto2",.05F);sourceWeaponAction("uchigatana_auto3",.10F);sourceWeaponAction("uchigatana_dash",.10F);
        sourceWeaponAction("longsword_auto1",.10F);sourceWeaponAction("longsword_auto2",.15F);sourceWeaponAction("longsword_auto3",.05F);sourceWeaponAction("longsword_dash",.10F);
        sourceWeaponAction("greatsword_auto1",.25F);sourceWeaponAction("greatsword_auto2",.10F);sourceWeaponAction("greatsword_dash",.20F);
        sourceWeaponAction("axe_auto1",.16F);sourceWeaponAction("axe_auto2",.16F);sourceWeaponAction("axe_dash",.25F);
        sourceWeaponAction("dagger_auto1",.05F);sourceWeaponAction("dagger_auto2",.05F);sourceWeaponAction("dagger_auto3",.05F);sourceWeaponAction("dagger_dash",.05F);
        sourceWeaponAction("spear_onehand_auto",.10F);sourceWeaponAction("spear_twohand_auto1",.10F);sourceWeaponAction("spear_twohand_auto2",.10F);sourceWeaponAction("spear_dash",.10F);
        action("roll_forward",.045F,.10F,full);
        action("roll_backward",.045F,.10F,full);
        action("step_forward",.04F,.08F,full);
        action("step_backward",.04F,.08F,full);
        action("step_left",.04F,.08F,full);
        action("step_right",.04F,.08F,full);
        reaction("hit_short",.025F,.08F,full);
        reaction("hit_long",.025F,.10F,full);
        reaction("knockdown",.025F,.12F,full);
        reaction("knockdown_wakeup_left",.05F,.10F,full);
        reaction("knockdown_wakeup_right",.05F,.10F,full);

        // Converted living/item clips are registered as upper-body composites so the
        // architecture is ready for item-use and weapon-style routing without a new
        // renderer rewrite.
        String[] composite={"dig_mainhand","dig_offhand","drink_mainhand","drink_offhand","eat_mainhand","eat_offhand","hold_crossbow","hold_dual","hold_greatsword","hold_liechtenauer","hold_longsword","hold_spear","hold_tachi","hold_uchigatana","hold_uchigatana_sheath","shield_mainhand","shield_offhand"};
        for(String n:composite) if(exists(clips,n)) composite(n,true,.10F,.10F,upper,AnimationDefinition.SpeedMode.NORMAL);

        // DBC Ki attacks already expose an exact ExtendedPlayer Ki-shoot state.
        // Use Epic Fight's own continuous crossbow aim family as a source-authored
        // upper-body energy-aim pose; no procedural Ki pose or copied DBC Euler angles.
        String[] kiAim={"crossbow_aim_down","crossbow_aim_mid","crossbow_aim_up"};
        for(String n:kiAim)if(exists(clips,n))composite(n,true,.065F,.08F,upper,AnimationDefinition.SpeedMode.NORMAL);

        // WeaponCapability living-motion modifiers are still composites over the DBC
        // locomotion base, but WALK/RUN must advance from the same movement-derived
        // clock as the base gait. This keeps weapon shoulders/arms in phase instead of
        // running at a hardcoded one-second clock while the legs accelerate.
        String[] weaponWalk={"walk_greatsword","walk_liechtenauer","walk_longsword","walk_spear","walk_twohand","walk_uchigatana","walk_uchigatana_sheath"};
        String[] weaponRun={"run_dual","run_greatsword","run_longsword","run_spear","run_uchigatana","run_uchigatana_sheath"};
        for(String n:weaponWalk)if(exists(clips,n))composite(n,true,.10F,.10F,upper,AnimationDefinition.SpeedMode.WALK);
        for(String n:weaponRun)if(exists(clips,n))composite(n,true,.08F,.08F,upper,AnimationDefinition.SpeedMode.RUN);

        // DBC's right-click defense has no unarmed Epic Fight guard clip in 20.9.5.
        // guard_dualsword is the closest two-arm defensive stance, so it is reused
        // strictly as an upper-body visual layer while JRMCore remains authoritative
        // for the actual blocking mechanic.
        if(exists(clips,"guard_dualsword")) composite("guard_dualsword",true,.065F,.10F,upper);

        // Make the complete converted 20.9.5 archive addressable by runtime metadata.
        // Explicit definitions above keep their exact source semantics; every remaining
        // clip receives a family-derived definition instead of being dead data in the JAR.
        for(String n:clips.names())if(!byName.containsKey(n))byName.put(n,EpicSourceRegistry.definition(n,mesh,full,upper));
    }

    private void living(LivingMotion m,String clip,boolean loop,float convert,AnimationDefinition.SpeedMode speed) {
        AnimationDefinition d=new AnimationDefinition(clip,loop,convert,.10F,AnimationDefinition.LayerType.BASE,AnimationDefinition.Priority.LOWEST,speed,full,true);
        living.put(m,d);byName.put(clip,d);
    }
    private void action(String clip,float convert,float out,JointMask mask){byName.put(clip,new AnimationDefinition(clip,false,convert,out,AnimationDefinition.LayerType.ACTION,AnimationDefinition.Priority.HIGHEST,AnimationDefinition.SpeedMode.NORMAL,mask,true));}
    private void sourceWeaponAction(String clip,float convert){if(!byName.containsKey(clip))byName.put(clip,new AnimationDefinition(clip,false,convert,.001F,AnimationDefinition.LayerType.ACTION,AnimationDefinition.Priority.HIGHEST,AnimationDefinition.SpeedMode.NORMAL,full,true));}
    private void reaction(String clip,float convert,float out,JointMask mask){byName.put(clip,new AnimationDefinition(clip,false,convert,out,AnimationDefinition.LayerType.REACTION,AnimationDefinition.Priority.MIDDLE,AnimationDefinition.SpeedMode.NORMAL,mask,true));}
    private void composite(String clip,boolean loop,float convert,float out,JointMask mask){composite(clip,loop,convert,out,mask,AnimationDefinition.SpeedMode.NORMAL);}
    private void composite(String clip,boolean loop,float convert,float out,JointMask mask,AnimationDefinition.SpeedMode speed){byName.put(clip,new AnimationDefinition(clip,loop,convert,out,AnimationDefinition.LayerType.COMPOSITE,AnimationDefinition.Priority.MIDDLE,speed,mask,true));}
    private static boolean exists(ClipLibrary clips,String n){Clip c=clips.get(n);return c!=null;}

    public AnimationDefinition get(String name){return byName.get(name);}
    public AnimationDefinition living(LivingMotion motion){return living.get(motion);}
    public int registeredCount(){return byName.size();}
}
