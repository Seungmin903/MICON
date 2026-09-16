# ECAPA + Singer-BYOL + VocalScope 음역 로컬 추천기

현재 버전은 보컬 음색 임베딩과 VocalScope 음역 분포를 함께 사용한다. 장르·분위기, 좋아요, 누적 사용자 profile은 아직 반영하지 않는다. 업로드 샘플의 관찰 음역을 사용하므로 출력은 생리적 한계를 진단하는 결과가 아니라, 그 샘플을 기준으로 음색과 조옮김 적합도를 비교한 결과다.

## 웹 데모

VocalScope를 실행한 뒤 `http://localhost:3000/recommend`에서 보컬 파일을 올려 실제 추천을 시험할 수 있다. 데모는 20–30초 무반주 녹음을 권장하며, 질의 음원과 임베딩은 추천 계산이 끝나면 삭제한다.

`recommend-algo` 브랜치에는 다음 재현 자산이 포함되어 있다.

- 검증된 번들 카탈로그의 ECAPA/BYOL 임베딩 인덱스
- 같은 곡의 분리 보컬 전체를 VocalScope로 분석한 5–95% 주요 음역·반음 분포 JSON
- 공식 Singer-BYOL 추론 코드의 최소 MIT 라이선스 사본과 고정 revision
- 공식 Hugging Face 저장소에서 ECAPA/BYOL 가중치 약 116 MB를 내려받아 SHA-256을 검증하는 설치기

새 checkout에서는 다음 명령 하나가 VocalScope 전체 Python 환경과 추천기를 준비한다.

```powershell
powershell -ExecutionPolicy Bypass -File model-server\setup.ps1
```

가중치는 `model-server/models/recommender`, checkout별 절대경로 설정은 `model-server/workspace/recommender/config.json`에 생성되며 둘 다 Git에서 제외된다. 모델 바이너리를 일반 Git에 넣지 않아도 revision과 SHA-256이 고정되므로 동일한 모델을 재현한다.

기존 `conditional_timbre` 실험 캐시로 데모 카탈로그를 다시 생성하려면 아래 명령을 사용할 수 있다.

```powershell
cd vocal-scope/model-server
python prepare_recommender_demo.py --research-root C:\path\to\conditional_timbre
```

로컬 MP3 폴더와 10필드 JSONL 메타데이터에서 대량 카탈로그를 만들 때는 재시작 가능한 전용 빌더를 사용한다. 저장소의 `recommender-assets/source-catalog/artist-policy.json`은 그룹·듀오·협업·트로트 제외와 경계 검토 근거를 보존한다. 원곡, stem, 곡별 중간 출력은 `workspace/catalog-build`에만 두며 Git에 포함하지 않는다.

```powershell
cd vocal-scope/model-server
$python = (Resolve-Path '..\.venv-model\Scripts\python.exe').Path

# 1. 메타데이터 매칭, SHA-256 중복 제거, 선곡 감사표 생성(GPU 미사용)
& $python build_audio_catalog.py plan `
  --audio-root C:\path\to\audio `
  --metadata C:\path\to\tracks.jsonl `
  --artist-policy recommender-assets\source-catalog\artist-policy.json

# 2. 곡별 HTDemucs → ECAPA/BYOL → CREPE+pYIN; 완료 곡은 자동 재사용
& $python build_audio_catalog.py build `
  --selection workspace\catalog-build\selection.jsonl `
  --separator htdemucs --device cuda --continue-on-error

# 3. 전곡 완료 여부와 모든 hash를 검증한 뒤 배포용 인덱스 조립·게시
& $python build_audio_catalog.py finalize `
  --selection workspace\catalog-build\selection.jsonl `
  --separator htdemucs --device cuda
