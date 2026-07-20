@echo off
title Shop 电商系统

echo ==============================
echo   Shop 电商系统 - 一键启动
echo ==============================
echo.

:: 设置 Java 和 Maven 路径
set "PROJ_DIR=D:\claudecode\shop"
set JAVA_HOME=C:\Users\26373\.jdks\openjdk-22.0.1
set PATH=%JAVA_HOME%\bin;D:\software\maven\apache-maven-3.9.9\bin;%PATH%

:: 跳过构建，先确保前端 dist 存在
if not exist "%PROJ_DIR%\frontend\dist" (
    echo [提示] 前端尚未构建，请先分别进入各 frontend 目录执行 npm run build
)

echo [1/3] 停止已有的 Nginx 进程...
"%PROJ_DIR%\deploy\nginx.exe" -c "%PROJ_DIR%\deploy\conf\nginx.conf" -p "%PROJ_DIR%\deploy" -s quit 2>nul
timeout /t 2 /nobreak >nul

echo [2/3] 启动 Nginx...
start "" "%PROJ_DIR%\deploy\nginx.exe" -c "%PROJ_DIR%\deploy\conf\nginx.conf" -p "%PROJ_DIR%\deploy"
echo    Nginx 已启动
echo    用户端：   http://localhost:8088
echo    管理后台： http://localhost:8088/admin/
echo    商家中心： http://localhost:8088/merchant/
echo.

echo [3/3] 启动后端（JDK 22）...
echo 按 Ctrl+C 可停止后端服务
echo.
cd /d "%PROJ_DIR%"
mvn spring-boot:run

pause
