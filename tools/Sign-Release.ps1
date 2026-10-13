# SPDX-License-Identifier: GPL-3.0-or-later
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$SigningDirectory=(Join-Path $env:LOCALAPPDATA 'CatchWave\Signing'),
    [string]$SdkDirectory=(Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)
$ErrorActionPreference='Stop'
$waveKey=Join-Path $SigningDirectory 'catchwave-release.p12'
$wavePassword=Join-Path $SigningDirectory 'password.dpapi'
$waveLineage=Join-Path $SigningDirectory 'certificate-lineage.bin'
$waveSigner=Join-Path $SdkDirectory 'build-tools\36.0.0\lib\apksigner.jar'
foreach($waveFile in @($InputApk,$waveKey,$wavePassword,$waveLineage,$waveSigner)) { if(-not(Test-Path -LiteralPath $waveFile)){throw 'Required APK, private signing profile or Android signer is unavailable.'} }
$waveJava=(Get-Command java.exe -ErrorAction Stop).Source
$wavePreviousSecret=$env:CATCHWAVE_RELEASE_PASSWORD
try {
    Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
    $waveBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($wavePassword),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    $env:CATCHWAVE_RELEASE_PASSWORD=[Text.Encoding]::UTF8.GetString($waveBytes)
    [Array]::Clear($waveBytes,0,$waveBytes.Length)
    # All supported clients are Android 11/API30+, so v3 covers the whole range.
    # The lineage preserves installed-data trust; the old signer has no rollback capability.
    & $waveJava -jar $waveSigner sign --ks $waveKey --ks-key-alias catchwave-release --ks-pass env:CATCHWAVE_RELEASE_PASSWORD --lineage $waveLineage --rotation-min-sdk-version 28 --min-sdk-version 30 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false --out $OutputApk $InputApk
    if($LASTEXITCODE -ne 0){throw 'Release signing failed.'}
    & $waveJava -jar $waveSigner verify --min-sdk-version 30 $OutputApk
    if($LASTEXITCODE -ne 0){throw 'Release signature verification failed.'}
    Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
} finally { $env:CATCHWAVE_RELEASE_PASSWORD=$wavePreviousSecret }
