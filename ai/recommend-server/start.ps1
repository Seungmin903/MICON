# 추천 서버 실행 (기본 0.0.0.0:8788). 에뮬레이터는 http://10.0.2.2:8788/ 로 붙는다.
param([int]$Port = 8788, [string]$BindHost = '0.0.0.0')
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot '.venv\Scripts\python.exe') -m uvicorn app:app --host $BindHost --port $Port
