package com.nicolas.epicfight1710.combat;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class WeaponRegistry200Test {
  static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
  static String json(String p)throws Exception{return new String(Files.readAllBytes(Paths.get(p)),StandardCharsets.UTF_8);}
  static void isType(String hierarchy,String id){String got=WeaponCapabilityRegistry.resolveHierarchy(hierarchy).id;check(id.equals(got),hierarchy+" -> "+got+" expected "+id);}
  public static void main(String[]a)throws Exception{
    WeaponRegistryData d=WeaponDefinitionLoader.parse(json(a[0]));
    check(d.types.size()==8,"type count="+d.types.size());
    check(d.itemRules.size()==7,"item rules="+d.itemRules.size());
    check(d.profiles.size()>=20,"profiles="+d.profiles.size());
    long g0=WeaponCapabilityRegistry.generation();WeaponCapabilityRegistry.install(d);long g1=WeaponCapabilityRegistry.generation();check(g1==g0+1,"generation not incremented");
    isType("JinRyuu.DragonBC.common.Npcs.ItemKatana -> net.minecraft.item.ItemSword","epicfight:uchigatana");
    isType("JinRyuu.DragonBC.common.Items.ItemZSword -> ItemSwordBase","epicfight:greatsword");
    isType("JinRyuu.DragonBC.common.Items.ItemBraveSword -> ItemWeapon","epicfight:sword");
    isType("some.mod.ItemToolAxe -> ItemAxe","epicfight:axe");
    isType("some.mod.ItemSpear","epicfight:spear_twohand");
    isType("some.mod.ItemDagger","epicfight:dagger");
    isType("some.mod.ItemLongsword","epicfight:longsword");
    isType("some.mod.UnknownThing","epicfight:fist");
    WeaponCapability1710 sword=WeaponCapabilityRegistry.type("epicfight:sword");
    check(sword.defaultStyle==EpicCombatStyle.ONE_HAND,"sword default");
    check(sword.style(EpicCombatStyle.ONE_HAND).comboLength()==3,"sword combo");
    check("sword_dash".equals(sword.style(EpicCombatStyle.ONE_HAND).dashAttack.clip),"sword dash");
    check("guard_sword".equals(sword.style(EpicCombatStyle.ONE_HAND).livingMotion("block")),"sword guard motion");
    WeaponCapability1710 uchi=WeaponCapabilityRegistry.type("epicfight:uchigatana");
    check(uchi.defaultStyle==EpicCombatStyle.TWO_HAND,"uchi default");
    check(!uchi.canBePlacedOffhand,"uchi offhand");
    WeaponCapability1710 spear=WeaponCapabilityRegistry.type("epicfight:spear_twohand");
    check(spear.defaultStyle==EpicCombatStyle.TWO_HAND,"spear default");
    AttackProfile p=sword.style(EpicCombatStyle.ONE_HAND).combo(0);
    check(!p.stateAt(.20f).canBasicAttack,"chain opened too early");
    check(p.stateAt(.40f).canBasicAttack,"chain did not open");
    check(p.collider!=null&&p.collider.count==3,"collider not inherited");
    System.out.println("PASS WeaponRegistry200Test types="+d.types.size()+" rules="+d.itemRules.size()+" profiles="+d.profiles.size()+" generation="+g0+"->"+g1);
  }
}
