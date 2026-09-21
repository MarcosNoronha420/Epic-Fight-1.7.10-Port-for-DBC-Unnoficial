param(
    [Parameter(Mandatory=$true)][string]$CompilerJar,
    [string]$Java = 'java',
    [string]$Python = 'python',
    [string]$UpstreamZip
)
$ErrorActionPreference = 'Stop'
# Invoke from repository root. Output is confined to ignored build/audit/classes.
# This runs standalone tests, not Forge compilation, packaging or client acceptance.
New-Item -ItemType Directory -Force -Path build/audit/classes | Out-Null
$auditSources = @('Clip','ClipLibrary','Mat4','SkeletonMesh') | ForEach-Object {
    'src/main/java/com/nicolas/epicfight1710/anim/' + $_ + '.java'
}
$auditSources += @('AnimationDefinition','AnimationPlayer','AnimationPose','AnimationLayer',
    'AnimationCatalog','JointMask','LivingMotion','EpicSourceRegistry') | ForEach-Object {
    'src/main/java/com/nicolas/epicfight1710/anim/runtime/' + $_ + '.java'
}
$auditSources += @('AttackProfile','AttackPhase','AttackState','AttackStateSpectrum',
    'ColliderDefinition','FistMoveset') | ForEach-Object {
    'src/main/java/com/nicolas/epicfight1710/combat/' + $_ + '.java'
}
$auditSources += @('src/main/java/com/nicolas/epicfight1710/client/AttackCadenceTracker.java',
    'src/main/java/com/nicolas/epicfight1710/client/RuntimeProfiler.java',
    'tools/tests/AnimationAudit220Test.java','tools/tests/CombatTiming200Test.java',
    'tools/tests/RuntimeProfiler220Test.java','tools/tests/AttackCadence220Test.java')
& $Java -jar $CompilerJar -1.8 -encoding UTF-8 -d build/audit/classes @auditSources
if ($LASTEXITCODE -ne 0) { throw 'Standalone compilation failed' }
foreach ($auditTest in @('AnimationAudit220Test','CombatTiming200Test',
    'com.nicolas.epicfight1710.client.AttackCadence220Test',
    'com.nicolas.epicfight1710.client.RuntimeProfiler220Test')) {
    & $Java -cp build/audit/classes $auditTest
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $auditTest" }
}
& $Java '-Depicfight1710.profiler=true' -cp build/audit/classes com.nicolas.epicfight1710.client.RuntimeProfiler220Test
if ($LASTEXITCODE -ne 0) { throw 'Profiler enabled test failed' }
$auditArguments = @('tools/tests/audit_assets.py')
if ($UpstreamZip) { $auditArguments += @('--upstream', $UpstreamZip) }
& $Python @auditArguments
if ($LASTEXITCODE -ne 0) { throw 'Binary asset audit failed' }
