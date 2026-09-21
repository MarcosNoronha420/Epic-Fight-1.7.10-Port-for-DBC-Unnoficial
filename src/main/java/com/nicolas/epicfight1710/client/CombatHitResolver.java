package com.nicolas.epicfight1710.client;

import com.nicolas.epicfight1710.combat.AttackPhase;
import com.nicolas.epicfight1710.combat.AttackProfile;
import com.nicolas.epicfight1710.combat.ColliderDefinition;
import com.nicolas.epicfight1710.combat.ResolvedWeaponCapability;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Spatial hit resolver for Epic-style attack windows.
 *
 * The old 2.1 path selected one entity from a forward cone and then marked the
 * whole phase consumed. 2.2 keeps a hit mask per target, sweeps the authored
 * collider between previous/current entity transforms, and allows one window to
 * hit multiple distinct entities without hitting the same entity twice.
 */
final class CombatHitResolver {
    private final IdentityHashMap<Object,Integer> hitWindowsByTarget=new IdentityHashMap<Object,Integer>();
    private final float[] viewVector=new float[3];
    private long candidateTests,obbTests,hits;

    void reset(){hitWindowsByTarget.clear();}

    int resolve(Object player,AttackProfile profile,ResolvedWeaponCapability capability,float previousAge,float currentAge){
        if(player==null||profile==null)return 0;
        AttackPhase[] phases=profile.hitPhases;if(phases==null||phases.length==0)return 0;
        ColliderDefinition collider=profile.collider;
        if(collider==null&&capability!=null&&capability.style!=null)collider=capability.style.collider;
        int total=0;
        for(int i=0;i<phases.length&&i<31;i++){
            AttackPhase phase=phases[i];
            if(phase==null||!phase.activeBetween(previousAge,currentAge))continue;
            total+=collider==null?resolveLegacy(player,phase,i):resolveCollider(player,phase,collider,i);
        }
        return total;
    }

    private int resolveLegacy(Object player,AttackPhase phase,int phaseIndex){
        Object target=Compat.findAttackTarget(player,phase.range,phase.minFacingDot);
        if(target==null||alreadyHit(target,phaseIndex))return 0;
        markHit(target,phaseIndex);
        if(Compat.vanillaAttack(player,target)){hits++;return 1;}
        return 0;
    }

    private int resolveCollider(Object player,AttackPhase phase,ColliderDefinition collider,int phaseIndex){
        List<?> entities=Compat.loadedEntities(player);if(entities==null||entities.isEmpty())return 0;
        int total=0;
        final int samples=Math.max(1,collider.count);
        final float range=phase.range>0.0F?phase.range:collider.range;
        final float facing=phase.minFacingDot;
        final double broad=range+Math.max(collider.sizeX,Math.max(collider.sizeY,collider.sizeZ))+1.5;
        final double broad2=broad*broad;

        double ax=Compat.x(player),ay=Compat.y(player),az=Compat.z(player);
        double attackY=ay+Math.max(.35,Compat.height(player)*.55F);
        float latestFx=Compat.viewX(player),latestFy=Compat.viewY(player),latestFz=Compat.viewZ(player);

        for(Object entity:entities){
            if(entity==null||entity==player||Compat.dead(entity)||alreadyHit(entity,phaseIndex)||!Compat.livingEntity(entity))continue;
            candidateTests++;
            double tx=Compat.x(entity),ty=Compat.y(entity)+Math.max(.25,Compat.height(entity)*.5F),tz=Compat.z(entity);
            double dx=tx-ax,dy=ty-attackY,dz=tz-az,dist2=dx*dx+dy*dy+dz*dz;
            if(dist2>broad2||dist2<1.0E-8)continue;
            double dist=Math.sqrt(dist2);
            double dot=(dx*latestFx+dy*latestFy+dz*latestFz)/dist;
            if(dot<facing)continue;
            if(!Compat.canSeeAttackTarget(player,entity))continue;

            boolean contact=false;
            for(int s=0;s<samples;s++){
                float partial=samples==1?1.0F:(float)s/(float)(samples-1);
                if(intersectsSample(player,entity,collider,range,partial)){contact=true;break;}
            }
            if(!contact)continue;
            markHit(entity,phaseIndex);
            if(Compat.vanillaAttack(player,entity)){hits++;total++;}
        }
        return total;
    }

