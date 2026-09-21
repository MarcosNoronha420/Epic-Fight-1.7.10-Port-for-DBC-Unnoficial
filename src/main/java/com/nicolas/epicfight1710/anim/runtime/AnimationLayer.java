package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.ClipLibrary;

/**
 * One animation layer with conversion (link-animation style) between clips.
 * Transition/fade clocks keep both previous- and current-tick values so render
 * partial ticks interpolate the weight at frame rate rather than stepping at 20 Hz.
 */
public final class AnimationLayer {
    private final AnimationPlayer player;
    private final AnimationPose currentPose;
    private final AnimationPose previousSnapshot;
    private final AnimationPose output;
    private float transitionAge,prevTransitionAge;
    private float transitionDuration;
    private boolean transition;
    private boolean enabled;
    private boolean freshEntry;
    private boolean exiting;
    private float exitAge,prevExitAge;
    private float exitDuration;

    public AnimationLayer(ClipLibrary clips,int joints) {
        player=new AnimationPlayer(clips);
        currentPose=new AnimationPose(joints);
        previousSnapshot=new AnimationPose(joints);
        output=new AnimationPose(joints);
    }

    public void play(AnimationDefinition def,float start,float partialSnapshot) {
        if(def==null){off();return;}
        if(enabled) {
            // Snapshot the pose that was actually visible at the state switch, including
            // an in-progress conversion. This avoids restarting from the old clip's raw
            // target pose when states change rapidly.
            sample(partialSnapshot,previousSnapshot);
            transition=true;transitionAge=prevTransitionAge=0;transitionDuration=Math.max(0.001F,def.convertTime);
            freshEntry=false;
        } else {
            if(def.layer==AnimationDefinition.LayerType.BASE) {
                previousSnapshot.setIdentityPresent();
                transition=true;transitionAge=prevTransitionAge=0;transitionDuration=Math.max(0.001F,def.convertTime);
            } else {
                previousSnapshot.clear();transition=false;transitionAge=prevTransitionAge=0;transitionDuration=0;
            }
            freshEntry=true;
        }
        exiting=false;exitAge=prevExitAge=0;exitDuration=0;
        player.play(def,start);enabled=true;
    }

    public void tick(float dt,float speed) {
        if(!enabled)return;
        player.tick(dt,speed);
        if(transition) {
            prevTransitionAge=transitionAge;
            transitionAge=Math.min(transitionDuration,transitionAge+dt);
            // Keep the conversion alive for the render interval that reaches 100%.
            if(prevTransitionAge>=transitionDuration)transition=false;
        }
        if(exiting) {
            prevExitAge=exitAge;
            exitAge=Math.min(exitDuration,exitAge+dt);
            // Same rule for exits: render the final 1 -> 0 interval before disabling.
            if(prevExitAge>=exitDuration){off();return;}
        }
    }

    public AnimationPose sample(float partial,AnimationPose target) {
        if(!enabled){target.clear();return target;}
        player.sample(partial,currentPose);
        if(transition) {
            float age=lerp(prevTransitionAge,transitionAge,clamp01(partial));
            float t=smooth(clamp01(age/transitionDuration));
            target.blend(previousSnapshot,currentPose,t,player.definition().mask);
        } else target.copyFrom(currentPose);
        return target;
    }

    public AnimationPose sample(float partial){return sample(partial,output);}

    /** Graceful exit for looped/composite states such as DBC guard. */
    public void requestStop(float duration) {
        if(!enabled||exiting)return;
        exiting=true;exitAge=prevExitAge=0.0F;exitDuration=Math.max(0.001F,duration);
    }

    public void off(){enabled=false;transition=false;freshEntry=false;exiting=false;transitionAge=prevTransitionAge=exitAge=prevExitAge=exitDuration=0;player.clear();output.clear();}
    public boolean enabled(){return enabled;}
    public boolean ended(){return !enabled||player.ended();}
    public AnimationDefinition definition(){return player.definition();}
    public float elapsed(){return player.elapsed();}
    public float interpolatedElapsed(float partial){return player.interpolatedElapsed(partial);}
    public float duration(){return player.clip()==null?0.0F:player.clip().duration;}

    public float blendOutWeight(float partial){
        if(!enabled||player.definition()==null||player.clip()==null)return 0.0F;
        AnimationDefinition d=player.definition();
        if(d.loop)return 1.0F;
        float remaining=player.clip().duration-player.interpolatedElapsed(partial);
        if(remaining>=d.blendOut)return 1.0F;
        return smooth(clamp01(remaining/Math.max(0.001F,d.blendOut)));
    }
    public float blendOutWeight(){return blendOutWeight(1.0F);}

    public float transitionWeight(float partial){
        if(!transition)return 1.0F;
        float age=lerp(prevTransitionAge,transitionAge,clamp01(partial));
        return smooth(clamp01(age/transitionDuration));
    }
    public float transitionWeight(){return transitionWeight(1.0F);}

    public float enterWeight(float partial){
        if(!enabled||player.definition()==null)return 0.0F;
        if(!freshEntry)return 1.0F;
        float c=player.definition().convertTime;
        float w=c<=0.0001F?1.0F:smooth(clamp01(player.interpolatedElapsed(partial)/c));
        if(w>=0.9999F&&partial>=.999F)freshEntry=false;
        return w;
    }
    public float enterWeight(){return enterWeight(1.0F);}

    public float exitWeight(float partial){
        if(!enabled)return 0.0F;
        if(!exiting)return 1.0F;
        float age=lerp(prevExitAge,exitAge,clamp01(partial));
        return 1.0F-smooth(clamp01(age/Math.max(.001F,exitDuration)));
    }
    public float exitWeight(){return exitWeight(1.0F);}

    public float effectiveWeight(float partial){return enterWeight(partial)*blendOutWeight(partial)*exitWeight(partial);}
    public float effectiveWeight(){return effectiveWeight(1.0F);}

    private static float smooth(float x){return x*x*(3.0F-2.0F*x);}
    private static float clamp01(float x){return x<0?0:(x>1?1:x);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
}
