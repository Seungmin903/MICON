# Cam-ON AI 서버 (물건 가져오기 인식) 기동 스크립트
#
# 노트북을 켤 때마다 이 창을 하나 띄워두면 된다. 창을 닫으면 서버가 죽고,
# 물건 가져오기 게임만 동작하지 않는다(닌자/몸으로말해요는 Docker 쪽이라 무관).
#
# 사전 조건 — 한 번만 해두면 재부팅해도 유지된다:
#   - Docker Desktop 실행 중 (컨테이너는 restart: unless-stopped라 자동 복구)
#   - Tailscale Funnel 등록됨 (--bg로 걸어둬서 tailscaled가 유지)

$venv = "C:\Users\SSAFY\Desktop\camon-ai-venv\Scripts\python.exe"
$aiDir = "C:\Users\SSAFY\Desktop\S15P11B110\ai"

# 배포 프론트 오리진. AI 서버의 CORS 허용 목록에 들어간다 — 안 맞으면 브라우저가
# 인식 요청을 전부 막아서 물건 가져오기가 통째로 죽는다.
$env:FRONTEND_BASE_URL = "https://camon.tail1ec1d2.ts.net"

# GPU(RTX 4070)용 큰 모델. VRAM이 부족하면 google/siglip2-base-patch16-224 로 낮춘다.
$env:AI_MODEL_ID = "google/siglip2-so400m-patch14-384"

Set-Location $aiDir
Write-Host "AI 서버 기동 중... (모델 로딩에 30초 정도 걸린다)" -ForegroundColor Cyan
Write-Host "확인: https://camon.tail1ec1d2.ts.net:8443/health" -ForegroundColor DarkGray
& $venv -m uvicorn app.main:app --host 0.0.0.0 --port 8100
