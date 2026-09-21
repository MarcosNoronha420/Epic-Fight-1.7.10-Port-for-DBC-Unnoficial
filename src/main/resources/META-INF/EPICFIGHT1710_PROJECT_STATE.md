# EpicFight1710-Port — Project State 2.0.1

## Source of truth
Epic Fight Forge 20.9.5 remains the behavior, animation, armature, WeaponCapability and item-joint reference. Compatibility target remains Minecraft 1.7.10 / Forge 10.13.4.1614 / Java 8u51 / Dragon Block C 1.4.85 / JRMCore 1.3.51 / JBRA 1.6.52 / Angelica 2.2.8.

## 2.0 runtime ownership
1. DBC/JRMCore owns player gameplay state, damage, flight physics and Dragon Ball mechanics.
2. JBRA owns race/form/skin/muscle/head/hair/clothing geometry and native item rendering.
3. EpicFight1710 owns source animation selection/composition, 20-joint armature pose, Weapon Type/Style presentation and Tool_R/Tool_L animation joints.
4. `LocalPlayerPatch1710` is the 1.7.10 compatibility counterpart of the current-player patch: resolved capability/style and combo lifetime live there instead of in the renderer.
5. `CombatController` owns input + one action clock + temporal hit sampling; it no longer serves as the weapon database.
6. Battle Mode OFF remains native.

## Data-driven weapon architecture
Embedded + external `weapon_types.json` provides Item Capability rules, Weapon Types, Styles, living motions, source combos, dash/mount slots, guard-hit clips, innate-skill metadata, StateSpectrum timing and collider metadata. F8 hot reload swaps the immutable registry and the local patch drops stale combo state through the registry generation counter.

## Animation/runtime improvements
Weapon living-motion composites come from the active Style. WALK/RUN changes preserve normalized gait phase and use locomotion speed modes. Attack `StateSpectrum` flags are evaluated from the same animation elapsed time used for rendering and hit intervals. DBC block can trigger an Epic source guard-hit reaction without replacing DBC defense calculations.

## First person
Same final animator pose and same JBRA RA/LA geometry as third person. One shared persistent camera adapter only. 2.0.1 layout v2 defaults to X=0.00, Y=+0.12, Z=-1.35 before Epic's eye-height/camera transform. Existing 2.0 config is migrated automatically so stale Z=0 cannot preserve the near-plane bug. Live controls still allow shared Y calibration. Offline projection proves depth/frustum safety, but final visual centering remains a live-client acceptance item.

## Protected systems
The packaged transformer is the exact recovered known-good bytecode with ASM5 `ClassReader.accept(ClassVisitor,int)`. Native JBRA first-person lifecycle, `JbraWeightedPartRenderer`, `WeaponItemMountHook`, DBC flight state/presentation and Dash remain protected.


## 2.0.1 live corrective delta
First-person uses shared depth-safe camera framing (0,+0.12,-1.35) with automatic migration from the broken 2.0 Z=0 config. FIST guard visual is restored with guard_dualsword fallback. Exact JRMCore getAnimKiShoot state drives Epic crossbow aim source clips for Ki presentation.
