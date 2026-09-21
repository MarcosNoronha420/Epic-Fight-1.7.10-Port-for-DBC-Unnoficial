package com.nicolas.epicfight1710.client;

/**
 * Short Dragon Ball-style vanish dash layered on top of the stable DBC flight state.
 *
 * Direction is sampled directly from the player's CURRENT movement key bindings at
 * the exact dash press (including rebinds and diagonals), then frozen for all slices.
 * Old velocity is never allowed to choose the dash direction.
 *
 * Visual feedback is the actual JRMCore/DBC Instant Transmission particle path
 * (bens_particles2.png) as one torso-locked arrival burst plus DBC's own sound. The old
 * cloud/crit/magicCrit trail/ring and Epic step recovery animation are removed.
 */
public final class DashController {
    public static final DashController INSTANCE=new DashController();

    private static final float DISTANCE=4.5F;
    private static final int MOVE_TICKS=3;
    private static final float STEP=DISTANCE/MOVE_TICKS;
    private static final int COOLDOWN_TICKS=8;
    private static final String DBC_DASH_SOUND="jinryuudragonbc:DBC5.instant_transmission";

    private final float[] dir=new float[3];
    private final float[] motion=new float[3];
    private Object player;
    private int stepsLeft;
    private int cooldownUntil;
    private boolean flying;
    private boolean oldInvisible;
    private boolean active;
    private boolean logged;
    private boolean missingInputLogged;

    private DashController(){}

    public boolean active(){return active;}
    public boolean activeFor(Object p){return active&&player==p;}
    public boolean ready(int tick){return !active&&tick>=cooldownUntil;}

    /** Starts a movement-only vanish. No step/recovery animation is returned. */
    public String start(Object p,int tick) {
        if(p==null||active||tick<cooldownUntil)return null;

        // Deterministic input contract: a dash only starts when at least one current
        // movement binding is physically held. This prevents a one-tick input miss from
        // falling back to stale Entity.motion* or camera direction and feeling random.
        if(!Compat.movementKeyVector(p,dir)) {
            if(!missingInputLogged) {
                missingInputLogged=true;
                System.out.println("[EpicFight1710] Vanish Dash waits for a held movement binding (Forward/Back/Left/Right); stale velocity and view fallback are intentionally ignored.");
            }
            return null;
        }
        float len=(float)Math.sqrt(dir[0]*dir[0]+dir[2]*dir[2]);
        if(len<1.0E-4F)return null;
        dir[0]/=len;dir[1]=0;dir[2]/=len;

        flying=DbcClientState.INSTANCE.flightVisualMode(p)!=DbcClientState.FLIGHT_NONE;
        player=p;stepsLeft=MOVE_TICKS;active=true;cooldownUntil=tick+COOLDOWN_TICKS;
        oldInvisible=Compat.invisible(p);
        Compat.rawMotionVector(p,motion);

        // Keep the native Instant Transmission burst BODY-LOCKED: do not leave an
        // origin sprite several blocks behind the player. The destination burst is
        // emitted after the final collision-resolved slice, centered through the torso.
        Compat.setInvisible(p,true);
        Compat.playEntitySound(p,DBC_DASH_SOUND,.25F,.96F);
        advance();
        if(!logged) {
            logged=true;
            System.out.println("[EpicFight1710] Vanish Dash 1.7 active: 4.5 blocks / 3 collision-resolved slices, direction frozen from held movement bindings, no step recovery/trail, one body-locked JRMCore Instant Transmission burst on arrival, DBC sound, flight altitude preserved.");
        }
        return null;
    }

    public void tick(Object current,int tick,boolean battleMode) {
        if(!active)return;
        if(current==null||current!=player||!battleMode||Compat.dead(current)) {finish(false);return;}
        if(stepsLeft>0)advance();
        if(stepsLeft<=0)finish(true);
    }

    public void cancel(){if(active)finish(false);}

    private void advance() {
        if(!active||player==null||stepsLeft<=0)return;
        // Never let residual DBC/vanilla velocity bend the frozen input direction.
        Compat.setMotion(player,0,0,0);
        double requested=STEP;
        double travelled=Compat.moveEntity(player,dir[0]*requested,0,dir[2]*requested);
        stepsLeft--;
        // A collision consumed most of a slice: stop instead of repeatedly forcing the
        // entity into the same wall for the remaining slices.
        if(travelled<requested*.28)stepsLeft=0;
    }

    private void finish(boolean destinationEffect) {
        Object p=player;
        if(p!=null) {
            Compat.setInvisible(p,oldInvisible);
            // Do not restore stale horizontal momentum: the held keys/DBC physics will
            // establish fresh movement next tick. Preserve only ordinary non-flight Y;
            // DBC hover receives zero Y so the dash cannot drag the player downward.
            Compat.setMotion(p,0,flying?0.0F:motion[1],0);
            if(destinationEffect)Compat.dbcInstantTransmissionParticle(p,Compat.x(p),Compat.y(p)+Compat.height(p)*.50F,Compat.z(p));
        }
        player=null;stepsLeft=0;active=false;
    }
}
