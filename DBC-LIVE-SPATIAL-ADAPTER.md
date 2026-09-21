# Live DBC spatial state adapter

Branch `feature/dbc-live-spatial-adapter`, based exactly on
`53a7b8cd0d605b1be6a4c0385945853e12460199`. This is a read-only client API;
it is not a collider, a live combat hook, or a complete world-space transform.
External review and acceptance in the target modpack remain pending.

## Public boundary

```java
DbcLiveSpatialAdapter adapter = DbcLiveSpatialAdapter.createClient();
// Game thread, after native client data/config initialization. Explicit player.
DbcSpatialStateSnapshot sample = adapter.capture(player, actionRevision);
NativeDbcSpatialProvider.Config config = adapter.config().config;
NativeDbcSpatialProvider.Descriptor descriptor = provider.evaluate(sample, config);
// Reuse sample/descriptor for the consumers of this sample. No capture per target.
// On a later capture, check adapter.isCurrent(sample) before retaining the sample.
// Descriptors use sample.spatialRevision and config.revision, never actionRevision.
// A missing config or sample.geometryRevision != config.revision is rejected.
// Recapture the sample after a config change; never mix spatial generations.
// Disconnect: adapter.clear(); player removal: adapter.invalidate(player).
```

`ReflectiveDbcSpatialSource` reads runtime fields and audited getters, then
`DbcLiveSpatialAdapter` constructs `Input` and calls the existing `Cache.capture`.
It never calls the uncached `DbcSpatialStateSnapshot.capture(Input)` entry point.
The existing cache remains the only authority for player snapshot revisions and
staleness. `actionRevision` is explicit caller input, independent of
`spatialRevision`. No existing tick/render/combat hook is modified or registered.

`DbcLiveSpatialConfigAdapter` captures global configuration independently of
players. It compares scalars and table contents (including in-place edits),
increments its own monotonic revision on change or availability change, and owns
immutable copies. No hash substitutes for identity. Copies occur on config
changes only. Every explicit capture rereads live values to detect same-tick
changes; it does not discover reflection handles again or evaluate poses/joints.
The caller should capture once per spatial sample, then share the result.

Snapshot and Config form one consistent spatial generation:
`snapshot.geometryRevision == config.revision`. The snapshot overload of
`NativeDbcSpatialProvider.evaluate` rejects a null config or a revision mismatch
with an explicit invalid descriptor **before** converting the snapshot into State.
It does not update or recapture the snapshot. After config changes, the consumer
must capture again. Both old-snapshot/new-config and new-snapshot/old-config pairs
are rejected. The existing snapshot-currentness check remains required as well.

The player cache owns only one identity map of active snapshots. Its spatial
revision counter increases across the entire lifetime of that cache, across
players and across `invalidate`/`clear`; it never resets or wraps. Each snapshot
has a liveness token without player references. Replacing a snapshot revokes its
old token; invalidating a player revokes the removed snapshot's token and removes
the only registration. Clearing revokes all active tokens, then empties the map.
`currentSpatialRevision` returns zero for an unregistered player. Recapturing the
same player/world/tick gets a new revision, so a retained old descriptor cannot
match it. Revision exhaustion throws explicitly rather than reusing a number.
There is no auxiliary map retaining removed players. Consumers that retain an
old snapshot themselves still own that snapshot's player reference.

## Audited runtime sources

Sources are the exact local DBC 1.4.85, JBRA 1.6.52, JRMCore 1.3.51,
JYearsC 1.2.5 and JFamilyC 1.2.18 JARs. The existing native audit records their
SHA-256 values; the test generators check the local hashes before extracting.
No native/decompiled code or binaries are distributed in this branch.

