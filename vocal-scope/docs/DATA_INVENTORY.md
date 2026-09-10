# VocalScope 데이터 인벤토리 (2026-09-02)

2026-09-09 운영 저장소 주석: 이 문서의 workspace·파일 경로·DB 미저장 설명은 로컬 VocalScope 분석 흐름의 기록입니다. 운영은 [SSAFY BE·MySQL + 개인 S3](../../docs/operations/S3_VOICE_STORAGE.md)를 사용하며, [배치 인수 절차](BATCH_HANDOFF.md)로 승인된 산출물 업로드 → object key 매핑 → 별도 DB 적재를 검증합니다. 전체 분석 JSON을 DB에 넣거나 로컬 workspace를 S3에 자동 동기화하도록 바꾼 것은 아닙니다.

모델 서버(`model-server/`)가 **지금 만들어 내는 데이터, 계산은 하지만 버리는 데이터, 팀 용어집에 정의만 되어 있고 아직 없는 데이터**를 한 곳에 정리한 문서입니다. 파이프라인 동작·설치는 `PIPELINE.md`, 실험 기록은 `docs/MODEL_PIPELINE.md`를 보세요. 이 문서는 "무엇을 빼낼 수 있는가"만 다룹니다.

검증 기준: 완료된 원곡 분석 작업(`workspace/48250e5f…/job.json`)과 가창 비교 작업(`workspace/12a049cd…/job.json`)의 실제 키 집합을 읽어 §2 표와 대조했습니다(누락 0). 줄 번호는 2026-09-02 기준 소스입니다.

---

## 0. 읽는 법

**계층 범례**

| 표시 | 뜻 |
|---|---|
| 🟢 | 지금 API/`job.json`으로 나오는 데이터 (§2) |
| 🟡 | 코드가 계산하지만 결과에 넣지 않아 버려지는 값. 소규모 수정으로 노출 가능 (§4) |
| ⚪ | 팀 용어집에 정의만 있고 코드에 없음 (§5) |

**UI 노출 컬럼 범례** (§2 표의 마지막 열)

| 값 | 뜻 |
|---|---|
| 표시 | 웹 화면에 렌더됨 |
| 사용 | 화면에 숫자로 안 보이지만 계산·그리기에 쓰임 (예: `beatGrid` 마디선) |
| 미표시 | TS 타입에 선언되어 수신은 되지만 렌더 코드 없음 |
| 유실 | 프론트 TS 타입에도 없어 수신 즉시 버려짐 |
| 내부 | 서버 안에서만 쓰이는 값·파일 |

**단위 불일치 주의** (프론트에서 그대로 섞어 쓰면 틀림)

| 필드 | 단위 |
|---|---|
| `ratios{low,middle,high}` | 0–100 **정수 %** (합 100) |
| `register.ratios{…}`, `breathyRatio`, `voicedRatio` | 0–1 실수 |
| `confidence` (음역 집계) | 0–100 정수 |
| `notes[].confidence`, `key.confidence`, `lyricScore`, `registerConfidence` | 0–1 실수 |
| `lightness`, `lightnessMean` | 0–100 |
| `comparison.scores.*`, `measures[].pitch/timing/score` | 0–100 |
| `comparison.notes[].pitchScore/timingScore` | 0–1 |

**저장 위치**: 결과는 **DB에 저장되지 않습니다.** `db/schema.ts`의 `analysis_jobs` 테이블에는 파일 메타와 상태 컬럼만 있고 분석 결과 컬럼이 0개입니다. 유일한 영속 저장소는 `model-server/workspace/<job-id>/job.json`(`app.py:50` `_save_job`)이며, 서버 재시작 시 `app.py:99-106`에서 다시 읽습니다. `app/api/**`, `db/index.ts`, `lib/audio-analysis.ts`의 런타임 코드는 호출처가 없고, 브라우저(`app/page.tsx:79`)가 Python 엔진(`:8787`)을 직접 호출합니다.

---

## 1. 데이터 흐름과 사용 모델

```
원곡 mp3/wav/flac/m4a/ogg/webm (≤250 MB)
  │  engine._convert_to_wav        → input.wav (44.1 kHz 2ch)
  ▼
[분리] separation.separate          → vocals.wav · instrumental.wav · result.separation
  │  Mel-Band RoFormer Kim (기본) / htdemucs 폴백
  ▼
[피치] pitch_engine.extract_vocal_melody
  │  CREPE full 360-bin → VAD → 세그먼트 Viterbi → pYIN 교차검증 → 튜닝 → 박자 → 분절 → 조성
  │  → notes[] · tempo/meter · key · beatGrid · 품질 비율 · pitch_frames.npz(10 ms 프레임)
  │  └─[성구] register_engine.analyze_register → notes[].register… · result.register
  ▼
[가사] lyrics_engine   Whisper turbo 받아쓰기(사용자 가사 없을 때) → wav2vec2-ko CTC 강제정렬 → 음절 타이밍
  ▼
[노래방] karaoke.build_karaoke_notes → karaokeNotes[] · result.karaoke · karaoke.mid/.musicxml
  ▼
[채보] engine._write_filtered_midi / _create_musicxml → vocal.mid · vocal.musicxml
  ▼  (kind=performance일 때)
[채점] compare_engine.compare_jobs   이조 탐색 → 서브시퀀스 DTW → 음표별 판정 → result.comparison
[키]   pitch_shift.pitch_shift_file  instrumental_key±N.wav (요청 시, 캐시)
```

| 단계 | 모델·라이브러리 | 변형/설정 | 위치 |
|---|---|---|---|
| 분리 | Mel-Band RoFormer (Kimberley Jensen vocals ckpt, MIT) | `melband-roformer-infer` 패키지, 청크 크기는 ckpt yaml, 44.1 kHz, OOM 시 청크 ½ 반복 후 폴백 | `separation.py:35-39, 86-139` |
| 분리 폴백 | HTDemucs | `demucs.separate -n htdemucs --two-stems vocals --segment 7 --overlap 0.25` | `separation.py:57-61` |
| F0 | torchcrepe **full** | 16 kHz / hop 160 (10 ms), batch 512, 360 bin(20 ¢) | `pitch_engine.py:31-42, 150` |
| F0 디코딩 | librosa `sequence.viterbi` | 삼각 전이행렬 폭 12 bin, 유성 세그먼트별 | `pitch_engine.py:173-187` |
| F0 교차검증 | librosa `pyin` | frame 2048 / hop 160, CREPE ±3 st 범위 | `pitch_engine.py:279-288` |
| 박자·온셋 | librosa `beat_track`, `onset_detect` | hop 512 | `pitch_engine.py:343-344, 436-449` |
| 조성 | Krumhansl–Schmuckler 프로파일 | 24 후보 상관 | `pitch_engine.py:660-694` |
| 성구 | 규칙 기반 배음 피처 (ML 없음) | H1−H2·HRF·기울기·알파·CPP, 자체 2-GMM(진단용) | `register_engine.py` |
| 받아쓰기 | openai-whisper `turbo` (large-v3-turbo) | ko, beam 5, temp 0 | `lyrics_engine.py:24, 117-129` |
| 강제정렬 | `kresnik/wav2vec2-large-xlsr-korean` + `torchaudio.functional.forced_align` | 20 ms 프레임, ~20 s 청크 | `lyrics_engine.py:23, 97-101, 230` |
| MIDI/MusicXML | pretty_midi, mido, music21 | program 53, 한글 가사 UTF-8 재삽입 | `engine.py:102-146, 188` |
| 채점 | 자체 서브시퀀스 DTW (numpy) | 옥타브 폴딩 비용 | `compare_engine.py:190-228` |
| 키 조절 | pedalboard `PitchShift` (Rubber Band) | torch 없는 서브프로세스, 20 s 조각 | `pitch_shift.py:34-39, 64-66, 153-161` |

