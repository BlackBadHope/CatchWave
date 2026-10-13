# SPDX-License-Identifier: GPL-3.0-or-later
param([Parameter(Mandatory)][string]$Source,[string]$Configuration='Release',[string]$PortableKit)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $Source).Path
if(!(Test-Path -LiteralPath (Join-Path $root 'source-manifest.json'))){throw 'Use prepare-source.py first'}
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs=(& $vswhere -latest -products '*' -property installationPath | Select-Object -First 1)
$toolset=Join-Path $vs 'MSBuild\Microsoft\VC\v170\Platforms\x64\PlatformToolsets\WindowsKernelModeDriver10.0'
$buildArgs=@('/nologo','/m:1',"/p:Configuration=$Configuration",'/p:Platform=x64','/p:SignMode=Off')
$msbuild=Join-Path $vs 'MSBuild\Current\Bin\amd64\MSBuild.exe'
if($PortableKit){
    $kit=(Resolve-Path -LiteralPath $PortableKit).Path
    $compiler=Join-Path $vs 'VC\Tools\MSVC\14.44.35207'
    $spectre=Join-Path $kit 'spectre-x64\Contents\VC\Tools\MSVC\14.44.35207\lib\spectre\x64'
    foreach($required in @('wdk\c\Include\10.0.26100.0\km\ntddk.h','v170\Platforms\x64\PlatformToolsets\WindowsKernelModeDriver10.0\Toolset.props','microsoft.windows.sdk.cpp\c\Include\10.0.26100.0\shared\ntdef.h')){
        if(!(Test-Path -LiteralPath (Join-Path $kit $required))){throw "Missing pinned portable toolkit file: $required"}
    }
    if(!(Test-Path -LiteralPath (Join-Path $spectre 'libcpmt.lib'))){throw 'Missing Spectre libraries; mitigation remains enabled'}
    if(!(Test-Path -LiteralPath (Join-Path $compiler 'bin\HostX64\x64\cl.exe'))){throw 'Required MSVC 14.44.35207 is absent'}
    $imports=Join-Path $root 'Directory.Build.props'
    $template=Join-Path $PSScriptRoot 'Directory.Build.props'
    if(Test-Path -LiteralPath $imports){if((Get-FileHash $imports).Hash -ne (Get-FileHash $template).Hash){throw 'Preserve the existing build props; choose a fresh prepared source directory'}}
    else{Copy-Item -LiteralPath $template -Destination $imports}
    $buildArgs+=@("/p:CatchWaveKit=$kit","/p:VCTargetsPath=$kit\v170\","/p:VSInstallDir=$vs\","/p:VCInstallDir=$vs\VC\","/p:VCToolsInstallDir=$compiler\","/p:VC_LibraryPath_VC_Desktop_CurrentPlatform_spectre=$spectre","/p:InfToolPath=$kit\wdk\c\bin\10.0.26100.0\x64\",'/p:InfToolArchitecture=Native64Bit','/p:WindowsTargetPlatformVersion=10.0.26100.0')
}elseif(!(Test-Path -LiteralPath $toolset)){throw 'BLOCKED: Windows Driver Kit toolset is absent. No driver installed or security settings changed.'}
& $msbuild (Join-Path $root 'EndpointsCommon\EndpointsCommon.vcxproj') @buildArgs
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $msbuild (Join-Path $root 'TabletAudioSample\TabletAudioSample.vcxproj') @buildArgs
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Write-Output 'Build completed. This is an unsigned development package, not an installable release.'
