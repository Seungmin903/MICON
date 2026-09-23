# Run in a private terminal after setting the required environment variables, or pass -EnvFile.
param(
    [ValidateRange(1, 65535)][int]$Port = 8788,
    [string]$BindHost = '0.0.0.0',
    [string]$Python = (Join-Path $PSScriptRoot '.venv\Scripts\python.exe'),
    [string]$EnvFile,
    [string]$BuildSha
)
$ErrorActionPreference = 'Stop'

if ($EnvFile) {
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        throw "Environment file not found: $EnvFile"
    }
    if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
        throw 'The selected Python interpreter is required to read the environment file.'
    }
    $dotenvJson = & $Python -c 'import json,sys; from dotenv import dotenv_values; print(json.dumps({key:value for key,value in dotenv_values(sys.argv[1]).items() if value is not None}))' (Resolve-Path -LiteralPath $EnvFile).Path
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the environment file.' }
    if ([string]::IsNullOrWhiteSpace(($dotenvJson -join ''))) { throw 'The environment file contained no readable assignments.' }
    try { $dotenvValues = $dotenvJson | ConvertFrom-Json }
    catch { throw 'The environment file could not be parsed.' }
    foreach ($entry in $dotenvValues.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable([string]$entry.Name, [string]$entry.Value, 'Process')
    }
}

foreach ($name in @('AI_SERVICE_TOKEN', 'MICON_BE_BASE_URL', 'MICON_BE_CALLBACK_URL')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be set in the current environment or supplied by -EnvFile."
    }
}
if (-not (Test-Path -LiteralPath $Python)) { throw 'Run setup.ps1 first or pass -Python.' }
Set-Location -LiteralPath $PSScriptRoot

$preflight = & $Python (Join-Path $PSScriptRoot 'scripts\preflight_local_catalog.py')
if ($LASTEXITCODE -ne 0) { throw 'Recommendation catalog preflight failed; Uvicorn was not started.' }
Write-Output $preflight

if (-not $env:RECOMMEND_CONCURRENCY) { $env:RECOMMEND_CONCURRENCY = '1' }
if ($BuildSha) {
    $env:MICON_BUILD_SHA = $BuildSha.Trim()
} elseif (-not $env:MICON_BUILD_SHA) {
    $revision = & git -C $PSScriptRoot rev-parse HEAD 2>$null
    $env:MICON_BUILD_SHA = if ($LASTEXITCODE -eq 0) { $revision.Trim() } else { 'unknown' }
}
if (-not $env:PYTHONUTF8) { $env:PYTHONUTF8 = '1' }
# In-memory concurrency and request identity assume one uvicorn process.
& $Python -m uvicorn app:app --host $BindHost --port $Port --workers 1
if ($LASTEXITCODE -ne 0) { throw 'FastAPI stopped with an error.' }
