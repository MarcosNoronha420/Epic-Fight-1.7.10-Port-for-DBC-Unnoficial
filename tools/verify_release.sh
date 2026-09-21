#!/usr/bin/env bash
set -euo pipefail
jar_path="${1:-/mnt/data/EpicFight1710-Port-2.2.0-RC2.jar}"
baseline="${2:-}"
version="${3:-2.2.0-RC2}"
[[ -f "$jar_path" ]] || { echo "FAIL jar missing: $jar_path"; exit 1; }
entries="$(jar tf "$jar_path")"
need(){ grep -Fxq "$1" <<<"$entries" || { echo "FAIL missing $1"; exit 1; }; }
for e in META-INF/MANIFEST.MF META-INF/EPICFIGHT1710_PROJECT_STATE.md mcmod.info \
 com/nicolas/epicfight1710/EpicFight1710.class \
 com/nicolas/epicfight1710/core/EpicFightTransformer.class \
 com/nicolas/epicfight1710/client/CombatController.class \
 com/nicolas/epicfight1710/client/CombatHitResolver.class \
 com/nicolas/epicfight1710/client/CombatGuardRuntime.class \
 com/nicolas/epicfight1710/client/AttackCadenceTracker.class \
 com/nicolas/epicfight1710/client/RuntimeProfiler.class \
 com/nicolas/epicfight1710/client/DbcClientState.class \
 com/nicolas/epicfight1710/client/DbcClientState\$TickSnapshot.class \
 com/nicolas/epicfight1710/client/PoseEngine.class \
 com/nicolas/epicfight1710/client/EpicFirstPersonBridge.class \
 com/nicolas/epicfight1710/client/FirstPersonBodyRenderer1710.class \
 com/nicolas/epicfight1710/client/FirstPersonWeaponRenderer.class \
 com/nicolas/epicfight1710/client/NativeJbraSkinContext.class \
 com/nicolas/epicfight1710/client/WeaponItemMountHook.class \
 com/nicolas/epicfight1710/combat/AttackState.class \
 com/nicolas/epicfight1710/combat/AttackStateSpectrum.class \
 com/nicolas/epicfight1710/combat/ColliderDefinition.class \
 com/nicolas/epicfight1710/combat/LocalPlayerPatch1710.class \
 com/nicolas/epicfight1710/combat/WeaponDefinitionLoader.class \
 assets/epicfight1710/data/weapon_types.json assets/epicfight1710/data/biped.dat \
 assets/epicfight1710/data/biped_old.dat assets/epicfight1710/data/clips.dat \
 assets/epicfight1710/data/clip_manifest.txt; do need "$e"; done

meta="$(unzip -p "$jar_path" mcmod.info | tr -d '\n\r\t ')"
grep -Fq "\"version\":\"$version\"" <<<"${meta// /}" || { echo FAIL mcmod-version; exit 1; }
grep -Fq "VERSION = \"$version\"" <<<"$(javap -classpath "$jar_path" -p -constants com.nicolas.epicfight1710.EpicFight1710)" || { echo FAIL runtime-version; exit 1; }
manifest="$(unzip -p "$jar_path" META-INF/MANIFEST.MF | tr -d '\r')"
grep -Fq 'FMLCorePlugin: com.nicolas.epicfight1710.core.EpicFightCorePlugin' <<<"$manifest" || { echo FAIL coremod-manifest; exit 1; }
grep -Fq 'FMLCorePluginContainsFMLMod: true' <<<"$manifest" || { echo FAIL coremod-contains-mod; exit 1; }
for prefix in net/minecraft/ net/minecraftforge/ cpw/mods/ org/lwjgl/ org/objectweb/asm/; do ! grep -q "^${prefix}.*\.class$" <<<"$entries" || { echo "FAIL packaged stub $prefix"; exit 1; }; done

transformer_hash="$(unzip -p "$jar_path" com/nicolas/epicfight1710/core/EpicFightTransformer.class | sha256sum | awk '{print $1}')"
[[ "$transformer_hash" == "01d8c1986e23fe63661d89a0accbe36fd19dc66a656c0d4fe492fbdc26429e4c" ]] || { echo "FAIL protected transformer hash $transformer_hash"; exit 1; }

asm_link="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.core.EpicFightTransformer | grep 'ClassReader.accept' | sort -u)"
grep -Fq 'ClassReader.accept:(Lorg/objectweb/asm/ClassVisitor;I)V' <<<"$asm_link" || { echo FAIL asm-ClassReader-linkage; exit 1; }
! grep -Fq 'ClassReader.accept:(Lorg/objectweb/asm/tree/ClassNode;I)V' <<<"$asm_link" || { echo FAIL asm-invalid-ClassNode-descriptor; exit 1; }

