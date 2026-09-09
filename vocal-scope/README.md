# VocalScope

원곡을 보컬과 반주로 분리하고 보컬 멜로디를 MIDI·MusicXML로 채보한 뒤 음역을 분석하는 로컬 AI 웹 애플리케이션입니다.

> **다른 PC 세팅·전체 파이프라인 정리는 [`PIPELINE.md`](PIPELINE.md)** 한 문서로 충분합니다(설치, 모델 가중치 위치·오프라인 번들, 단계별 모델/알고리즘, API, 채점 로직, 검증 기준).

## 실행

1. 최초 1회 `model-server/setup.ps1`을 실행해 모델 환경을 설치합니다.
2. 이후 `VocalScope 실행.cmd`를 실행합니다.
3. 브라우저에서 원곡을 선택하고(가사가 있으면 붙여 넣고) **전체 AI 분석 시작**을 누릅니다.

첫 실행 시 Whisper `turbo`(1.5 GB)와 wav2vec2 한국어 모델(1.3 GB)을 내려받습니다.

AI 엔진은 `127.0.0.1:8787`에만 바인딩되며 업로드한 음원과 결과물은 `model-server/workspace`에 로컬 저장됩니다.

## 운영 서버로 인수 (2026-09-09)

운영은 [SSAFY 서버의 BE·MySQL + 개인 AWS S3](../docs/operations/S3_VOICE_STORAGE.md) 구조를 사용합니다. 위 로컬 실행 방식은 그대로이며 workspace가 S3와 자동 동기화되는 것은 아닙니다. 승인된 산출물을 별도 업로더 권한으로 catalog/에 업로드하고, 곡 ID↔object key 목록을 BE/AI 담당자가 검토해 DB에 별도로 적재합니다. [배치 인수 절차](docs/BATCH_HANDOFF.md)를 참고하세요.

개인 버킷 생성·운영 연결과 AI 서비스 배포는 아직 미완료입니다. BE 녹음 분석은 만료 있는 다운로드 URL 계약을 사용하지만 이 로컬 웹앱의 직접 업로드 API가 자동으로 교체되었다는 뜻은 아닙니다. 운영 분석 API 어댑터·인증·다운로드·파일 제공은 담당자가 검증해야 하며, 키·URL 서명·로컬 절대경로를 공개하지 않습니다.

## 모델 파이프라인

- Mel-Band RoFormer(Kim 보컬 모델, MIT): 보컬·반주 분리 — 공개 벤치마크 보컬 SDR ≈ 11 dB(HTDemucs 8.4 dB). 첫 실행 시 913 MB 다운로드, 4분 곡 ≈ 42 s. OOM·오류 시 HTDemucs로 자동 폴백하며 업로드 화면에서 모델을 직접 고를 수도 있습니다.
- 성구(진성/가성) 분석(`model-server/register_engine.py`): 추적된 F0의 배음 구조(H1−H2, 배음 풍부도, 스펙트럼 기울기, 알파 비율, CPP)를 10 ms 프레임마다 계산 → 곡 내 로버스트 z-점수 → 2-성분 GMM으로 두 성구가 실제로 관측되는지(Ashman D ≥ 2) 검정 → 음표별 `진성 우세 / 가성 우세 / 혼합 / 불명확` + 신뢰도. 확정 판정이 아니라 우세·신뢰도 표현이며, 한 성구로만 부른 곡은 억지로 둘로 나누지 않습니다. 결과 화면에 성구 비율·성구별 음역이 표시되고 피아노롤에서 가성 우세 음표는 보라색으로 표시됩니다.
- 보컬 활동 감지(VAD): 녹음 레벨(dBFS), 믹스 대비 보컬 에너지 비율, CREPE 주기성을 히스테리시스로 결합해 노래가 시작되기 전 구간과 분리 잔여음을 제외
- CREPE Full: GPU에서 전 구간 확률을 1회 계산하고, 유성 세그먼트만 Viterbi로 디코딩한 가창 기본주파수(F0) 추적
- pYIN 교차 검증: 세그먼트별 좁은 음역 pYIN으로 두 경로 F0 합의·옥타브 불일치율을 계산하고 옥타브 오류를 보정 (유성 판정에는 사용하지 않음)
- 기준 피치 편차 보정: 반음 반올림 전에 전역 튜닝 오프셋(센트)을 제거
- 음표 분절: 비브라토에 안전한 히스테리시스 분절 + 음절 온셋(스펙트럼 플럭스·RMS 골) 분할
- Librosa: 첫 박자, BPM 추정 → 박 단위 16분/셋잇단 격자 정렬
- 조성 추정(Krumhansl–Schmuckler) → 조표가 있는 MIDI, Music21로 MusicXML 변환 및 각 음표 아래 `C3`, `F♯4` 형식의 음표명 추가(플랫 조는 플랫으로 표기)
- OpenSheetMusicDisplay: 웹에서 마디·박자·쉼표를 포함한 전체 오선 악보 표시
- VocalScope 분석기: 최고음, 최저음, 5–95% 주요 음역, 구간별 발성시간 비율과 분석 품질 지표(유성 비율, 두 경로 F0 합의, 옥타브 불일치율) 계산

## 노래방 악보 (가사 정렬 · 멜로디 단순화 · 검수 편집기)