---

## 2. 🟢 현재 추출되는 데이터 (전수)

### 2.1 작업 봉투 (`GET /jobs/{id}` 최상위)

| 필드 | 타입/단위 | 의미 | 생성 위치 | UI |
|---|---|---|---|---|
| `id` | str uuid4 | 작업 ID = `workspace/` 폴더명 | `app.py:186` | 사용 |
| `kind` | `reference` \| `performance` | 원곡 분석 / 가창 비교 | `app.py:187, 304` | 사용 |
| `filename` | str | 업로드 원본 파일명 | `app.py:188` | 표시 |
| `createdAt` | float epoch s | 생성 시각 | `app.py:189` | 표시(목록) |
| `sourceType` | `mix` \| `vocal` | vocal이면 분리 생략 | `app.py:190` | 내부 |
| `separator` | `melband` \| `htdemucs` | 요청 백엔드(정규화) | `app.py:191` | 내부 |
| `hasLyrics` | bool | 사용자 가사 입력 여부 | `app.py:192` | 내부 |
| `status` | `queued`/`processing`/`complete`/`failed` | 재시작 시 processing→failed | `app.py:75-106` | 표시 |
| `progress` | int 0–100 | performance는 ×0.9 스케일 | `app.py:78` | 표시 |
| `message` | str(ko) | 진행 단계 문구 | `app.py:194` | 표시 |
| `result` | dict \| null | §2.2 이하 | `app.py:94` | — |
| `error` | str \| null | 실패 사유 | `app.py:96` | 표시 |
| `referenceJobId` | str | (performance) 원곡 작업 | `app.py:305` | 사용 |
| `referenceFilename` | str | (performance) 원곡 파일명 | `app.py:306` | 표시 |
| `compareOptions` | `{allowTranspose, octaveTolerant}` | (performance) 채점 옵션 | `app.py:311` | 사용 |

### 2.2 음역 집계 (`engine._analyze_range`, `engine.py:42-72`)

| 필드 | 타입/단위 | 의미 | 위치 | UI |
|---|---|---|---|---|
| `lowest` / `highest` | str 음이름 (`"D3"`) | 최저·최고 실음 | `engine.py:62-63` | 표시 |
| `lowestMidi` / `highestMidi` | int MIDI | 위와 같은 값의 숫자형 | `:65-66` | 사용(RangeMap) |
| `coreRange` | str `"A3–E5"` | 발성 시간 가중 5–95 백분위 핵심 음역 | `:64` | 표시 |
| `ratios` | `{low, middle, high}` 정수 % | 음역 span 3등분 구간별 발성 시간 비율 | `:53-60` | 표시 |
| `confidence` | int 0–100 | 길이 가중 평균 F0 신뢰도 | `:68` | 표시 |
| `noteCount` | int | 음표 개수 | `:70` | 표시 |
| `voicedDuration` | float s | 음표 길이 합 | `:71` | 미표시 |
| `notes` | list | §2.5 | `:69` | — |

### 2.3 피치·박자·조성 (`pitch_engine.extract_vocal_melody`, `pitch_engine.py:825-849`)

| 필드 | 타입/단위 | 의미 | 위치 | UI |
|---|---|---|---|---|
| `tempoBpm` | int | 60–190 범위로 접은 BPM | `:827` | 표시 |
| `tempoRawBpm` | float \| null | 접기 전 원시 BPM | `:828` | 미표시 |
| `tempoCandidates` | int[] | ½·1·2× 후보(30–300) | `:829` | 표시 |
| `timeSignature` | `"4/4"` \| `"3/4"` | 박자표 | `:830` | 표시 |
| `meterConfidence` | float 0–1 | 박 강도 대비 | `:831` | 표시 |
| `timeSignatureCandidates` | `[{timeSignature, confidence}]` | 3/4·4/4 정규화 신뢰도 | `:832` | 미표시 |
| `vocalStart` | float s | 첫 유성 프레임 | `:833` | 표시 |
| `vocalEnd` | float s | 마지막 유성 프레임 | `:834` | 미표시(목록엔 있음) |
| `voicedRatio` | float 0–1 | 전체 프레임 중 유성 비율 | `:835` | 표시 |
| `voicedSeconds` | float s | 유성 프레임 총 시간 | `:836` | 표시 |
| `f0AgreementRatio` | float 0–1 | CREPE·pYIN 합의율(\|Δ\| ≤ 0.7 st) | `:837` | 표시 |
| `octaveMismatchRatio` | float 0–1 | \|Δ−12\| ≤ 0.7 st 비율 | `:838` | 표시 |
| `comparedFrames` | int | 합의율 계산에 쓰인 프레임 수 | `:839` | 유실 |
| `tuningCents` | int ¢ | 전역 기준 피치 편차(A440 대비) | `:840` | 표시 |
| `key.tonic` | int 0–11 | 으뜸음 피치클래스 | `:689-694` | 사용(키 시프트) |
| `key.mode` | `major` \| `minor` | 조 | | 사용 |
| `key.sharps` | int −5…6 | 조표 | | 미표시 |
| `key.name` | str `"B major"` | 표시용 이름 | | 표시 |
| `key.confidence` | float 0–1 | 1위·2위 상관 마진 기반 | | 미표시 |
| `beatGrid.beatTimes` | float[] s | 비트 시각 | `:842-845` | 사용(마디선) |
| `beatGrid.offsetBeats` | float | 다운비트 위상 보정 | | 사용 |
| `pitchMethod` | str | 알고리즘 설명 | `:846` | 미표시(`page.tsx:649`에 하드코딩) |
| `timings` | `{load, crepe, vad, beats, viterbi, pyin, notes, register, align}` s | 단계별 소요 | `:848` | 유실 |
| `register` | dict | §2.4 | `:847` | — |

### 2.4 성구 요약 `register{}` (`register_engine.py:428-452`, 후처리 `engine.py:417-420`)

