package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.combat.ResolvedWeaponCapability;

/**
 * Owns the DBC -> Epic guard runtime state.
 *
 * JRMCore remains authoritative for whether the player is blocking.  This class
 * only provides the two-tick release hysteresis used by the animation layer and
 * converts a newly observed hurt tick into the matching Epic guard-hit clip.
 * Keeping this state out of CombatController prevents input/action timing from
 * becoming coupled to DBC defense reflection.
 */
final class CombatGuardRuntime {
    private boolean blocking;
    private int releaseTicks;
    private int lastHurtTime;
    private String pendingReaction;

    boolean blocking(){return blocking;}

    String pollReaction(){
        String reaction=pendingReaction;
        pendingReaction=null;
        return reaction;
    }

    void clear(){
        blocking=false;
        releaseTicks=0;
        lastHurtTime=0;
        pendingReaction=null;
    }

    void reset(Object player,boolean battleMode){
        pendingReaction=null;
        releaseTicks=0;
        blocking=battleMode&&player!=null&&Compat.dbcBlocking(player)==1;
        lastHurtTime=player==null?0:Compat.hurtTime(player);
    }

    /** @return true when the visual guard state changed this tick. */
    boolean tick(Object player,boolean battleMode,ResolvedWeaponCapability capability){
        boolean previous=blocking;
        boolean raw=battleMode&&player!=null&&Compat.dbcBlocking(player)==1;
        if(raw){
            blocking=true;
            releaseTicks=0;
        }else if(blocking&&battleMode){
            if(++releaseTicks>=2){
                blocking=false;
                releaseTicks=0;
            }
        }else{
            blocking=false;
            releaseTicks=0;
        }

        int hurt=player==null?0:Compat.hurtTime(player);
        if(battleMode&&blocking&&hurt>lastHurtTime){
            String reaction=capability==null?null:capability.guardHitClip();
            if(reaction==null&&capability!=null&&capability.isFist()
                    &&RuntimeAssets.CLIPS.get("guard_dualsword_hit")!=null){
                reaction="guard_dualsword_hit";
            }
            if(reaction!=null&&RuntimeAssets.CLIPS.get(reaction)!=null)pendingReaction=reaction;
        }
        lastHurtTime=hurt;
        return previous!=blocking;
    }
}
