# EpicFight1710 2.0 Weapon Types

2.0 moves weapon behavior out of `CombatController` and into an Epic-Fight-style data model.

Runtime chain:

`held item -> ItemCapabilityRule -> WeaponCapability1710 (Weapon Type) -> WeaponStyleDefinition (Style) -> combo / dash / mount / living motion / collider -> CombatController + LayeredAnimator`

## External file and hot reload

On first client start, the embedded definition is copied to:

`config/epicfight1710/weapon_types.json`

Edit that file and press **Reload Weapon Types** (F8 by default; editable in Controls). A valid reload installs the new immutable registry and increments its generation; `LocalPlayerPatch1710` observes the generation change and clears stale combo/style state.

If the external JSON cannot be parsed, the loader falls back to the embedded release defaults rather than installing a partial registry.

## Top level

```json
{
  "format": 1,
  "weaponTypes": [ ... ],
  "itemRules": [ ... ]
}
```

`itemRules` are ordered. The first matching class-hierarchy token wins, so more specific types (katana, Z Sword, longsword, dagger, spear, axe) precede generic sword matching.

## Weapon Type

Each Weapon Type contains:
- `id`: stable capability id;
- `category`: FIST/SWORD/UCHIGATANA/LONGSWORD/GREATSWORD/AXE/DAGGER/SPEAR;
- `defaultStyle`: fallback Style;
- `offhand`: capability metadata;
- `styles`: one or more Style definitions.

2.0 ships eight Weapon Types: fist, sword, uchigatana, longsword, greatsword, axe, dagger and two-hand spear.

## Style

A Style can define:
- ordered normal `combo` attacks;
- `dash` attack;
- `mountAttack`;
- `air` source clip metadata;
- `guardHit` reaction clip;
- `innateSkill` metadata;
- `living` motion overrides;
- `collider` parameters;
- `conditions`.

Supported Style condition types in 2.0 are `always`, `never`, `riding`/`mount`, `sprinting`, `sneaking`, `blocking`, `flying`, and `airborne`.

The release enables real MOUNT styles when riding. Source styles that need a modern-Epic mechanic not yet represented faithfully on 1.7.10 (SHEATH/OCHS/dual-dagger/one-hand spear) are retained in the data but explicitly disabled with `never`; they are not silently guessed.

## Attack timeline / StateSpectrum adapter

An attack entry can define:
- `clip`
- `startup`
- `activeEnd`
- `chainOpen`
- `chainClose`
- `inactionEnd`
- `movementLockEnd`
- `turningLockEnd`
- `range`
- `facing`
- `nextCombo`
- optional `hitWindows`

These values build one `AttackProfile` and one `AttackStateSpectrum`. The same elapsed action clock drives rendering, contact sampling, `ATTACKING`, `CAN_BASIC_ATTACK`, inaction and movement/turn lock state. The controller therefore no longer maintains a second independent combo-recovery timer.

`relentless_combo` remains a deliberate compatibility rule: it is the real eight-phase source clip but is selected only when measured CPS is strictly greater than 6.8. Its collision test continues to sweep previous -> current elapsed time so accelerated playback cannot tunnel over narrow contact windows.

## Collider status

Weapon/style collider data is loaded and attached to attack profiles in 2.0. The current DBC damage adapter still uses the proven range/facing target selection rather than replacing DBC/JRMCore gameplay with an unverified modern-Epic collision engine. This separation is intentional: it makes collider definitions data-driven now without risking a live DBC combat regression before the full armature-space collider bridge is validated.

## First-person calibration

The source-parity renderer still uses the same final 20-joint pose as third person and the real JBRA RA/LA geometry. 2.0 adds one persistent shared framing adapter only:

`config/epicfight1710/first_person.properties`

2.0.1 layout-v2 defaults: `frameX=0.00`, `frameY=+0.12`, `frameZ=-1.35`. Existing 2.0 files without `layoutVersion=2` are migrated automatically because `frameZ=0` placed the real JBRA cuboids on the near plane.

Controls (all editable):
- `[` = arms up (+0.05 Y)
- `]` = arms down (-0.05 Y)
- `\` = reset

Both arms move together. No per-arm fitting, source keyframe edit, or first-person-only punch solver is introduced.
