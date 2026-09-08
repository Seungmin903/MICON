# 현재 PC의 모델 캐시를 오프라인 번들(models/ 폴더)로 내보냅니다. install-models.ps1 과 짝.
#   powershell -ExecutionPolicy Bypass -File model-server\export-models.ps1 -Out <출력 폴더>
# 내보내는 항목(약 3.7 GB): Mel-Band RoFormer(871 MB), HTDemucs(81 MB), wav2vec2-ko(1.2 GB), Whisper turbo(1.6 GB).
param([Parameter(Mandatory = $true)][string]$Out)
$ErrorActionPreference = 'Stop'
$cache = Join-Path $env:USERPROFILE '.cache'
$items = @(
  @{ Source = Join-Path $cache 'melband-roformer-infer\melband-roformer-kim-vocals'; Target = Join-Path $Out 'melband-roformer-infer\melband-roformer-kim-vocals' },
  @{ Source = Join-Path $cache 'huggingface\hub\models--adefossez--HTDemucs';        Target = Join-Path $Out 'huggingface\hub\models--adefossez--HTDemucs' },
  @{ Source = Join-Path $cache 'huggingface\hub\models--kresnik--wav2vec2-large-xlsr-korean'; Target = Join-Path $Out 'huggingface\hub\models--kresnik--wav2vec2-large-xlsr-korean' }
)
foreach ($item in $items) {
  if (-not (Test-Path -LiteralPath $item.Source)) { Write-Warning "캐시에 없음(건너뜀): $($item.Source)"; continue }
  New-Item -ItemType Directory -Force -Path $item.Target | Out-Null
  Write-Host "내보내기: $($item.Source)"
  & robocopy $item.Source $item.Target /E /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "복사 실패($LASTEXITCODE): $($item.Source)" }
}
$whisper = Join-Path $cache 'whisper\large-v3-turbo.pt'
if (Test-Path -LiteralPath $whisper) {
  New-Item -ItemType Directory -Force -Path (Join-Path $Out 'whisper') | Out-Null
  Write-Host "내보내기: $whisper"
  Copy-Item -LiteralPath $whisper -Destination (Join-Path $Out 'whisper\large-v3-turbo.pt') -Force
} else { Write-Warning "캐시에 없음(건너뜀): $whisper" }
$size = (Get-ChildItem -LiteralPath $Out -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1GB
Write-Host ("모델 번들 내보내기 완료: {0} ({1:N2} GB)" -f $Out, $size) -ForegroundColor Green
