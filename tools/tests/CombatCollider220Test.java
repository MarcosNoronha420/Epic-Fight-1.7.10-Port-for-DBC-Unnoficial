package com.nicolas.epicfight1710.client;
public final class CombatCollider220Test {
  static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  public static void main(String[]args){
    // Identity OBB basis. Target AABB center is inside / outside each principal axis.
    check(CombatHitResolver.intersectsObbAabb(0,0,0, 1,0,0, 0,1,0, 0,0,1, .5,.5,1.0,.3,.9),"center contact");
    check(CombatHitResolver.intersectsObbAabb(.79,0,0, 1,0,0, 0,1,0, 0,0,1, .5,.5,1.0,.3,.9),"AABB inflation contact");
    check(!CombatHitResolver.intersectsObbAabb(.81,0,0, 1,0,0, 0,1,0, 0,0,1, .5,.5,1.0,.3,.9),"right-axis reject");
    check(!CombatHitResolver.intersectsObbAabb(0,1.41,0, 1,0,0, 0,1,0, 0,0,1, .5,.5,1.0,.3,.9),"up-axis reject");
    check(!CombatHitResolver.intersectsObbAabb(0,0,1.31, 1,0,0, 0,1,0, 0,0,1, .5,.5,1.0,.3,.3),"forward-axis reject");
    // Rotated 90 degrees around Y: right=(0,0,-1), forward=(1,0,0).
    check(CombatHitResolver.intersectsObbAabb(1.1,0,0, 0,0,-1, 0,1,0, 1,0,0, .25,.5,1.0,.2,.8),"rotated forward contact");
    System.out.println("PASS CombatCollider220Test OBB/AABB conservative intersection + rotated basis");
  }
}
