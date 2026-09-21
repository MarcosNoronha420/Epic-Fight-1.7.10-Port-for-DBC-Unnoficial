package com.nicolas.epicfight1710.anim.runtime;

import com.nicolas.epicfight1710.anim.SkeletonMesh;

/**
 * Metadata bridge for the complete converted Epic Fight 20.9.5 biped archive.
 *
 * Earlier alphas shipped 242 source clips but only created runtime definitions for
 * a small hand-written subset. That made the project behave like a demo even though
 * the source data was already present. This registry classifies every converted clip
 * into Epic-style layer/mask semantics so gameplay routers can opt into weapon,
 * interaction, reaction and skill families without another renderer rewrite.
 *
 * Classification never changes the clip's authored keyframes.
 */
public final class EpicSourceRegistry {
    public enum Family { LIVING, WEAPON_LOCOMOTION, HOLD, ITEM_USE, AIM, GUARD, REACTION, DODGE, ATTACK, SKILL }

    private EpicSourceRegistry() {}

    public static Family family(String n) {
        if(n==null)return Family.SKILL;
        if(n.equals("idle")||n.equals("walk")||n.equals("run")||n.equals("sneak")||n.equals("jump")||n.equals("fall")||n.equals("landing")||n.equals("fly")||n.equals("float")||n.equals("swim")||n.equals("climb")||n.equals("mount")||n.equals("sit")||n.equals("sleep")||n.equals("kneel")||n.equals("death")||n.startsWith("creative_"))return Family.LIVING;
        if(n.startsWith("walk_")||n.startsWith("run_"))return Family.WEAPON_LOCOMOTION;
        if(n.startsWith("hold_")||n.startsWith("shield_"))return Family.HOLD;
        if(n.startsWith("dig_")||n.startsWith("eat_")||n.startsWith("drink_")||n.indexOf("reload")>=0)return Family.ITEM_USE;
        if(n.indexOf("_aim_")>=0||n.startsWith("bow_aim")||n.startsWith("crossbow_aim")||n.startsWith("javelin_aim"))return Family.AIM;
        if(n.startsWith("guard_"))return n.indexOf("_hit")>=0?Family.REACTION:Family.GUARD;
        if(n.startsWith("hit_")||n.startsWith("knockdown")||n.endsWith("_hit"))return Family.REACTION;
        if(n.startsWith("roll_")||n.startsWith("step_"))return Family.DODGE;
        if(n.indexOf("_auto")>=0||n.endsWith("_dash")||n.endsWith("_airslash")||n.startsWith("mob_"))return Family.ATTACK;
        return Family.SKILL;
    }

    public static AnimationDefinition definition(String clip,SkeletonMesh mesh,JointMask full,JointMask upper) {
        Family f=family(clip);
        AnimationDefinition.LayerType layer;
        AnimationDefinition.Priority priority;
        AnimationDefinition.SpeedMode speed=AnimationDefinition.SpeedMode.NORMAL;
        JointMask mask=full;
        boolean loop=false;
        float convert=.08F,out=.10F;
        switch(f) {
            case LIVING:
                layer=AnimationDefinition.LayerType.BASE;priority=AnimationDefinition.Priority.LOWEST;loop=true;
                if(clip.startsWith("walk"))speed=AnimationDefinition.SpeedMode.WALK;
                else if(clip.startsWith("run"))speed=AnimationDefinition.SpeedMode.RUN;
                else if(clip.equals("sneak"))speed=AnimationDefinition.SpeedMode.SNEAK;
                else if(clip.indexOf("fly")>=0) speed=AnimationDefinition.SpeedMode.FLY;
                if(clip.equals("jump")||clip.equals("landing")||clip.equals("death"))loop=false;
                break;
            case WEAPON_LOCOMOTION:
                layer=AnimationDefinition.LayerType.COMPOSITE;priority=AnimationDefinition.Priority.MIDDLE;loop=true;mask=full;
                speed=clip.startsWith("run_")?AnimationDefinition.SpeedMode.RUN:AnimationDefinition.SpeedMode.WALK;
                break;
            case HOLD:
                layer=AnimationDefinition.LayerType.COMPOSITE;priority=AnimationDefinition.Priority.MIDDLE;loop=true;mask=upper;convert=.10F;
                break;
            case ITEM_USE:
            case AIM:
                layer=AnimationDefinition.LayerType.COMPOSITE;priority=AnimationDefinition.Priority.MIDDLE;loop=f==Family.AIM;mask=upper;convert=.08F;
                break;
            case GUARD:
                layer=AnimationDefinition.LayerType.COMPOSITE;priority=AnimationDefinition.Priority.MIDDLE;loop=true;mask=upper;convert=.065F;
                break;
            case REACTION:
                layer=AnimationDefinition.LayerType.REACTION;priority=AnimationDefinition.Priority.MIDDLE;mask=full;convert=.025F;
                break;
            case DODGE:
                layer=AnimationDefinition.LayerType.ACTION;priority=AnimationDefinition.Priority.HIGHEST;mask=full;convert=.045F;
                break;
            case ATTACK:
                layer=AnimationDefinition.LayerType.ACTION;priority=AnimationDefinition.Priority.HIGHEST;mask=full;convert=.08F;
                break;
            default:
                layer=AnimationDefinition.LayerType.ACTION;priority=AnimationDefinition.Priority.HIGHEST;mask=full;convert=.08F;
                break;
        }
        return new AnimationDefinition(clip,loop,convert,out,layer,priority,speed,mask,true);
    }
}
