# EpicFight1710-Port 2.2.0-RC2

Structural release candidate for the Minecraft 1.7.10 Epic Fight port over DBC/JRMCore/JBRA.

2.1.2-RC1 remains the live-tested baseline. 2.2.0-RC2 changes internal ownership rather than animation fidelity: real spatial collider resolution replaces the old one-target cone selector, DBC/JRMCore state is snapshotted at tick lifetime, duplicate final-pose work is suppressed per logical render sample, guard/CPS logic is split out of CombatController, and skinning hot paths reuse pose/matrix state instead of repeatedly climbing back into PoseEngine.

The accepted WORLD-BODY first-person camera contract is frozen: exact legacy RenderPlayer `-Entity.yOffset`, POV Y=-0.075, horizontal body-back/eye-forward=0.0625, native JBRA appearance ownership and Tool_R semantics. No reduced animation rate, hidden limbs, simplified skeleton or lower-quality fallback is introduced for performance.

Runtime profiler is opt-in with `-Depicfight1710.profiler=true`. It reports average microseconds/call for Animator, collider, DBC-state, JBRA-skin, first-person and combat without enabling synchronous hot-path logging by default.

This is an RC: static/unit/package validation can prove invariants and Java 8 compatibility, but live Forge 1.7.10 testing is still required for visual parity, combat feel and frametime acceptance.