& $python build_audio_catalog.py publish
```

`publish` 전에는 실행 중인 추천 AI 서버를 종료해야 한다. Windows에서 서버가 `.npy` 파일을 메모리 매핑하고 있으면 디렉터리 교체가 안전하게 거부되며 기존 카탈로그는 유지된다.

`build --limit 3`으로 먼저 소규모 검증할 수 있다. HTDemucs 가중치가 없으면 첫 `build`에서 약 84 MB를 공식 배포처로부터 한 번 내려받는다. 곡마다 원본 SHA, 분리 모델 가중치, ffmpeg·Python 패키지 버전, 코드·체크포인트 fingerprint, 출력 shape/hash를 원자적 체크포인트에 저장하므로 중단 후 같은 명령을 실행해 이어간다. 최종 인덱스에는 가사나 로컬 경로를 넣지 않고 장르·앨범·발매일·언어·커버·TJ 별칭만 보존한다.

전체 VocalScope 모델 환경 없이 추천 화면만 시험할 때는 `recommendation_app:app`을 실행하면 된다.

```powershell
python -m uvicorn recommendation_app:app --app-dir model-server --host 127.0.0.1 --port 8787
```

## 고정한 알고리즘

1. 반주가 제거된 보컬을 4초 창, 2초 hop으로 나눈다.
2. 구간별로 ECAPA 192차원과 Singer-BYOL 1000차원 임베딩을 추출한다.
3. 각 모델의 전역 프로토타입으로 Top-50을 찾고 두 후보군의 합집합을 만든다.
4. 후보군은 `30% 전역 centroid cosine + 70% 양방향 구간 coverage 중앙값`으로 재정렬한다.
5. 모델별 cosine 기반 원시 점수를 0~1 범위로 제한한 절대 유사도로 사용한다. 같은 사용자-곡 쌍의 모델 점수는 후보군 추가·삭제에 따라 바뀌지 않지만, 최종 순위는 제공되는 곡 목록에 따라 달라진다.
6. 두 모델을 고정 50:50으로 결합한다. ECAPA margin 0.060, BYOL margin 0.079 또는 다중 구간 Top-1 5표로 계산하는 확신도는 진단 정보로만 제공하며, 후보군 변화가 같은 사용자-곡 점수를 바꾸지 않도록 가중치에는 사용하지 않는다.
7. 같은 업로드에서 CREPE full + Viterbi + pYIN으로 음표를 얻고 지속시간 가중 5–95% 중심 음역과 반음 히스토그램을 만든다.
8. 각 곡을 −6~+5반음 이동해 `분포 커버리지 65% + 중앙음 근접도 20% + 폭 적합도 15%`가 가장 큰 추천 키를 고른다. 관찰 음역에는 양쪽 2반음의 보수적 여유를 둔다.
9. 커버리지 80%를 중심값, 0.05를 scale로 한 sigmoid를 계산하고, 이동한 P05/P95가 관찰 음역±2반음을 벗어난 총 반음 수를 지수적으로 감점한다. 기존 음역 점수 70%와 이 연속 적합도 30%를 합쳐 12개 키 전체에서 최고점을 고른다.
10. `음색 중심`은 음색 75%·음역 25%, `음역 중심`은 음색 30%·음역 70%다. 적합/부적합을 50점 경계로 나누지 않고 가중합을 그대로 최종 점수로 사용한다. 사용자 F0 합의율 60% 미만, 옥타브 불일치율 10% 초과 등 피치 품질이 낮으면 전체를 음색 전용으로 폴백한다. 개별 카탈로그 곡의 피치 품질이 낮으면 해당 곡의 음역 점수를 0으로 둔다.

확신도 규칙은 10명×12문항 청음 실험에서 나온 **임시 진단 휴리스틱**이다. 최종 점수 가중에는 사용하지 않으며 성능이 확정된 학습 모델로 표현하면 안 된다.

20–30초 녹음에서 얻은 범위는 사용자의 전체 편안한 음역이 아니다. 이후 누적 사용자 profile이 연결되면 여러 연습 기록의 강건한 합집합으로 교체해야 한다.

## 사용자 평가 후 조정할 값

아래 값에는 객관적으로 하나뿐인 정답이 없다. 실제 사용자가 추천곡을 얼마나 편하게 부르는지, 추천 결과를 얼마나 선호하는지를 수집한 뒤 변경한다. 평가할 때는 사용한 파라미터 세트와 알고리즘 버전을 결과에 함께 저장해 서로 다른 실험을 구분해야 한다.

### 연속 적합도

- 커버리지 sigmoid 중심값: 현재 `0.80`. `0.75 / 0.80 / 0.85` 등을 비교한다.
- 커버리지 sigmoid scale: 현재 `0.05`. 작을수록 중심 근처 변화가 가파르고 클수록 완만하다.
- 연속 적합도의 최종 비중: 현재 `RangeScore = 0.70 × BaseRange + 0.30 × SoftFit`. `SoftFit`의 30% 비중이 실제 편안함과 맞는지 검증한다.

### 음역 점수

- 기본 음역 점수 가중치: 현재 커버리지·중앙음·음역 폭을 `65% / 20% / 15%`로 결합한다.
- 사용자 관찰 음역 여유: 현재 P05/P95 양쪽에 `±2반음`을 더한다. 너무 넓으면 어려운 곡이 높은 점수를 받고, 너무 좁으면 부를 수 있는 곡이 과도하게 감점될 수 있다.
- 고음·저음 경계 초과 감점: 현재 양쪽 초과 반음 합계에 같은 지수 감점 `exp(-초과 반음 합계 / 4)`를 적용한다. 고음과 저음의 체감 난도가 다르다면 서로 다른 decay 또는 반음당 감점을 평가한다.
- 추천 키 탐색 범위: 현재 `-6~+5반음`이다. 사용자가 실제로 받아들이는 조옮김 범위를 조사한다.
- 동점 키 선택: 현재 점수, 연속 적합도, 커버리지가 같으면 원키와 가까운 키를 우선하고, 그래도 같으면 낮은 키를 선택한다. 낮은 키 우선과 원키 근접 우선 중 무엇이 더 나은지 평가한다.

### 음색 점수

- centroid·구간 유사도 비중: 현재 `30% / 70%`다.
- ECAPA·Singer-BYOL 기본 비중: 현재 고정 `50% / 50%`다.
- 모델 확신도 기반 동적 가중: 과거 제안은 한 모델만 확실할 때 `60% / 40%`였으나, 현재는 후보군 변화가 절대 점수를 바꾸는 문제를 피하려고 사용하지 않는다. 재도입한다면 `55:45 / 60:40 / 70:30` 등을 사용자 평가하고, 후보군과 독립적인 신뢰도 산정 방법도 함께 마련해야 한다.
- ECAPA 확신 margin: 현재 진단 기준 `0.060`이다.
- Singer-BYOL 확신 margin: 현재 진단 기준 `0.079`다.
- 안정 투표: 현재 동일 Top-1 곡 최소 `5표`다.
- 음색·음역 최종 비중: 음색 중심 모드는 `75:25`, 음역 중심 모드는 `30:70`이다.

### 피치 분석 신뢰도

- 음표 confidence: 현재 `0.45` 이상.
- CREPE–pYIN 합의율: 현재 `0.60` 이상.
- 옥타브 불일치율: 현재 `0.10` 이하.
- 교차검증 프레임: 현재 최소 `50`개.
- 유성음 시간: 현재 최소 `2초`.

이 임계값들은 먼저 피치 추출 성공·실패 사례로 오류율을 측정한 뒤 조정한다. 추천 만족도 실험과 피치 검출 정확도 실험은 목적이 다르므로 결과를 분리해 기록한다.

## 1. 임베딩 생성

입력은 사용자 깨끗한 녹음 또는 곡에서 분리한 `vocals.wav`이어야 한다. 혼합음을 그대로 넣으면 반주·녹음 환경이 추천을 왜곡한다.

```powershell
cd vocal-scope/model-server
python -m pip install -r requirements-recommender.txt
python -m timbre_recommender embed-vocal `
  --audio C:\data\query_vocals.wav `
  --output-prefix C:\data\query `
  --ecapa-checkpoint C:\models\ecapa\embedding_model.ckpt `
  --singer-repo C:\models\ssl-singer-identity `
  --singer-checkpoint-root C:\models\singer_identity_official
```

