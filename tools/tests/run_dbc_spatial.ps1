param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [Parameter(Mandatory=$true)][string]$LwjglJar,
    [string]$Java = 'java',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
# Real client/render sources with LWJGL symbols. No GL context/native draw.
# Only Forge entry/resource anchor and Tessellator are compile boundary doubles.
& $Python tools/tests/prepare_spatial_oracles.py
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare approved spatial oracles' }
New-Item -ItemType Directory -Force build/spatial/classes | Out-Null
# Compile the extracted operators alone too: accidental client/GL dependencies fail.
New-Item -ItemType Directory -Force build/spatial/pure | Out-Null
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d build/spatial/pure `
    src/main/java/com/nicolas/epicfight1710/anim/Mat4.java `
    src/main/java/com/nicolas/epicfight1710/client/DbcSpatialMath.java
if ($LASTEXITCODE -ne 0) { throw 'Pure spatial dependency check failed' }
$spatialSources = @('anim','combat','util','client') | ForEach-Object {
    Get-ChildItem "src/main/java/com/nicolas/epicfight1710/$_" -Recurse -Filter '*.java' |
        Where-Object { $_.Name -notin @('ClientBootstrap.java','ClientHooks.java') } | ForEach-Object FullName
}
$spatialSources += @('tools/tests/headless/EpicFight1710.java','tools/tests/spatial/Tessellator.java',
    'tools/tests/DbcSpatialMathTest.java','tools/tests/CombatPoseSnapshotTest.java')
$spatialSources += Get-ChildItem build/spatial/oracle -Filter '*.java' | ForEach-Object FullName
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -cp $LwjglJar -d build/spatial/classes @spatialSources
if ($LASTEXITCODE -ne 0) { throw 'Spatial/client source compilation failed' }
& $Java -cp "build/spatial/classes;src/main/resources;$LwjglJar" com.nicolas.epicfight1710.client.DbcSpatialMathTest
if ($LASTEXITCODE -ne 0) { throw 'Spatial math tests failed' }
