# BXDC.bot - 一键启动脚本 (PowerShell)
# 启动三个服务：Skill Gateway (Java)、Agent Core (Node.js)、Frontend (Vue)

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$LogDir = Join-Path $RootDir "logs"

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  BXDC.bot 服务启动中..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ==========================================
# 1. 启动 Skill Gateway (Spring Boot, port 18080)
# ==========================================
Write-Host "[1/3] 启动 Skill Gateway (port 18080)..." -ForegroundColor Green
$gwDir = Join-Path $RootDir "backend\skill-gateway"
$gwLog = Join-Path $LogDir "skill-gateway.log"

Start-Process -WindowStyle Minimized -FilePath "cmd" `
  -ArgumentList "/c", "cd /d `"$gwDir`" && mvn spring-boot:run -Dspring-boot.run.fork=false -Dmaven.test.skip=true > `"$gwLog`" 2>&1"
Write-Host "  Skill Gateway 正在启动（日志: logs\skill-gateway.log）" -ForegroundColor Yellow

# ==========================================
# 2. 启动 Agent Core (NestJS, port 3000)
# ==========================================
Write-Host "[2/3] 启动 Agent Core (port 3000)..." -ForegroundColor Green
$acDir = Join-Path $RootDir "backend\agent-core"
$acLog = Join-Path $LogDir "agent-core.log"

Start-Process -WindowStyle Minimized -FilePath "cmd" `
  -ArgumentList "/c", "cd /d `"$acDir`" && npm run start:dev > `"$acLog`" 2>&1"
Write-Host "  Agent Core 正在启动（日志: logs\agent-core.log）" -ForegroundColor Yellow

# ==========================================
# 3. 启动 Frontend (Vite, port 5173)
# ==========================================
Write-Host "[3/3] 启动 Frontend (port 5173)..." -ForegroundColor Green
$feDir = Join-Path $RootDir "frontend"
$feLog = Join-Path $LogDir "frontend.log"

Start-Process -WindowStyle Minimized -FilePath "cmd" `
  -ArgumentList "/c", "cd /d `"$feDir`" && npm run dev > `"$feLog`" 2>&1"
Write-Host "  Frontend 正在启动（日志: logs\frontend.log）" -ForegroundColor Yellow

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  所有服务已启动！" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  访问地址:" -ForegroundColor White
Write-Host "  Frontend:      http://localhost:5173" -ForegroundColor Magenta
Write-Host "  Skill Gateway: http://localhost:18080" -ForegroundColor Magenta
Write-Host "  Agent Core:    http://localhost:3000" -ForegroundColor Magenta
Write-Host ""
Write-Host "  日志文件:" -ForegroundColor White
Write-Host "  Skill Gateway: logs\skill-gateway.log" -ForegroundColor Gray
Write-Host "  Agent Core:    logs\agent-core.log" -ForegroundColor Gray
Write-Host "  Frontend:      logs\frontend.log" -ForegroundColor Gray
Write-Host ""
Write-Host "  停止服务请运行: stop-services.bat 或 stop-services.ps1" -ForegroundColor Yellow
Write-Host ""