cc="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.CombatController)"
for n in 'CombatHitResolver.resolve' 'CombatGuardRuntime.tick' 'AttackCadenceTracker.notePress' 'AttackProfile.stateAt' 'LocalPlayerPatch1710.update' 'LocalPlayerPatch1710.commit'; do grep -Fq "$n" <<<"$cc" || { echo "FAIL combat orchestration missing $n"; exit 1; }; done
hit="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.CombatHitResolver)"
for n in 'AttackPhase.activeBetween' 'ColliderDefinition.count' 'Compat.loadedEntities' 'java/util/IdentityHashMap' 'Compat.vanillaAttack' 'intersectsObbAabb'; do grep -Fq "$n" <<<"$hit" || { echo "FAIL collider resolver missing $n"; exit 1; }; done
cad="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.AttackCadenceTracker)"
grep -Fq 'FAST_FIST_CPS_THRESHOLD = 6.8f' <<<"$cad" || { echo FAIL cps-threshold; exit 1; }
grep -Fq 'MAX_FAST_FIST_PLAYBACK = 2.0f' <<<"$cad" || { echo FAIL cps-cap; exit 1; }
grep -Fq 'java/lang/Math.round:(F)I' <<<"$cad" || { echo FAIL epic-speed-quantization; exit 1; }
guard="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.client.CombatGuardRuntime)"
for n in 'Compat.dbcBlocking' 'Compat.hurtTime' 'guard_dualsword_hit'; do grep -Fq "$n" <<<"$guard" || { echo "FAIL guard runtime missing $n"; exit 1; }; done

pose="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.client.PoseEngine)"
for n in 'DbcClientState.poseRevision' 'LayeredAnimator.poseSerial'; do grep -Fq "$n" <<<"$pose" || { echo "FAIL pose cache missing $n"; exit 1; }; done
dbcstate="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.DbcClientState)"
for n in 'getAnimKiShoot' 'TickSnapshot' 'poseRevision'; do grep -Fq "$n" <<<"$dbcstate" || { echo "FAIL DBC snapshot missing $n"; exit 1; }; done
prof="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.RuntimeProfiler)"
for n in 'epicfight1710.profiler' 'Animator' 'JBRA-skin' 'first-person' 'collider'; do grep -Fq "$n" <<<"$prof" || { echo "FAIL profiler missing $n"; exit 1; }; done
# Execute the profiler class initializer; RC1 crashed here before the menu because INSTANCE was initialized before NAMES.
verify_root="$(cd "$(dirname "$0")/.." && pwd)"
prof_tmp="$(mktemp -d)"
trap 'rm -rf "$prof_tmp"' EXIT
javac -source 8 -target 8 -cp "$jar_path" -d "$prof_tmp" "$verify_root/tools/tests/RuntimeProfiler220Test.java" >/dev/null 2>&1
java -cp "$jar_path:$prof_tmp" com.nicolas.epicfight1710.client.RuntimeProfiler220Test | grep -Fq 'PASS RuntimeProfiler220Test enabled=false' || { echo FAIL profiler-disabled-init; exit 1; }
java -Depicfight1710.profiler=true -cp "$jar_path:$prof_tmp" com.nicolas.epicfight1710.client.RuntimeProfiler220Test | grep -Fq 'PASS RuntimeProfiler220Test enabled=true' || { echo FAIL profiler-enabled-init; exit 1; }
skin="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.client.SkinnedPlayerRenderer)"
grep -Fq 'PoseEngine.poseSerial' <<<"$skin" || { echo FAIL pose-serial-skin-cache; exit 1; }
[[ "$(grep -Fc 'PoseEngine.skinMatrices' <<<"$skin")" -eq 1 ]] || { echo FAIL repeated-high-level-skinMatrices-access; exit 1; }
hook="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.client.ModelRendererSkinHook)"
grep -Fq 'RuntimeProfiler.JBRA_SKIN' <<<"$hook" || grep -Fq 'RuntimeProfiler.end' <<<"$hook" || { echo FAIL jbra-skin-profiler-hook; exit 1; }

