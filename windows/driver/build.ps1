# SPDX-License-Identifier: GPL-3.0-or-later
param([Parameter(Mandatory)][string]$Source,[string]$Configuration='Release')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $Source).Path
if(!(Test-Path -LiteralPath (Join-Path $root 'source-manifest.json'))){throw 'Use prepare-source.py first'}
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs=(& $vswhere -latest -products '*' -property installationPath | Select-Object -First 1)
$toolset=Join-Path $vs 'MSBuild\Microsoft\VC\v170\Platforms\x64\PlatformToolsets\WindowsKernelModeDriver10.0'
if(!(Test-Path -LiteralPath $toolset)){throw 'BLOCKED: Windows Driver Kit toolset is absent. No driver installed or security settings changed.'}
$msbuild=Join-Path $vs 'MSBuild\Current\Bin\MSBuild.exe'
& $msbuild (Join-Path $root 'EndpointsCommon\EndpointsCommon.vcxproj') /nologo /m:1 "/p:Configuration=$Configuration" /p:Platform=x64 /p:SignMode=Off
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $msbuild (Join-Path $root 'TabletAudioSample\TabletAudioSample.vcxproj') /nologo /m:1 "/p:Configuration=$Configuration" /p:Platform=x64 /p:SignMode=Off
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Write-Output 'Build completed. This is an unsigned development package, not an installable release.'
