# Performance / architecture audit — 2.2.0-RC2

## Baseline evidence
The latest 2.1.2 live log showed the 2.1 renderer cache redesign working: roughly 99% of recursive topology checks were avoided in the sampled steady-state run, with about 83% of repeated matrix-skin position work already reused. That moved the next target away from blind JBRA cache tuning and toward duplicated high-level runtime work.

## 2.2 targets
1. Final pose composition can be requested by multiple JBRA/POV/Tool_R passes for the same logical render sample.
2. DBC/JRMCore state was queried by several subsystems with overlapping reflection.
3. `SkinnedPlayerRenderer.emitNormal()` reached back into `PoseEngine.skinMatrices()` from geometry emission.
4. Combat had a declared collider model but selected one target through a cone/range helper; the old global hit mask also prevented one hit window from contacting multiple entities.
5. Guard and CPS history lived inside the main combat controller despite independent lifetimes.

## Corrections
- Exact duplicate pose signature cache in `PoseEngine`, guarded by DBC visual revision so live flight evidence cannot freeze a stale pose inside the same tick.
- Tick-stable `DbcClientState.TickSnapshot` for blocking, Ki attack, floating/ground/flying and speed state.
- `poseSerial`-based skin cache and one captured matrix array per skinned render.
- Spatial OBB/AABB collider sweeps with per-target/window hit memory.
- Isolated guard and attack-cadence runtimes.
- Opt-in profiler with no timing calls when disabled beyond a static boolean branch.

## Fidelity constraints
No animation decimation, joint removal, hidden-body performance trick, pose approximation or first-person camera change is allowed. The optimization strategy is fewer repeated computations and reflection calls, not lower visual fidelity.