| 필드 | 타입/단위 | 의미 | UI |
|---|---|---|---|
| `method` | str | 알고리즘 설명 | 미표시(`page.tsx:609` 하드코딩) |
| `bimodal` | bool | 가성 초 ≥ max(1 s, 전체 1 %) | 표시 |
| `twoRegisters` | bool | `bimodal`과 **동일 값** | 미표시 |
| `ashmanD` | float \| null | 2-GMM 분리도(진단용, 판정 미사용) | 미표시 |
| `componentWeights` | [float, float] \| null | GMM 가중치 | 유실 |
| `singleRegisterLabel` | `modal` \| `unclear` \| null | 단일 성구 때 라벨 | 표시 |
| `seconds` | `{modal, falsetto, mixed, unclear}` s | 라벨별 발성 시간 | 표시 |
| `ratios` | 같은 키, 0–1 | 라벨별 비율 | 표시 |
| `breathyRatio` | float 0–1 | breathy 음표 시간 비율 | 표시 |
| `lightnessMean` | float 0–100 \| null | 길이 가중 평균 발성 가벼움 | 표시 |
| `lightnessHistogram` | float[10] s | 10점 구간별 발성 시간 | 표시 |
| `medianFeatures` | `{h1h2, hrf, slope, alpha, cpp}` dB·dB/oct | 곡 전체 중앙값 | 미표시 |
| `pitchTrendSlopes` | 같은 키, dB/semitone \| null | 음높이–피처 추세 기울기 | 유실 |
| `pitchMedianMidi` | float | 곡 중앙 음높이 | 유실 |
| `falsettoPitchFloorMidi` | float | 가성 허용 하한(60 백분위) | 미표시 |
| `modalRangeMidi` / `falsettoRangeMidi` | [min, max] \| null | 성구별 MIDI 음역(음표 2개↑) | 사용(RangeMap) |
| `modalRange` / `falsettoRange` | str \| null | 위의 음이름 표기 (`engine.py:418-420`) | 표시 |
| `usableFrames` / `atypicalFrames` | int | 유효 프레임 / 타당성 게이트 제외 프레임 | 미표시 |
| `medianMidi` | `{modal, falsetto}` float \| null | 라벨별 중앙 음높이 | 유실 |
| `caveats` | str[] (ko) | 경고 문구(최대 3종) | 표시 |
| `confidenceScale` | float 0.42–1.0 | 신뢰도 하향 배율 | 미표시 |
| `labels` | dict | `{modal:"진성 우세", falsetto:"가성 우세", mixed:"혼합", unclear:"불명확"}` | 유실 |
| `error` | str | 성구 분석 실패 시만 (`pitch_engine.py:799`) | 표시 |

### 2.5 원곡 채보 음표 `notes[]` (음표당 22키)

| 필드 | 타입/단위 | 의미 | 위치 | UI |
|---|---|---|---|---|
| `sourceStart` / `sourceEnd` | float s | 실제 오디오 시각 | `pitch_engine.py:563-564, 811-812` | 사용 |
| `start` / `end` | float s | 위와 **동일한 별칭** | `:813-814` | 사용 |
| `duration` | float s | sourceEnd − sourceStart | `:815` | 사용(히스토그램) |
| `midi` | int 36–95 | 반올림 MIDI | `:566` | 표시 |
| `actualMidi` | float | 튜닝 보정 F0 중앙값(어택 40 ms 제외) | `:565, 816` | 유실(`RangeAnalysis` 타입 없음) |
| `cents` | int ¢ | (actualMidi − midi) × 100 | `:568, 817` | 유실 |
| `confidence` | float 0–1 | 0.65·주기성 + 0.35·pYIN 확률 | `:778, 818` | 사용(가중) |
| `name` | str `"F♯4"` | 음이름 | `engine.py:375` | 표시 |
| `scoreStartBeat` / `scoreEndBeat` | float beat | 양자화된 박 위치 | `pitch_engine.py:624-625` | 사용 |
| `scoreStart` / `scoreEnd` | float s | 박 × 60/BPM | `:645-646` | 사용 |
| `register` | `modal`/`falsetto`/`mixed`/`unclear` | 성구 라벨 | `register_engine.py:369` | 표시(편집기 외곽선) |
| `registerScore` | float \| null | 음높이 조건부 복합 z 점수 | `:370` | 미표시 |
| `registerConfidence` | float 0–1 | caveat 배율 반영 신뢰도 | `:371` | 표시(선택 음표) |
| `h1h2Db` | float dB \| null | 음표 중앙 H1−H2 | `:372` | 표시(선택 음표) |
| `hrfDb` | float dB \| null | 배음 풍부도(2배음↑ 합 − H1) | `:373` | 미표시 |
| `cppDb` | float dB \| null | 켑스트럼 피크 돋보임 | `:374` | 미표시 |
| `breathy` | bool | 숨소리 플래그(라벨과 독립) | `:375` | 표시 |
| `lightness` | int 0–100 \| null | 발성 가벼움(0 벨팅, 100 열린 가성) | `:376, 456-469` | 표시(색) |

### 2.6 노래방 음표 `karaokeNotes[]` (음표당 27–28키)

2.5의 `sourceStart, sourceEnd, midi, actualMidi, confidence`와 성구 주석 8종(`karaoke.py:31`)이 그대로 실리고, `finalize_notes`(`karaoke.py:249-273`)가 `name, cents, scoreStart/End, scoreStartBeat/EndBeat, duration`을 채웁니다. `start`/`end` 별칭은 **없습니다**. 추가 필드:

| 필드 | 타입 | 의미 | 위치 | UI |
|---|---|---|---|---|
| `lyric` | str \| null | 음절 1자(또는 라틴·숫자 런) | `karaoke.py:128` | 표시 |
| `lyricScore` | float 0–1 \| null | CTC 정렬 평균 확률 | `:129, 272` | 미표시 |
| `lyricStart` / `lyricEnd` | float s | CTC 토큰 시간 구간 | `:131-132` | 미표시 |
| `explicitExtend` | bool | 가사에 `~`/`_`/`—` 연장 표기 | `:133` | 미표시 |
| `lyricStatus` | `aligned` \| `low-confidence`(<0.2) \| `unknown-language` | | `:134` | 미표시 |
| `lyricExtend` | bool | 멜리스마 연장 음표 | `:110, 123` | 미표시 |
| `quantizationAdjusted` | bool | 양자화 충돌로 시작 이동(있을 때만) | `:215` | 미표시 |

### 2.7 보조 딕셔너리

**`lyrics{}`** (`engine.py:385, 401-410`)

| 필드 | 타입 | 의미 | UI |
|---|---|---|---|
| `source` | `user` \| `whisper` \| `none` \| `skipped` | 가사 출처(performance는 skipped) | 표시 |
| `text` | str | 사용된 가사 전문 | 표시 |
| `syllables` | int | 정렬된 음절 수 | 표시 |
| `alignmentScore` | float 0–1 \| null | CTC 평균 확률 | 표시 |
| `unknownTokens` | int | vocab 밖 토큰 수 | 표시 |
| `explicitExtensions` | int | 연장 표기 수 | 미표시 |
| `error` | str ≤400 \| null | 가사 단계 실패(작업은 계속) | 표시 |

