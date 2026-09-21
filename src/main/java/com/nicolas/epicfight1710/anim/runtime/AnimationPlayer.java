package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.Clip;
import com.nicolas.epicfight1710.anim.ClipLibrary;

/** Time cursor for one DynamicAnimation-like slot. */
public final class AnimationPlayer {
    private final ClipLibrary clips;
    private AnimationDefinition definition;
    private Clip activeClip;
    private float elapsed;
    private float prevElapsed;
    private boolean reachedEnd=true;

    public AnimationPlayer(ClipLibrary clips){this.clips=clips;}

    public void play(AnimationDefinition def,float startTime) {
        definition=def;activeClip=def==null?null:clips.get(def.clip);elapsed=Math.max(0.0F,startTime);prevElapsed=elapsed;reachedEnd=def==null||activeClip==null;
    }
    public void clear(){definition=null;activeClip=null;elapsed=prevElapsed=0;reachedEnd=true;}

    public void tick(float dt,float speed) {
        prevElapsed=elapsed;
        if(definition==null){reachedEnd=true;return;}
        Clip clip=activeClip;if(clip==null){reachedEnd=true;return;}
        elapsed+=Math.max(0.0F,dt*speed);
        if(definition.loop && clip.duration>0.0001F) {
            while(elapsed>=clip.duration)elapsed-=clip.duration;
            while(elapsed<0)elapsed+=clip.duration;
            reachedEnd=false;
        } else if(elapsed>=clip.duration) {elapsed=clip.duration;reachedEnd=true;}
    }

    public void sample(float partial,AnimationPose out) {
        if(definition==null){out.clear();return;}
        Clip c=activeClip;if(c==null){out.clear();return;}
        out.sample(c,interpolatedElapsed(partial),definition.loop);
    }

    /**
     * Render-time cursor. Looping clips are unwrapped before interpolation so a
     * boundary such as 0.76 -> 0.02 is treated as forward time through duration,
     * never as a one-frame jump through the middle of the animation.
     */
    public float interpolatedElapsed(float partial) {
        if(definition==null)return 0.0F;
        Clip c=activeClip;if(c==null)return 0.0F;
        float p=clamp01(partial);
        if(definition.loop && c.duration>0.0001F) {
            float a=prevElapsed,b=elapsed;
            if(b<a)b+=c.duration;
            float t=a+(b-a)*p;
            while(t>=c.duration)t-=c.duration;
            while(t<0.0F)t+=c.duration;
            return t;
        }
        return prevElapsed+(elapsed-prevElapsed)*p;
    }

    public AnimationDefinition definition(){return definition;}
    public float elapsed(){return elapsed;}
    public float prevElapsed(){return prevElapsed;}

    /**
     * Do not report a non-looping clip as fully ended on the exact tick that first
     * reaches its final key. Keeping it alive through that render interval lets
     * blend-out weight reach zero continuously instead of disappearing at tick rate.
     */
    public boolean ended(){
        if(definition==null)return true;
        Clip c=clip();if(c==null)return true;
        if(definition.loop)return false;
        return reachedEnd && prevElapsed>=c.duration-.00001F;
    }
    public Clip clip(){return activeClip;}
    private static float clamp01(float v){return v<0?0:(v>1?1:v);}
}
