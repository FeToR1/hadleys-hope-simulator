param(
    [ValidateRange(1, 256)][int]$Workers = 4,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173,
    [ValidateRange(0.1, 100)][double]$Speed = 2,
    [string]$Scenario = 'examples/physics/settlement-5000.json',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resultsDirectory = Join-Path $workspaceRoot 'results'
New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
# Auto-detect valid JDK 17 if JAVA_HOME is invalid or points to a non-existent folder
$validJava = $false
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $validJava = $true
} else {
    $candidates = @(
        'C:\Users\Григорий\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20.1+1',
        "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20.1+1",
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Java\jdk-17*'
    )
    foreach ($cand in $candidates) {
        $resolved = Resolve-Path $cand -ErrorAction SilentlyContinue
        if ($resolved -and (Test-Path (Join-Path $resolved[0].Path 'bin/java.exe'))) {
            $env:JAVA_HOME = $resolved[0].Path
            $validJava = $true
            break
        }
    }
    if (!$validJava) {
        $found = Get-ChildItem -Path "$env:USERPROFILE\.gradle\jdks" -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $env:JAVA_HOME = $found.Directory.Parent.FullName
            $validJava = $true
        }
    }
}

# Avoid Cyrillic path issues with Gradle cache and daemon on Windows
if (Test-Path 'B:\gradle_user_home') {
    $env:GRADLE_USER_HOME = 'B:\gradle_user_home'
}
if (Test-Path 'B:\temp') {
    $env:TEMP = 'B:\temp'
    $env:TMP = 'B:\temp'
}

if ($BackendPort -eq $FrontendPort) { throw 'Backend and frontend need different ports.' }
# Ensure any previous session is completely stopped before starting a new one
$stopScript = Join-Path $PSScriptRoot 'stop-5000.ps1'
if (Test-Path $stopScript) {
    & $stopScript -BackendPort $BackendPort -FrontendPort $FrontendPort
} else {
    foreach ($port in $BackendPort, $FrontendPort) {
        $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($conns) {
            foreach ($conn in $conns) {
                $pidToKill = $conn.OwningProcess
                if ($pidToKill -and $pidToKill -ne $PID) {
                    Write-Host "Freeing occupied port $port (stopping stale process PID $pidToKill)..." -ForegroundColor Yellow
                    Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
                }
            }
            Start-Sleep -Milliseconds 800
        }
    }
}

Push-Location $workspaceRoot
try {
    if (!$SkipBuild) {
        $gradleArgs = @('-p', 'colony-dsl-parser', 'installDist')
        if ($env:GRADLE_USER_HOME) {
            $gradleArgs += @('-g', $env:GRADLE_USER_HOME)
        }
        & '.\colony-dsl-parser\gradlew.bat' @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    if (!$SkipBuild -or !(Test-Path 'frontend/node_modules/vite/bin/vite.js')) {
        & npm.cmd --prefix frontend ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    $classpath = Join-Path $workspaceRoot 'colony-dsl-parser/build/install/colony-dsl-parser/lib/*'
    if (!(Test-Path 'colony-dsl-parser/build/install/colony-dsl-parser/lib/colony-dsl-parser.jar')) {
        throw 'Build the backend before using -SkipBuild.'
    }
    $oldWorkers = $env:HH_REFERENCE_WORKERS
    $oldBackendUrl = $env:VITE_BACKEND_URL
    $oldCompactObserver = $env:HH_COMPACT_OBSERVER
    $frontend = $null
    try {
        $env:HH_REFERENCE_WORKERS = "$Workers"
        $env:HH_COMPACT_OBSERVER = '1'
        $env:VITE_BACKEND_URL = "http://127.0.0.1:$BackendPort"
        $javaExecutable = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
            Join-Path $env:JAVA_HOME 'bin/java.exe'
        } else { (Get-Command java.exe).Source }
        $backend = Start-Process -FilePath $javaExecutable -WindowStyle Hidden -PassThru `
            -WorkingDirectory $workspaceRoot `
            -ArgumentList @('-Xms512m', '-Xmx2g', '-cp', "`"$classpath`"", 'colony.cli.MainKt',
                'serve', $Scenario, "$BackendPort") `
            -RedirectStandardOutput (Join-Path $resultsDirectory 'backend-5000.log') `
            -RedirectStandardError (Join-Path $resultsDirectory 'backend-5000.err.log')
        try {
            $ready = $false
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                if ($backend.HasExited) { throw 'Backend exited; see results/backend-5000.err.log.' }
                try {
                    $health = Invoke-RestMethod "http://127.0.0.1:$BackendPort/health" -TimeoutSec 2
                    $ready = $health.houses -ge 300 -and $health.contexts -gt 0
                } catch { }
                if ($ready) { break }
                Start-Sleep -Milliseconds 500
            }
            if (!$ready) { throw 'Backend did not become ready within 30 seconds.' }
            $frontend = Start-Process -FilePath (Get-Command node.exe).Source -WindowStyle Hidden -PassThru `
                -WorkingDirectory (Join-Path $workspaceRoot 'frontend') `
                -ArgumentList @('node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort') `
                -RedirectStandardOutput (Join-Path $resultsDirectory 'frontend-5000.log') `
                -RedirectStandardError (Join-Path $resultsDirectory 'frontend-5000.err.log')
            $frontendReady = $false
            for ($attempt = 0; $attempt -lt 30; $attempt++) {
                if ($frontend.HasExited) { throw 'Frontend exited; see results/frontend-5000.err.log.' }
                try { $frontendReady = (Invoke-WebRequest "http://127.0.0.1:$FrontendPort" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { }
                if ($frontendReady) { break }
                Start-Sleep -Milliseconds 500
            }
            if (!$frontendReady) { throw 'Frontend did not become ready within 15 seconds.' }
            $speedValue = $Speed.ToString([System.Globalization.CultureInfo]::InvariantCulture)
            $health = Invoke-RestMethod "http://127.0.0.1:$BackendPort/control/speed?value=$speedValue" -Method Post
            # Start the existing full-day scenario even before an observer connects.
            $health = Invoke-RestMethod "http://127.0.0.1:$BackendPort/control/resume" -Method Post
            [pscustomobject]@{
                BackendPid = $backend.Id; FrontendPid = $frontend.Id
                BackendUrl = "http://127.0.0.1:$BackendPort"; FrontendUrl = "http://127.0.0.1:$FrontendPort"
                Scenario = $Scenario; Mode = 'reference'; Workers = $Workers
            } | ConvertTo-Json | Set-Content (Join-Path $resultsDirectory 'local-5000.json') -Encoding UTF8
            Write-Output "5000 houses, reference mode, $Workers behavior workers."
            Write-Output "UI: http://127.0.0.1:$FrontendPort; backend: http://127.0.0.1:$BackendPort"
            Write-Output 'Logs and process IDs: results/local-5000.json, results/*-5000*.log'
        } catch {
            if ($frontend -and !$frontend.HasExited) { $frontend.Kill() }
            # Some Java installations use a forwarding launcher; also stop its actual JVM child on failure.
            Get-CimInstance Win32_Process -Filter "ParentProcessId = $($backend.Id)" | ForEach-Object {
                Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
            }
            if (!$backend.HasExited) { $backend.Kill() }
            throw
        }
    } finally {
        $env:HH_REFERENCE_WORKERS = $oldWorkers
        $env:VITE_BACKEND_URL = $oldBackendUrl
        $env:HH_COMPACT_OBSERVER = $oldCompactObserver
    }
} finally { Pop-Location }
