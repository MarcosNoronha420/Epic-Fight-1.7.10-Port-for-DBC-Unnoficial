"""Freeze approved renderer code in ignored build/, never regenerate from edits."""
from pathlib import Path
import re
import subprocess

ROOT = Path.cwd()
BASE = "0caa399eead162b9557880664bfa236eed5183c4"
OUT = ROOT / "build/spatial/oracle"
OUT.mkdir(parents=True, exist_ok=True)

def source(name, package="client", base=BASE):
    return subprocess.check_output([
        "git", "-c", "safe.directory=" + ROOT.as_posix(), "show",
        base + ":src/main/java/com/nicolas/epicfight1710/" + package + "/" + name + ".java"
    ]).decode("utf-8")

for name in ("JbraWeightedPartRenderer", "WeaponItemMountHook", "DbcRetargetMath"):
    (OUT / ("Baseline" + name + ".java")).write_text(
        source(name).replace(name, "Baseline" + name), encoding="utf-8")

(OUT / "BaselineLayeredAnimator.java").write_text(
    source("LayeredAnimator", "anim/runtime", "883035ee7d0d15cba7298ab4e29a8aec22849587")
    .replace("LayeredAnimator", "BaselineLayeredAnimator"), encoding="utf-8")

# Exact constant and arithmetic from the original head path, isolated from GL.
head = source("NativeJbraSkinContext")
constant = re.search(r"private final float\[\] epicToJbra=\{.*?\};", head, re.S).group(0)
operations = re.search(r"Mat4.mul\(epicToJbra,skin\[chest\],headPoseTmp\);\s*"
                       r"Mat4.mul\(headPoseTmp,epicToJbra,headPoseJbra\);", head).group(0)
code = "package com.nicolas.epicfight1710.client;\nimport com.nicolas.epicfight1710.anim.Mat4;\n"
code += "final class BaselineHeadMath {\n" + constant + "\n"
code += "void convert(float[] skin,float[] headPoseTmp,float[] headPoseJbra){\n"
code += operations.replace("skin[chest]", "skin") + "\n}\n}\n"
(OUT / "BaselineHeadMath.java").write_text(code, encoding="utf-8")
print("Prepared spatial oracles from " + BASE)
