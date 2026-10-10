#define AppName "BrewPOS Self-Ordering Kiosk"
#define AppVersion "0.1.0"
[Setup]
AppId={{A6136CB7-6FC8-4218-99A7-5CD3AA1E58D9}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Azurate Software Solutions
DefaultDirName={localappdata}\Programs\BrewPOS Kiosk
DefaultGroupName=BrewPOS Kiosk
PrivilegesRequired=lowest
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
OutputDir=..\artifacts
OutputBaseFilename=BrewPOS-Kiosk-Windows-v0.1.0-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
DisableProgramGroupPage=yes
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
[Icons]
Name: "{autoprograms}\BrewPOS Kiosk"; Filename: "{app}\BrewPOS.Kiosk.Windows.exe"
Name: "{autodesktop}\BrewPOS Kiosk"; Filename: "{app}\BrewPOS.Kiosk.Windows.exe"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create desktop shortcut"; Flags: unchecked
[Run]
Filename: "{app}\BrewPOS.Kiosk.Windows.exe"; Description: "Launch BrewPOS Kiosk"; Flags: nowait postinstall skipifsilent
; NEVER delete %LOCALAPPDATA%\Azurate\BrewPOS.Kiosk.Windows; unsent paid-counter tickets must survive upgrades.