**`karaoke{}`** (`karaoke.py:295-303`, `engine.py:256, 415`, `app.py:235-238`)

| 필드 | 타입 | 의미 | UI |
|---|---|---|---|
| `sourceNotes` | int | 입력 음표 수 | 미표시 |
| `karaokeNotes` | int | 단순화 후 음표 수 | 미표시 |
| `noteCount` | int | `karaokeNotes`와 **중복** | 미표시 |
| `notesWithLyric` | int | 가사 붙은 음표 | 표시 |
| `melismaNotes` | int | `lyricExtend` 음표 | 미표시 |
| `lowConfidenceLyrics` | int | `lyricStatus ≠ aligned` | 미표시 |
| `quantizationAdjustments` | int | 시작 이동된 음표 | 미표시 |
| `unassignedSyllables` | int | 어느 음표에도 못 붙은 음절 | 미표시 |
| `lyricsEmbedded` | bool | MIDI에 가사 삽입 성공 | 미표시 |
| `octaveShift` | int 0\|1 | 표기 옥타브 | 미표시(`scoreOctave.shift`로 표시) |
| `edited` | bool | 편집 저장 후에만 | 미표시 |

**`separation{}`** (`separation.py:158, 177-189`; 분리 생략 시 `{"backend":"none","model":null,"seconds":0.0}`)

| 필드 | 타입 | 의미 | UI |
|---|---|---|---|
| `backend` | `melband` \| `htdemucs` \| `none` | 실제 사용 | 표시 |
| `model` | str | `mel-band-roformer-kim` / `htdemucs` | 미표시 |
| `requested` | str | 요청값 | 미표시 |
| `seconds` | float s | 분리 소요 | 표시 |
| `chunkSeconds` | float s | (melband) 최종 청크 길이 | 미표시 |
| `oomRetries` | int | (melband) OOM 재시도 수 | 미표시 |
| `device` | str | 분리 장치 | 미표시 |
| `fallbackReason` | str | 폴백 시 예외 요약 | 표시 |

**기타 최상위** (`engine.py:421-438`, `app.py`)

| 필드 | 타입 | 의미 | UI |
|---|---|---|---|
| `scoreOctave` | `{mode: auto\|up\|actual, shift: 0\|1}` | 8vb 표기 여부(중앙값 < F4면 1, `engine.py:161-175`) | 표시 |
| `device` | `cuda` \| `cpu` | 분석 장치 | 표시 |
| `model` | str | `"<분리모델> + crepe-full + pyin + wav2vec2-ko"` | 표시 |
| `karaokeVersion` | int | 편집·재정렬마다 +1 | 표시 |
| `artifacts` | `{vocals, instrumental?, midi, musicxml, karaoke-midi, karaoke-musicxml, pitch-frames}` | 파일명 → `GET /files/{kind}` | 표시(링크) |
| `comparison` | dict | performance만, §2.9 | — |

### 2.8 프레임 파일 `pitch_frames.npz` (`engine.py:368-372`)

10 ms 격자, 곡 전체 길이(4분 곡 ≈ 24,000–33,000 프레임).

| 배열 | dtype | 의미 | 읽는 곳 |
|---|---|---|---|
| `midi` | float32 | 튜닝 보정 F0(MIDI 실수), 무성 = NaN | `compare_engine.load_frames` |
| `voiced` | bool | VAD 결과 | 같음 |
| `confidence` | float32 0–1 | 프레임 신뢰도 | **아무도 안 읽음** |
| `dbfs` | float32 dBFS | 프레임 음압 | **아무도 안 읽음** |
| `frame_seconds` | float32 스칼라 | 0.01 | `load_frames` |

`GET /jobs/{id}/files/pitch-frames`로 노출되지만 프론트는 다운로드하지 않습니다(서버 내부 채점 전용).

### 2.9 채점 결과 `comparison{}` (`compare_engine.compare_jobs`, `compare_engine.py:694-737`)

**최상위**

| 필드 | 타입/단위 | 의미 | UI |
|---|---|---|---|
| `referenceJobId` / `performanceJobId` | str | | 사용 |
| `options` | `{allowTranspose, octaveTolerant}` | | 미표시 |
| `transposeSemitones` | int | 사용자 F0에서 뺄 반음(옥타브 포함) | 표시 |
| `transposition.semitones` | int | 최종 이조 | 표시 |
| `transposition.pitchClassShift` | int −6…5 | 피치클래스 이동 | 미표시 |
| `transposition.medianGap` | float st | 중앙 음높이 차 | 미표시 |
| `transposition.confidence` | float 0–1 | 후보 비용 마진 | 미표시 |
| `transposition.searchHop` | float s | 탐색 격자 | 유실 |
| `transposition.candidateCosts` | dict `"-6"…"5"` → float | 12 후보 DTW 비용 | 유실 |
| `referenceKey` / `performanceKey` | str \| null | 두 조성 이름 | 미표시(프론트 재계산) |
| `startOffsetSeconds` | float s | 녹음 시작 오프셋 | 미표시 |
| `tempoRatio` | float | 부른 구간 길이 / 원곡 구간 길이 | 표시 |
| `coverage.refStart/refEnd/perfStart` | float s | 커버 구간 | 표시 |
| `coverage.perfEnd` | float s | | 미표시 |
| `coverage.notesTotal` | int | | 미표시 |
| `coverage.notesCovered/notesSung/notesMissed` | int | | 표시 |
| `coverage.coveredRatio` | float 0–1 | | 표시 |
| `coverage.sungRatio` | float 0–1 | | 미표시 |
| `scores.pitch/timing/overall` | float 0–100 | 가중 평균(0.65/0.35) | 표시 |
| `scores.grade` | `S/A/B/C/D/F` | 95/88/78/65/50 경계 | 표시 |
| `scores.stabilityCents` | float ¢ \| null | 부른 음표 내 F0 표준편차 평균 | 표시 |
| `pitchStats.good/sharp/flat/wrong` | int | 음표 수 | 표시 |
| `pitchStats.missed` | int | | 미표시 |
| `pitchStats.meanCents` | int ¢ | | 표시 |
| `pitchStats.medianCents` | int ¢ | | 미표시 |
| `pitchStats.octaveShiftedNotes/shortNotes` | int | | 표시 |
| `timingStats.good/early/late/off` | int | | 표시 |
| `timingStats.missed` | int | | 미표시 |
| `timingStats.meanDeviationMs` | int ms | 부호 있음(+ 늦음) | 표시 |
| `timingStats.absDeviationMs` | int ms | | 미표시 |
| `curves.hop/start` | float s | 곡선 격자 | 사용 |
| `curves.ref/user` | float[] MIDI \| null | 원곡·사용자 F0 곡선 | 표시(오버레이) |
| `curves.perfTime` | float[] s \| null | 재생 위치 동기화용 | 사용 |
| `alignment.method` | str | DTW 설명 | 표시 |
| `alignment.matchedRatio` | float 0–1 | 유성 프레임 매칭 비율 | 표시 |
| `alignment.reliable` | bool | voicedPairs ≥ 40 && matchedRatio ≥ 0.45 | 표시 |
| `alignment.hop/pathCost/voicedPairs/referenceFrames/noteSource/referenceNoteCount` | | 정렬 진단 | 미표시 |
| `thresholds` | `{pitchGoodCents 45, pitchWrongCents 150, timingGoodMs 120, timingOffMs 450, weights{pitch .65, timing .35}}` | UI가 하드코딩 안 하도록 동봉 | 미표시(라이브 채점은 TS에 하드코딩) |

