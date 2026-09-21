package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.anim.Clip;
import com.nicolas.epicfight1710.combat.AttackProfile;
import com.nicolas.epicfight1710.combat.AttackState;
import com.nicolas.epicfight1710.combat.FistMoveset;
import com.nicolas.epicfight1710.combat.LocalPlayerPatch1710;
import com.nicolas.epicfight1710.combat.ResolvedWeaponCapability;
import com.nicolas.epicfight1710.combat.WeaponCapabilityRegistry;

/**
 * Client-side bridge for Epic Fight 20.9.5 BasicAttack semantics.
 *
 * 2.0 deliberately narrows this class: Item Capability matching, active Style,
 * combo lifetime and weapon definitions live in LocalPlayerPatch1710 + the data-
 * driven WeaponCapability registry. 2.2 delegates spatial contact to
 * CombatHitResolver; this controller keeps action selection/clock and DBC damage
 * dispatch orchestration only.
 */
public final class CombatController {
    public static final CombatController INSTANCE=new CombatController();
    private enum ActionType { NONE, ATTACK, DASH }

    private int playbackSpeedTick=Integer.MIN_VALUE;
    private float cachedActionPlaybackSpeed=1.0F;
    private boolean fastCpsLogged;

    private final LocalPlayerPatch1710 playerPatch=new LocalPlayerPatch1710();
    private final CombatHitResolver hitResolver=new CombatHitResolver();
    private final AttackCadenceTracker cadence=new AttackCadenceTracker();
    private final CombatGuardRuntime guardRuntime=new CombatGuardRuntime();
    private boolean battleMode;
    private int clientTick;
    private String clipName;
    private float attackElapsed,previousAttackElapsed;
    private boolean dash;
    private int airborneTicks;
    private ActionType actionType=ActionType.NONE;
    private int battleEnterTick;
    private boolean dashWasDown;
    private ResolvedWeaponCapability actionCapability=WeaponCapabilityRegistry.resolveActive(null);
    private AttackProfile activeProfile;

    private boolean weaponLogged,sourceBasicAttackLogged,relentlessLogged,stateSpectrumLogged,mountLogged;

    private CombatController() {}

    public boolean battleMode(){return battleMode;}
    public int tick(){return clientTick;}
    public String attackClip(){return clipName;}
    public int comboStage(){return playerPatch.comboStage();}
    public int airborneTicks(){return airborneTicks;}
    public boolean attacking(){return actionType==ActionType.ATTACK&&clipName!=null;}
    public boolean dashPose(){return actionType==ActionType.DASH&&clipName!=null;}
    public float battleModeAge(){return battleMode?(clientTick-battleEnterTick)/20.0F:0.0F;}
    public boolean blocking(){return battleMode&&guardRuntime.blocking();}
    public ResolvedWeaponCapability activeCapability(Object player){return playerPatch.update(player,clientTick);}
    public AttackState actionState(){return activeProfile==null?null:activeProfile.stateAt(attackAge(0.0F));}
    public String pollGuardReaction(){return guardRuntime.pollReaction();}

    public boolean dbcFlying(){
        Object p=Compat.player();
        return battleMode&&p!=null&&DbcClientState.INSTANCE.flightVisualMode(p)!=DbcClientState.FLIGHT_NONE;
    }

    public void tickClient() {
        long prof=RuntimeProfiler.INSTANCE.begin();
        try { tickClientInternal(); } finally { RuntimeProfiler.INSTANCE.end(RuntimeProfiler.COMBAT,prof); }
    }

