package com.nicolas.epicfight1710.combat;

/**
 * Small 1.7.10 representation of Epic Fight's weapon Style layer.
 *
 * WeaponStyle in the legacy port names weapon *families* (sword, spear, etc.).
 * Epic Fight itself keeps that separate from the active combat Style.  Keeping the
 * concepts separate is important for future ONE_HAND/TWO_HAND/SHEATH/OCHS routing
 * and for data-driven item capabilities.
 */
public enum EpicCombatStyle {
    COMMON,
    ONE_HAND,
    TWO_HAND,
    MOUNT,
    RANGED,
    SHEATH,
    OCHS
}