**`comparison.notes[]`** (`compare_engine.py:381-438`; 원곡 노래방 음표 기준, 음표당 최대 27키)

| 필드 | 타입/단위 | 의미 | UI |
|---|---|---|---|
| `index` | int | 원곡 음표 순번 | 사용 |
| `refStart` / `refEnd` | float s | 원곡 시각 | 사용 |
| `midi` | int | 기보 MIDI | 표시 |
| `targetMidi` | float | 원가수 actualMidi 기준 목표(±0.5 st 안이면) | 사용 |
| `name`, `lyric`, `beat` | | 음이름·가사·박 위치 | 표시/미표시(beat) |
| `covered` | bool | 부른 구간 안 | 사용 |
| `pitchStatus` | `good`/`sharp`/`flat`/`wrong`/`missed`/`unsung` | ±45 ¢ good, >150 ¢ wrong | 표시 |
| `timingStatus` | `good`/`early`/`late`/`off`/`unsung` | ±120 ms good, >450 ms off | 표시 |
| `perfStart` / `perfEnd` | float s | (covered) 사용자 녹음 시각 | 사용/미표시 |
| `sungMidi` | float | (부름) 부른 절대 MIDI | 미표시 |
| `sungInKey` | float | 이조 보정 후 MIDI | 사용 |
| `sungName` | str | 부른 음이름 | 표시(hover) |
| `centsFromWritten` | int ¢ | 기보음 대비 | 미표시 |
| `octaveShift` | int | 옥타브 접기 횟수 | 표시(hover) |
| `stabilityCents` | float ¢ \| null | 음 내 F0 표준편차(5프레임↑) | 미표시 |
| `heldRatio` | float 0–1 | 음 길이 중 유성 비율 | 미표시 |
| `onsetRefined` | bool | 온셋 미세탐색 성공 | 미표시 |
| `onsetDeviation` | float s | + 늦음 | 표시(hover) |
| `short` | bool | 0.4 s↑ 음을 55 % 미만만 유지 | 표시(hover) |
| `centsError` | int ¢ \| null | 판정에 쓴 오차(옥타브 접기 후) | 표시(hover) |
| `pitchScore` / `timingScore` | float 0–1 | 선형 점수(30→150 ¢, 60→400 ms) | 미표시 |
| `weight` | float 0.1–1.5 | 음 길이 가중 | 미표시 |

**`comparison.measures[]`** (`compare_engine.py:479-501`): `measure`(1부터), `start`/`end`(s), `notes`, `pitch`/`timing`/`score`(0–100 정수), `missed/sharp/flat/wrong/early/late`(수), `lyric`(≤24자). UI: 히트맵에 `score`·`missed`·`lyric`만 표시.

**`comparison.issues[]`** (`compare_engine.py:555-566`, 최대 12개, 심각도 내림차순): `start`, `end`, `perfStart`, `noteIndices[]`, `lyric`(≤30자), `notes`, `summary`(ko, 예 `"22음 낮게 (−61¢) · 4음 빨리 (−87 ms)"`), `severity`. UI: 표시.

### 2.10 HTTP 엔드포인트 (`app.py`)

| 메서드·경로 | 입력 | 응답 | 위치 |
|---|---|---|---|
| `GET /health` | — | `{ok, engine, device, gpu, models[7], separators[], defaultSeparator}` | `:109-120` |
| `GET /jobs?kind=reference&limit=50` | query | `{jobs:[{id, kind, filename, createdAt, status, key, tempoBpm, vocalStart, vocalEnd, noteCount, hasKaraoke, hasPitchFrames, referenceJobId, overall}]}` | `:141-169` |
| `POST /jobs` → 202 | multipart `audio`, `sourceType`, `lyrics`(≤20,000자), `separator` | 작업 봉투(`queued`) | `:172-201` |
| `GET /jobs/{id}` | — | 작업 봉투 + `result` | `:266-271` |
| `POST /jobs/{id}/karaoke` | JSON `{notes[] ≤3000, octave}` | 봉투(`karaokeNotes`, `karaokeVersion+1`, `karaoke.edited`) | `:213-240` |
| `POST /jobs/{id}/lyrics` | JSON `{text}` | 봉투(`lyrics`, `karaoke`, `karaokeNotes` 재생성) | `:243-263` |
| `POST /jobs/{id}/compare` → 202 | multipart `audio`, `sourceType`, `separator`, `allowTranspose`, `octaveTolerant` | 새 performance 봉투 | `:281-324` |
| `POST /jobs/{id}/comparison` | JSON `{allowTranspose?, octaveTolerant?}` | 봉투(`result.comparison` 재계산, 오디오 재분석 없음) | `:327-347` |
| `POST /jobs/{id}/instrumental-key` | JSON `{semitones}` (±6) | `{semitones, file, url, seconds, range}` | `:350-373` |
| `GET /jobs/{id}/files/{kind}?semitones=0` | `kind` ∈ vocals · instrumental · midi · musicxml · karaoke-midi · karaoke-musicxml · pitch-frames | 파일 | `:376-398` |

제한: 확장자 `mp3 wav flac m4a ogg webm`, 250 MB(`app.py:23-24`). CORS 오리진 3개(`:29-39`).

### 2.11 라이브 채점(브라우저 측) 산출

모델 서버 밖이지만 "빼낼 수 있는 데이터"이므로 함께 둡니다. 전부 클라이언트 계산, 서버로는 종료 후 webm만 `/compare`로 보냅니다.

| 산출 | 필드 | 위치 |
|---|---|---|
| AudioWorklet 프레임(20 ms) | `{t, f0, clarity, rms, rmsLow}` — 자체 YIN(≈16 kHz, 창 1024, 70–1050 Hz, 2·3배음 옥타브 보정) | `public/pitch-worklet.js:6-12, 119-122` |
| LiveScorer 실시간 | 점수·등급·음정/박자 판정 수·정확/높게/낮게/놓침·마지막 판정 ¢·빠름/늦음 | `app/live-scoring.tsx:681-699` |
| 마디 히트맵 | 마디별 점수 | `:707-715` |
| 지연 보정 | 최근 40개 온셋 편차 중앙값(±0.4 s) ms | `:165-168, 671` |
| 입력으로 쓰는 서버 필드 | `tempoBpm, timeSignature, vocalStart, key, beatGrid, karaokeNotes[](10개↑ 우선)/notes[], artifacts.instrumental, lyrics.text` | `:257-277, 416` |

