# 추천 서버 실행 (기본 0.0.0.0:8788). 에뮬레이터는 http://10.0.2.2:8788/ 로 붙는다.
param([int]$Port = 8788, [string]$BindHost = '0.0.0.0')
Set-Location -LiteralPath $PSScriptRoot
if (-not $env:MICON_BUILD_SHA) {
    $revision = & git -C $PSScriptRoot rev-parse HEAD 2>$null
    $env:MICON_BUILD_SHA = if ($LASTEXITCODE -eq 0) { $revision.Trim() } else { 'unknown' }
}
& (Join-Path $PSScriptRoot '.venv\Scripts\python.exe') -m uvicorn app:app --host $BindHost --port $Port
