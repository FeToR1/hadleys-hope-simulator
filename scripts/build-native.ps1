param([string]$BuildDirectory = 'native/build')

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildPath = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($BuildDirectory)) {
    $BuildDirectory
} else { Join-Path $workspaceRoot $BuildDirectory }))
$cachePath = Join-Path $buildPath 'CMakeCache.txt'
$cacheValues = @{}
if (Test-Path -LiteralPath $cachePath) {
    foreach ($line in (Get-Content -LiteralPath $cachePath)) {
        if ($line -match '^([^:#]+):[^=]+=(.*)$') { $cacheValues[$matches[1]] = $matches[2] }
    }
}
$cachedCompiler = $cacheValues['CMAKE_CXX_COMPILER']

$gcc = Get-Command g++.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$candidates = @(Get-Command cmake.exe -All -CommandType Application -ErrorAction SilentlyContinue | ForEach-Object { $_.Source })
foreach ($compiler in @($cachedCompiler, $gcc.Source)) {
    if ($compiler -and (Test-Path -LiteralPath $compiler -PathType Leaf)) {
        $candidates += Join-Path (Split-Path -Parent $compiler) 'cmake.exe'
    }
}
if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'CMake/bin/cmake.exe' }

# MSYS/Cygwin CMake interprets Windows drive paths as Unix paths. Query the
# supported generators instead of relying on the installation directory name.
$cmakeExecutable = $null
foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    $capabilitiesText = & $candidate -E capabilities
    if ($LASTEXITCODE -ne 0) { continue }
    $capabilities = ($capabilitiesText -join "`n") | ConvertFrom-Json
    if ($capabilities.generators.name -contains 'MinGW Makefiles') {
        $cmakeExecutable = $candidate
        break
    }
}
if (!$cmakeExecutable) {
    throw 'Windows CMake was not found. Add Windows CMake to PATH; MSYS/Cygwin CMake cannot build this Windows configuration.'
}

$configureArgs = @('-S', (Join-Path $workspaceRoot 'native'), '-B', $buildPath, '-DCMAKE_BUILD_TYPE=Release')
$resetCache = $false
if (Test-Path -LiteralPath $cachePath) {
    $cachedDirectory = $cacheValues['CMAKE_CACHEFILE_DIR']
    $resetCache = ($cachedDirectory -and $cachedDirectory.Replace('/', '\').TrimEnd('\') -ine $buildPath.TrimEnd('\')) -or
        ($cachedCompiler -and !(Test-Path -LiteralPath $cachedCompiler -PathType Leaf)) -or
        ($cacheValues['CMAKE_GENERATOR'] -and $capabilities.generators.name -notcontains $cacheValues['CMAKE_GENERATOR'])
    if ($resetCache) {
        if ($capabilities.version.major -lt 3 -or ($capabilities.version.major -eq 3 -and $capabilities.version.minor -lt 24)) {
            throw 'Repairing the incompatible CMake cache requires Windows CMake 3.24 or newer (--fresh).'
        }
        Write-Host 'Reconfiguring incompatible native build cache with Windows CMake.'
        $configureArgs += '--fresh'
    }
}
# Keep a valid existing toolchain. For a fresh MinGW build, select its generator
# explicitly rather than CMake's default Visual Studio generator.
if ($gcc -and !$env:CXX -and !$env:CMAKE_GENERATOR -and ($resetCache -or !(Test-Path -LiteralPath $cachePath))) {
    $ninja = Get-Command ninja.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($ninja) { $configureArgs += @('-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$($ninja.Source)") }
    else { $configureArgs += @('-G', 'MinGW Makefiles') }
    $configureArgs += "-DCMAKE_CXX_COMPILER=$($gcc.Source)"
}

$previousPath = $env:PATH
try {
    $toolDirectories = @(Split-Path -Parent $cmakeExecutable)
    if ($cachedCompiler -and (Test-Path -LiteralPath $cachedCompiler -PathType Leaf)) {
        $toolDirectories += Split-Path -Parent $cachedCompiler
    } elseif ($gcc) { $toolDirectories += Split-Path -Parent $gcc.Source }
    $env:PATH = (($toolDirectories | Select-Object -Unique) -join ';') + ';' + $previousPath
    Write-Host "Native build: $cmakeExecutable"
    & $cmakeExecutable @configureArgs
    if ($LASTEXITCODE -ne 0) { throw 'Native VM configure failed.' }
    & $cmakeExecutable --build $buildPath --config Release
    if ($LASTEXITCODE -ne 0) { throw 'Native VM build failed.' }
} finally { $env:PATH = $previousPath }
