package com.nicolas.epicfight1710.combat;

/** Metadata only. Owned by the combat tick, never advanced by pose evaluation.
 * Single game-thread API; player/world are compared by identity, not equals().
 */
public final class CombatPoseState {
    private Object player,world;
    private long serial,epoch;
    private int tick;
    private String clip;
    private Frame frame;

    public void observeContext(Object player,Object world,int tick){
        if(this.player!=player||this.world!=world){
            this.player=player;this.world=world;clip=null;epoch++;frame=null;
        }
        if(this.tick!=tick){this.tick=tick;epoch++;frame=null;}
    }

    public void startAction(String clip){
        if(clip==null)throw new IllegalArgumentException("clip");
        if(serial==Long.MAX_VALUE)throw new IllegalStateException("Action serial exhausted");
        serial++;epoch++;this.clip=clip;frame=null;
    }

    public void clearAction(){clip=null;epoch++;frame=null;}
    public long actionSerial(){return serial;}

    /** Capture at an authoritative tick boundary. A changed age/revision retires
     * older frames; repeated identical captures return the same frame.
     */
    public Frame capture(float previousAge,float currentAge,long profileRevision){
        if(player==null||world==null||clip==null)return null;
        if(!finite(previousAge)||!finite(currentAge)||previousAge<0||currentAge<previousAge)
            throw new IllegalArgumentException("Invalid action interval");
        if(frame!=null&&frame.isValid()&&frame.previousAge==previousAge&&frame.currentAge==currentAge
                &&frame.profileRevision==profileRevision)return frame;
        epoch++;
        frame=new Frame(this,epoch,player,world,serial,tick,clip,previousAge,currentAge,profileRevision);
        return frame;
    }

    private static boolean finite(float f){return !Float.isNaN(f)&&!Float.isInfinite(f);}

    public static final class Frame {
        private final CombatPoseState owner;
        private final long epoch;
        public final Object playerIdentity,worldIdentity;
        public final long actionSerial,profileRevision;
        public final int gameTick;
        public final String clip;
        public final float previousAge,currentAge;
        private Frame(CombatPoseState owner,long epoch,Object player,Object world,long serial,int tick,
                      String clip,float previous,float current,long revision){
            this.owner=owner;this.epoch=epoch;playerIdentity=player;worldIdentity=world;
            actionSerial=serial;gameTick=tick;this.clip=clip;previousAge=previous;
            currentAge=current;profileRevision=revision;
        }
        public boolean isValid(){return owner.epoch==epoch&&owner.player==playerIdentity
                &&owner.world==worldIdentity&&owner.clip!=null&&owner.serial==actionSerial;}
    }
}
