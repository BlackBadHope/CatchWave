# SPDX-License-Identifier: GPL-3.0-or-later
param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference='Stop'
$kit=[IO.Path]::GetFullPath($Destination)
[IO.Directory]::CreateDirectory($kit) | Out-Null
$lock=Get-Content (Join-Path $PSScriptRoot 'toolchain-lock.json') -Raw | ConvertFrom-Json
foreach($package in $lock.nuget_sha512_hex.psobject.Properties){
    $name=$package.Name;$version=$lock.wdk_sdk_version
    $archive=Join-Path $kit "$name.$version.nupkg"
    if(!(Test-Path -LiteralPath $archive)){Invoke-WebRequest "https://api.nuget.org/v3-flatcontainer/$name/$version/$name.$version.nupkg" -OutFile $archive}
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $package.Value){throw "Package digest mismatch: $name"}
    $folder=if($name -eq 'microsoft.windows.wdk.x64'){'wdk'}else{$name}
    if(!(Test-Path -LiteralPath (Join-Path $kit $folder))){[IO.Compression.ZipFile]::ExtractToDirectory($archive,(Join-Path $kit $folder))}
}
foreach($item in @($lock.build_tools_vsix,$lock.spectre_vsix)){
    $archive=Join-Path $kit ($item.extract_folder+'.vsix')
    if(!(Test-Path -LiteralPath $archive)){Invoke-WebRequest $item.url -OutFile $archive}
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $item.sha256){throw 'Microsoft build payload digest mismatch'}
    if(!(Test-Path -LiteralPath (Join-Path $kit $item.extract_folder))){[IO.Compression.ZipFile]::ExtractToDirectory($archive,(Join-Path $kit $item.extract_folder))}
}
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs=& $vswhere -latest -products '*' -property installationPath | Select-Object -First 1
if(!$vs){throw 'Visual Studio 2022 C++ build tools are required'}
$base=Join-Path $vs 'MSBuild\Microsoft\VC\v170'
if(!(Test-Path -LiteralPath (Join-Path $kit 'v170'))){Copy-Item -LiteralPath $base -Destination (Join-Path $kit 'v170') -Recurse}
Copy-Item (Join-Path $kit 'build-tools-vsix\Contents\MSBuild\Microsoft\VC\v170\*') -Destination (Join-Path $kit 'v170') -Recurse -Force
Write-Output 'Portable toolkit prepared and payloads verified. No components or drivers installed.'