| Captured component | Live source / read operation | Semantics and guard |
|---|---|---|
| Player/world/tick | Explicit player object; `Entity.worldObj/field_70170_p`, `ticksExisted/field_70173_aa` | Identity equality; tick is the entity game-tick counter, consistent with the existing snapshot's int contract. No render clock. |
| Name | `getCommandSenderName/func_70005_c_` | Resolve `JRMCoreH.plyrs` by this name for every read. Reject duplicates; never retain an index. |
| Client world and local identity | `DBCClient.mc`, its `theWorld/field_71441_e`, `thePlayer/field_71439_g`, world `isRemote/field_72995_K` | Only a world equal to this live client world may consume the client arrays. The local identity only gates local flight fields; it never replaces the requested player. No Minecraft/Compat bootstrap. |
| Availability | `JRMCoreH.DBC/JYC/JFC` -> `iml` | Read-only installed/synchronized mod flags. These helpers read `modsC` on the client or Forge's mod-presence registry. |
| Race, power type, DNS | `JRMCoreH.data1[index]` columns 0, 2, 1 | Same indexed row used natively. Missing/bad row is not a zero/base-form fallback. |
| Transformation state | `JRMCoreH.data2[index]` column 0 | Exact equivalent of `data(playerName,2,...)[0]`; passed separately to `resolveJYearsCAge`. |
| Scale-table form | The same `data2[index][0]` used by native `preRenderCallback` | This audited live path has form == transformationState; the fields remain separate. Cosmetic remapping in other draw branches is outside this descriptor. Tests also supply intentionally different values. |
| Release / constitution | `dat10[index]` column 0 / `dat14[index]` comma column 2 | No local `curRelease` or `PlyrAttrbts` static is reused for a remote player. |
| DNS revision | Exact immutable DNS string | Content equality; no hash collision and no temporary object identity. Retained string is a revision representation, not a mutable native array. |
| Gender/body type | `JRMCoreH.dnsGender`, `dnsSkinT`, `dnsBodyT` or `dnsBodyC1_0` | Pure string parsers, with length checks before invocation. Body-type selector follows the native skin-type branch. |
| Model variant | DNS gender + 1 under `JFC()`, with native 1/2/3 selection and Sai/Half-Sai Oozaru override | Without JFC, initial native gen=1 is the explicit absent-addon branch. A present addon with missing DNS is unavailable. Never reads gen/g statics. |
| Age / growth | `JinRyuu.JRMCore.JYearsCH.p` row `name;age`; `JYearsCConfig.pgut` | The helper's synchronized table, read by player name. Duplicate rows, bad/missing age or config yield age unavailable. No age increment or setter. |
| Age scale | Existing `resolveJYearsCAge` | Race/powerType gate followed by transformation states 7/8/14. `nativeState` and cosmetic form never feed that gate. |
| Child / sneaking / invisibility | `isChild/func_70631_g_`, `isSneaking/func_70093_af`, `isInvisible/func_82150_aj` | Read-only entity getters; missing getter is unavailable. Child is not inferred from JYearsC age. Invisible-player native presentation is not inferred from stale y. |
| Divine / fusion spectator | `JRMCoreH.StusEfcts` token table, `dat19[index]` | Divine uses index-overload column 1 token 17 and race 3. Spectator is native fusion-spectator token 11, not a new Minecraft game mode. |
| Native presentation | `data4[index][2]` KO, entity DataWatcher UI values, status tokens and data3/onGround | Native precedence: spectator, KO y=3, UI y=4+id, prone y=2, normal y=1; any missing prerequisite prevents a fabricated normal y. |
| UI logical state | `getDataWatcher/func_70096_w` -> `getWatchableObjectString/func_75681_e(JRMCoreConfig.ExtendedPlayerOtherID)` | Same columns 7 and 9 read by `ExtendedPlayer.getUIAnim/getUIAnimID`. Require the native 12-column layout. Reject the missing-row zero fallback used by native `getOtherCode`. |
| Flight | Local-only `DBCKiTech.floating`, `dodge_forwDash_STE`; player-bound data3 and status tokens 7/9/4; onGround | Positive native state only. No input polling, velocity threshold or time-airborne inference. See modes below. |
| Pixel scale | `.0625F` | Fixed player-model pixel unit in exact JBRA draws; not an estimated socket/world offset. |
| Body scalar | Shared `NativeDbcSpatialProvider.bodyScale` | Native f1 after constitution/release, **before** race/form anisotropy. Extracted from the existing kernel without changing operation order. Full outer XYZ scale remains in Descriptor. Oozaru override stays unavailable. |
| Geometry revision | Global config snapshot revision | Per-player DNS/race/age/etc. changes are handled by the existing spatialRevision, independently of configRevision/actionRevision. It is not a revision of complete native topology. |

Parallel-array reads require `array.length >= plyrs.length`, matching native
`dnn`; each selected row must exist. Player names and the `plyrs` reference are
checked again after reads. These are game-thread reads, not an atomic protocol
for off-thread packet mutation. No old row is substituted when a row disappears.

## Presentation and flight boundary

For presentation, native `StusEfctsClient(int, EntityPlayer)` checks the **whole**
dat19 row, whereas the int-index overload checks column 1. The reader preserves
that distinction instead of assuming the overloads are identical.

For an airborne Ki player: token 7 or a local `dodge_forwDash_STE` is `FAST`;
token 9 + data3 `1` without token 4 is `DIRECTIONAL`; local floating or data3 `1`
is `NORMAL`. A grounded Ki player is `GROUNDED`. Without positive airborne
evidence the mode is `UNKNOWN`, including a plain jump/fall or missing remote
flight data. A local floating toggle alone does not invent native prone y=2.
Missing pieces of the normal presentation predicate give UNKNOWN/nativeState=-1.

The provider still accepts only its previously established subset: normal
presentation/nativeState=1, Ki, grounded or normal flight, complete inputs.
Prone, directional/fast flight, KO, UI, spectator and Oozaru remain rejected for
the native spatial descriptor. Their available logical state can still be
represented by the snapshot. UI timer is read to choose the branch; the complete
time-varying UI geometry is not described. Existing visual behavior is untouched.

Invisibility currently makes body availability unavailable as a conservative
adapter policy. This correction does not change that policy. Visual invisibility
has **not** been defined as physical absence for combat. Before joint-local
collider work, a separate decision is required on whether an invisible player
continues to have a spatial body/collider; unavailability must not be interpreted
as that gameplay decision.

## Availability, reflection and purity

