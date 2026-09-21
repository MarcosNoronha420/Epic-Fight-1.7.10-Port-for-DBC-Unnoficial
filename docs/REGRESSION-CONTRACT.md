# Regression Contract — baseline 2.2.0-RC2

This document defines behavior that must remain intact unless a branch explicitly targets that subsystem.

- Minecraft 1.7.10 + Forge starts and reaches the main menu.
- A world opens without an EpicFight1710 runtime exception.
- Battle Mode OFF remains vanilla/DBC compatible.
- Battle Mode ON toggles correctly.
- Basic fist attacks and combo chaining work.
- RELENTLESS_COMBO keeps all 8 authored hit windows.
- Guard works and exits cleanly.
- Walk, run, sneak, jump and fall remain functional.
- Hover, normal flight and sprint/fast flight remain functional.
- Third-person JBRA body rendering remains intact.
- First-person WORLD-BODY framing remains unchanged unless the branch explicitly targets first person.
- Tool_R mounting remains unchanged unless the branch explicitly targets item mounting.
- Ki attack aim integration remains functional.
- JBRA remains authoritative for head/face/hair/forms.
- No duplicated body/player render.
- No first-person arms above the head regression.
- No large frametime/FPS regression relative to the accepted baseline.

## Protected subsystems

Changes outside these areas must not modify their implementation or output without an explicit reason and review:

- First-person WORLD-BODY renderer
- Tool_R item mount
- DBC flight adapter / sprint-flight isolation
- JBRA head/face/hair ownership
- Accepted animation assets
