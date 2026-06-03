# BXDC.bot - 一键停止脚本 (PowerShell)
# 停止三个服务：Skill Gateway (Java)、Agent Core (Node.js)、Frontend (Vue)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  正在停止 BXDC.bot 服务..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ==========================================
# 1. 停止 Skill Gateway (Java)
# ==========================================
Write-Host "[1/3] 停止 Skill Gateway (Java)..." -ForegroundColor Green
$stopped = $false
Get-Process -Name "java" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $cmdLine = (Get-WmiObject Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
        if ($cmdLine -match "skill-gateway") {
            Stop-Process -Id $_.Id -Force
            Write-Host "  Skill Gateway (PID: $($_.Id)) 已停止" -ForegroundColor Yellow
            $stopped = $true
        }
    } catch {}
}
if (-not $stopped) {
    Write-Host "  未找到 Skill Gateway 进程" -ForegroundColor Gray
}

# ==========================================
# 2. 停止 Agent Core (Node.js)
# ==========================================
Write-Host "[2/3] 停止 Agent Core (Node.js)..." -ForegroundColor Green
$stopped = $false
Get-Process -Name "node" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $cmdLine = (Get-WmiObject Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
        if ($cmdLine -match "agent-core" -or $cmdLine -match "nest") {
            Stop-Process -Id $_.Id -Force
            Write-Host "  Agent Core (PID: $($_.Id)) 已停止" -ForegroundColor Yellow
            $stopped = $true
        }
    } catch {}
}
if (-not $stopped) {
    Write-Host "  未找到 Agent Core 进程" -ForegroundColor Gray
}

# ==========================================
# 3. 停止 Frontend (Node.js / Vite)
# ==========================================
Write-Host "[3/3] 停止 Frontend (Vite)..." -ForegroundColor Green
$stopped = $false
Get-Process -Name "node" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $cmdLine = (Get-WmiObject Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
        if ($cmdLine -match "vite") {
            Stop-Process -Id $_.Id -Force
            Write-Host "  Frontend (PID: $($_.Id)) 已停止" -ForegroundColor Yellow
            $stopped = $true
        }
    } catch {}
}
if (-not $stopped) {
    Write-Host "  未找到 Frontend 进程" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  所有服务已停止！" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