loader="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.combat.WeaponDefinitionLoader)"
for n in 'weapon_types.json' 'WeaponCapabilityRegistry.install' 'MiniJson.parse' 'config/epicfight1710'; do grep -Fq "$n" <<<"$loader" || { echo "FAIL data loader missing $n"; exit 1; }; done
keys="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.ClientKeyBindings)"
grep -Fq 'key.epicfight1710.reload_weapons' <<<"$keys" || { echo FAIL reload-key; exit 1; }
for old in first_person_up first_person_down first_person_reset; do ! grep -Fq "$old" <<<"$keys" || { echo FAIL obsolete-fp-tuning-key; exit 1; }; done
hooks="$(javap -classpath "$jar_path" -p -c com.nicolas.epicfight1710.client.ClientHooks)"
grep -Fq 'WeaponDefinitionLoader.load' <<<"$hooks" || { echo FAIL hot-reload-wiring; exit 1; }
grep -Fq 'FirstPersonCombatAnimator.render' <<<"$hooks" || { echo FAIL world-body-hand-wiring; exit 1; }
fp="$(javap -classpath "$jar_path" -p -c -constants com.nicolas.epicfight1710.client.FirstPersonBodyRenderer1710)"
grep -Fq 'FirstPersonWeaponRenderer.render' <<<"$fp" || { echo FAIL fp-tool-r-pass; exit 1; }
grep -Fq 'renderBody' <<<"$fp" || { echo FAIL fp-jrm-transform-tree; exit 1; }
! grep -Eq 'Method .*RenderManager.*renderEntityStatic' <<<"$fp" || { echo FAIL full-player-render-in-pov; exit 1; }
! grep -Eq 'Method .*renderEquippedItemsJBRA' <<<"$fp" || { echo FAIL full-jbra-item-pipeline-in-pov; exit 1; }
for old in FirstPersonTuning FirstPersonCamera1710 VanillaFirstPersonItemRenderer; do ! grep -Fq "com/nicolas/epicfight1710/client/${old}.class" <<<"$entries" || { echo "FAIL obsolete class packaged: $old"; exit 1; }; done

ann="$(javap -classpath "$jar_path" -v com.nicolas.epicfight1710.client.ClientHooks 2>/dev/null || true)"
grep -q 'RuntimeVisibleAnnotations' <<<"$ann" || { echo 'FAIL ClientHooks lacks RuntimeVisibleAnnotations'; exit 1; }
grep -q 'cpw.mods.fml.common.eventhandler.SubscribeEvent' <<<"$ann" || { echo 'FAIL ClientHooks lacks SubscribeEvent'; exit 1; }
! grep -q 'RuntimeInvisibleAnnotations' <<<"$ann" || { echo 'FAIL ClientHooks contains RuntimeInvisibleAnnotations'; exit 1; }

python3 - "$jar_path" <<'PY'
import sys,zipfile,struct,json
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
 ns=z.namelist()
 if len(ns)!=len(set(ns)): raise SystemExit('FAIL duplicate entries')
 cs=[n for n in ns if n.startswith('com/nicolas/epicfight1710/') and n.endswith('.class')]
 bad=[]
 for n in cs:
  b=z.read(n); major=struct.unpack('>H',b[6:8])[0]
  if major!=52: bad.append((n,major))
 if bad: raise SystemExit('FAIL non-Java8 '+repr(bad[:10]))
 data=json.loads(z.read('assets/epicfight1710/data/weapon_types.json').decode('utf-8'))
 if len(data.get('weaponTypes',[]))!=8: raise SystemExit('FAIL weaponTypes count')
 if len(data.get('itemRules',[]))!=7: raise SystemExit('FAIL itemRules count')
 print('PASS all %d project classes are Java 8 major 52; JSON has 8 Weapon Types / 7 Item rules'%len(cs))
PY

if [[ -n "$baseline" ]]; then
 [[ -f "$baseline" ]] || { echo "FAIL baseline missing: $baseline"; exit 1; }
 for e in \
  com/nicolas/epicfight1710/client/FirstPersonBodyRenderer1710.class \
  com/nicolas/epicfight1710/client/FirstPersonWeaponRenderer.class \
  com/nicolas/epicfight1710/client/DbcFlightStateMachine.class \
  com/nicolas/epicfight1710/client/DbcFlightRenderHook.class \
  com/nicolas/epicfight1710/client/DashController.class \
  assets/epicfight1710/data/weapon_types.json assets/epicfight1710/data/clips.dat assets/epicfight1710/data/biped.dat assets/epicfight1710/data/biped_old.dat; do
   a="$(unzip -p "$jar_path" "$e" | sha256sum | awk '{print $1}')"; b="$(unzip -p "$baseline" "$e" | sha256sum | awk '{print $1}')"
   [[ "$a" == "$b" ]] || { echo "FAIL protected baseline drift: $e"; exit 1; }
 done
 echo 'PASS protected visual/gameplay binaries and assets match 2.1.2 baseline.'
fi

echo "PASS $version static release gates."
