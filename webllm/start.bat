@echo off
chcp 65001 >nul
cd /d %~dp0
echo 启动 webllm 桥接服务（网页版模型）...
echo 首次使用请先安装依赖：pip install -r requirements.txt ^&^& playwright install chromium
python server.py
pause
