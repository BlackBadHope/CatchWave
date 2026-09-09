param([switch]$Validate)
$ErrorActionPreference = 'Stop'
$waveProjectRoot = $PSScriptRoot
$waveJavaCommand = Get-Command java.exe -ErrorAction Stop
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $waveJavaCommand.Source)
$waveScratch = Join-Path $env:TEMP 'CatchWave-build'
New-Item -ItemType Directory -Force -Path $waveScratch | Out-Null
Push-Location $waveProjectRoot
try {
    $waveTasks = @(':app:assembleRelease')
    if ($Validate) { $waveTasks = @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease') }
    & .\gradlew.bat --no-daemon --project-cache-dir "$waveScratch/cache" "-PcatchwaveBuildRoot=$waveScratch" @waveTasks
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
    $waveDist = Join-Path $waveProjectRoot 'dist'
    New-Item -ItemType Directory -Force -Path $waveDist | Out-Null
    Copy-Item -LiteralPath "$waveScratch/app/outputs/apk/release/app-release.apk" -Destination "$waveDist/Podhvat-0.1.12.apk"
    Get-FileHash -Algorithm SHA256 -LiteralPath "$waveDist/Podhvat-0.1.12.apk"
} finally { Pop-Location }
