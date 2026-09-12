# 账管卫士（LocalERP）一键打包脚本
# 用法：双击同目录下的 build-portable.bat（绿色版）或 build-installer.bat（安装包）
param(
  [switch]$Portable,   # 打绿色版文件夹 + zip
  [switch]$Installer   # 编译单文件安装包（会自动先确保绿色版存在）
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# ================= 配置区：按需修改 =================
$AppName    = '账管卫士'
$AppVersion = '0.6.0'
$JarVersion = '0.6.0'   # 与 backend/pom.xml 的 version 保持一致
$Jdk        = 'C:\Users\ZhuanZ\erp-tools\jdk-17.0.20.1+1'
$Mvn        = 'C:\Users\ZhuanZ\erp-tools\apache-maven-3.9.16\bin\mvn.cmd'
$Icon       = Join-Path $PSScriptRoot 'app-icon.ico'
# ====================================================

$Out = Join-Path $Root 'out'
function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# 默认（不带参数）：只打绿色版
if (-not ($Portable -or $Installer)) { $Portable = $true }
# 打安装包但绿色版不存在：自动先打绿色版
if ($Installer -and -not (Test-Path (Join-Path $Out "$AppName\$AppName.exe"))) { $Portable = $true }

if ($Portable) {
  Step '1/4 构建前端'
  Push-Location (Join-Path $Root 'frontend')
  & npm run build
  if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
  Pop-Location

  Step '2/4 构建后端 jar（前端页面自动打进去）'
  $env:JAVA_HOME = $Jdk
  Push-Location (Join-Path $Root 'backend')
  & $Mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
  Pop-Location

  Step '3/4 生成绿色版文件夹（自带精简 JRE，对方无需装 Java）'
  $AppDir = Join-Path $Out $AppName
  if (Test-Path $AppDir) { Remove-Item -Recurse -Force $AppDir }
  $Staging = Join-Path $PSScriptRoot 'staging'
  if (Test-Path $Staging) { Remove-Item -Recurse -Force $Staging }
  New-Item -ItemType Directory -Path $Staging | Out-Null
  Copy-Item (Join-Path $Root "backend\target\local-erp-$JarVersion.jar") $Staging
  & (Join-Path $Jdk 'bin\jpackage.exe') --type app-image --name $AppName --app-version $AppVersion `
    --vendor local-erp --icon $Icon --input $Staging --main-jar "local-erp-$JarVersion.jar" `
    --add-modules 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.charsets,jdk.localedata' `
    --java-options '-Dfile.encoding=UTF-8' --java-options '-Xms64m' --java-options '-Xmx768m' --dest $Out
  if ($LASTEXITCODE -ne 0) { throw 'jpackage 失败（若提示目录已存在，先退出正在运行的程序再打包）' }
  Remove-Item -Recurse -Force $Staging

  # jpackage 生成的 runtime 默认不含 java.exe，而备用启动器（控制台启动.bat）要靠它绕开 exe 启动器
  # 直接看到真实报错。从同版本 JDK 复制一份即可（java.exe 只依赖 runtime\bin 下的 jli.dll 与 server\jvm.dll）
  $RuntimeBin = Join-Path $AppDir 'runtime\bin'
  $Launcher   = Join-Path $RuntimeBin 'java.exe'
  if (-not (Test-Path $Launcher)) {
    $JdkJava = Join-Path $Jdk 'bin\java.exe'
    if (Test-Path $JdkJava) {
      Copy-Item $JdkJava $Launcher -Force
      Write-Host '  已补入 runtime\bin\java.exe（控制台启动.bat 现在可用）' -ForegroundColor Yellow
    } else {
      Write-Host '  警告：未找到 JDK 的 java.exe，控制台启动.bat 将不可用' -ForegroundColor Red
    }
  }

  Step '3.5/4 附带备用启动器与 webllm 桥接（网页版 AI 模型，可选）'
  Copy-Item (Join-Path $PSScriptRoot 'launch-fallback.bat') (Join-Path $AppDir "$AppName-控制台启动.bat")
  $WebllmDst = Join-Path $AppDir 'webllm'
  if (Test-Path $WebllmDst) { Remove-Item -Recurse -Force $WebllmDst }
  New-Item -ItemType Directory -Path $WebllmDst | Out-Null
  Copy-Item (Join-Path $Root 'webllm\server.py') $WebllmDst
  Copy-Item (Join-Path $Root 'webllm\toolcall.py') $WebllmDst
  Copy-Item (Join-Path $Root 'webllm\browser.py') $WebllmDst
  Copy-Item (Join-Path $Root 'webllm\requirements.txt') $WebllmDst
  Copy-Item (Join-Path $Root 'webllm\start.bat') $WebllmDst
  Copy-Item (Join-Path $Root 'webllm\adapters') (Join-Path $WebllmDst 'adapters') -Recurse
  Remove-Item -Recurse -Force (Join-Path $WebllmDst 'adapters\__pycache__') -ErrorAction SilentlyContinue

  Step '4/4 压缩绿色版 zip'
  $Zip = Join-Path $Out "$AppName-绿色版-$AppVersion.zip"
  if (Test-Path $Zip) { Remove-Item $Zip -Force }
  Compress-Archive -Path $AppDir -DestinationPath $Zip -Force
}

if ($Installer) {
  Step '编译单文件安装包（Inno Setup）'
  $Iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
  if (-not (Test-Path $Iscc)) { throw '未找到 ISCC.exe，请先安装 Inno Setup 6：https://jrsoftware.org/isdl.php' }
  & $Iscc "/DMyAppVersion=$AppVersion" (Join-Path $PSScriptRoot 'LocalERP-Setup.iss')
  if ($LASTEXITCODE -ne 0) { throw '安装包编译失败' }
}

Step '完成'
Write-Host "产物在 out 文件夹："
if ($Portable)  { Write-Host "  $AppName-绿色版-$AppVersion.zip    <- 发给对方（免安装，解压双击即用）" }
if ($Installer) { Write-Host "  $AppName-Setup-$AppVersion.exe   <- 发给对方（双击安装出桌面快捷方式）" }
