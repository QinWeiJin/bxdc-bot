@echo off
chcp 65001 >nul

echo ========================================
echo   正在停止 BXDC.bot 服务...
echo ========================================
echo.

:: Stop Skill Gateway (Java process)
echo [1/3] 停止 Skill Gateway (Java)...
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq BXDC-SkillGateway*" /fo list ^| find "PID:"') do (
    taskkill /PID %%a /F /T >nul 2>&1
    echo   Skill Gateway (PID: %%a) 已停止
)
:: Fallback: kill java processes running skill-gateway
for /f "tokens=2" %%a in ('wmic process where "name='java.exe' and commandline like '%%skill-gateway%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
    echo   Skill Gateway Java (PID: !PID!) 已停止
)
for /f "tokens=2" %%a in ('wmic process where "name='cmd.exe' and commandline like '%%BXDC-SkillGateway%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
)

:: Stop Agent Core (Node.js / npm)
echo [2/3] 停止 Agent Core (Node.js)...
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq BXDC-AgentCore*" /fo list ^| find "PID:"') do (
    taskkill /PID %%a /F /T >nul 2>&1
    echo   Agent Core (PID: %%a) 已停止
)
for /f "tokens=2" %%a in ('wmic process where "name='node.exe' and commandline like '%%agent-core%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
    echo   Agent Core Node (PID: !PID!) 已停止
)
for /f "tokens=2" %%a in ('wmic process where "name='cmd.exe' and commandline like '%%BXDC-AgentCore%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
)

:: Stop Frontend (Node.js / Vite)
echo [3/3] 停止 Frontend (Vite)...
for /f "tokens=2" %%a in ('tasklist /fi "WINDOWTITLE eq BXDC-Frontend*" /fo list ^| find "PID:"') do (
    taskkill /PID %%a /F /T >nul 2>&1
    echo   Frontend (PID: %%a) 已停止
)
for /f "tokens=2" %%a in ('wmic process where "name='node.exe' and commandline like '%%vite%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
    echo   Frontend Node (PID: !PID!) 已停止
)
for /f "tokens=2" %%a in ('wmic process where "name='cmd.exe' and commandline like '%%BXDC-Frontend%%'" get processid /value ^| find "="') do (
    set PID=%%a
    set PID=!PID:~0,-1!
    taskkill /PID !PID! /F /T >nul 2>&1
)

echo.
echo ========================================
echo   所有服务已停止！
echo ========================================
echo.
pause
