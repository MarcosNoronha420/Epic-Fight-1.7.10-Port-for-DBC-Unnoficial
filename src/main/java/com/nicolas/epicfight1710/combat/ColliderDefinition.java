package com.nicolas.epicfight1710.combat;

/**
 * Data-facing Epic Fight collider preset used by the 1.7.10 spatial hit resolver.
 * Damage still delegates to Minecraft/DBC, while volume/count/range/facing remain
 * authored alongside the active Weapon Type instead of being hard-coded in input code.
 */
public final class ColliderDefinition {
    public final int count;
    public final float centerX,centerY,centerZ;
    public final float sizeX,sizeY,sizeZ;
    public final float range,minFacingDot;

    public ColliderDefinition(int count,float centerX,float centerY,float centerZ,
                              float sizeX,float sizeY,float sizeZ,float range,float minFacingDot){
        this.count=Math.max(1,count);this.centerX=centerX;this.centerY=centerY;this.centerZ=centerZ;
        this.sizeX=Math.abs(sizeX);this.sizeY=Math.abs(sizeY);this.sizeZ=Math.abs(sizeZ);
        this.range=range;this.minFacingDot=minFacingDot;
    }
}
