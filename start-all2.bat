@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ================================================
echo           BXDC-Bot 项目启动脚本
echo ================================================
echo.

set "PROJECT_ROOT=%~dp0"

echo [1/5] 安装 Agent Core 依赖并构建...
pushd "%PROJECT_ROOT%backend\agent-core"
if not exist "node_modules" (
    echo 正在安装 Agent Core 依赖...
    call npm install
)
echo 正在构建 Agent Core...
call npm run build
popd

echo.
echo [2/5] 安装 Frontend 依赖...
pushd "%PROJECT_ROOT%frontend"
if not exist "node_modules" (
    echo 正在安装 Frontend 依赖...
    call npm install
)
popd

echo.
echo [3/5] 启动 Skill Gateway (Java Spring Boot)...
set "SG_PATH=%PROJECT_ROOT%backend\skill-gateway"
start "Skill Gateway" cmd /k "cd /d "!SG_PATH!" && mvn spring-boot:run -Dspring-boot.run.debug=true"

echo 等待 Skill Gateway 启动中...
timeout /t 15 /nobreak >nul

echo.
echo [4/5] 启动 Agent Core (Node.js NestJS)...
set "AC_PATH=%PROJECT_ROOT%backend\agent-core"
start "Agent Core" cmd /k "cd /d "!AC_PATH!" && npm run start:dev"

echo 等待 Agent Core 启动中...
timeout /t 10 /nobreak >nul

echo.
echo [5/5] 启动 Frontend (Vue + Vite)...
set "FE_PATH=%PROJECT_ROOT%frontend"
start "Frontend" cmd /k "cd /d "!FE_PATH!" && npm run dev"

echo.
echo ================================================
echo          所有服务已启动！
echo ================================================
echo 请保持这些终端窗口打开。
echo.
echo   前端访问: http://localhost:5173
echo   Agent Core: http://localhost:3000
echo   Skill Gateway: http://localhost:8080
echo.
pause