param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [Parameter(Mandatory=$true)][string]$CfrJar,
    [string]$Java = 'java',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
& $Python tools/tests/prepare_live_spatial_oracles.py --cfr $CfrJar --java $Java
if ($LASTEXITCODE -ne 0) { throw 'Live native oracle preparation failed' }
New-Item -ItemType Directory -Force build/dbc-live-spatial/classes | Out-Null
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d build/dbc-live-spatial/classes `
    src/main/java/com/nicolas/epicfight1710/util/Reflect.java `
    src/main/java/com/nicolas/epicfight1710/combat/DbcSpatialStateSnapshot.java `
    src/main/java/com/nicolas/epicfight1710/combat/NativeDbcSpatialProvider.java `
    src/main/java/com/nicolas/epicfight1710/combat/DbcLiveSpatialAdapter.java `
    src/main/java/com/nicolas/epicfight1710/combat/DbcLiveSpatialConfigAdapter.java `
    src/main/java/com/nicolas/epicfight1710/combat/ReflectiveDbcSpatialSource.java `
    build/dbc-live-spatial/oracle/NativeLiveReadOracle.java `
    tools/tests/DbcLiveSpatialAdapterTest.java
if ($LASTEXITCODE -ne 0) { throw 'Live DBC spatial adapter compilation failed' }
& $Java -cp build/dbc-live-spatial/classes DbcLiveSpatialAdapterTest
if ($LASTEXITCODE -ne 0) { throw 'Live DBC spatial adapter regression failed' }
