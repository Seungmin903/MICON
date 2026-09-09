$pidFile = Join-Path $PSScriptRoot '.vocalscope-pids.json'
if (Test-Path -LiteralPath $pidFile) {
  $saved = Get-Content -Raw -LiteralPath $pidFile | ConvertFrom-Json
  @($saved.engine, $saved.web) | Where-Object { $_ } | ForEach-Object {
    Stop-Process -Id $_ -ErrorAction SilentlyContinue
  }
  Remove-Item -LiteralPath $pidFile -Force
}
Write-Host 'VocalScope를 종료했습니다.'
