; ============================================================
; 账管卫士（LocalERP）单文件安装包脚本 — Inno Setup 6.5+（免费开源）
;
; 平时不用手动编译它：双击 package\build-installer.bat 即可。
; 手动编译方式：
;   命令行： "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" /DMyAppVersion=0.1.0 package\LocalERP-Setup.iss
;   图形界面：Inno Setup Compiler 打开本文件 → Build > Compile
;              （GUI 编译时本文件里的默认版本号生效）
;
; 安装特性：按用户安装，免管理员权限、免 UAC；
;   卸载时连运行数据一并删除 = 完全卸载。
; ============================================================

#ifndef MyAppName
#define MyAppName "账管卫士"
#endif
#ifndef MyAppVersion
#define MyAppVersion "0.6.0"
#endif
#define MyAppExeName MyAppName + ".exe"

[Setup]
; AppId 固定：同一台电脑重复安装/升级视为覆盖更新，不会装出两份
AppId={{7A1C30E2-5B8D-4F6E-9A24-C83D1E0F5B77}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
DefaultDirName={autopf}\{#MyAppName}
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir=..\out
OutputBaseFilename={#MyAppName}-Setup-{#MyAppVersion}
SetupIconFile=app-icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式(&D)"; GroupDescription: "附加任务："; Flags: checkedonce

[Files]
; 整个绿色版文件夹装进 {app}（runtime\ 是自带 JRE，对方电脑无需装 Java）
Source: "..\out\{#MyAppName}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "立即启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 运行期间产生的数据库/备份/日志/网页模型登录态一并删除：卸载 = 彻底清除
Type: filesandordirs; Name: "{app}\app\data"
Type: filesandordirs; Name: "{app}\app\backup"
Type: filesandordirs; Name: "{app}\webllm\profile"
Type: files; Name: "{app}\webllm\bridge.log"
