package com.nicolas.epicfight1710.anim.runtime;

public final class AnimationDefinition {
    public enum LayerType { BASE, COMPOSITE, ACTION, REACTION }
    public enum Priority { LOWEST, MIDDLE, HIGHEST }
    public enum SpeedMode { NORMAL, WALK, RUN, SNEAK, FLY }

    public final String clip;
    public final boolean loop;
    public final float convertTime;
    public final float blendOut;
    public final LayerType layer;
    public final Priority priority;
    public final SpeedMode speedMode;
    public final JointMask mask;
    public final boolean lockHorizontalRoot;

    public AnimationDefinition(String clip,boolean loop,float convertTime,float blendOut,LayerType layer,Priority priority,SpeedMode speedMode,JointMask mask,boolean lockHorizontalRoot) {
        this.clip=clip;this.loop=loop;this.convertTime=convertTime;this.blendOut=blendOut;this.layer=layer;this.priority=priority;this.speedMode=speedMode;this.mask=mask;this.lockHorizontalRoot=lockHorizontalRoot;
    }
}
