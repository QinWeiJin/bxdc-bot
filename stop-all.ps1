# bxdc-bot stop-all script
Write-Host "=== Stopping all bxdc-bot services ==="
$ErrorActionPreference = "SilentlyContinue"
taskkill /f /fi "IMAGENAME eq java.exe" 2>$null | Out-Null
taskkill /f /fi "IMAGENAME eq node.exe" 2>$null | Out-Null
$ErrorActionPreference = "Continue"
Write-Host "All java.exe and node.exe processes killed."
Write-Host "=== Done ==="
