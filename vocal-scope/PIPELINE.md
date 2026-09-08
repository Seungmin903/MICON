# VocalScope 파이프라인 정리 (2026-08-31)

노래 한 곡(원곡 MP3 등)을 넣으면 **보컬 분리 → 가창 F0 추적 → 음역·성구 분석 → 박자 정렬 채보(MIDI/MusicXML) → 가사 정렬 노래방 악보 → 반주 키 조절**까지 만들고, 이어서 **사용자가 부른 녹음을 같은 파이프라인으로 분석해 원곡과 비교·채점**하는 로컬 웹앱입니다. 모든 AI 연산은 사용자 PC(GPU 권장)에서 실행되며 음원은 외부로 나가지 않습니다.

이 문서는 다른 PC에서 그대로 재현할 수 있도록 **구성 → 설치 → 파이프라인 단계별 모델/알고리즘 → 산출물·API → 채점 로직 → 검증 → 한계**를 한 곳에 정리한 것입니다. 단계별 수치·실험 기록은 `docs/MODEL_PIPELINE.md`, 운영 메모는 `HANDOFF.md`를 보세요.

---

## 1. 구성

```
브라우저 (Next.js/vinext, :3000)  ──HTTP──▶  로컬 AI 엔진 (FastAPI + uvicorn, :8787)
  app/page.tsx              업로드·결과·악보·키조절            model-server/app.py        작업 큐·REST
  app/karaoke-editor.tsx    노래방 악보 검수 편집기             model-server/engine.py     파이프라인 오케스트레이션
  app/key-shift-player.tsx  반주 키 ±6 반음 플레이어             model-server/separation.py 보컬 분리
  app/performance-compare.tsx 부른 노래 채점 패널               model-server/pitch_engine.py F0·VAD·음표·박자·조성
                                                              model-server/register_engine.py 진성/가성
                                                              model-server/lyrics_engine.py Whisper·wav2vec2 정렬
                                                              model-server/karaoke.py    노래방 멜로디 단순화
                                                              model-server/pitch_shift.py 반주 키 조절
                                                              model-server/compare_engine.py 원곡 vs 가창 비교
                                                              model-server/evaluate.py   오프라인 평가 CLI
```

작업 폴더: `model-server/workspace/<job-id>/` — `original.*`, `input.wav`(44.1 kHz 스테레오), `vocals.wav`, `instrumental.wav`, `pitch_frames.npz`, `vocal.mid/.musicxml`, `karaoke.mid/.musicxml`, `instrumental_key±N.wav`, `job.json`(상태 + 전체 결과).

## 2. 다른 PC에서 설치·실행 (Windows)

> 웹 없이 **카탈로그 일괄 분석만** 다른 PC에서 돌릴 때는 `docs/BATCH_HANDOFF.md`를 따르세요(모델 다운로드 2.2 GB, Whisper 불필요).

