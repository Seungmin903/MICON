# Run from a private terminal after setting the three environment variables below.
param([int]$Port = 8788, [string]$BindHost = '0.0.0.0',
      [string]$Python = (Join-Path $PSScriptRoot '.venv\Scripts\python.exe'))
$ErrorActionPreference = 'Stop'
foreach ($name in @('AI_SERVICE_TOKEN', 'MICON_BE_BASE_URL', 'MICON_BE_CALLBACK_URL')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be set before starting the laptop service."
    }
}
if (-not (Test-Path -LiteralPath $Python)) { throw 'Run setup.ps1 first or pass -Python.' }
Set-Location -LiteralPath $PSScriptRoot
if (-not $env:RECOMMEND_CONCURRENCY) { $env:RECOMMEND_CONCURRENCY = '1' }
if (-not $env:MICON_BUILD_SHA) {
    $revision = & git -C $PSScriptRoot rev-parse HEAD 2>$null
    $env:MICON_BUILD_SHA = if ($LASTEXITCODE -eq 0) { $revision.Trim() } else { 'unknown' }
}
# In-memory concurrency and request identity assume one uvicorn process.
& $Python -m uvicorn app:app --host $BindHost --port $Port --workers 1
if ($LASTEXITCODE -ne 0) { throw 'FastAPI stopped with an error.' }
