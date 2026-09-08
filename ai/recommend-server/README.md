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
| `POST /recommendations` (202) | multipart `audio`(≤2분 보컬), `topK`(≤20), `mode=timbre\|range`, `sourceTrackId`, `genres`(반복 가능, 온보딩 선호 장르) | job `{id, status, progress, message, genres}` |
| `GET /recommendations/{jobId}` | — | job. `status=complete` 면 `result.recommendations[]` |

**응답 계약**: `schemas.py` 가 원본. JSON Schema 는 [docs/response-schema.json](docs/response-schema.json), 실제 값 예시는 [docs/sample-response.json](docs/sample-response.json)
(카탈로그 곡 하나를 사용자 입력으로 삼은 합성 샘플). FE DTO 는 이 둘로 만든다. 스키마를 바꾸면 `scripts/make_sample_response.py` 를 다시 돌린다.

`result.recommendations[]` 한 항목:

| 필드 | 뜻 |
|---|---|
| `position` | 목록 위치 1~. **`final_score` 내림차순**이라 앞일수록 적합. 화면의 "적합할수록 크게" 규칙과 같은 뜻. 클라이언트 재정렬 금지 |
| `song_id` | 앱 `songs.song_id` (`tj-번호`). `metadata.tj_id` 에서 유도 |
| `axis`, `label` | 어떤 할당으로 뽑혔나 (`two-axis-quota-v1`, 2:2:1). `ECAPA`(목소리 물성) · `SINGER_BYOL`(부르는 방식) · `CONSENSUS`(두 축 모두 상위 10위 안, 없으면 자리 비움) |
| `axis_scores`, `axis_rank`, `confidence` | 축별 0~1 백분위·순위, 해당 축 query 구간 일치율. 로그·진단용, 표시 금지, 순서에 쓰지 않음 |
| `range_fit`, `recommended_key` | 음역 적합도 상세와 추천 키(반음). 사용자 음역 프로파일이 없으면 null |
| `final_score` | 최종 점수. 클라이언트 계산 금지 |
| `scoring_version`, `composition_version` | 이벤트 로그에 함께 기록 |

`result.algorithm` 에 `composition`(quota·pattern), `language_gate`(장르→허용 언어, 제외 수), `range_filter`(음역 하한, 제외 수), `catalog_filter` 가 실린다.
게이트 규칙: `genres` 없음 → 전체 언어. `팝`→en, 그 외 장르→ko. 카탈로그 언어는 ISO 639-2(kor/eng/jpn), mul·zxx 는 항상 통과. 값은 `timbre_recommender/composition.py` 상단 상수.

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