---

## 3. 분류 기준·상수 부록

**VAD** (`pitch_engine.py:44-59`): 주기성 진입 0.62 / 유지 0.45 · 절대 dB 진입 −50 / 유지 −56 · 상대 dB(95백분위 기준) 진입 −35 / 유지 −42 · 하드 플로어 −66 · 보컬/믹스 에너지 비 veto 0.06 / 진입 0.15 · 시드 3프레임 · 갭 6(60 ms)/10(100 ms) · 최소 구간 12(120 ms) · 프리롤 8 · 패드 5.

**F0 비교·분절** (`pitch_engine.py:36-42, 61-67, 558-567, 784`): 합의 0.7 st · 음 변경 0.6 st/5프레임 · 평활 15프레임 · 최소 조각 8 · 병합 갭 0.10 s · 범위 C2–B6 · 음표 MIDI 36–95 · 음표 신뢰도 하한 0.2 · 최소 음표 길이 max(7, min(10, 0.8×16분음표)) 프레임.

**성구 라벨** (`register_engine.py:40-88, 329-376`):

| 상수 | 값 | 용도 |
|---|---|---|
| `FALSETTO_SCORE` / `MIXED_SCORE` / `MODAL_SCORE` | 0.6 / 0.4 / 0.3 | 복합 점수 경계 |
| `FALSETTO_H1H2_ANCHOR_DB` (+`LOW_PITCH_ANCHOR_PENALTY_DB`, `STRONG_ANCHOR_EXTRA_DB`) | 8 dB (+3, +4) | 절대 앵커 |
| `FALSETTO_HRF_MAX_DB` | 0 dB | 상위 배음 합 ≤ H1 |
| `MIXED_H1H2_MIN_DB` / `MODAL_H1H2_MAX_DB` | 5 / 5 dB | |
| `FALSETTO_PITCH_PERCENTILE` | 60 | 가성 허용 음역 하한 |
| `MIN_NOTE_SECONDS` | 0.15 s | 미만은 `unclear` |
| `H1H2_MAX_PLAUSIBLE_DB` / `HRF_MIN_PLAUSIBLE_DB` | 20 / −15 dB | 악기 잔여음 제외 게이트 |
| `BREATHY_Z` | 1.0 | CPP 잔차 z → `breathy` |
| `SOFT_CONFIDENCE_SCALE` / `LOW_PITCH_CONFIDENCE_SCALE` | ×0.7 / ×0.6 | caveat 배율 |
| `MIN_FALSETTO_SECONDS` / `MIN_FALSETTO_RATIO` | 1.0 s / 1 % | 두 성구 선언 |
| `WEIGHTS` | h1h2 +0.30, hrf −0.25, slope −0.15, alpha −0.10, cpp −0.20 | 복합 "가성스러움" |
| `lightness` | `sigmoid(1.6 × (0.5·clip(score, ±2.5) + 0.5·((h1h2−4)/8 − hrf/10))) × 100` | `:456-469` |

**노래방 단순화** (`karaoke.py:19-28`): 음절 허용 0.15 s · 분할 여유 0.08 · 최소 조각 0.06 · 장식음 min(0.13, 0.85×16분음표) · 스쿱 0.20 · 멜리스마 0.25 · 레가토 갭 0.10 · 8분 허용 0.17 · 최소 0.25박 · 이웃 흡수 ≤3 st.

**채점** (`compare_engine.py:40-73`): 정렬 hop 0.05 / 거친 탐색 0.10 · 비용 포화 4 st · 유성 불일치 0.7 · 워프 페널티 0.25 · 이조 후보 −6…+5 · 음정 good ≤45 ¢, wrong >150 ¢ · missed 유성 <35 % 또는 유성 프레임 <3 · 박자 good ≤120 ms, off >450 ms · 짧음 55 % · 어택 15 % 제외 · 점수 선형 30→150 ¢, 60→400 ms · 가중 0.65/0.35 · 등급 S 95 / A 88 / B 78 / C 65 / D 50 / F.

**없는 분류 체계** (문의가 잦은 항목):
- 성부 라벨(테너·소프라노 등) **없음**. 음역은 `lowest/highest/coreRange` + span 3등분 `ratios`만. 성별 판별 미구현.
- 음색 카테고리 **없음**. 가장 가까운 것은 성구 4라벨 + `lightness` + `breathy`.
- 원곡 음표의 안정도 등급 **없음**. `stabilityCents`는 비교 작업의 사용자 음표에만 존재.

---

## 4. 🟡 계산되지만 버려지는 값 (노출 후보 16종)

