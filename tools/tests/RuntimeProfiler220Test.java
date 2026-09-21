package com.nicolas.epicfight1710.client;
public final class RuntimeProfiler220Test {
  public static void main(String[] args) {
    RuntimeProfiler p=RuntimeProfiler.INSTANCE;
    long start=p.begin();
    p.end(RuntimeProfiler.ANIMATOR,start);
    p.reportIfDue(0);
    System.out.println("PASS RuntimeProfiler220Test enabled="+p.enabled());
  }
}
