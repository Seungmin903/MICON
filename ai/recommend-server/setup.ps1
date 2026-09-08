# 추천 서버 1회 설치: Python 3.11 가상환경 + 의존성 + ECAPA/Singer-BYOL 가중치(약 116 MB, SHA-256 검증).
# 실행: powershell -ExecutionPolicy Bypass -File setup.ps1   (이 폴더에서)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'

if (-not (Get-Command uv -ErrorAction SilentlyContinue)) { throw 'uv 가 없습니다. https://docs.astral.sh/uv/ 설치 후 다시 실행.' }
if (-not (Test-Path -LiteralPath '.venv')) { uv venv --python 3.11 .venv; if ($LASTEXITCODE -ne 0) { throw 'venv 생성 실패' } }
uv pip install --python $python -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw '의존성 설치 실패' }
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
  uv pip install --python $python --reinstall 'torch==2.13.0' 'torchaudio==2.11.0' 'torchvision==0.28.0' --index-url 'https://download.pytorch.org/whl/cu130'
  if ($LASTEXITCODE -ne 0) { throw 'CUDA PyTorch 설치 실패' }
}
& $python setup_recommender.py
if ($LASTEXITCODE -ne 0) { throw '추천 모델 준비 실패' }
Write-Host '추천 서버 설치 완료. start.ps1 로 실행.' -ForegroundColor Green