| # | 값 | 지금 | 노출하면 | 수정 지점 | 난이도 |
|---|---|---|---|---|---|
| 1 | npz `dbfs`, `confidence` | 저장만, 아무도 안 읽음 | 성량·다이내믹 곡선, 프레이즈 크레셴도, 신뢰도 가중 채점 | `compare_engine.py:100-102` `load_frames`에 두 배열 추가 | 낮음 |
| 2 | 유성 구간 목록 `_voiced_regions(voiced)` | 첫·끝 값만 → `vocalStart/End` | 프레이즈 수, 최장 프레이즈, 호흡 간격 분포 | `pitch_engine.py:824, 833-834` | 낮음 |
| 3 | 온셋 시각 집합 `onsets` | 분절에만 쓰고 폐기 | 음절 밀도(음절/초), 아티큘레이션 | `pitch_engine.py:782` | 낮음 |
| 4 | 원곡 음표별 F0 표준편차·비브라토 | 미계산(`midi_tuned` 곡선은 있음) | 음표별 안정도, 4–8 Hz 비브라토 유무·깊이 | `pitch_engine.py:547-570` 음표 루프 | 중간 |
| 5 | VAD 통계 `referenceDb`, `strict/lenient` 마스크 | `debug=True`(evaluate.py)만 | 녹음 레벨 진단, VAD 신뢰도 | `pitch_engine.py:232-239, 858-870` | 낮음 |
| 6 | pYIN 원시·음표별 합의율 | 두 비율로만 축약; register 내부에서 음표별 `agree` 계산 후 폐기 | 음표별 F0 신뢰 근거 | `pitch_engine.py:747-777`, `register_engine.py:336` | 낮음 |
| 7 | 성구 프레임 배열 `frames{composite, breathiness, h1h2, hrf, slope, alpha, cpp}` | `debug` 외 폐기; 음표엔 h1h2/hrf/cpp만 | 음표별 `slope`/`alpha`, 성구 전환 시각(passaggio) 탐지 재료 | `pitch_engine.py:796-797`, `register_engine.py:453` | 중간 |
| 8 | `breathiness` 연속값 | 불리언 `breathy`로만 | 숨소리 정도(z) | `register_engine.py:341, 375` | 낮음 |
| 9 | GMM `mu`/`sigma` | `ashmanD`·`weight`만 | 두 성구 중심값 | `register_engine.py:215`; `_posterior_high` 미사용 함수 `:218` | 낮음 |
| 10 | 조성 후보 24개 상관계수 | 최고값 + 마진만 | `keyCandidates` top-3(`tempoCandidates`와 같은 패턴) | `pitch_engine.py:674-687` | 낮음 |
| 11 | 튜닝 원형 평균 벡터 크기 | 0.2 게이트로만 사용 | `tuningConfidence` | `pitch_engine.py:333-336` | 낮음 |
| 12 | 다운비트 위상 `downbeat_index`, `meter` | `offsetBeats`에 흡수 | 마디 시작 시각 목록 | `pitch_engine.py:404, 648` | 낮음 |
| 13 | 음역 구간 절대 초 `zones{low,middle,high}` | %로만 변환 | 구간별 발성 시간(초) | `engine.py:52-59` | 낮음 |
| 14 | 가사 부산물 `lines`, `tokenCount`, Whisper `segments` | result에 없음 | 줄 단위 타이밍, 받아쓰기 세그먼트 수 | `lyrics_engine.py:143, 251-258`, `engine.py:395-408` | 낮음 |
| 15 | DTW `path`, `accumulated`, 성능 측 프레임 소스 | `load_frames` 4번째 반환값을 `_`로 폐기 | 정렬 경로 시각화, `performanceFrames` 플래그 | `compare_engine.py:621, 647` | 낮음 |
| 16 | 분리 `model_path`, peak 리스케일 게인 | 캐시에만 기록 | 재현성 메타, 클리핑 여부 | `separation.py:99, 104-107` | 낮음 |

---

## 5. ⚪ 용어집 정의 vs 구현

기준 문서: `Desktop\claude\프로젝트 핵심 용어집 — 음성·음색·음역·분석 품질.md` (12장). 지표성 용어만 다룹니다.

### 5.1 구현됨 (→ §2 필드)

| 용어집 | 장 | 구현 필드 |
|---|---|---|
| 기본주파수 F0, 피치 추적 | §4 | `notes[].actualMidi`, npz `midi` |
| MIDI note, 센트 | §4 | `notes[].midi`, `notes[].cents` |
| 옥타브 오류 / 불일치율 | §4, §9 | `octaveMismatchRatio`, `comparison.notes[].octaveShift` |
| 유성/무성, 유성 비율 | §4 | `voicedRatio`, `voicedSeconds`, npz `voiced` |
| 원키, 키 시프트, 조옮김 | §4 | `key`, `POST /instrumental-key`, `transposeSemitones` |
| 발성 가능 음역(최저·최고음) | §5 | `lowest` / `highest` |
| 테시투라 ≈ 핵심 음역 | §5 | `coreRange`(5–95 %) |
| 성구별 음역 | §5 | `register.modalRange` / `falsettoRange` |
| 성구, 진성/가성/믹스 우세, 판정 신뢰도 | §7.1 | `notes[].register`, `registerConfidence`, `register.ratios` |
| 어택/onset, 리듬 정확도 | §7.2 | `comparison.notes[].onsetDeviation`, `timingStats` |
| H1−H2, CPP, 스펙트럼 기울기 | §8.4 | `h1h2Db`, `cppDb`, `medianFeatures.slope` |
| 주기성 | §8.4 | VAD 내부 `periodicity` → `notes[].confidence` |
| VAD, F0 confidence, 두 경로 합의 | §9 | `vocalStart`, `confidence`, `f0AgreementRatio` |
| 폴백, 분리 아티팩트 경고 | §9 | `separation.fallbackReason`, UI 경고(`page.tsx:604`) |
| 피치 안정성 | §4 | `comparison.scores.stabilityCents`(사용자 가창만) |
| 보정 calibration | §9 | 라이브 채점 지연 자동 보정 |

### 5.2 부분 구현

| 용어집 | 상태 |
|---|---|
| §6 음량(dBFS) | npz `dbfs`에 프레임 값 존재, result·UI 노출 0 (§4-1) |
| §8.5 피처 분포 | `register.medianFeatures` 계산됨, UI 미노출 |
| §8.6 지각 축 | 용어집 6쌍(밝음↔어두움 등) 대신 자체 축 `lightness` 1개 |
| §9 품질 게이트 | `register.caveats[]`·`confidenceScale`이 유일한 근사. "미측정 / 분석 불가 / 낮은 결과 / 재측정 필요" 4단계 상태 없음(`register.error` 단일 문자열) |
| §7.1 믹스 보이스 | `mixed` 라벨만 있고 전환 구간(passaggio) 검출 없음 |

### 5.3 미구현

| 장 | 미구현 항목 |
|---|---|
| §4 | 추천 키(selected key), 상대 음높이 위치(0–1 개인 음역 정규화) |
| §5 | 실사용 음역, 편안 음역, 안정 음역, 구간별 음역, 지속 고음·고음 지속력, 부담 구간, 음역 적합도, 음역 폭 (verse/chorus 구간 분할 자체가 없음) |
| §6 | 음압, dB SPL, 라우드니스, 클리핑, AGC 감지 |
| §7.1 | 두성, 성구 전환/passaggio, 보이스 브레이크 |
| §7.2 | 비브라토, 서스테인, 프레이징, 딕션, 호흡 프레이즈 |
| §8.1–8.3 | 음색 체계 전체: 기준 음색 B_u, timbre delta, 음색 궤적, 음높이 조건부 음색 T_u(p,r,d,v) |
| §8.4 | 스펙트럼 포락선·중심·롤오프·평탄도, MFCC, HNR, 포먼트·대역폭·안티포먼트, 어택 시간, 스펙트럼 플럭스, 음색 변화율 |
| §8.5 | 음색 임베딩, 가창자 정체성 임베딩, 임베딩 유사도, 프로토타입(단일·다중·구간), 이웃 안정성 |
| §8.7 | 음색 유사도·관계 점수·적합도·신뢰도, "원곡처럼 붙는 / 새롭게 해석하는" 관계 |
| §9 | SNR, 노이즈 플로어, RT60, 바람 잡음, 파열음, 반주 누출 수치, 오토튠·더블링·이펙트 플래그, 유효 음성 길이, 마이크 조건 일관성, 도메인 갭, 품질 점수, 모델 매니페스트 |
| §10 | 사용자 보이스 프로필, 곡 보컬 요구 프로필, 분위기 프로필, 구간 프로필, 추천 게이트, 표현·종합 적합도, 추천 근거, 키 시뮬레이션 (추천 시스템 자체가 없음) |

