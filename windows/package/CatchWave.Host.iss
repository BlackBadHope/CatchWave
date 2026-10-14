; Inno Setup 6.3 or newer. Shortcut launches CatchWave.Host.exe with no extra arguments.
#ifndef MyAppVersion
#define MyAppVersion "0.1.47"
#endif
#ifndef PublishDir
#define PublishDir "..\..\dist\CatchWave-Host"
#endif

[Setup]
AppId={{7C4E9B2A-1D55-4F0E-9A6C-3B8E5D21F047}
AppName=CatchWave Host
AppVersion={#MyAppVersion}
DefaultDirName={localappdata}\CatchWave Host
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist
OutputBaseFilename=CatchWave-Host-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\CatchWave.Host.exe
SetupIconFile=..\CatchWave.Host\CatchWave.ico
CloseApplications=no

[Files]
Source: "{#PublishDir}\CatchWave.Host.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PublishDir}\START-HERE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PublishDir}\START-HERE-RU.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\CatchWave Host"; Filename: "{app}\CatchWave.Host.exe"
Name: "{autodesktop}\CatchWave Host"; Filename: "{app}\CatchWave.Host.exe"

[Run]
Filename: "{app}\CatchWave.Host.exe"; Description: "Open CatchWave Host"; Flags: nowait postinstall skipifsilent
