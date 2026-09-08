$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$pythonExe = Join-Path $projectRoot '.venv-model\Scripts\python.exe'
$pidFile = Join-Path $projectRoot '.vocalscope-pids.json'
$processIds = @{}

if (-not (Test-Path -LiteralPath $pythonExe)) {
  throw 'AI 모델이 설치되지 않았습니다. model-server\setup.ps1을 먼저 실행해주세요.'
}

try {
  Invoke-RestMethod -Uri 'http://127.0.0.1:8787/health' -TimeoutSec 2 | Out-Null
} catch {
  $engine = Start-Process -FilePath $pythonExe -ArgumentList @('-m', 'uvicorn', 'app:app', '--app-dir', 'model-server', '--host', '127.0.0.1', '--port', '8787') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
  $processIds.engine = $engine.Id
}

try {
  Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:3000/' -TimeoutSec 2 | Out-Null
} catch {
  $npmExe = (Get-Command npm.cmd).Source
  $web = Start-Process -FilePath $npmExe -ArgumentList @('run', 'dev') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
  $processIds.web = $web.Id
}

$processIds | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8
for ($attempt = 0; $attempt -lt 30; $attempt++) {
  try {
    Invoke-RestMethod -Uri 'http://127.0.0.1:8787/health' -TimeoutSec 2 | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:3000/' -TimeoutSec 2 | Out-Null
    Start-Process 'http://localhost:3000/'
    Write-Host 'VocalScope가 실행되었습니다.' -ForegroundColor Green
    exit 0
  } catch {
    Start-Sleep -Seconds 1
  }
}

throw 'VocalScope 시작 시간이 초과되었습니다.'