### 5.4 역방향 불일치·규칙 위반

- 코드에는 있으나 용어집에 정식 항목이 없는 피처: `alpha`(알파 비율), `hrf`(배음 풍부도), `lightness`(발성 가벼움).
- §11 금지 표현: "음정"은 "피치 정확도"로 명시하라는 규칙을 UI가 위반 — `page.tsx`, `live-scoring.tsx:687`, `performance-compare.tsx:529`가 "음정" 라벨 사용.
- §12 후속 합의 10개 항목 미해결. 특히 "용어별 화면 문구와 내부 필드명" 대응표가 없음(이 문서 §2가 그 초안 역할).

---

## 6. 문서↔코드 불일치와 죽은 코드

### 6.1 문서 불일치

| # | 문서 | 실제 코드 |
|---|---|---|
| 1 | `docs/MODEL_PIPELINE_SUMMARY.md` 전체가 HTDemucs 기준, 성구·채점·키 조절 누락 | 기본 분리 Mel-Band RoFormer(`separation.py:35`), register/compare/pitch_shift 존재 → **전면 구식** |
| 2 | `docs/MODEL_PIPELINE.md:57` "1760 Hz 초과 bin은 0" | `FMAX = B6 ≈ 1975.5 Hz`(`pitch_engine.py:37`) |
| 3 | `PIPELINE.md:78` "2-GMM(Ashman D ≥ 2) 이진성 판정" | GMM은 진단용(`register_engine.py:22, 322`); `twoRegisters`는 가성 초 ≥ max(1 s, 1 %)로 결정. `BIMODAL_D` 미사용 |
| 4 | `PIPELINE.md:78` 게이트 "H1−H2 > 20 dB"만 | `HRF < −15 dB`도 함께 적용(`register_engine.py:293`) |
| 5 | `PIPELINE.md:78` "곡 내 z-점수" | 실제는 음높이 조건부 잔차 z(`_pitch_trend`); `MODEL_PIPELINE.md:102`가 정확 |
| 6 | `파이프라인 정리.md:70` 지원 형식에 webm 없음 | `.webm` 포함(`app.py:23`) |
| 7 | `HANDOFF.md:41` Mel-Band 913 MB ↔ `PIPELINE.md:53` 871 MB | 문서 간 상충 |
| 8 | `PIPELINE.md:61` 버전 고정 목록 | ~~`requirements.txt`에 pretty_midi·mido·soundfile·numpy·scipy·ml_collections·PyYAML 없음, transformers 핀 아님~~ → **2026-09-03 해결**: 검증 환경 실제 버전으로 전부 고정(torch 2.13.0 포함) |
| 9 | `docs/MODEL_PIPELINE.md:44` "8 s 청크, overlap 2, fp16" | 청크는 ckpt yaml 값(`separation.py:123`); 코드에 해당 지정 없음 |
| 10 | `HANDOFF.md:105` 엔드포인트 목록 | `/compare`, `/comparison`, `/instrumental-key`, `GET /jobs` 누락(08-27 기준) |
| 11 | `evaluate.py:103` `falsettoProbability` 출력 | register_engine이 더 이상 생성하지 않음 → 항상 None |
| 12 | `compare_engine.py:59` 주석 "≤ off → slightly sharp/flat" | `PITCH_OFF_CENTS` 미사용; 판정은 45/150 2단계 |
| 13 | `파이프라인 정리.md:359` "유성 35 % 미만이면 missed" | 유성 프레임 < 3도 missed(`compare_engine.py:405`) |
| 14 | `app.py:238` 편집 저장 | `karaoke.sourceNotes/melismaNotes/lowConfidenceLyrics/quantizationAdjustments/unassignedSyllables`는 갱신 안 됨(stale) |
| 15 | `engine.py:425` `result.model` | performance 작업은 wav2vec2를 안 돌리는데 문자열에 `wav2vec2-ko` 포함 |

### 6.2 죽은 코드·상수 (참조 0회)

- `compare_engine.PITCH_OFF_CENTS`(`:59`)
- `register_engine.BIMODAL_D`, `MIN_COMPONENT_WEIGHT`, `ANCHOR_H1H2_DB`, `FALSETTO_POSTERIOR`, `MODAL_POSTERIOR`(`:54-58`), 함수 `_posterior_high`(`:218`)
- `evaluate.py:103` `falsettoProbability`
- 프론트: `app/api/jobs/route.ts`, `app/api/jobs/[id]/route.ts`, `db/index.ts` `getDb()`, `lib/audio-analysis.ts`의 `analyzeVocalFile`/`createMusicXml`/`midiToName` (타입 `RangeAnalysis`만 import됨)

### 6.3 프론트 하드코딩 중복 (서버 값을 안 쓰는 곳)

| 프론트 | 서버 원본 |
|---|---|
| 채점 임계 `live-scoring.tsx:14-24` | `comparison.thresholds` / `compare_engine.py:40-73` |
| 피치 트래커명 `page.tsx:649` `"CREPE FULL"` | `result.pitchMethod` |
| 성구 설명문 `page.tsx:609` | `register.method` |
| 키 범위 `key-shift-player.tsx:5` `KEY_RANGE = 6` | `pitch_shift.MIN/MAX_SEMITONES`, `/instrumental-key` 응답 `range` |

---

## 7. 다음 단계와의 대응

2026-08-28 결정된 순서(성구 → 사용자 무반주 녹음 → 음역·성구 적합도/키 시뮬레이션 → 음색 임베딩·추천)에 이 인벤토리를 대응시키면:

| 단계 | 이미 있는 데이터 | 비어 있는 데이터 |
|---|---|---|
| 성구 판정 고도화 | `notes[].register/lightness/h1h2Db/hrfDb/cppDb`, `register.medianFeatures` | 프레임 배열(§4-7) → passaggio 시각, `slope`/`alpha` 음표별, 사람 청취 라벨 |
| 사용자 녹음 프로필 | performance 작업의 `notes[]`·`register`·npz(같은 파이프라인) | 실사용·편안·안정 음역(§5.3), 프레이즈 목록(§4-2), 성량(§4-1) |
| 음역 적합도·키 시뮬레이션 | `lowest/highest/coreRange`, `key`, `beatGrid`, `/instrumental-key` | 상대 음높이 위치, 부담 구간, 추천 키 |
| 음색 임베딩·추천 | 배음 피처 5종 중앙값 | 스펙트럼·MFCC·포먼트 피처, 임베딩, 곡 요구 프로필, 유사도 |
| 품질 게이트 | `caveats`, `confidenceScale`, `f0AgreementRatio`, `separation.fallbackReason` | SNR·클리핑·반주 누출 수치, 4단계 상태, 품질 점수 |
| 라이브 채점 실사용 | 클라이언트 YIN + LiveScorer | 마이크 실측 검증, 서버 `thresholds` 연동 |
