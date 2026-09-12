@echo off
rem 一键打包绿色版 zip（双击运行）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -Portable
pause
