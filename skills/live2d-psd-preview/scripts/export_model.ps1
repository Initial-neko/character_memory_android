param(
    [Parameter(Mandatory=$true)][string]$ToolDir,
    [Parameter(Mandatory=$true)][string]$PsdInput,
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$GradleHome,
    [ValidatePattern('^$|^#[0-9a-fA-F]{6}$')][string]$MouthColor = ''
)
$ErrorActionPreference = 'Stop'
$taskTool = (Resolve-Path -LiteralPath $ToolDir).Path
$taskInput = (Resolve-Path -LiteralPath $PsdInput).Path
$taskOutput = [System.IO.Path]::GetFullPath($OutputDir)
if (Test-Path -LiteralPath $taskOutput) { throw 'Use a new output directory' }
$taskJava = (Resolve-Path -LiteralPath $JavaHome).Path
$taskGradle = [System.IO.Path]::GetFullPath($GradleHome)
$taskInit = Join-Path $PSScriptRoot 'headless.init.gradle'
$taskWrapper = Join-Path $taskTool 'gradlew.bat'
if (!(Test-Path -LiteralPath $taskWrapper)) { throw 'Auto_Vtb_beta Gradle wrapper missing' }
$taskLog = "$taskOutput.export.log"
$taskReport = "$taskOutput.export.json"
$taskParent = Split-Path -Parent $taskOutput
New-Item -ItemType Directory -Path $taskParent -Force | Out-Null
$taskOldJava = $env:JAVA_HOME
$taskOldGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $taskJava
    $env:GRADLE_USER_HOME = $taskGradle
    Push-Location $taskTool
    try {
        $taskArgs = @('--offline','--no-daemon','-Pkotlin.compiler.execution.strategy=in-process',
            '-I',$taskInit,'skillExport',"-Plive2dHelperDir=$PSScriptRoot",
            "-PpsdInput=$taskInput","-PmodelOutput=$taskOutput","-PmouthColor=$MouthColor")
        & $taskWrapper @taskArgs *> $taskLog
        $taskCode = $LASTEXITCODE
        @{exit_code=$taskCode;offline=$true;input_sha256=(Get-FileHash -LiteralPath $taskInput -Algorithm SHA256).Hash;
            explicit_mouth_color=$MouthColor;runtime_verified=$false} |
            ConvertTo-Json | Set-Content -Encoding utf8 $taskReport
        if ($taskCode -ne 0) { throw "Export failed; inspect $taskLog" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $taskOldJava
    $env:GRADLE_USER_HOME = $taskOldGradle
}