    /**
     * OBB vs entity-AABB conservative test. Collider dimensions are treated as half
     * extents, matching Epic Fight's box-collider convention. The 1.7.10 adapter has
     * no server-side joint collider, so the box is anchored to the interpolated player
     * view transform while preserving the authored center/size/range data.
     */
    private boolean intersectsSample(Object player,Object target,ColliderDefinition c,float range,float partial){
        obbTests++;
        double px=lerp(Compat.px(player),Compat.x(player),partial);
        double py=lerp(Compat.py(player),Compat.y(player),partial)+Math.max(.35,Compat.height(player)*.55F);
        double pz=lerp(Compat.pz(player),Compat.z(player),partial);

        Compat.viewVector(player,partial,viewVector);
        double fl=Math.sqrt(viewVector[0]*viewVector[0]+viewVector[1]*viewVector[1]+viewVector[2]*viewVector[2]);if(fl<1.0E-8)return false;
        double fx=viewVector[0]/fl,fy=viewVector[1]/fl,fz=viewVector[2]/fl;
        // right = normalized(worldUp x forward); near vertical, derive it from yaw.
        double rx=fz,ry=0.0,rz=-fx,rl=Math.sqrt(rx*rx+rz*rz);
        if(rl<1.0E-6){double yaw=Math.toRadians(Compat.prevYaw(player)+wrapDegrees(Compat.yaw(player)-Compat.prevYaw(player))*partial);rx=Math.cos(yaw);rz=Math.sin(yaw);rl=1.0;}
        rx/=rl;rz/=rl;
        // up = forward x right
        double ux=-fy*rz,uy=fz*rx-fx*rz,uz=fy*rx;
        double ul=Math.sqrt(ux*ux+uy*uy+uz*uz);if(ul<1.0E-8){ux=0;uy=1;uz=0;}else{ux/=ul;uy/=ul;uz/=ul;}

        // Epic Fight's OBBCollider constructor treats size as half-extents and center
        // as the model-local center. Keep that authored volume intact; range is only
        // the coarse entity-query/facing gate, never a hidden translation of the box.
        double forwardCenter=-c.centerZ;
        double cx=px+rx*c.centerX+ux*c.centerY+fx*forwardCenter;
        double cy=py+ry*c.centerX+uy*c.centerY+fy*forwardCenter;
        double cz=pz+rz*c.centerX+uz*c.centerY+fz*forwardCenter;

        double tx=lerp(Compat.px(target),Compat.x(target),partial);
        double ty=lerp(Compat.py(target),Compat.y(target),partial)+Compat.height(target)*.5F;
        double tz=lerp(Compat.pz(target),Compat.z(target),partial);
        double dx=tx-cx,dy=ty-cy,dz=tz-cz;
        double halfW=Math.max(.10,Compat.width(target)*.5F),halfH=Math.max(.10,Compat.height(target)*.5F);
        return intersectsObbAabb(dx,dy,dz,rx,ry,rz,ux,uy,uz,fx,fy,fz,c.sizeX,c.sizeY,c.sizeZ,halfW,halfH);
    }

    static boolean intersectsObbAabb(double dx,double dy,double dz,
            double rx,double ry,double rz,double ux,double uy,double uz,double fx,double fy,double fz,
            double ex,double ey,double ez,double targetHalfWidth,double targetHalfHeight){
        double localR=dx*rx+dy*ry+dz*rz;
        double localU=dx*ux+dy*uy+dz*uz;
        double localF=dx*fx+dy*fy+dz*fz;
        double inflateR=Math.abs(rx)*targetHalfWidth+Math.abs(ry)*targetHalfHeight+Math.abs(rz)*targetHalfWidth;
        double inflateU=Math.abs(ux)*targetHalfWidth+Math.abs(uy)*targetHalfHeight+Math.abs(uz)*targetHalfWidth;
        double inflateF=Math.abs(fx)*targetHalfWidth+Math.abs(fy)*targetHalfHeight+Math.abs(fz)*targetHalfWidth;
        return Math.abs(localR)<=ex+inflateR&&Math.abs(localU)<=ey+inflateU&&Math.abs(localF)<=ez+inflateF;
    }

    private boolean alreadyHit(Object target,int phase){Integer mask=hitWindowsByTarget.get(target);return mask!=null&&(mask.intValue()&(1<<phase))!=0;}
    private void markHit(Object target,int phase){Integer old=hitWindowsByTarget.get(target);int mask=old==null?0:old.intValue();hitWindowsByTarget.put(target,Integer.valueOf(mask|(1<<phase)));}
    private static double lerp(double a,double b,float t){return a+(b-a)*t;}
    private static float wrapDegrees(float v){while(v>180.0F)v-=360.0F;while(v<-180.0F)v+=360.0F;return v;}
    long candidateTests(){return candidateTests;}long obbTests(){return obbTests;}long hits(){return hits;}
}