- 가사: 업로드 시 가사를 붙여 넣으면(권장) 그 텍스트를, 비우면 Whisper(`turbo`)가 보컬 트랙에서 받아쓴 텍스트를 사용합니다. 받아쓰기는 오탈자가 있을 수 있어 결과 화면의 **가사 수정 → 가사 다시 정렬**로 교정할 수 있습니다.
- 음절 타이밍: `kresnik/wav2vec2-large-xlsr-korean`(한글 음절 단위 CTC) 확률을 20 s 단위로 계산해 이어 붙인 뒤 `torchaudio.functional.forced_align`으로 전곡을 한 번에 강제 정렬합니다(`model-server/lyrics_engine.py`). 한글이 아닌 구간은 `[UNK]` 한 토큰으로 처리해 순서를 유지합니다.
- 노래방 기준 멜로디(`model-server/karaoke.py`): 음절 온셋으로 같은 음 반복 분절 → 음절을 가장 가까운 음표에 부착 → 16분음표보다 짧은 장식음·스쿱·짧은 멜리스마 정리 → 8분음표 격자(필요 시 16분) 정렬. 원곡 채보(`vocal.musicxml`)와 별도로 `karaoke.mid`·`karaoke.musicxml`(1행 가사, 2행 음이름)을 만듭니다.
- 검수 편집기: 결과 화면의 피아노롤에서 드래그로 음높이·위치·길이를 고치고 분할·병합·삭제·가사 입력 후 **수정 반영**을 누르면 `POST /jobs/{id}/karaoke`가 악보를 다시 생성합니다. `POST /jobs/{id}/lyrics`는 교정한 가사로 정렬부터 다시 수행합니다.
- 처리 시간(RTX 4050, 4분 곡): 분리 ~15 s + 멜로디 ~35 s + Whisper ~50 s(가사 입력 시 생략) + 정렬 ~12 s.

## 라이브 채점 (퍼펙트 스코어식)

원곡 결과 화면의 **LIVE SCORING** 카드에서 반주(키 조절 가능)를 들으며 마이크로 부르면, 브라우저 안의 AudioWorklet YIN 피치 추적(20 ms)이 재생 위치 기준으로 음표가 끝날 때마다 **즉시** 채점합니다 — DTW·보컬 분리 불필요, 헤드폰 권장. 러너웨이(다가오는 음표 + 내 목소리 궤적)·실시간 점수·마디 히트맵이 표시되고, 장비 지연은 부른 박자의 이동 중앙값으로 자동 보정됩니다. 종료 후 「정밀 리포트」 버튼이 녹음본(webm)을 아래의 업로드 채점 파이프라인에 넘깁니다. 판정 기준·점수식은 `compare_engine.py`와 동일 상수입니다.

## 부른 노래 채점 (원곡 vs 내 녹음)

원곡 분석 결과 화면 아래 **SING & COMPARE** 카드에 내가 부른 녹음(노래방·MR 위에 부른 파일 또는 무반주)을 올리면, 같은 파이프라인으로 목소리를 분리·F0 추적한 뒤 원곡 멜로디와 DTW로 정렬해 채점합니다.

- 결과: 종합 점수·등급(S–F), 음정/박자 점수, 안 부른 음, 키 차이(노래방 키 조절·옥타브 자동 보정), 부른 구간.
- 어디서 틀렸나: 원곡 음표를 판정 색(정확·높게·낮게·다른 음·안 부름)으로 그린 피치 오버레이 + 내 목소리 음높이 곡선, 마디별 점수 히트맵, 다시 들어볼 구간 목록(▶로 내 녹음 해당 지점 재생).
- 이전에 분석한 원곡은 업로드 패널의 "저장된 원곡" 선택으로 바로 불러와 채점만 할 수 있습니다(`GET /jobs`).
- API: `POST /jobs/{원곡id}/compare` (multipart `audio`, `sourceType=mix|vocal`, `allowTranspose`, `octaveTolerant`) → 새 job 폴링 → `result.comparison`. 옵션만 바꿔 재채점: `POST /jobs/{id}/comparison`.
- 알고리즘 상세는 `docs/MODEL_PIPELINE.md` [13] 참고. 다음 단계(계획): 분석 결과를 메타데이터 파일로 보관해 두고, 녹음만 올리면 저장된 원곡 중 일치 곡을 찾아 채점.

## 엔진 평가 CLI

```powershell
.venv-model\Scripts\python.exe model-server\evaluate.py --job model-server\workspace\<job-id> --expect-vocal-start-min 21
.venv-model\Scripts\python.exe model-server\evaluate.py --audio "곡.mp3" --out <출력 폴더> --dump-frames
.venv-model\Scripts\python.exe model-server\evaluate.py --folder "노래 폴더" --out <출력 폴더>
```

첫 보컬 시점, 음표 수, 두 경로 F0 합의, 옥타브 불일치율, 단계별 소요 시간을 출력하고 `result.json`·`vocal.musicxml`·(옵션) `frames.csv`를 저장합니다.

자동 채보는 100% 일치를 보장하지 않습니다. 코러스·더블링이 겹치거나 분리 트랙에 반주가 남으면 화면의 분석 품질 지표를 확인하고 전문 검수를 권장합니다.
