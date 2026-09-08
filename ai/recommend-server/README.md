# MICON 추천 서버 (`ai/recommend-server`)

음색(ECAPA + Singer-BYOL 두 축)과 음역(키 이동 적합도)으로 추천곡·추천 키를 내려주는 FastAPI 서버.
알고리즘 원본은 시험 브랜치 `recommend-algo` 의 `vocal-scope/model-server` 이고, 여기에는 서버에 필요한 부품만 옮겼다.
알고리즘 설명과 조정 파라미터는 [docs/TIMBRE_RECOMMENDER.md](docs/TIMBRE_RECOMMENDER.md).

- VocalScope 엔진(8787)과 **별도 프로세스**, 포트 **8788**. 의존성이 다르고 소유자가 달라 따로 배포한다.
- 호출 주체: 운영은 BE(Spring) → 이 서버. 앱은 BE 만 본다. 개발 중 앱은 `http://10.0.2.2:8788/` 로 직접 붙는다.
- 카탈로그 v1 은 파일 인덱스 (`recommender-assets/catalog-index/`, 374곡). `*.npy` 는 Git LFS.

## 설치·실행 (Windows)

```powershell
cd ai\recommend-server
powershell -ExecutionPolicy Bypass -File setup.ps1   # venv + 의존성 + 모델 가중치(116 MB)
powershell -ExecutionPolicy Bypass -File start.ps1   # 0.0.0.0:8788
```

`setup.ps1` 은 `models/recommender/` 에 가중치를, `workspace/recommender/config.json` 에 이 checkout 의 절대경로 설정을 만든다. 둘 다 Git 제외.
`nvidia-smi` 가 있으면 CUDA 빌드 PyTorch 로 바꿔 설치한다. 추론(30초 녹음)은 CPU 로도 수 초라 EC2 CPU 인스턴스로 충분하다. 곡 적재(Demucs 분리)는 GPU PC 에서 오프라인으로 한다.

## API

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `GET /health` | — | `{ok, service, version}` |
| `GET /recommendations/status` | — | 카탈로그 곡 수·모델·누락 설정 |
| `POST /recommendations` (202) | multipart `audio`(≤2분 보컬), `topK`, `mode=timbre\|range`, `sourceTrackId` | job `{id, status, progress, message}` |
| `GET /recommendations/{jobId}` | — | job. `status=complete` 면 `result.recommendations[]` |

응답 스키마 고정과 `compose_two_axis` 연결은 다음 단계. 고정되면 `docs/sample-response.json` 이 FE DTO 의 원본이 된다.

## 테스트

```powershell
.venv\Scripts\python.exe -m pytest tests
```

## 폴더

| 경로 | 내용 |
|---|---|
| `app.py` | FastAPI 진입점 |
| `recommendation_api.py` | 라우트·잡 처리 |
| `timbre_recommender/` | 임베딩·카탈로그·음역 점수·순위 |
| `pitch_engine.py`, `register_engine.py`, `pitch_profile.py` | 사용자 녹음 → 음역 프로파일 (CREPE + pYIN) |
| `separation.py`, `build_audio_catalog.py`, `build_range_catalog.py` | 곡 적재 (오프라인) |
| `third_party/ssl-singer-identity/` | Singer-BYOL 공식 코드 사본 (MIT, revision 고정) |
| `recommender-assets/` | 카탈로그 인덱스, 모델 출처, 선곡 정책 |
