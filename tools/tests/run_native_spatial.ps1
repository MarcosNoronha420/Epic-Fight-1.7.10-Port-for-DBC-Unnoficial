param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [Parameter(Mandatory=$true)][string]$CfrJar,
    [string]$Java = 'java',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
& $Python tools/tests/prepare_native_oracles.py --cfr $CfrJar --java $Java
if ($LASTEXITCODE -ne 0) { throw 'Native hash/decompilation/oracle preparation failed' }
New-Item -ItemType Directory -Force build/native-oracle/classes | Out-Null
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d build/native-oracle/classes `
    src/main/java/com/nicolas/epicfight1710/combat/NativeDbcSpatialProvider.java `
    build/native-oracle/NativeBodyOracle.java build/native-oracle/NativeFormulaOracle.java `
    tools/tests/NativeDbcSpatialProviderTest.java
if ($LASTEXITCODE -ne 0) { throw 'Pure native descriptor compilation failed' }
& $Java -cp build/native-oracle/classes NativeDbcSpatialProviderTest
if ($LASTEXITCODE -ne 0) { throw 'Native descriptor regression failed' }
