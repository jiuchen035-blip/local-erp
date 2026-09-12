@echo off
rem 一键打包单文件安装包 LocalERP-Setup.exe（双击运行）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -Installer
pause
