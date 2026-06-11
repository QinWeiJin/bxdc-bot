# bxdc-bot start-all script
$root = $PSScriptRoot

Write-Host "=== bxdc-bot Start ==="

Write-Host "[1/4] Stopping existing services..."
$ErrorActionPreference = "SilentlyContinue"
taskkill /f /fi "IMAGENAME eq java.exe" 2>$null | Out-Null
taskkill /f /fi "IMAGENAME eq node.exe" 2>$null | Out-Null
$ErrorActionPreference = "Continue"
Start-Sleep -Seconds 3

Write-Host "[2/4] Starting skill-gateway (port 18080)..."
New-Item -ItemType Directory -Path "E:\AI\bot\log" -Force | Out-Null
Start-Process powershell -WindowStyle Hidden -ArgumentList "-Command",
    "`$env:JAVA_HOME='D:\soft\jdk'; cd '$root\backend\skill-gateway'; java -jar target\skill-gateway-0.0.1-SNAPSHOT.jar 2>&1 | Out-File 'E:\AI\bot\log\skill-gateway.log' -Encoding utf8"

Write-Host "[3/4] Starting agent-core (port 3000)..."
Start-Process powershell -WindowStyle Hidden -ArgumentList "-Command",
    "cd '$root\backend\agent-core'; npm run start:dev 2>&1 | Out-File 'E:\AI\bot\log\agent-core.log' -Encoding utf8"

Write-Host "[4/4] Starting frontend (port 5173)..."
Start-Process powershell -WindowStyle Hidden -ArgumentList "-Command",
    "cd '$root\frontend'; npm run dev 2>&1 | Out-File 'E:\AI\bot\log\frontend.log' -Encoding utf8"

Write-Host ""
Write-Host "Services starting in background (no popup windows)."
Write-Host "Please wait 30-60 seconds, then visit:"
Write-Host "  frontend      : http://localhost:5173"
Write-Host "  agent-core    : http://localhost:3000"
Write-Host "  skill-gateway : http://localhost:18080"
Write-Host ""
Write-Host "Logs: E:\AI\bot\log\"
Write-Host "Stop: .\stop-all.bat"
Write-Host "=== Done ==="