요구: Windows 10/11, **NVIDIA GPU 6 GB 이상 권장**(RTX 4050 6 GB에서 개발·검증; CPU만이면 동작하지만 4분 곡 분리에 수 분 소요), Node.js ≥ 22.13, [uv](https://docs.astral.sh/uv/), 인터넷(첫 실행 시 모델 다운로드 ~3.7 GB; 오프라인이면 §2-1 모델 번들 사용).

```powershell
# 0) 압축 해제 후 프로젝트 폴더(vocal-scope)에서
npm install                                  # 웹 의존성
powershell -ExecutionPolicy Bypass -File model-server\setup.ps1   # .venv-model(Python 3.11) + requirements + GPU면 torch cu130
# (선택, 오프라인) 모델 번들을 캐시에 설치
powershell -ExecutionPolicy Bypass -File model-server\install-models.ps1 -Bundle <모델번들폴더>
# 1) 실행: 엔진(8787) + 웹(3000) 기동 후 브라우저 오픈
.\"VocalScope 실행.cmd"          # 또는 powershell -File run-local.ps1
# 종료
powershell -File stop-local.ps1
```

운영 규칙(하드-won):
- 파이썬은 **항상 `.venv-model\Scripts\python.exe`**. 시스템 Python엔 torch가 없습니다.
- 엔진은 `--reload`가 없어 **`model-server/*.py` 수정 후 반드시 재시작**. `stop-local.ps1`이 못 죽이면 `Get-NetTCPConnection -LocalPort 8787`로 PID를 찾아 부모(uv 트램펄린 python.exe)까지 종료.
- GPU 6 GB 공유 → `evaluate.py`와 엔진 분석을 동시에 돌리지 않기.
- 웹은 `npm run dev`(HMR). lint는 `.venv-model/.vinext/.wrangler`를 ignore(package.json에 반영).

### 2-1. 모델 가중치 (첫 실행 시 자동 다운로드 / 오프라인 번들)

| 단계 | 모델 | 크기 | 캐시 위치 (자동 다운로드) |
|---|---|---|---|
| 보컬 분리(기본) | Mel-Band RoFormer, Kim vocals ckpt (MIT) | 871 MB | `%USERPROFILE%\.cache\melband-roformer-infer\melband-roformer-kim-vocals\` (`MelBandRoformer.ckpt`, `config_vocals_mel_band_roformer.yaml`) |
| 보컬 분리(폴백) | HTDemucs (demucs 4.1) | 81 MB | `%USERPROFILE%\.cache\huggingface\hub\models--adefossez--HTDemucs\` |
| F0 추적 | CREPE full (torchcrepe) | 85 MB | pip 패키지 내장 `torchcrepe/assets/full.pth` — 별도 다운로드 없음 |
| 가사 받아쓰기 | Whisper `turbo` (large-v3-turbo) | 1.6 GB | `%USERPROFILE%\.cache\whisper\large-v3-turbo.pt` |
| 가사 강제 정렬 | `kresnik/wav2vec2-large-xlsr-korean` | 1.2 GB | `%USERPROFILE%\.cache\huggingface\hub\models--kresnik--wav2vec2-large-xlsr-korean\` |

오프라인 설치: 모델 번들(`vocalscope-models-*.zip`, 위 4개 캐시를 `models/` 아래 같은 구조로 담음)을 풀고 `model-server\install-models.ps1 -Bundle <풀린 models 폴더>`를 실행하면 캐시 위치로 복사됩니다. 현재 PC의 캐시를 번들로 내보내려면 `model-server\export-models.ps1 -Out <폴더>`.

고정 버전(2026-08-31 검증 환경): Python 3.11 · torch 2.13.0+cu130 · torchaudio 2.11.0 · torchcrepe 0.0.24 · demucs 4.1.0 · melband-roformer-infer 0.1.5 · librosa 0.11.0 · numpy 1.26.4 · scipy 1.17.1 · transformers 5.16.1 · openai-whisper 20250625 · pedalboard 0.9.24 · music21 9.7.1 · pretty_midi 0.2.11 · mido 1.3.3 · fastapi 0.116.1 · Node 22 · next 16 · vinext 1.0.0-beta.3 · opensheetmusicdisplay 2.1.2.

## 3. 원곡 분석 파이프라인 (`engine.run_pipeline`)

모든 F0 관련 곡선은 **16 kHz · hop 160 = 10 ms 프레임 격자**를 공유합니다. 진행률 메시지는 `job.json`으로 폴링됩니다.

| # | 단계 | 구현 | 핵심 내용 |
|---|---|---|---|
| 0 | 음원 정규화 | `engine._convert_to_wav` (imageio-ffmpeg) | 어떤 포맷이든 44.1 kHz 스테레오 `input.wav`. |
| 1 | 보컬 분리 | `separation.separate` | 기본 **Mel-Band RoFormer**(fp16, 8 s 청크, overlap 2, CUDA OOM 시 청크 절반→ 재시도, 실패 시 HTDemucs 폴백; 4분 곡 ≈ 42 s, VRAM 2.2 GB). `sourceType=vocal`이면 생략. 패키지가 이모지를 출력해 cp949 콘솔에서 죽으므로 stdout 리다이렉트. |
| 2 | 피치 확률 | `pitch_engine._crepe_probabilities` | CREPE full의 360-bin 시그모이드 확률을 곡 전체에 1회 계산(C2–B6 밖 bin 0). |
| 3 | 보컬 활동 감지(VAD) | `_detect_voice_activity` | 스템 dBFS(절대/상대 임계) + 보컬/믹스 에너지 비율 + CREPE 주기성의 히스테리시스(seed 3프레임 → 관대 영역 확장), 에너지 인식 갭 채움, 파열음 프리롤. 첫 가창 이전 구간이 음표가 되지 않게 함(신호등 기준 21.5–22.3 s). |
| 4 | 세그먼트 Viterbi | `_viterbi_bins`, `_local_average_midi` | 유성 구간만 자체 Viterbi(열 정규화 emission, ±12 bin 삼각 전이) → ±4 bin 확률 가중 서브-bin MIDI. |
| 5 | pYIN 교차검증 | `_pyin_segment`, `_compare_paths`, `_octave_correct` | CREPE 추정 ±3 st(12–30 st 폭)로 좁힌 pYIN. 지표 **F0 합의율**(|Δ| ≤ 0.7 st), **옥타브 불일치율**; 옥타브 불일치면 중앙값 추세로 보정. pYIN은 유/무성을 결정하지 않음. |
| 6 | 기준 피치 편차 | `_estimate_tuning` | 안정 프레임의 반음 소수부 원형 평균 → 반올림 전 제거(`tuningCents`). |
| 7 | 박자 | `_estimate_beats` (librosa) | onset 강도 → beat_track → 중앙값 간격 템포(60–190 정규화, 후보 ½/1/2×), 3/4 vs 4/4 다운비트 위상 대조. |
| 8 | 음표 분절 | `_syllable_onsets`, `_segment_notes` | 히스테리시스 경계(0.6 st, 5프레임 지속, 글라이드 중간 분할) + 음절 onset(onset_detect p99 정규화, 6 dB RMS 딥) 컷, 최소 길이 = 16분음표 0.8배, 같은 음 100 ms 이내 병합. `actualMidi`(중앙값)·`cents`·`confidence`(0.65 주기성 + 0.35 pYIN prob). |
| 8b | 성구(진성/가성) | `register_engine.analyze_register` | H1−H2·HRF·스펙트럼 기울기·알파비·CPP → 곡 내 z-점수 → 2-GMM(Ashman D ≥ 2) 이진성 판정 → 음표별 `modal/falsetto/mixed/unclear` + 신뢰도 + **발성 가벼움 0–100**. H1−H2 > 20 dB 프레임은 악기 잔여음으로 제외, 가성이 진성보다 낮은 음높이면 신뢰도 ×0.6 + 경고. 규칙 기반 1단계(청취 라벨 미검증). |
| 9 | 격자 정렬·조성·채보 | `_align_notes_to_beats`, `_estimate_key`, `engine._write_filtered_midi`, `_create_musicxml` | 박자별 16분/셋잇단 격자 선택, 첫 마디 재정렬, Krumhansl 프로파일 조성(조표 방향으로 임시표 재표기), 남성 음역(중앙값 < F4)은 8vb 표기 옵션. → `vocal.mid`, `vocal.musicxml`(음이름 가사행). |
| 10 | 가사 텍스트 | `lyrics_engine.transcribe_lyrics` | 사용자 입력 우선, 없으면 Whisper turbo(≈50 s/4분, 오탈자 있음). |
| 11 | 가사 음절 정렬 | `lyrics_engine.align_lyrics` | wav2vec2 한국어 CTC(음절 vocab 1,202자) + `torchaudio.functional.forced_align`(≈12 s). 영어·숫자는 미등록 토큰 → 근사 타이밍. GPU에 올렸다 내림. |
| 12 | 노래방 멜로디 | `karaoke.build_karaoke_notes` | 음절 onset으로 분할·배정, 장식음(짧은 무가사 음) 병합, 8분 격자 스냅 → `karaoke.mid/.musicxml`(1행 가사·2행 음이름). 편집기 저장 시 `rebuild_karaoke`, 가사 수정 시 `realign_lyrics`. mido는 latin-1로 저장하므로 한글 가사는 저장 후 UTF-8로 재열어 삽입. |
| 13 | 프레임 F0 저장 | `run_pipeline` | 10 ms 보정 MIDI·유성 마스크·confidence·dBFS → `pitch_frames.npz` (채점·향후 원곡 매칭용 메타데이터). |
| 14 | 반주 키 조절 (요청 시) | `pitch_shift.pitch_shift_file` | pedalboard Rubber Band, ±6 반음 템포 유지, 20 s 조각 + 상관도 적응 크로스페이드, **torch가 import된 프로세스에서 5–15배 느려지므로 서브프로세스 렌더**(≈3 s), `instrumental_key±N.wav` 캐시. |

### 결과 요약 지표 (`result`)
`lowest/highest/coreRange(5–95 %)/ratios(low·middle·high)/confidence`, `tempoBpm·timeSignature·key`, `vocalStart·vocalEnd·voicedRatio`, **품질**: `f0AgreementRatio`(> 0.8 양호, < 0.6 경고), `octaveMismatchRatio`(< 0.05), `tuningCents`, `register{ratios, modalRange, falsettoRange, lightnessMean, caveats}`, `lyrics{source, alignmentScore}`, `karaoke{...}`, `notes[]`, `karaokeNotes[]`, `artifacts{}`, `timings{}`.

## 4. 부른 노래 채점 파이프라인 (`compare_engine.compare_jobs`)

사용자 녹음은 **§3의 0–9·13단계를 그대로**(가사 10–12만 생략, `skip_lyrics=True`) 거쳐 목소리만 분리·추적한 뒤 원곡 job과 비교합니다.

1. **격자화**: 두 10 ms 곡선을 50 ms(셀 > 14M이면 100 ms) 격자로 중앙값 비닝(유성 ≥ 40 %). 원곡은 `vocalStart−2 s … vocalEnd+2 s`만 사용.
2. **이조(키) 탐색** (`search_transposition`): 피치클래스 이동 −6…+5 각각을 저해상도(≤ 3M 셀) 옥타브-폴딩 DTW로 정렬 → 비용 최소 후보 = 키 차이, 정렬 쌍의 원시 MIDI 차 중앙값으로 옥타브 확정(노래방 키 조절·옥타브 낮춰 부르기 대응). `allowTranspose=false`면 0.
3. **정렬** (`subsequence_dtw`, 자체 구현): 부른 노래 = 쿼리(전부 소비), 원곡 = 임의 부분열(일부만 불러도, 녹음이 원곡보다 길어도 됨). 비용 = 옥타브 폴딩 |Δ|(4 st 포화) / 유·무성 불일치 0.7 / 둘 다 무성 0.1, 비대각 스텝 +0.25. 행 단위 numpy 벡터화(prefix-sum + cummin) → 4분×4분도 1–2 s. 정렬 신뢰도 = 유성 쌍 중 1 st 이내 비율(< 45 % → `reliable=false` 경고). *librosa `dtw(subseq=True)`는 쿼리가 더 길면 경로 열이 뒤바뀌는 문제가 있어 사용하지 않음.*
4. **음표 판정** (`judge_notes`; 기준 = `karaokeNotes`, 없으면 `notes`; 목표 음높이 = 원가수의 `actualMidi`):
   - 음정: 매핑 구간의 사용자 F0 중앙값(앞 15 % 어택 제외) − 이조 → 옥타브 폴딩 → cents. `good` ≤ 45¢ · `sharp/flat` ≤ 150¢ · `wrong` > 150¢ · `missed` = 유성 비율 < 35 %.
   - 박자: (부른 시각 − 원곡 시각) 오프셋의 **±8 s 로버스트 선형 트렌드**(무반주 템포 드리프트는 흡수, 순간 밀림만 남김) + 10 ms 온셋 미세 탐색(매핑 시각에 가장 가까운 ±0.6 st 진입 전이, 50 ms 지속, 이동 ≤ 0.25 s). `good` ≤ 120 ms · `early/late` · `off` > 450 ms. 긴 음을 55 % 미만으로 끊으면 `short`.
5. **점수** (`summarise`): 음 길이(0.1–1.5 s) 가중. 음정 30¢→150¢, 박자 60→400 ms 선형 감점, 안 부른 음 0. 종합 = 음정 0.65 + 박자 0.35 → S ≥ 95 / A 88 / B 78 / C 65 / D 50 / F. 부른 구간 밖 음표는 `unsung`(점수 제외, 완창률로 표시). 부가: 마디별 히트맵(`measures`), 연속 문제 구간(`issues`, 심각도순), 경향(평균 cents/ms, 옥타브 바꾼 음, 짧게 끊은 음), 차트용 곡선(`curves`: 원곡 시간축 50 ms 원곡/사용자 MIDI + 사용자 녹음 시각 매핑 → 재생 위치 동기화).

임계값·가중은 모두 `compare_engine.py` 상단 상수. 오프라인: `python model-server/compare_engine.py --reference <job> --performance <job> [--no-transpose] [--no-octave] [--out f]`.

### 4-b. 라이브 채점 (브라우저, 서버 연산 없음)

업로드 방식(§4)과 별개로, **반주를 재생하며 마이크로 부르는 실시간 모드**(`app/live-scoring.tsx` + `public/pitch-worklet.js`). 재생 위치를 알기 때문에 DTW·분리가 필요 없다.

- 피치 추적: AudioWorklet에서 3:1 데시메이트(≈16 kHz) 후 20 ms마다 자체 YIN(CMNDF, 창 1024, 70–1050 Hz, 포물선 보간) — 순음 ±7¢, 노이즈는 clarity 게이트로 거부(Node 단위테스트 `public/pitch-worklet.js`의 `__vocalscopePitch`). **배음 딥 보정**: k·τ(2·3배) 근처에 0.04 이상 더 깊은 딥이 있으면 진짜 주기로 채택(H2 우세 저음의 옥타브 위 오검출 방지), **경계 프레임 게이트**: 창 앞/뒤 절반 RMS 중 작은 값(rmsLow)이 바닥 미만이면 무성(소리 꺼짐 꼬리 차단).
- 마이크: `getUserMedia(echoCancellation: true, noiseSuppression/autoGainControl: false)` — 스피커 재생 시 반주 유입을 브라우저 EC가 줄여주지만 **헤드폰 권장**.
- 판정: compare_engine과 동일 상수(±45¢/150¢, 유성 35 %, ±120 ms). 음표 종료 +0.25 s에 확정. 온셋 미검출(레가토)은 박자 중립(0.5). 장비 입·출력 지연은 최근 40개 온셋 편차의 중앙값(±0.4 s 한계)으로 자동 보정.
- 시계 동기: 워크릿 샘플 시계 ↔ `audio.currentTime`을 2 s마다 재동기화, 잔차는 자동 지연 보정이 흡수.
- 키 조절: 기존 `instrumental_key±N` 렌더 재사용, 목표 음높이도 +N.
- 종료 후: MediaRecorder(webm/opus) 녹음본을 `POST /jobs/{id}/compare`(sourceType=vocal, 분리 생략)로 올려 §4 정밀 리포트 생성(서버는 `.webm` 업로드 허용).
- 한계: 라이브 판정은 근사치(온셋 60 ms 분해능, 창 64 ms 지연) — 최종 점수는 정밀 리포트가 기준. 브라우저(마이크·재생) 실사용 검증 필요.

## 5. REST API (엔진 :8787)

| 메서드·경로 | 설명 |
|---|---|
| `GET /health` | 장치·모델 목록·분리 백엔드 |
| `GET /jobs?kind=reference&limit=50` | 완료된 원곡 분석 목록(키·BPM·노래방 악보 유무·`pitch_frames` 유무) — 저장된 원곡 불러오기 |
| `POST /jobs` (multipart `audio`, `sourceType=mix|vocal`, `lyrics`, `separator=melband|htdemucs`) → 202 job | 원곡 분석 시작 |
| `GET /jobs/{id}` | 상태·진행률·`result` |
| `POST /jobs/{id}/karaoke {notes, octave}` | 편집기 음표로 노래방 악보 재생성 |
| `POST /jobs/{id}/lyrics {text}` | 가사 재정렬 |
| `POST /jobs/{id}/instrumental-key {semitones}` / `GET /jobs/{id}/files/instrumental?semitones=N` | 반주 키 조절 렌더·재생 |
| `POST /jobs/{id}/compare` (multipart `audio`, `sourceType`, `separator`, `allowTranspose`, `octaveTolerant`) → 202 performance job | 부른 노래 분석 + 채점(`result.comparison`) |
| `POST /jobs/{perfId}/comparison {allowTranspose, octaveTolerant}` | 오디오 재분석 없이 재채점 |
| `GET /jobs/{id}/files/{vocals|instrumental|midi|musicxml|karaoke-midi|karaoke-musicxml|pitch-frames}` | 산출물 다운로드 |

## 6. 검증 방법과 기준

```powershell
# 기존 작업의 stem 재사용(분리 생략, ~40 s) — 신호등: 첫 보컬 ≥ 21 s 단언
.venv-model\Scripts\python.exe model-server\evaluate.py --job model-server\workspace\<job-id> --expect-vocal-start-min 21
# 음원부터 전체 / 폴더 일괄
.venv-model\Scripts\python.exe model-server\evaluate.py --audio song.mp3 --out <dir>
.venv-model\Scripts\python.exe model-server\evaluate.py --folder "C:\songs" --out <dir>
```
합격 기준(신호등): vocalStart 21.5–22.3 s·조기 음표 0, F0 합의 > 0.8, 옥타브 불일치 < 0.05, 엔진 < 90 s, MusicXML 1번 마디에 음표 존재, 조성 B major. 테스트 곡 7곡(이무진 3, 아이유 2, YB, 한로로).

채점 검증(합성): 신호등 보컬 스템 21–80 s를 +2키·1.2 s 지연·40–46 s −80¢·60–64 s 200 ms 지연·68–71 s 무음 → 이조 +2, 오프셋 −19.8 s, 템포 1.00, 각 구간 `flat −86¢ 11음 / late +156 ms 9음 / missed 4음`, 나머지 |Δt| 평균 47 ms, 종합 89(A); 무반주 경로와 MR 믹스(분리) 경로 동일. 실제 녹음 1건(엔플라잉 Flashback 234 s, 원키): 80.7(B), 정렬 일치 78 %.

## 7. 한계 · 다음 단계

- 성구 판정은 규칙 기반이며 사람 청취 라벨로 검증되지 않음(→ GTSinger 기반 지도학습 계획).
- 남/여 성부 자동 판별 불가(음역만으로는 오판) → 결과 화면 "표기 옥타브"로 수동 교정.
- Whisper 받아쓰기 오탈자 → 정확한 가사 입력 권장. 코드(화음)·곡 구조·듀엣 파트 분리 미구현.
- 채점: 후렴 반복 곡에서 일부만 부르면 다른 절에 정렬될 수 있음(가사 미사용). 랩·애드리브·화음은 감점. 실제 다수 가창으로 임계값 보정 필요.
- **다음 단계**: 분석 결과(`job.json` + `pitch_frames.npz`)를 곡 메타데이터 라이브러리로 보관하고, 녹음만 올리면 후보 곡마다 저해상도 DTW 비용(또는 크로마 지문 사전 필터)으로 원곡을 찾아 자동 채점. `GET /jobs`가 그 라이브러리의 첫 형태.
