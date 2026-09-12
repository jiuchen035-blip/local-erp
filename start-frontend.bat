@echo off
chcp 65001 >nul
cd /d %~dp0frontend
title Local-ERP 前端

if not exist node_modules (
    echo [首次运行] 安装依赖...
    call npm install --no-audit --no-fund
)

echo 启动前端 http://localhost:5173
call npm run dev
pause
