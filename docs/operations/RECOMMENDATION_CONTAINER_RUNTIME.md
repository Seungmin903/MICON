# 추천 AI 운영 컨테이너 실행

recommend-server는 음성 분석, 곡 적합도, 닮은 가수, 추천 API를 한 FastAPI 프로세스의 8788 포트에서 제공한다. BE 블루/그린 슬롯과 독립적인 singleton 서비스이므로 `infra/compose/recommendation.compose.yml`로 별도 실행한다.

## 연결 계약

```text
BE backend:8080 ──micon_backend──▶ micon-recommendation:8788
AI                  └────────────▶ backend:8080/internal/embedding-catalog/active
AI                  └────────────▶ backend:8080/callbacks/voice-analysis-components
```

`micon_backend`는 MySQL compose가 만든 internal external network다. AI와 BE 모두 이 네트워크에 붙고 서비스 토큰을 공유한다. Nginx의 공개 `/internal/` 차단을 우회하거나 해제하지 않는다. 호스트에는 운영 진단용 8788을 loopback으로만 게시한다.

## 최초 준비

먼저 BE 호스트 준비 스크립트를 실행해 서비스 토큰과 두 runtime env 파일을 만든다. 이미 존재하는 파일은 덮어쓰지 않는다.

```sh
sudo infra/deploy/prepare_backend_host.sh
sudo stat -c '%n %u:%g %a' /etc/micon/backend-secrets/ai_service_token \
  /etc/micon/runtime/backend.env /etc/micon/runtime/recommendation.env
```

`/etc/micon/runtime/backend.env`에는 다음 값을 유지하고 기능 플래그는 최초 검증 전까지 false로 둔다.

```text
AI_RECOMMENDATION_BASE_URL=http://micon-recommendation:8788
AI_VOICE_ANALYSIS_ENABLED=false
AI_ARTIST_SEARCH_ENABLED=false
AI_SONG_FIT_ENABLED=false
AI_RECOMMENDATION_ENABLED=false
AI_RECOMMENDATION_POLLING_ENABLED=false
```

## 빌드와 기동

Git LFS 카탈로그 파일을 받은 정확한 커밋 checkout에서 실행한다.

```sh
git lfs pull --include="ai/recommend-server/recommender-assets/catalog-index/*"
COMMIT_SHA="$(git rev-parse --short=12 HEAD)"
export MICON_RECOMMENDATION_IMAGE="micon-recommendation:${COMMIT_SHA}"
export MICON_RECOMMENDATION_BIND_PORT=8788
export MICON_RECOMMENDATION_ENV_FILE=/etc/micon/runtime/recommendation.env

sudo -E docker compose -f infra/compose/recommendation.compose.yml config --quiet
sudo -E docker compose -f infra/compose/recommendation.compose.yml build micon-recommendation
sudo -E docker compose -f infra/compose/recommendation.compose.yml up -d --no-build micon-recommendation
curl --fail --silent --show-error http://127.0.0.1:8788/health
```

이미지는 CPU용 PyTorch와 모델 가중치, RANGE worker를 포함한다. 작업 상태는 `micon_analysis_jobs`, `micon_recommendation_jobs` 볼륨에 저장한다.

## 양방향 준비 상태 확인

BE 컨테이너에서 AI DNS와 health를 확인한다.

```sh
sudo docker exec micon-backend-blue curl --fail --silent --show-error \
  http://micon-recommendation:8788/health
```

AI 컨테이너 안에서는 secret 값을 출력하지 않고 인증된 상태 API를 호출한다. `ready=true`, `engineReady=true`, `catalogLink.mappedTracks>0`이 모두 필요하다.

```sh
sudo docker exec micon-recommendation python -c \
  'import json,pathlib,urllib.request; t=pathlib.Path("/run/secrets/ai_service_token").read_text().strip(); r=urllib.request.Request("http://127.0.0.1:8788/recommendations/status",headers={"Authorization":"Bearer "+t}); d=json.load(urllib.request.urlopen(r,timeout=10)); assert d["ready"] and d["engineReady"] and d["catalogLink"]["mappedTracks"]>0; print("recommendation ready")'
```

카탈로그 연결 실패는 `catalogLink.error`에 남는다. 시작 시 BE가 늦었으면 추천 접수 또는 상태 확인이 빈 짝표를 최대 30초에 한 번 재조회한다. 주소·토큰 오류를 재시도로 감추지 않는다.

## 기능 활성화 순서

각 단계의 실제 요청·응답과 BE 저장 결과를 확인한 뒤 다음 단계로 진행한다.

1. `AI_SONG_FIT_ENABLED=true`
2. `AI_VOICE_ANALYSIS_ENABLED=true`로 TIMBRE 접수·컴포넌트 콜백 확인
3. `AI_ARTIST_SEARCH_ENABLED=true`
4. `AI_RECOMMENDATION_ENABLED=true`, 이후 `AI_RECOMMENDATION_POLLING_ENABLED=true`
5. RANGE 실제 WAV와 worker 결과 확인

실패하면 관련 플래그를 false로 되돌리고 BE만 재시작한다. AI 컨테이너를 내릴 때 볼륨을 삭제하지 않는다.

```sh
sudo -E docker compose -f infra/compose/recommendation.compose.yml down
```
