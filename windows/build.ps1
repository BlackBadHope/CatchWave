param([switch]$Validate, [switch]$Package)
$ErrorActionPreference='Stop'
$waveBuild=Join-Path $env:TEMP 'CatchWave-public25-host-build'
$waveProject=Join-Path $PSScriptRoot 'CatchWave.Host\CatchWave.Host.csproj'
if(-not $Package){
    & dotnet build $waveProject -c Release "-p:BaseIntermediateOutputPath=$waveBuild\obj\" "-p:OutputPath=$waveBuild\bin\"
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    if($Validate){& dotnet "$waveBuild\bin\CatchWave.Host.dll" --self-test;exit $LASTEXITCODE}
    Write-Output "Built output: $waveBuild\bin"
    return
}

$publish=Join-Path $env:TEMP 'CatchWave-public25-host-package'
if(Test-Path -LiteralPath $publish){Remove-Item -LiteralPath $publish -Recurse -Force}
& dotnet publish $waveProject -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:IncludeAllContentForSelfExtract=true `
    -p:DebugType=none `
    -p:DebugSymbols=false `
    -p:EnableWindowsTargeting=true `
    -o $publish
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}

$loose=Get-ChildItem -LiteralPath $publish -File | Where-Object { $_.Extension -in '.dll','.cmd','.bat' }
if($loose){throw "Single-file package still has extra files: $($loose.Name -join ', ')"}
$exe=Join-Path $publish 'CatchWave.Host.exe'
if(-not (Test-Path -LiteralPath $exe)){throw 'CatchWave.Host.exe was not produced'}
if((Get-Item -LiteralPath $exe).Length -lt 20MB){throw 'CatchWave.Host.exe is too small to be a self-contained single file'}

$repo=Split-Path -Parent $PSScriptRoot
$dist=Join-Path $repo 'dist\CatchWave-Host'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item -LiteralPath $exe -Destination (Join-Path $dist 'CatchWave.Host.exe') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package\START-HERE.txt') -Destination $dist -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'package\START-HERE-RU.txt') -Destination $dist -Force
Get-ChildItem -LiteralPath $dist -File | ForEach-Object { Write-Output ("{0}  {1}" -f $_.Name, $_.Length) }

& $exe --self-test
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}

$iscc=@(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if(-not $iscc){
    Write-Output 'Inno Setup 6 not found. The single exe is in dist\CatchWave-Host. Install Inno Setup 6.3 or newer and run -Package again for CatchWave-Host-Setup.exe.'
    return
}
$version=(Select-Xml -LiteralPath $waveProject -XPath '//Version').Node.InnerText
& $iscc "/DMyAppVersion=$version" "/DPublishDir=$dist" (Join-Path $PSScriptRoot 'package\CatchWave.Host.iss')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Write-Output "Installer: $(Join-Path $repo 'dist\CatchWave-Host-Setup.exe')"
