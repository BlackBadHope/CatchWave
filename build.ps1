param([switch]$Validate)
$ErrorActionPreference = 'Stop'
$waveProjectRoot = $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $waveJavaCommand = Get-Command java.exe -ErrorAction Stop
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $waveJavaCommand.Source)
}
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) { throw 'Set JAVA_HOME to a valid JDK 17 directory.' }
$waveScratch = Join-Path $env:TEMP 'CatchWave-public25-build'
New-Item -ItemType Directory -Force -Path $waveScratch | Out-Null
Push-Location $waveProjectRoot
try {
    $waveTasks = @(':app:assembleRelease')
    if ($Validate) { $waveTasks = @(':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease') }
    & .\gradlew.bat --no-daemon --project-cache-dir "$waveScratch/cache" "-PcatchwaveBuildRoot=$waveScratch" @waveTasks
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
    $waveDist = Join-Path $waveProjectRoot 'dist'
    New-Item -ItemType Directory -Force -Path $waveDist | Out-Null
    $waveMetadata = Get-Content -LiteralPath "$waveScratch/app/outputs/apk/release/output-metadata.json" -Raw | ConvertFrom-Json
    $waveElement = $waveMetadata.elements[0]
    $waveName = "CatchWave-$($waveElement.versionName)-code$($waveElement.versionCode).apk"
    Copy-Item -LiteralPath "$waveScratch/app/outputs/apk/release/app-release.apk" -Destination (Join-Path $waveDist $waveName)
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $waveDist $waveName)
} finally { Pop-Location }