    private void tickClientInternal() {
        clientTick++;
        RuntimeProfiler.INSTANCE.reportIfDue(clientTick);
        Object p=Compat.player();
        if(p==null){
            airborneTicks=0;guardRuntime.clear();
            DashController.INSTANCE.cancel();dashWasDown=ClientKeyBindings.dashVanishDown();playerPatch.reset();return;
        }
        playerPatch.update(p,clientTick);
        if(Compat.onGround(p))airborneTicks=0;else if(airborneTicks<1000000)airborneTicks++;

        boolean guardChanged=guardRuntime.tick(p,battleMode,playerPatch.current());

        DbcClientState.INSTANCE.refreshFlightVisual(p,clientTick);
        DashController.INSTANCE.tick(p,clientTick,battleMode);
        boolean dashKey=ClientKeyBindings.dashVanishDown();
        if(battleMode&&!Compat.guiOpen()&&dashKey&&!dashWasDown)dashPressed();
        dashWasDown=dashKey;
        if(guardChanged&&Boolean.getBoolean("epicfight1710.debugcombat"))
            System.out.println("[EpicFight1710] DBC defense "+(guardRuntime.blocking()?"ON -> Epic guard Style layer":"OFF -> guard fade-out"));
        if(!battleMode)return;

        if(clipName!=null) {
            previousAttackElapsed=attackElapsed;
            attackElapsed+=0.05F*actionPlaybackSpeed();
            float age=attackElapsed;
            if(actionType==ActionType.ATTACK)tickAttack(p,age);
            Clip c=RuntimeAssets.CLIPS.get(clipName);
            float end=c==null?(dash?.92F:.55F):c.duration;
            if(age>=end)clearAction();
        }
    }

    private void tickAttack(Object p,float age) {
        AttackProfile profile=activeProfile;if(profile==null)return;
        long prof=RuntimeProfiler.INSTANCE.begin();
        hitResolver.resolve(p,profile,actionCapability,previousAttackElapsed,age);
        RuntimeProfiler.INSTANCE.end(RuntimeProfiler.COLLIDER,prof);
    }

    public void toggleBattleMode() {
        battleMode=!battleMode;
        if(battleMode) {
            battleEnterTick=clientTick;Object p=Compat.player();
            guardRuntime.reset(p,true);
            if(p!=null)DbcClientState.INSTANCE.refreshFlightVisual(p,clientTick);
            PoseEngine.INSTANCE.reset();playerPatch.reset();if(p!=null)playerPatch.update(p,clientTick);
            JbraWeightedPartRenderer.INSTANCE.resetPerformanceCounters();RuntimeProfiler.INSTANCE.reset();
            System.out.println("[EpicFight1710] Battle mode ON");
        } else {
            clearAction();DashController.INSTANCE.cancel();guardRuntime.clear();playerPatch.reset();
            PoseEngine.INSTANCE.reset();System.out.println("[EpicFight1710] Battle mode OFF");
        }
    }

    public void attackPressed() {
        if(!battleMode||guardRuntime.blocking()||DashController.INSTANCE.active())return;
        Object p=Compat.player();if(p==null)return;
        cadence.notePress();playbackSpeedTick=Integer.MIN_VALUE;

        ResolvedWeaponCapability now=playerPatch.update(p,clientTick);
        if(clipName!=null) {
            if(actionType!=ActionType.ATTACK||activeProfile==null)return;
            AttackState state=activeProfile.stateAt(attackAge(0.0F));
            if(!state.canBasicAttack||activeProfile.dash)return;
            if(actionCapability==null||!actionCapability.sameTypeAndStyle(now))return;
            AttackProfile next=selectCombo(now,playerPatch.comboCounter());
            if(next!=null)startBasicAttack(next,now,false);
            return;
        }

        boolean flying=Compat.dbcFlying(p),ground=Compat.onGround(p);
        // Exact BasicAttack rejects ordinary in-air state. Confirmed DBC flight stays
        // a deliberate adapter exception so Dragon Ball aerial combat remains usable.
        if(!ground&&!flying)return;

        AttackProfile profile;
        if(Compat.riding(p)&&now.mount()!=null){
            profile=now.mount();if(profile!=null){if(!mountLogged){mountLogged=true;System.out.println("[EpicFight1710] Source mount-attack slot active through Style=MOUNT when the 1.7.10 player is riding.");}startBasicAttack(profile,now,true);}return;
        }
        if(ground&&Compat.sprinting(p)){
            profile=now.dash();if(profile!=null)startBasicAttack(profile,now,true);
        }else{
            profile=selectCombo(now,playerPatch.comboCounter());if(profile!=null)startBasicAttack(profile,now,false);
        }
    }

    private AttackProfile selectFistCombo(int counter){return FistMoveset.combo(counter,cadence.measuredCps()>AttackCadenceTracker.FAST_FIST_CPS_THRESHOLD);}
    private AttackProfile selectCombo(ResolvedWeaponCapability capability,int counter){
        if(capability==null)capability=WeaponCapabilityRegistry.resolveActive(null);
        if(capability.isFist())return selectFistCombo(counter);
        return capability.combo(counter,false);
    }

