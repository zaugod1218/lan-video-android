@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在共享 D:\yingshiapp
echo.
python video_server.py "D:\yingshiapp"
echo.
echo 服务已经停止，按任意键关闭窗口。
pause >nul
