param(
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173
)

$ErrorActionPreference = 'SilentlyContinue'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resultsDirectory = Join-Path $workspaceRoot 'results'
$localJson = Join-Path $resultsDirectory 'local-5000.json'

Write-Host "Stopping 5000-house settlement simulation session..." -ForegroundColor Cyan

$stoppedCount = 0

# 1. Stop by recorded PIDs from results/local-5000.json
if (Test-Path $localJson) {
    try {
        $raw = Get-Content $localJson -Raw -Encoding UTF8
        $meta = $raw | ConvertFrom-Json
        if ($meta.BackendPid) {
            $bp = Get-Process -Id $meta.BackendPid -ErrorAction SilentlyContinue
            if ($bp) {
                Get-CimInstance Win32_Process -Filter "ParentProcessId = $($meta.BackendPid)" -ErrorAction SilentlyContinue | ForEach-Object {
                    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
                }
                Stop-Process -Id $meta.BackendPid -Force -ErrorAction SilentlyContinue
                Write-Host "Stopped Backend process (PID $($meta.BackendPid))." -ForegroundColor Green
                $stoppedCount++
            }
        }
        if ($meta.FrontendPid) {
            $fp = Get-Process -Id $meta.FrontendPid -ErrorAction SilentlyContinue
            if ($fp) {
                Get-CimInstance Win32_Process -Filter "ParentProcessId = $($meta.FrontendPid)" -ErrorAction SilentlyContinue | ForEach-Object {
                    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
                }
                Stop-Process -Id $meta.FrontendPid -Force -ErrorAction SilentlyContinue
                Write-Host "Stopped Frontend process (PID $($meta.FrontendPid))." -ForegroundColor Green
                $stoppedCount++
            }
        }
    } catch { }
    Remove-Item $localJson -Force -ErrorAction SilentlyContinue
}

# 2. Free listening ports (8080, 5173)
foreach ($port in $BackendPort, $FrontendPort) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        foreach ($conn in $conns) {
            $pidToKill = $conn.OwningProcess
            if ($pidToKill -and $pidToKill -ne $PID) {
                Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
                Write-Host "Stopped process on port $port (PID $pidToKill)." -ForegroundColor Green
                $stoppedCount++
            }
        }
    }
}

# 3. Clean up any stray colony-dsl-parser or vite processes from this workspace
$strayProcs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    ($_.Name -eq 'java.exe' -and $_.CommandLine -like '*colony.cli.MainKt*') -or
    ($_.Name -eq 'node.exe' -and $_.CommandLine -like '*vite.js*')
}
foreach ($proc in $strayProcs) {
    if ($proc.ProcessId -ne $PID) {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped background process $($proc.Name) (PID $($proc.ProcessId))." -ForegroundColor Green
        $stoppedCount++
    }
}

Start-Sleep -Milliseconds 400

if ($stoppedCount -gt 0) {
    Write-Host "Previous simulation session was completely stopped." -ForegroundColor Green
} else {
    Write-Host "No active session processes found (ports $BackendPort and $FrontendPort are free)." -ForegroundColor Yellow
}