`NOT_APPLICABLE` means confirmed absent addon/path. `UNAVAILABLE` means missing
or malformed synchronized data, missing members, class linkage failure, unknown
presentation or unsupported component. `AVAILABLE` does not claim complete
world topology. A snapshot may be structurally valid while provider inputs are
unavailable; consumers must check `isUsableForNativeProvider`/descriptor validity.

`Reflect` supplies the existing field/method discovery mechanism. Handles are
cached per owner class and name, including missing members and missing classes.
The only methods invoked are the presence helpers, DNS parsers, entity getters
and DataWatcher string getter listed above; all other native access is field
reading. No setters, NBT access, ExtendedPlayer mutation, input, ticks, commands,
packets, GUI, camera or render methods are invoked. Failures are deduplicated in
diagnostics and logged by member/class (without player DNS); reads fail closed.
An absent class and a class with broken linkage have different availability.
Construct the source after mod initialization; hot installation is not supported.

`Compat` was inspected but its bootstrap discovers RenderManager and its cached
flags can hide a same-tick change. This adapter reuses `util.Reflect` instead,
with member caches restricted to the read-only boundary. `DbcClientState` owns
render observations and flight-machine state and is deliberately not called.

Rejected sources: `RenderPlayerJBRA.gen/childScl`, `ModelBipedBody.f/g/y`, last
model/socket/matrix, native renderer callbacks; `JYearsCComTickH.serverTick`;
`FamilyCComJFCGen/FamilyCComJFCsoc`; JRMCore `PlyrAttrbts(player)` (server fusion
cleanup may mutate NBT); native data helpers with synthesized missing-row
defaults. Family config for pregnancy/children is not required by this partial
player descriptor and is not imported into a guessed body transform.

## Remaining limits

- Client synchronized data only. A server adapter requires its own read-only
  authoritative data contract. Foreign/server worlds return unavailable.
- Native arrays carry names but no world/generation stamp. A row still present
  during native resynchronization cannot be proven fresh beyond the native
  protocol's own authority. The adapter rejects foreign worlds and missing rows;
  it does not claim an atomic server snapshot or invent a packet revision.
- Pregnancy, breast/hair/face subgroups, complete model topology, neutral joint
  sockets/retarget, final Hand/Tool world positions and all collider work remain
  outside this phase. Oozaru scale/geometry, prone/fast transforms and unsupported
  visual branches are not approximated.
- No automatic tick hook was installed. A future consumer must retain one adapter,
  use the game thread, clear it on disconnect, invalidate removed players, and
  reuse captured samples. No gameplay is enabled by adding this API.
- Tests are Java 8 headless integration tests plus exact-JAR equation/branch
  oracles. They are not a graphical Minecraft acceptance run or a full Forge build.

## Reproducible checks

`run_dbc_live_spatial.ps1 -CompilerJar <ecj> -CfrJar <cfr> -Python <python>`
generates the native DNS/presentation source slices only under ignored `build/`,
then compiles the actual adapter, reflection reader, config cache and fixtures.
The test class resolver supplies native-shaped classes, so tests exercise the
production reflection path, not only a pre-filled Input builder. Native
presentation is compared against the extracted original conditions across
ground/KO/UI/status/data3 combinations. Native DNS parsers are extracted from
the verified JRMCore JAR rather than redistributed as fixture code.

Required previous suites: `run_dbc_state_snapshot.ps1`, `run_native_spatial.ps1`,
`run_combat_pose.ps1`, `run_dbc_spatial.ps1`, `run_audit.ps1`. All commands run
from repository root; JARs, generated oracles/classes and logs stay ignored.
Results for this delivery are recorded after execution below.

Observed after the generation-consistency correction on Java `1.8.0_503`,
ECJ `-1.8` (all six suites exited successfully; existing checks preserved):

| Suite | Observed result |
|---|---|
| `run_dbc_live_spatial.ps1` | 1,580 checks, including config/snapshot generation mismatch in both directions, null config, production reflection path and 768 native presentation combinations; exact-JAR oracles generated under build only |
| `run_dbc_state_snapshot.ps1` | 207 assertions; existing 146 retained, plus cache replacement/invalidate/clear, structural retention checks without GC, recapture/descriptor collision checks and explicit revision-exhaustion rejection |
| `run_native_spatial.ps1` | 180,900 assertions; 35,280 scale states and 84 body cases; shared f1 extraction matches native equations |
| `run_combat_pose.ps1` | 3,587,358 assertions; 180 bit-identical baseline composition samples; existing collider/weapon/style regressions also pass (no new implementation) |
| `run_dbc_spatial.ps1` | 622,593 assertions; real render sources compile headlessly and previous pose regressions pass |
| `run_audit.ps1` | 242 clips, 968 finite poses, 480 golden joint matrices; timing/cadence/profiler and binary asset audit pass |

Compiler warnings concern unused locals in generated native slices and test
presentation-noise fields. No compiler error. Expected failure-fixture diagnostic
messages (duplicate/malformed row and broken optional linkage) are observed.
No graphical Minecraft session or full Forge packaging was performed; these
results do not declare the phase externally approved.
