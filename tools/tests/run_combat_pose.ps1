param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
# Headless integration, NOT a Forge build or visual acceptance test.
# Compile actual animator/controller/guard/Compat; only native entry boundaries
# are test doubles. All output and the frozen baseline oracle remain in build/.
$poseOutput = 'build/pose-headless'
New-Item -ItemType Directory -Force -Path "$poseOutput/classes" | Out-Null
$poseRoot = (Get-Location).Path.Replace('\','/')
$poseBaseline = & git -c "safe.directory=$poseRoot" show '883035ee7d0d15cba7298ab4e29a8aec22849587:src/main/java/com/nicolas/epicfight1710/anim/runtime/LayeredAnimator.java'
if ($LASTEXITCODE -ne 0) { throw 'Approved baseline required for regression oracle' }
$poseOracle = ($poseBaseline -join "`n").Replace('LayeredAnimator','BaselineLayeredAnimator')
[IO.File]::WriteAllText((Join-Path (Get-Location) "$poseOutput/BaselineLayeredAnimator.java"), $poseOracle, (New-Object Text.UTF8Encoding($false)))
$poseSources = @('anim','combat','util') | ForEach-Object {
    Get-ChildItem "src/main/java/com/nicolas/epicfight1710/$_" -Recurse -Filter '*.java' | ForEach-Object FullName
}
$poseSources += @('CombatController','CombatGuardRuntime','CombatHitResolver','AttackCadenceTracker',
    'RuntimeProfiler','Compat','DbcClientState','DbcFlightStateMachine','DashController','DbcAnatomicalRig',
    'DbcRetargetMath','PoseEngine','RuntimeAssets') | ForEach-Object {
    "src/main/java/com/nicolas/epicfight1710/client/$_.java"
}
$poseSources += Get-ChildItem tools/tests/headless -Filter '*.java' | ForEach-Object FullName
$poseSources += "$poseOutput/BaselineLayeredAnimator.java"
$poseSources += 'tools/tests/CombatPoseSnapshotTest.java'
$poseSources += @('CombatCollider220Test','WeaponRegistry200Test','StyleMount200Test') | ForEach-Object { "tools/tests/$_.java" }
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d "$poseOutput/classes" @poseSources
if ($LASTEXITCODE -ne 0) { throw 'Headless pose compilation failed' }
& $Java -cp "$poseOutput/classes;src/main/resources" com.nicolas.epicfight1710.client.CombatPoseSnapshotTest
if ($LASTEXITCODE -ne 0) { throw 'Combat pose tests failed' }
foreach ($poseRegression in @('com.nicolas.epicfight1710.client.CombatCollider220Test',
    'com.nicolas.epicfight1710.combat.WeaponRegistry200Test',
    'com.nicolas.epicfight1710.combat.StyleMount200Test')) {
    & $Java -cp "$poseOutput/classes;src/main/resources" $poseRegression 'src/main/resources/assets/epicfight1710/data/weapon_types.json'
    if ($LASTEXITCODE -ne 0) { throw "Regression failed: $poseRegression" }
}
