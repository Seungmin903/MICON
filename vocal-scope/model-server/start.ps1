$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonExe = Join-Path $projectRoot '.venv-model\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $pythonExe)) {
  throw '모델 환경이 없습니다. 먼저 setup.ps1을 실행해주세요.'
}

Set-Location -LiteralPath $projectRoot
& $pythonExe -m uvicorn app:app --app-dir model-server --host 127.0.0.1 --port 8787