Singer-BYOL은 공식 `SonyCSLParis/ssl-singer-identity` 코드를 revision `24751d11c6c169adc53be906fd36560df447f974`에 고정해 사용한다. ECAPA·BYOL 체크포인트 hash와, Git 정보가 있는 경우 Singer-BYOL 코드 revision이 다르면 추출을 중단한다. 출력은 `query.ecapa.npy`, `query.singer_byol.npy`, `query.segments.json`이다.

## 2. 카탈로그 인덱스

UTF-8 CSV로 다음 필드를 준비한다. 경로는 CSV 위치 기준 상대경로 또는 절대경로다.

```csv
track_id,title,artist,ecapa_path,singer_byol_path
track-001,노래 A,가수 A,emb/track-001.ecapa.npy,emb/track-001.singer_byol.npy
track-002,노래 B,가수 B,emb/track-002.ecapa.npy,emb/track-002.singer_byol.npy
```

```powershell
python -m timbre_recommender build-index `
  --manifest C:\data\catalog.csv `
  --output-dir C:\data\timbre-index
```

인덱스는 곡당 최대 9개 구간을 균등 선택하고, 모델 차원·전처리 계약·원본 임베딩 hash·manifest hash를 `index.json`에 남긴다. 배열은 pickle을 사용하지 않는 `.npy`로 저장한다.

## 3. 추천

```powershell
python -m timbre_recommender recommend `
  --index-dir C:\data\timbre-index `
  --ecapa C:\data\query.ecapa.npy `
  --singer-byol C:\data\query.singer_byol.npy `
  --top-k 10 `
  --output C:\data\recommendations.json
```

이 CLI 명령은 임베딩 단독 회귀 검증용으로 음색 순위를 출력한다. 웹/API는 업로드 음원에서 음역을 함께 추출하며 multipart `mode=timbre|range`로 두 최종 결합 모드를 선택한다. 질의 곡이 카탈로그에 있다면 CLI의 `--exclude-track-id track-001` 또는 웹/API의 `sourceTrackId`로 제외한다. `recommendations.json`에는 최종 점수뿐 아니라 모델별 raw/absolute score, centroid/segment 점수, 확신 진단, 실제 결합 가중치가 함께 남는다.

## 서버 통합 전 보존할 계약

- ECAPA와 BYOL은 반드시 같은 4초 구간을 분석한다.
- 모델 버전·체크포인트 hash·전처리 버전이 다른 벡터를 한 인덱스에 섞지 않는다.
- 모델별 raw cosine 조합은 0~1 절대 유사도로 제한한 뒤 결합하며 후보군 백분위를 사용하지 않는다. 이 값은 후보군과 독립적인 쌍별 유사도이지 성공 확률은 아니다.
- 음원·분리 보컬·임베딩은 깃에 커밋하지 않는다.
- 음역 점수와 음색 순위 로직은 각각 `vocal-range-keyshift-continuous-v2`, `ecapa-singer-byol-absolute-fusion-v2`로 독립 버전 관리한다.
