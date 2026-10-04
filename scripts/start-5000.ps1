param(
    [ValidateSet('reference', 'native')][string]$Runtime = 'reference',
    [ValidateRange(1, 256)][int]$Workers = 4,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173,
    [ValidateRange(0.1, 100)][double]$Speed = 10,
    [string]$Scenario = 'examples/physics/settlement-5000.json',
    [switch]$SkipBuild,
    [switch]$OpenBrowser
)

$ErrorActionPreference = 'Stop'
$workerLimit = if ($Runtime -eq 'native') { 32 } else { 256 }
if ($Workers -gt $workerLimit) { throw "$Runtime runtime supports at most $workerLimit workers." }
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$scenarioPath = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($Scenario)) { $Scenario } else { Join-Path $workspaceRoot $Scenario }))
if (!(Test-Path -LiteralPath $scenarioPath -PathType Leaf)) { throw "Scenario file not found: $scenarioPath" }
$resultsDirectory = Join-Path $workspaceRoot 'results'
New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
if ($BackendPort -eq $FrontendPort) { throw 'Backend and frontend need different ports.' }
# Stop only this workspace's old session, then refuse to attach to an unrelated listener.
$stopScript = Join-Path $PSScriptRoot 'stop-5000.ps1'
if (!(Test-Path -LiteralPath $stopScript -PathType Leaf)) { throw "Required stop script is missing: $stopScript" }
& $stopScript -BackendPort $BackendPort -FrontendPort $FrontendPort
foreach ($port in $BackendPort, $FrontendPort) {
    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) { throw "Port $port is still occupied after stopping this project's session." }
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
    if ($Runtime -eq 'native') {
        $nativeCandidates = @('native/build/Release/hh-vm.exe', 'native/build/Debug/hh-vm.exe', 'native/build/hh-vm.exe', 'native/build/hh-vm')
        if ($env:HH_VM) {
            $vmPath = if ([IO.Path]::IsPathRooted($env:HH_VM)) { $env:HH_VM } else { Join-Path $workspaceRoot $env:HH_VM }
            $nativeVm = [IO.Path]::GetFullPath($vmPath)
            if (!(Test-Path -LiteralPath $nativeVm -PathType Leaf)) { throw "HH_VM does not point to a native executable: $nativeVm" }
        } else {
            $nativeVm = $nativeCandidates | ForEach-Object { Join-Path $workspaceRoot $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        }
        if (!$env:HH_VM) {
            if (!$SkipBuild) {
                & cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
                if ($LASTEXITCODE -ne 0) { throw 'Native VM configure failed.' }
                & cmake --build native/build --config Release
                if ($LASTEXITCODE -ne 0) { throw 'Native VM build failed.' }
                $nativeVm = $nativeCandidates | ForEach-Object { Join-Path $workspaceRoot $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
            }
            if (!$nativeVm) { throw 'Native VM executable is missing; run without -SkipBuild to build it.' }
        }
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
    $oldNativeWorkers = $env:HH_NATIVE_WORKERS
    $oldNativeVm = $env:HH_VM
    $oldBackendUrl = $env:VITE_BACKEND_URL
    $oldCompactObserver = $env:HH_COMPACT_OBSERVER
    $frontend = $null
    try {
        $env:HH_REFERENCE_WORKERS = if ($Runtime -eq 'reference') { "$Workers" } else { $null }
        $env:HH_NATIVE_WORKERS = if ($Runtime -eq 'native') { "$Workers" } else { $null }
        if ($Runtime -eq 'native') { $env:HH_VM = $nativeVm } else { $env:HH_VM = $null }
        $env:HH_COMPACT_OBSERVER = '1'
        $env:VITE_BACKEND_URL = "http://127.0.0.1:$BackendPort"
        $javaExecutable = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
            Join-Path $env:JAVA_HOME 'bin/java.exe'
        } else { (Get-Command java.exe).Source }
        $backend = Start-Process -FilePath $javaExecutable -WindowStyle Hidden -PassThru `
            -WorkingDirectory $workspaceRoot `
            -ArgumentList @('-Xms512m', '-Xmx2g', '-cp', "`"$classpath`"", 'colony.cli.MainKt',
                $(if ($Runtime -eq 'native') { 'serve-native' } else { 'serve' }), "`"$scenarioPath`"", "$BackendPort") `
            -RedirectStandardOutput (Join-Path $resultsDirectory 'backend-5000.log') `
            -RedirectStandardError (Join-Path $resultsDirectory 'backend-5000.err.log')
        try {
            $ready = $false
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                if ($backend.HasExited) { throw 'Backend exited; see results/backend-5000.err.log.' }
                try {
                    $health = Invoke-RestMethod "http://127.0.0.1:$BackendPort/health" -TimeoutSec 2
                    $ready = $health.houses -gt 0 -and $health.contexts -gt 0 -and $health.runId -and $health.status -in @('waiting', 'running', 'paused')
                } catch { }
                if ($ready) { break }
                Start-Sleep -Milliseconds 500
            }
            if (!$ready) { throw 'Backend did not become ready within 30 seconds.' }
            $viteScript = Join-Path $workspaceRoot 'frontend/node_modules/vite/bin/vite.js'
            $frontend = Start-Process -FilePath (Get-Command node.exe).Source -WindowStyle Hidden -PassThru `
                -WorkingDirectory (Join-Path $workspaceRoot 'frontend') `
                -ArgumentList @("`"$viteScript`"", '--host', '127.0.0.1', '--port', "$FrontendPort", '--strictPort') `
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
            if ($OpenBrowser) { Start-Process "http://127.0.0.1:$FrontendPort" | Out-Null }
            [pscustomobject]@{
                BackendPid = $backend.Id; FrontendPid = $frontend.Id
                BackendUrl = "http://127.0.0.1:$BackendPort"; FrontendUrl = "http://127.0.0.1:$FrontendPort"
                Scenario = $scenarioPath; Mode = $Runtime; Workers = $Workers; Houses = $health.houses
            } | ConvertTo-Json | Set-Content (Join-Path $resultsDirectory 'local-5000.json') -Encoding UTF8
            Write-Output "$($health.houses) houses, $Runtime mode, $Workers behavior workers."
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
        $env:HH_NATIVE_WORKERS = $oldNativeWorkers
        $env:HH_VM = $oldNativeVm
        $env:VITE_BACKEND_URL = $oldBackendUrl
        $env:HH_COMPACT_OBSERVER = $oldCompactObserver
    }
} finally { Pop-Location }
