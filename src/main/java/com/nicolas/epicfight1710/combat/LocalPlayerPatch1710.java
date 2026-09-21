package com.nicolas.epicfight1710.combat;

/**
 * Local-player compatibility patch inspired by Epic Fight PlayerPatch/BasicAttack.
 * It owns current ItemCapability/Style and combo lifetime so CombatController only
 * handles input + animation clock instead of also being the weapon database.
 */
public final class LocalPlayerPatch1710 {
    private ResolvedWeaponCapability current=WeaponCapabilityRegistry.resolveActive(null);
    private int comboCounter,comboStage,lastActionTick=Integer.MIN_VALUE;
    private long registryGeneration=WeaponCapabilityRegistry.generation();
    private int lastUpdateTick=Integer.MIN_VALUE;
    private Object lastUpdatePlayer;

    public void reset(){registryGeneration=WeaponCapabilityRegistry.generation();current=WeaponCapabilityRegistry.resolveActive(null);comboCounter=comboStage=0;lastActionTick=Integer.MIN_VALUE;lastUpdateTick=Integer.MIN_VALUE;lastUpdatePlayer=null;}
    public ResolvedWeaponCapability update(Object player,int tick){
        long gen=WeaponCapabilityRegistry.generation();
        if(player==lastUpdatePlayer&&tick==lastUpdateTick&&gen==registryGeneration)return current;
        if(gen!=registryGeneration){registryGeneration=gen;current=WeaponCapabilityRegistry.resolveActive(null);comboCounter=comboStage=0;lastActionTick=Integer.MIN_VALUE;}
        ResolvedWeaponCapability now=WeaponCapabilityRegistry.resolveActive(player);
        if(current==null||!current.sameTypeAndStyle(now)){current=now;comboCounter=comboStage=0;lastActionTick=Integer.MIN_VALUE;}
        if(lastActionTick!=Integer.MIN_VALUE&&tick-lastActionTick>16){comboCounter=comboStage=0;lastActionTick=Integer.MIN_VALUE;}
        lastUpdatePlayer=player;lastUpdateTick=tick;
        return current;
    }
    public ResolvedWeaponCapability current(){return current;}
    public int comboStage(){return comboStage;}
    public int comboCounter(){return comboCounter;}
    public AttackProfile nextNormal(boolean relentless){return current==null?null:current.combo(comboCounter,relentless);}
    public void commit(AttackProfile p,int tick,boolean resetCombo){
        if(p==null)return;lastActionTick=tick;
        if(resetCombo||p.dash){comboCounter=comboStage=0;return;}
        comboStage=comboCounter;comboCounter=p.nextCombo;
    }
    public void forceResetCombo(){comboCounter=comboStage=0;lastActionTick=Integer.MIN_VALUE;}
}