    private void startBasicAttack(AttackProfile profile,ResolvedWeaponCapability capability,boolean resetCombo){
        actionCapability=capability==null?WeaponCapabilityRegistry.resolveActive(null):capability;
        activeProfile=profile;playerPatch.commit(profile,clientTick,resetCombo||profile.dash);
        startClip(profile.clip,ActionType.ATTACK,profile.dash);
        if(actionCapability.armed()&&!weaponLogged){
            weaponLogged=true;
            System.out.println("[EpicFight1710] Epic WeaponCapability pipeline active: Item Capability -> Weapon Type="+actionCapability.capability.id+" category="+actionCapability.capability.category+" -> Style="+actionCapability.combatStyle()+" -> "+profile.clip+". Combo/living/collider data comes from weapon_types.json.");
        }
        if(!sourceBasicAttackLogged){sourceBasicAttackLogged=true;System.out.println("[EpicFight1710] Epic 20.9.5 BasicAttack gate active: one AnimationPlayer clock drives contact, StateSpectrum recovery and combo chaining; no early queued-click buffer.");}
        if(!stateSpectrumLogged){stateSpectrumLogged=true;System.out.println("[EpicFight1710] Attack StateSpectrum adapter active: ATTACKING/CAN_BASIC_ATTACK/INACTION/movement-turn locks are evaluated from the source action timeline instead of duplicated controller timers.");}
        if("relentless_combo".equals(profile.clip)&&!relentlessLogged){relentlessLogged=true;System.out.println("[EpicFight1710] RELENTLESS_COMBO remains gated strictly above measured 6.8 CPS and uses all 8 authored hit windows.");}
    }

    /** 4.5-block DBC Vanish Dash; intentionally separate from Epic BasicAttack dash. */
    public void dashPressed(){
        if(!battleMode||clipName!=null||DashController.INSTANCE.active())return;
        Object p=Compat.player();if(p==null)return;
        String recovery=DashController.INSTANCE.start(p,clientTick);
        if(DashController.INSTANCE.active()&&recovery!=null&&RuntimeAssets.CLIPS.get(recovery)!=null)startClip(recovery,ActionType.DASH,false);
    }

    private void clearAction(){clipName=null;activeProfile=null;hitResolver.reset();dash=false;actionType=ActionType.NONE;actionCapability=WeaponCapabilityRegistry.resolveActive(null);attackElapsed=previousAttackElapsed=0.0F;playbackSpeedTick=Integer.MIN_VALUE;cachedActionPlaybackSpeed=1.0F;}
    private void startClip(String name,ActionType type,boolean isDash){clipName=name;actionType=type;dash=isDash;attackElapsed=previousAttackElapsed=0.0F;hitResolver.reset();playbackSpeedTick=Integer.MIN_VALUE;cachedActionPlaybackSpeed=1.0F;if(Boolean.getBoolean("epicfight1710.debugactions"))System.out.println("[EpicFight1710] Action clip started: "+name+" type="+type+" @ tick "+clientTick);}

    public float attackAge(float partial){if(clipName==null)return -1.0F;float a=partial<0.0F?0.0F:(partial>1.0F?1.0F:partial);return previousAttackElapsed+(attackElapsed-previousAttackElapsed)*a;}

    public float actionPlaybackSpeed(){if(playbackSpeedTick==clientTick)return cachedActionPlaybackSpeed;playbackSpeedTick=clientTick;cachedActionPlaybackSpeed=computeActionPlaybackSpeed();return cachedActionPlaybackSpeed;}
    private float computeActionPlaybackSpeed(){
        if(actionType!=ActionType.ATTACK||actionCapability==null||!actionCapability.isFist())return 1.0F;
        float speed=cadence.playbackSpeed();
        if(speed>1.0F&&!fastCpsLogged){fastCpsLogged=true;System.out.println("[EpicFight1710] Source-style FIST attack-speed scaling active above 6.8 CPS: Epic 3-decimal ratio, interval-swept hit windows and StateSpectrum recovery share one clock; capped at 2.0x.");}
        return speed;
    }
}
