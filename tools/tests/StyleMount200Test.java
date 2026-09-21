package com.nicolas.epicfight1710.combat;
import java.nio.charset.StandardCharsets;import java.nio.file.*;
public final class StyleMount200Test{
 public static final class MockPlayer{public Object ridingEntity; public boolean onGround=true; public int ticksExisted; public int hurtTime;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]a)throws Exception{
  String json=new String(Files.readAllBytes(Paths.get(a[0])),StandardCharsets.UTF_8);WeaponRegistryData d=WeaponDefinitionLoader.parse(json);WeaponCapabilityRegistry.install(d);
  WeaponCapability1710 s=WeaponCapabilityRegistry.type("epicfight:sword");MockPlayer p=new MockPlayer();
  check(s.resolveStyle(p).style==EpicCombatStyle.ONE_HAND,"not riding must use ONE_HAND");
  p.ridingEntity=new Object();check(s.resolveStyle(p).style==EpicCombatStyle.MOUNT,"riding must use MOUNT");check(s.resolve(p).mount()!=null,"mount attack absent");
  AttackStateSpectrum sp=new AttackStateSpectrum(.1f,.2f,.4f,.7f,.5f,.3f,.2f);
  check(!sp.stateAt(.39f).canBasicAttack,"canBasic early");check(sp.stateAt(.4f).canBasicAttack,"canBasic open");check(sp.stateAt(.7f).canBasicAttack,"canBasic close inclusive");check(!sp.stateAt(.71f).canBasicAttack,"canBasic after close");
  check(sp.stateAt(.15f).attacking,"attacking contact");check(!sp.stateAt(.25f).attacking,"attacking after contact");
  System.out.println("PASS StyleMount200Test ONE_HAND -> MOUNT + finite StateSpectrum chain window");
 }
}
