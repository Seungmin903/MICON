param(
  # PyTorch CUDA wheel tag. cu130 needs driver >= 580; older drivers use cu128/cu126. 'cpu' skips the GPU build.
  [string]$CudaTag = 'cu130',
  [string]$TorchVersion = '2.13.0'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot '.venv-model'
$requirements = Join-Path $PSScriptRoot 'requirements.txt'
$python = Join-Path $venvPath 'Scripts\python.exe'

Set-Location -LiteralPath $projectRoot
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
  throw 'uv가 없습니다. https://docs.astral.sh/uv/ 설치 후 다시 실행하세요. (winget install astral-sh.uv)'
}
if (-not (Test-Path -LiteralPath $venvPath)) {
  uv venv --python 3.11 .venv-model
}
uv pip install --python $python -r $requirements

$hasGpu = [bool](Get-Command nvidia-smi -ErrorAction SilentlyContinue)
if ($hasGpu -and $CudaTag -ne 'cpu') {
  $driver = (& nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>$null | Select-Object -First 1)
  Write-Host "NVIDIA 드라이버 $driver 감지 → torch==$TorchVersion ($CudaTag) 설치" -ForegroundColor Cyan
  $tags = @($CudaTag) + @('cu128', 'cu126') | Select-Object -Unique
  $installed = $false
  foreach ($tag in $tags) {
    try {
      uv pip install --python $python --reinstall "torch==$TorchVersion" --index-url "https://download.pytorch.org/whl/$tag"
      $installed = $true
      Write-Host "torch $TorchVersion+$tag 설치 완료" -ForegroundColor Green
      break
    } catch {
      Write-Warning "torch+$tag 설치 실패 — 다음 CUDA 태그로 재시도합니다. ($($_.Exception.Message))"
    }
  }
  if (-not $installed) { throw 'GPU용 torch 설치에 실패했습니다. -CudaTag cpu 로 CPU 전용 설치를 시도하세요.' }
  & $python -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '-')"
} else {
  Write-Warning 'nvidia-smi가 없거나 -CudaTag cpu → CPU 전용 torch. 분리(Mel-Band)는 곡당 수 분이 걸립니다.'
}
Write-Host 'VocalScope AI 엔진 설치가 완료되었습니다.' -ForegroundColor Green
