# 오프라인 모델 설치: export-models.ps1 로 내보낸 번들(models/ 폴더)을 각 캐시 위치로 복사합니다.
#   powershell -ExecutionPolicy Bypass -File model-server\install-models.ps1 -Bundle <풀린 번들의 models 폴더>
# 캐시 위치(자동 다운로드와 동일):
#   melband-roformer-infer  → %USERPROFILE%\.cache\melband-roformer-infer\
#   huggingface/hub         → %USERPROFILE%\.cache\huggingface\hub\   (HTDemucs, kresnik/wav2vec2-large-xlsr-korean)
#   whisper                 → %USERPROFILE%\.cache\whisper\           (large-v3-turbo.pt)
# CREPE(torchcrepe/assets/full.pth)는 pip 패키지에 포함되어 있어 setup.ps1 만으로 설치됩니다.
param(
  [Parameter(Mandatory = $true)][string]$Bundle,
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Bundle)) { throw "번들 폴더를 찾을 수 없습니다: $Bundle" }
$cache = Join-Path $env:USERPROFILE '.cache'
$targets = @(
  @{ Name = 'Mel-Band RoFormer';   Source = Join-Path $Bundle 'melband-roformer-infer'; Target = Join-Path $cache 'melband-roformer-infer' },
  @{ Name = 'HuggingFace hub (HTDemucs, wav2vec2-ko)'; Source = Join-Path $Bundle 'huggingface\hub'; Target = Join-Path $cache 'huggingface\hub' },
  @{ Name = 'Whisper turbo';       Source = Join-Path $Bundle 'whisper';                Target = Join-Path $cache 'whisper' }
)
foreach ($item in $targets) {
  if (-not (Test-Path -LiteralPath $item.Source)) { Write-Warning "번들에 없음(건너뜀): $($item.Name) — $($item.Source)"; continue }
  New-Item -ItemType Directory -Force -Path $item.Target | Out-Null
  Write-Host "복사 중: $($item.Name) → $($item.Target)"
  $copyArgs = @($item.Source, $item.Target, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
  if (-not $Force) { $copyArgs += '/XO' }   # 기존 파일이 더 새로우면 덮어쓰지 않음
  & robocopy @copyArgs | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "복사 실패($LASTEXITCODE): $($item.Name)" }
}
Write-Host '모델 설치 완료. 엔진 첫 실행 시 다운로드 없이 캐시를 사용합니다.' -ForegroundColor Green
