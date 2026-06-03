@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set ROOT=%~dp0
set LOGDIR=%ROOT%logs

if not exist "%LOGDIR%" mkdir "%LOGDIR%"

echo ========================================
echo   BXDC.bot 服务启动中...
echo ========================================
echo.

:: ==========================================
:: 1. Skill Gateway (Spring Boot, port 18080)
:: ==========================================
echo [1/3] 启动 Skill Gateway (port 18080)...
set GW_DIR=%ROOT%backend\skill-gateway
start "BXDC-SkillGateway" /MIN cmd /c "cd /d "%GW_DIR%" && mvn spring-boot:run -Dspring-boot.run.fork=false -Dmaven.test.skip=true > "%LOGDIR%\skill-gateway.log" 2>&1"
echo   Skill Gateway 正在启动（新窗口，日志: logs\skill-gateway.log）

:: ==========================================
:: 2. Agent Core (NestJS, port 3000)
:: ==========================================
echo [2/3] 启动 Agent Core (port 3000)...
set AC_DIR=%ROOT%backend\agent-core
start "BXDC-AgentCore" /MIN cmd /c "cd /d "%AC_DIR%" && npm run start:dev > "%LOGDIR%\agent-core.log" 2>&1"
echo   Agent Core 正在启动（新窗口，日志: logs\agent-core.log）

:: ==========================================
:: 3. Frontend (Vite, port 5173)
:: ==========================================
echo [3/3] 启动 Frontend (port 5173)...
set FE_DIR=%ROOT%frontend
start "BXDC-Frontend" /MIN cmd /c "cd /d "%FE_DIR%" && npm run dev > "%LOGDIR%\frontend.log" 2>&1"
echo   Frontend 正在启动（新窗口，日志: logs\frontend.log）

echo.
echo ========================================
echo   所有服务已启动！
echo ========================================
echo.
echo   访问地址:
echo   Frontend:      http://localhost:5173
echo   Skill Gateway: http://localhost:18080
echo   Agent Core:    http://localhost:3000
echo.
echo   停止服务请运行: stop-services.bat
echo.
pause
