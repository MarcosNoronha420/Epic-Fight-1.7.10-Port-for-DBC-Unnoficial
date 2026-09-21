# Development Workflow

Baseline: `v2.2.0-RC2-STABLE`.

All work should be isolated by subsystem. Do not combine unrelated refactors in one branch.

Recommended flow:

1. Branch from the stable baseline.
2. Change one subsystem only.
3. Add/extend automated tests for the changed behavior.
4. Run `tools/verify_release.sh` and subsystem tests.
5. Compare changed files against the baseline.
6. Test in the minimal DBC/JBRA development instance.
7. Test in the full modpack.
8. Merge only after regression contract checks pass.

Initial branch: `feature/joint-local-collider`.

The first goal is to make attack colliders follow authored arm/hand/tool joint transforms through the attack animation without changing renderer, first person, Tool_R mount, flight, or unrelated Animator behavior.
