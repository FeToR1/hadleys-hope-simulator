param(
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workspacePrefix = $workspaceRoot.TrimEnd('\') + '\'
$resultsDirectory = Join-Path $workspaceRoot 'results'
$localJson = Join-Path $resultsDirectory 'local-5000.json'

Write-Host "Stopping 5000-house settlement simulation session..." -ForegroundColor Cyan

$stoppedCount = 0
$stopErrors = [System.Collections.Generic.List[string]]::new()
$metadataSafeToRemove = $true
$requestedPids = [System.Collections.Generic.HashSet[int]]::new()

function Get-ProjectOwnedProcessCommand([int]$ProcessId) {
    try { $process = Get-Process -Id $ProcessId -ErrorAction Stop }
    catch {
        if ($_.FullyQualifiedErrorId -like '*NoProcessFoundForGivenId*') { return $null }
        throw
    }
    $command = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    return [PSCustomObject]@{ Name = $process.ProcessName; CommandLine = $command.CommandLine }
}

function Test-ProjectBackend([string]$Name, [string]$CommandLine) {
    return $Name -ieq 'java' -and $CommandLine -and
        $CommandLine.IndexOf($workspacePrefix, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $CommandLine -like '*colony.cli.MainKt*'
}

function Test-ProjectFrontend([string]$CommandLine) {
    $vitePath = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'frontend/node_modules/vite/bin/vite.js'))
    return $CommandLine -and $CommandLine.IndexOf($vitePath, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Stop-ProjectProcess([int]$ProcessId, [string]$Reason) {
    if (-not $ProcessId -or $ProcessId -eq $PID -or -not $requestedPids.Add($ProcessId)) { return }
    try {
        Stop-Process -Id $ProcessId -Force -ErrorAction Stop
        Write-Host "Sent stop to $Reason (PID $ProcessId)." -ForegroundColor Green
        $script:stoppedCount++
    } catch {
        $script:stopErrors.Add("Could not stop $Reason (PID $ProcessId): $($_.Exception.Message)")
    }
}

# 1. Stop by recorded PIDs from results/local-5000.json
if (Test-Path $localJson) {
    try {
        $raw = Get-Content $localJson -Raw -Encoding UTF8
        $meta = $raw | ConvertFrom-Json
        if ($meta.BackendPid -and $meta.BackendUrl) {
            $owned = Get-ProjectOwnedProcessCommand ([int]$meta.BackendPid)
            if ($owned) {
                if ($owned -and (Test-ProjectBackend $owned.Name $owned.CommandLine)) { Stop-ProjectProcess ([int]$meta.BackendPid) 'backend' }
                else { $metadataSafeToRemove = $false; $stopErrors.Add("Backend PID $($meta.BackendPid) is active but is not owned by this project; metadata retained.") }
            }
        }
        if ($meta.FrontendPid -and $meta.FrontendUrl) {
            $owned = Get-ProjectOwnedProcessCommand ([int]$meta.FrontendPid)
            if ($owned) {
                if ($owned -and (Test-ProjectFrontend $owned.CommandLine)) { Stop-ProjectProcess ([int]$meta.FrontendPid) 'frontend' }
                else { $metadataSafeToRemove = $false; $stopErrors.Add("Frontend PID $($meta.FrontendPid) is active but is not owned by this project; metadata retained.") }
            }
        }
    } catch { $metadataSafeToRemove = $false; $stopErrors.Add("Could not read session metadata: $($_.Exception.Message)") }
}

# 2. Free configured listening ports only for processes in this workspace.
foreach ($port in $BackendPort, $FrontendPort) {
    try { $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop }
    catch {
        $conns = @()
        if ($_.FullyQualifiedErrorId -notlike 'CmdletizationQuery_NotFound,*' -and $_.FullyQualifiedErrorId -notlike '*NoMatchingCimInstancesFound*') {
            $stopErrors.Add("Could not inspect port ${port}: $($_.Exception.Message)")
        }
    }
    if ($conns) {
        foreach ($conn in $conns) {
            $pidToKill = $conn.OwningProcess
            if ($pidToKill -and $pidToKill -ne $PID) {
                try { $owned = Get-ProjectOwnedProcessCommand ([int]$pidToKill) }
                catch { $owned = $null; $stopErrors.Add("Could not inspect PID $pidToKill on port ${port}: $($_.Exception.Message)") }
                $ownedBackend = $owned -and (Test-ProjectBackend $owned.Name $owned.CommandLine)
                $ownedFrontend = $owned -and (Test-ProjectFrontend $owned.CommandLine)
                if ($ownedBackend -or $ownedFrontend) {
                    Stop-ProjectProcess ([int]$pidToKill) "project process on port $port"
                }
            }
        }
    }
}

# 3. Clean up any stray colony-dsl-parser or vite processes from this workspace
$strayProcs = Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
    $_.CommandLine -and $_.CommandLine.IndexOf($workspacePrefix, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and (
        ($_.Name -eq 'java.exe' -and $_.CommandLine -like '*colony.cli.MainKt*') -or
        ($_.Name -eq 'node.exe' -and $_.CommandLine.IndexOf((Join-Path $workspaceRoot 'frontend/node_modules/vite/bin/vite.js'), [StringComparison]::OrdinalIgnoreCase) -ge 0))
}
foreach ($proc in $strayProcs) {
    if ($proc.ProcessId -ne $PID) {
        Stop-ProjectProcess ([int]$proc.ProcessId) $proc.Name
    }
}

Start-Sleep -Milliseconds 400

$activeOwned = @()
foreach ($proc in (Get-CimInstance Win32_Process -ErrorAction Stop)) {
    if ($proc.CommandLine -and $proc.CommandLine.IndexOf($workspacePrefix, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        (($proc.Name -eq 'java.exe' -and $proc.CommandLine -like '*colony.cli.MainKt*') -or
         ($proc.Name -eq 'node.exe' -and (Test-ProjectFrontend $proc.CommandLine)))) { $activeOwned += $proc }
}
$portListeners = @()
foreach ($port in $BackendPort, $FrontendPort) {
    try {
        $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
        foreach ($connection in $connections) {
            $owned = $false
            try {
                $process = Get-ProjectOwnedProcessCommand ([int]$connection.OwningProcess)
                $owned = $process -and ((Test-ProjectBackend $process.Name $process.CommandLine) -or (Test-ProjectFrontend $process.CommandLine))
            } catch { $stopErrors.Add("Could not identify listener PID $($connection.OwningProcess) on port ${port}: $($_.Exception.Message)") }
            $portListeners += [PSCustomObject]@{ Port = $port; ProcessId = $connection.OwningProcess; Owned = [bool]$owned }
        }
    }
    catch {
        if ($_.FullyQualifiedErrorId -notlike 'CmdletizationQuery_NotFound,*' -and $_.FullyQualifiedErrorId -notlike '*NoMatchingCimInstancesFound*') {
            $stopErrors.Add("Could not verify port ${port} after stop: $($_.Exception.Message)")
        }
    }
}

if ($metadataSafeToRemove -and $activeOwned.Count -eq 0 -and $localJson -and (Test-Path $localJson)) {
    Remove-Item $localJson -Force -ErrorAction Stop
}

if ($activeOwned.Count -gt 0) { Write-Host "Project-owned processes remain active after stop:" -ForegroundColor Red; $activeOwned | ForEach-Object { Write-Host "  $($_.Name) PID $($_.ProcessId)" } }
if (@($portListeners | Where-Object { -not $_.Owned }).Count -gt 0) { Write-Host "Unrelated listener(s) remain on configured ports and were preserved:" -ForegroundColor Yellow; $portListeners | Where-Object { -not $_.Owned } | ForEach-Object { Write-Host "  port $($_.Port), PID $($_.ProcessId)" } }
if ($stopErrors.Count -gt 0) { foreach ($message in $stopErrors) { Write-Host "WARNING: $message" -ForegroundColor Yellow } }
if ($activeOwned.Count -eq 0 -and $portListeners.Count -eq 0 -and $stopErrors.Count -eq 0) { Write-Host "Project processes stopped; configured ports $BackendPort and $FrontendPort are free." -ForegroundColor Green }
