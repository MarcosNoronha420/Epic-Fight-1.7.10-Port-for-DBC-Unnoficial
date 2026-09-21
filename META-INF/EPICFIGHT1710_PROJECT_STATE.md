# EpicFight1710-Port 2.2.0-RC2 — Structural runtime state

## Frozen baseline
- Minecraft 1.7.10 / Forge 10.13.4.1614 / Java 8 class major 52.
- DBC 1.4.85 / JRMCore 1.3.51 / JBRA 1.6.52.
- 2.1.2-RC1 is the functional baseline.
- WORLD-BODY first person is protected: RenderPlayer `-Entity.yOffset`, POV Y=-0.075 and horizontal body-back/eye-forward=0.0625.
- JBRA remains appearance owner; Epic armature remains animation/deformation owner.
- Tool_R, DBC flight presentation, Vanish Dash and original animation assets remain protected.
- `EpicFightTransformer.class` must remain SHA-256 `01d8c1986e23fe63661d89a0accbe36fd19dc66a656c0d4fe492fbdc26429e4c`.

## 2.2.0-RC2 structural changes
- `CombatHitResolver`: authored `ColliderDefinition` is now executable runtime data. Active windows sweep a view-space OBB over `count` samples and may hit multiple entities, while an identity map records target × hit-window so one target is not damaged twice by the same window.
- `CombatGuardRuntime`: JRMCore block state, two-tick visual release hysteresis and guard-hit reaction selection no longer live in `CombatController`.
- `AttackCadenceTracker`: CPS history and source-style fast-fist playback scaling no longer live in `CombatController`.
- `DbcClientState.TickSnapshot`: stable DBC/JRMCore/vanilla state is resolved once per player tick; live JBRA flight evidence remains render-sensitive.
- `DbcClientState.visualRevision`: same-tick flight evidence invalidates the final-pose cache immediately when visual state changes.
- `PoseEngine`: exact duplicate logical render samples reuse the already-built final pose and expose `poseSerial` for downstream skin caches.
- `SkinnedPlayerRenderer`: skinning is cached by `poseSerial`; the skin matrix array is captured once per render and passed through normal emission instead of repeatedly querying `PoseEngine` inside geometry loops.
- `ModelRendererSkinHook`: active JBRA skin dispatch is measured by the optional subsystem profiler.
- `RuntimeProfiler`: opt-in (`-Depicfight1710.profiler=true`) microsecond accounting for Animator, collider, DBC state, JBRA skin, first person and combat.

## Collider scope in RC1
The new resolver is a real spatial OBB/AABB contact path and uses Epic-style half-extents/center semantics. It is still anchored to the interpolated player view transform in this 1.7.10 adapter. Joint-local Tool_R/Hand_R attack-collider sampling is intentionally not claimed yet; that is the next parity step after this RC proves the new contact path in the real client.

## Protected / deliberately unchanged
- `FirstPersonBodyRenderer1710` visual transform and visibility policy.
- `FirstPersonWeaponRenderer` Tool_R behavior.
- `DbcFlightStateMachine`, `DbcFlightRenderHook`, `DashController` behavior.
- `weapon_types.json`, `clips.dat`, `biped.dat`, `biped_old.dat` in this RC.
- Animation sampling remains display-frame interpolated; no 20 TPS downgrade.

## Remaining architecture work after RC1 acceptance
- Move remaining action-clock/selection orchestration out of `CombatController`.
- Centralize mouse/keyboard edge state without changing Forge event ordering.
- Upgrade collider anchoring from player-view space to sampled armature joint transforms.
- Extend weapon JSON only where behavior can be source-grounded instead of adding arbitrary knobs.
- Continue JBRA fallback reduction using live profiler evidence rather than speculative visual shortcuts.

## Live acceptance
Test Battle Mode OFF/ON in the same scene, then idle/walk/run/jump, fist combo including RELENTLESS_COMBO, multi-target hits, guard + guard-hit, dash, normal/fast flight, held weapons/Tool_R, first person straight/down and third-person JBRA forms. Send `latest.log`; for profiling also launch once with `-Depicfight1710.profiler=true`.
