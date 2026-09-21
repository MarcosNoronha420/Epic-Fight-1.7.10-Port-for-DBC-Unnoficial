package com.nicolas.epicfight1710.client;
public final class AttackCadence220Test {
  static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  static float playbackAt(double cps){
    AttackCadenceTracker t=new AttackCadenceTracker();long now=System.nanoTime(),step=(long)(1_000_000_000.0/cps);long[] a=new long[16];int n=8;
    for(int i=0;i<n;i++)a[i]=now-(long)(n-1-i)*step;
    t.seed(a,n,n);return t.playbackSpeed();
  }
  public static void main(String[]args){
    float p67=playbackAt(6.7),p8=playbackAt(8),p10=playbackAt(10),p136=playbackAt(13.6),p20=playbackAt(20);
    check(Math.abs(p67-1f)<.0001f,"<=6.8 must be 1x");check(p8>1&&p8<2,"8 cps");check(p10>p8&&p10<2,"10 cps");check(Math.abs(p136-2f)<.01f,"13.6");check(Math.abs(p20-2f)<.0001f,"20");
    System.out.println("PASS AttackCadence220Test speeds="+p67+","+p8+","+p10+","+p136+","+p20);
  }
}
