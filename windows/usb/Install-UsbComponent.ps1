# SPDX-License-Identifier: GPL-3.0-or-later
# Owner-invoked component setup. Never launched by the ordinary Host startup.
$ErrorActionPreference='Stop'
if(Get-Service UsbDk -ErrorAction SilentlyContinue){Write-Output 'UsbDk already exists. Preserve the installed shared component.';exit 0}
$usbSetupFolder=Join-Path $env:TEMP 'CatchWave-UsbDk-1.0.22'
New-Item -ItemType Directory -Force -Path $usbSetupFolder | Out-Null
$usbSetupMsi=Join-Path $usbSetupFolder 'UsbDk_1.0.22_x64.msi'
if(!(Test-Path -LiteralPath $usbSetupMsi)){Invoke-WebRequest -Uri 'https://github.com/daynix/UsbDk/releases/download/v1.00-22/UsbDk_1.0.22_x64.msi' -OutFile $usbSetupMsi}
if((Get-FileHash -LiteralPath $usbSetupMsi -Algorithm SHA256).Hash -ne '91F6F695E1E13C656024E6D3B55620BF08D8835EF05EE0496935BA6BB62466A5'){throw 'USB installer digest mismatch'}
if((Get-AuthenticodeSignature -LiteralPath $usbSetupMsi).Status -ne 'Valid'){throw 'USB installer signature invalid'}
Write-Output 'Installing the signed USB component may briefly restart USB devices. Windows may require a PC restart. No phone security or existing phone driver is changed.'
$usbSetupArguments='/i "'+$usbSetupMsi+'" /passive /norestart /l*v "'+$usbSetupFolder+'\installation.log"'
$usbSetupProcess=Start-Process -FilePath "$env:WINDIR\System32\msiexec.exe" -ArgumentList $usbSetupArguments -Verb RunAs -WindowStyle Hidden -PassThru -Wait
if($usbSetupProcess.ExitCode -notin @(0,3010)){throw ('USB component installation failed: '+$usbSetupProcess.ExitCode)}
Write-Output 'Installed UsbDk 1.0.22. If Host reports USB_DRIVER_RESTART_REQUIRED, save work and restart the PC. This script never restarts the PC.'
