@echo off
title Stop Shop

echo ==============================
echo   停止 Shop 服务
echo ==============================
echo.

set "PROJ_DIR=D:\claudecode\shop"

echo [1/2] 停止 Nginx...
"%PROJ_DIR%\deploy\nginx.exe" -c "%PROJ_DIR%\deploy\conf\nginx.conf" -p "%PROJ_DIR%\deploy" -s quit
if %errorlevel% equ 0 (
    echo    Nginx 已停止
) else (
    echo    Nginx 可能未运行
)
echo.

echo [2/2] 停止后端...
echo    请在后端运行的窗口按 Ctrl+C 停止 Spring Boot
echo    或通过任务管理器结束 java 进程
echo.

echo 操作完成
pause
