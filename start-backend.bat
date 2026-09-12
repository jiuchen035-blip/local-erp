@echo off
chcp 65001 >nul
cd /d %~dp0backend
title Local-ERP 后端

rem 便携版 JDK（优先），未装则退回系统 java
set "JAVA_CMD=C:\Users\ZhuanZ\erp-tools\jdk-17.0.20.1+1\bin\java.exe"
if not exist "%JAVA_CMD%" set "JAVA_CMD=java"

if not exist "target\local-erp-0.5.0.jar" (
    echo [首次运行] 正在构建，请稍候...
    call C:\Users\ZhuanZ\erp-tools\apache-maven-3.9.16\bin\mvn.cmd -q -DskipTests package
)

echo 启动后端 http://localhost:8080 （开发数据在 backend\data\erp.db）
"%JAVA_CMD%" -jar target\local-erp-0.5.0.jar
pause
