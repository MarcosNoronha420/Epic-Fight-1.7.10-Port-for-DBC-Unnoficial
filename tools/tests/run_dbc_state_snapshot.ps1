param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force build/dbc-state-snapshot/classes | Out-Null
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d build/dbc-state-snapshot/classes `
    src/main/java/com/nicolas/epicfight1710/combat/DbcSpatialStateSnapshot.java `
    src/main/java/com/nicolas/epicfight1710/combat/NativeDbcSpatialProvider.java `
    tools/tests/DbcSpatialStateSnapshotTest.java
if ($LASTEXITCODE -ne 0) { throw 'DBC spatial state snapshot compilation failed' }
& $Java -cp build/dbc-state-snapshot/classes DbcSpatialStateSnapshotTest
if ($LASTEXITCODE -ne 0) { throw 'DBC spatial state snapshot regression failed' }
