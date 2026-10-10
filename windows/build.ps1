param([switch]$Validate)
$ErrorActionPreference='Stop'
$waveBuild=Join-Path $env:TEMP 'CatchWave-public25-host-build'
$waveProject=Join-Path $PSScriptRoot 'CatchWave.Host\CatchWave.Host.csproj'
& dotnet build $waveProject -c Release "-p:BaseIntermediateOutputPath=$waveBuild\obj\" "-p:OutputPath=$waveBuild\bin\"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if($Validate){& "$waveBuild\bin\CatchWave.Host.exe" --self-test;exit $LASTEXITCODE}
Write-Output "Built output: $waveBuild\bin"
