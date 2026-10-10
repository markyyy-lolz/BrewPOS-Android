#define AppName "Cafe Oabi BrewPOS Windows"
#define AppVersion "1.0.0"
#define AppExe "CafeOabi.BrewPOS.exe"
[Setup]
AppId={{9B5EA603-6ACC-49A0-9D9E-3F6738CB7B91}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Azurate Software Solutions
DefaultDirName={localappdata}\Programs\Cafe Oabi BrewPOS
DefaultGroupName=Cafe Oabi BrewPOS
PrivilegesRequired=lowest
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir=..\artifacts
OutputBaseFilename=Cafe-Oabi-BrewPOS-Windows-v1.0.0-Setup
DisableProgramGroupPage=yes
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
[Icons]
Name: "{autoprograms}\Cafe Oabi BrewPOS"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\Cafe Oabi BrewPOS"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create desktop shortcut"; Flags: unchecked
[Run]
Filename: "{app}\{#AppExe}"; Description: "Launch Cafe Oabi BrewPOS"; Flags: nowait postinstall skipifsilent
; LOCAL SQLITE IN %LOCALAPPDATA%\Azurate\CafeOabi.BrewPOS IS NEVER ERASED BY UNINSTALL.
