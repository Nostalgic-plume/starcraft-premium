$connections = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
foreach ($conn in $connections) {
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Write-Host "Killed PID $($conn.OwningProcess)"
}
