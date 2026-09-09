# VocalScope 모델 파이프라인 정리 (2026-08-27 기준)

원곡 mp3 한 개가 들어와서 노래방 악보가 나오기까지, **어떤 모델이 어떤 순서로 무엇을 처리하는지**만 정리한 문서입니다. 프론트·API·저장 구조는 다루지 않습니다. 코드 위치는 `model-server/` 기준입니다.

## 한눈에 보기

```
원곡 mp3/wav
 │
 ├─[0] ffmpeg ─────────────── 44.1 kHz 스테레오 WAV 정규화 (input.wav)
 │
 ├─[1] Mel-Band RoFormer ──── 보컬 / 반주 분리 (vocals.wav, instrumental.wav) · HTDemucs 폴백 [딥러닝]
 │
 ├─[2] CREPE full ─────────── 보컬 stem 전 구간 10 ms 프레임별 360-bin 피치 확률행렬     [딥러닝]
 │        │
 │        ├─[3] VAD ────────── 에너지 + 보컬/믹스 비율 + CREPE 주기성 → 노래하는 구간 판정 [규칙]
 │        ├─[4] Viterbi ────── 유성 구간마다 확률행렬 디코딩 → F0 곡선                     [알고리즘]
 │        ├─[5] pYIN ───────── 같은 구간을 좁은 음역으로 재추적 → 합의율·옥타브 보정      [신호처리]
 │        └─[6] 튜닝 보정 ──── 전역 기준 피치 편차(센트) 제거                             [알고리즘]
 │
 ├─[7] 박자 추정 (librosa) ── BPM, 첫 박, 3/4·4/4                                        [알고리즘]
 ├─[8] 음표 분절 ──────────── 히스테리시스 + 음절 온셋 → 음표 목록                        [규칙]
 ├─[8b] 성구(진성/가성) ───── F0 배음 피처(H1−H2·HRF·기울기·CPP) → 음높이 조건부 잔차 + 절대 앵커 [신호처리]
 ├─[9] 격자 정렬 + 조성 ───── 16분/셋잇단 격자, Krumhansl–Schmuckler 조성                 [알고리즘]
 │        └─ 원곡 채보 악보: vocal.mid → vocal.musicxml (music21)
 │
 ├─[10] Whisper turbo ─────── (가사 미입력 시) 보컬 stem 받아쓰기 → 가사 텍스트            [딥러닝]
 ├─[11] wav2vec2-ko CTC ───── 가사 음절을 보컬 타이밍에 강제 정렬 → 음절별 시작/끝 시각    [딥러닝]
 │
 └─[12] 노래방 멜로디 ─────── 음절 기준 분절·장식음 정리·8분음표 격자 → karaoke.mid/musicxml [규칙]
```

딥러닝 모델은 **Mel-Band RoFormer(폴백 HTDemucs), CREPE full, Whisper turbo, wav2vec2 한국어 CTC** 4개이고, 나머지는 신호처리·규칙 기반 알고리즘입니다. 모든 연산은 로컬 GPU(RTX 4050 6 GB)에서 실행되며, 큰 모델(Whisper, wav2vec2)은 작업마다 GPU에 올리고 끝나면 내립니다.

---

## 단계별 상세

### [0] 음원 정규화 — ffmpeg (`engine._convert_to_wav`)
- 입력: mp3/wav/flac/m4a/ogg → 출력: `input.wav` (44.1 kHz, 2ch)
- 이후 모든 단계가 같은 WAV를 기준으로 하도록 형식을 통일합니다.

### [1] 보컬 분리 — Mel-Band RoFormer, 폴백 HTDemucs (`separation.separate`, 2026-08-28 교체)
- 기본 모델: **Mel-Band RoFormer** 보컬 체크포인트(Kimberley Jensen, MIT, `melband-roformer-infer` 패키지로 로드, 가중치 913 MB는 첫 실행 시 `%USERPROFILE%\.cache\melband-roformer-infer`에 다운로드). 설정: 8 s 청크, overlap 2, fp16 autocast. 모델은 CPU에 상주시키고 분리 중에만 GPU로 올립니다(피크 VRAM 2.2 GB).
- 근거: 공개 multisong 벤치마크 보컬 SDR **10.98 dB vs htdemucs 8.38 dB**(ZFTurbo/Music-Source-Separation-Training `docs/pretrained_models.md`). Mel 스케일 대역 분할이 보컬 분리에 특히 유리하다는 것이 원논문(Wang et al. 2023, arXiv 2310.01809)의 주장이며, 같은 저자들이 보컬 분리 + 보컬 멜로디 채보를 함께 다룬 후속 논문(arXiv 2409.04702)도 있습니다.
- 자체 실측(신호등): 보컬 시작 전 인트로 구간의 stem 누출 **−72 dBFS(htdemucs) → −94 dBFS(Mel-Band)**, 고역(4–8 kHz) 보존 동등, 엔진 지표(vocalStart·F0 합의·옥타브 불일치·조성)는 동일하게 합격. 성구 분석의 배음 피처가 분리 잔여음에 오염되지 않도록 하는 것이 교체의 핵심 이유입니다.
- 시간: 4분 곡 **≈ 42 s**(RTX 4050, 모델 로드 2.5 s 포함). 첫 실행 직후 디스크 캐시가 없을 때는 200 s까지 늘어날 수 있습니다. htdemucs는 13–18 s.
- 폴백: CUDA OOM이면 청크를 절반씩 줄여 재시도(최소 2 s), 그래도 실패하거나 다른 예외가 나면 자동으로 `htdemucs`로 처리하고 `result.separation.fallbackReason`에 이유를 남깁니다. 업로드 폼(`separator=melband|htdemucs`)이나 환경변수 `VOCALSCOPE_SEPARATOR`로 강제 선택할 수 있습니다.
- 폴백 모델 HTDemucs: `htdemucs` (`demucs==4.1.0`), `--two-stems vocals`, `segment 7`, `overlap 0.25`, `shifts 1`.
- 출력: `vocals.wav`(보컬), `instrumental.wav`(반주 = 원곡 − 보컬)
- 왜 분리가 필요한가: 분리 없이 원곡을 그대로 채보하면(실측, 신호등) 인트로 반주 27개 음표 오검출, 두 경로 F0 합의 87 % → 50 %, 옥타브 불일치 0.2 % → 19 %, 최저음이 베이스(D2)로 잡혀 음역 분석이 무효가 됩니다.

### [2] 피치 확률행렬 — CREPE full (`pitch_engine._crepe_probabilities`)
- 모델: `torchcrepe` full (360-bin 피치 분류 CNN, Kim et al. 2018)
- 입력: 보컬 stem을 16 kHz 모노로 로드, hop 160 샘플 = **10 ms 프레임 격자**(이후 모든 곡선이 이 격자를 공유)
- 처리: 전 구간을 배치 512로 1회 추론 → 프레임 × 360 bin 시그모이드 확률행렬(CPU 보관, 4분 곡 ≈ 35 MB)
- 음역 마스크: C2(65.4 Hz) 미만, 1760 Hz 초과 bin은 0
- 부산물: `주기성 = 프레임별 최대 확률`(VAD 입력), argmax 기반 초기 MIDI 곡선
- torchcrepe 기본 디코더는 쓰지 않습니다(시그모이드 출력을 softmax해 과평활, ±20¢ 디더 추가, 5.12 s 배치마다 불연속). 대신 아래 [4]의 자체 Viterbi를 사용합니다.

### [3] 보컬 활동 감지(VAD) (`pitch_engine._detect_voice_activity`)
- 입력(10 ms 프레임): 보컬 stem dBFS(40 ms RMS), 보컬/믹스 RMS 비율(원곡 분석일 때만), CREPE 주기성(중앙값 필터 5)
- 임계(고정): 주기성 진입 0.62 / 유지 0.45, 절대 dBFS 진입 −50 / 유지 −56, 상대 dB(95 백분위 기준) 진입 −35 / 유지 −42, 비율 veto < 0.06 / 진입 ≥ 0.15
- 방식: 엄격 조건을 30 ms 이상 만족하는 **시드**가 포함된 관대 조건 연결성분만 유성으로 채택(히스테리시스). 이후 ≤ 60 ms 갭 채움(에너지가 유지되면 ≤ 100 ms), 120 ms 미만 구간 제거, 최대 80 ms 에너지 유도 프리롤(자음 어택 보존)
- 출력: `voiced` 마스크(분절용), `analysis` 마스크(±50 ms 패딩, 디코딩 문맥용), 첫 보컬 시각·유성 비율
- 효과: 노래가 시작되기 전 구간에서는 음표가 생기지 않습니다(5곡 모두 조기 음표 0개, 첫 보컬 시각 오차 ≤ 0.3 s).

### [4] 세그먼트별 Viterbi 디코딩 (`pitch_engine._viterbi_bins`, `_local_average_midi`)
- 유성 세그먼트의 확률 슬라이스만 디코딩(무음 구간 계산 생략 → 속도)
- 전이행렬: 삼각형, 폭 12 bin(원본 CREPE 레시피), emission은 열 정규화한 확률
- 서브빈 음높이: 디코딩 bin ±4 bin 확률 가중평균으로 센트 계산(디더 없음, 결정적)
- 출력: 세그먼트별 연속 MIDI 곡선

### [5] pYIN 교차 검증 (`pitch_engine._pyin_segment`, `_compare_paths`, `_octave_correct`)
- `librosa.pyin`, frame 2048 / hop 160, 음역을 CREPE 추정치 기준 **[min−3, max+3] 반음**(최소 12, 최대 30 반음)으로 좁혀 실행 → 전 음역 대비 수십 배 빠름
- 비교(pYIN이 유성이라고 본 프레임만): 합의 |Δ| ≤ 0.7 반음, 옥타브 불일치 |Δ − 12| ≤ 0.7 반음
- 옥타브 보정: 불일치 프레임에서 pYIN 값이 CREPE 300 ms 러닝 중앙값과 일치하면 CREPE 프레임을 ±12 반음 이동
- **pYIN은 유성 판정에 쓰지 않습니다**(과거 재현율 붕괴의 원인). 용도는 (i) `confidence = 0.65·주기성 + 0.35·pYIN 확률`, (ii) 옥타브 보정, (iii) 품질 지표 `f0AgreementRatio`, `octaveMismatchRatio`
- 실측: 5곡 합의 86–91 %, 옥타브 불일치 0.1–1.7 %

### [6] 기준 피치 편차 보정 (`pitch_engine._estimate_tuning`)
- 안정 프레임(5 프레임 중앙값과 0.2 반음 이내)의 MIDI 소수부를 주기성 가중 **원형 평균** → 전역 오프셋(센트)
- 반음 **반올림 전에** 곡선에서 차감(예전엔 반올림 후 계산해 인접 반음 사이에서 음표가 쪼개졌음). 결과 길이 < 0.2면 0으로 간주. 5곡 모두 0–2¢.

### [7] 박자 추정 — librosa (`pitch_engine._estimate_beats`)
- 입력: 원곡(믹스) 16 kHz(보컬 파일만 분석할 때는 보컬)
- `onset_strength` → `beat_track`(hop 512) → 박 간격 중앙값으로 BPM 재산출, 60–190 범위로 옥타브 정규화
- 박자표: 박 강도의 3주기 vs 4주기 대비로 3/4·4/4 판별 + 첫 박(다운비트) 위상
- 출력: 박 시각 배열, BPM, 박자표, 시각→박 매핑(이후 모든 격자가 공유, `beatGrid`)

### [8] 음표 분절 (`pitch_engine._syllable_onsets`, `_segment_notes`)
- 최소 음표 길이: `max(70 ms, min(100 ms, 0.8 × 16분음표))`
- 음 변화 감지: 150 ms 중앙값 평활 곡선이 **현재 음 중심(음 시작 후 3–12번째 프레임 중앙값에 고정)** 에서 0.6 반음 이상 벗어난 상태가 50 ms 이상 지속될 때 전환 → 비브라토(4–8 Hz)는 전환으로 보지 않음. 경계는 원곡선이 두 중심의 중점을 지나는 프레임으로 정밀화, 100 ms 넘는 활주는 중점에서 분할
- 음절 온셋: 스펙트럼 플럭스(유성 구간 99 백분위 정규화, delta 0.30, wait 70 ms) + ±80 ms 내 6 dB 이상 RMS 골 → 같은 음이 이어지는 반복 음절 분할
- 음 높이: 튠 보정 곡선의 중앙값(150 ms 이상 음은 첫 40 ms 제외 → 스쿱 편향 제거) 반올림, C2–B6 범위, confidence < 0.2 제외
- 병합: 같은 음·간격 ≤ 100 ms·사이에 온셋 없음이면 합침

### [8b] 성구(진성/가성) 분석 (`register_engine.analyze_register`, 2026-08-28 추가)
- 입력: 보컬 stem(원본 44.1 kHz), [4]–[5]에서 나온 튜닝 보정 전 F0(Hz), `voiced`, 프레임 신뢰도, CREPE·pYIN 합의 마스크, [8]의 음표 목록. 같은 10 ms 격자.
- 프레임 피처(유성·신뢰도 ≥ 0.5 프레임만, 4096-pt Hann 창): **H1−H2**(1·2배음 진폭 차, dB), **HRF**(2배음 이상 에너지 합 / H1, dB), **스펙트럼 기울기**(4 kHz 이하 배음 진폭의 log2(배음 번호) 회귀 기울기, dB/oct), **알파 비율**(1–5 kHz / 50 Hz–1 kHz 에너지, dB), **CPP**(Hillenbrand 켑스트럼 피크 돋보임, 1/F0 ±15 % 탐색). 배음 진폭은 k·F0 ±12 % 창 안의 최대값.
- 타당성 게이트: H1−H2 > 20 dB 또는 HRF < −15 dB 프레임은 사람 목소리 범위 밖(악기 잔여음·F0 오류)으로 제외(YB 나는 나비 지속 F♯4 구간이 이 경우).
- **음높이 조건부 잔차**: 피처마다 반음 단위 중앙값(≥ 20 프레임인 반음만)에 가중 직선을 맞춰 "이 가수가 이 음높이에서 평소 내는 값"을 구하고, 그 잔차의 로버스트 z(±4 클립)를 가중합 `0.30·H1H2 − 0.25·HRF − 0.15·기울기 − 0.10·알파 − 0.20·CPP`(양수 = 가성 방향) → 유성 구간 내 150 ms 중앙값 평활. 곡 전체 z만 쓰면 "약한 verse vs 벨팅 chorus"의 강약 차이가 점수를 지배해 실패했음(아이유: 저음 verse H1−H2 +5 dB, C5 벨팅 −5 dB, E5 이상 +10 dB).
- **절대 앵커 + 음높이 사전정보**: 음표가 `가성 우세`가 되려면 (a) 잔차 점수 ≥ 0.6 **그리고** 음표 중앙 H1−H2 ≥ 8 dB(곡 중앙 음높이보다 낮으면 11 dB), HRF ≤ 0 dB(상위 배음 합이 H1 이하) — 또는 H1−H2 ≥ 앵커+4 dB이고 HRF ≤ −3 dB인 강한 절대 증거 — 이면서 (b) **곡의 가창 음높이 상위 40 %(60 백분위 이상)** 에 있어야 함. 같은 음향 특성이 그 아래 음역에 나타나면 `진성 우세 + breathy(숨소리)` 로 표기. 점수 0.4–0.6이고 H1−H2 ≥ 5 dB인 상위 음역 음표는 `혼합`, 그 외는 `진성 우세`.
- 신뢰도: 가성 = `(0.4 + 0.3·점수 여유 + 0.3·앵커 여유) × F0 신뢰도 × (0.5 + 0.5·CREPE·pYIN 합의율)`; 가성 구간의 중앙 음높이가 진성보다 낮으면 ×0.6, 6 dB 이상 작게 녹음돼 있으면 ×0.7(경고문 동반). 150 ms 미만 음표는 항상 불명확(0). 가성 우세 합계가 1초(또는 1 %) 미만이면 곡 전체는 "진성 위주"로 요약하고 개별 검수를 권함.
- **발성 가벼움(`lightness`, 0–100)**: 라벨과 별개로 음표마다 연속값을 냅니다. 절반은 음높이 조건부 잔차 점수(가수 자신과의 상대 비교), 절반은 절대 스케일 `(H1−H2 − 4)/8 − HRF/10` 을 합쳐 시그모이드로 0–100에 매핑. 기준점: 벨팅(H1−H2 −5 dB, HRF +9) ≈ 10, 중립(4 dB/0 dB) ≈ 50, 가벼운 가성(12 dB/−5 dB) ≈ 85. 두성·믹스는 대체로 40–75 사이에 놓입니다. 피아노롤 색(짙은 잉크 → 보라)과 결과 화면의 10구간 분포 막대, 선택 음표 패널의 미터가 이 값을 표시하고, 가성 우세 판정은 보라 테두리로 병기합니다(LILAC 실측: 진성 우세 중앙 44, 혼합 67, 가성 우세 91).
- 출력: `notes[].register/registerConfidence/registerScore/lightness/breathy/h1h2Db/hrfDb/cppDb`, 곡 요약(`lightnessMean`, `lightnessHistogram` 포함) `register`(bimodal, ashmanD, ratios, seconds, modalRange, falsettoRange, medianFeatures). `evaluate.py --dump-frames`의 `frames.csv`에 `reg_*` 열로 프레임 피처가 함께 저장됩니다.
- 한계: 규칙 기반 1단계입니다. 숨소리 많은 약한 진성과 가성은 H1−H2·CPP만으로는 음향적으로 겹치므로 음높이 사전정보로 갈라 놓은 상태이고(저음역의 진짜 가성은 놓침), 모음 조건은 보정되지 않았으며, 두성(head voice)·믹스는 가성과 진성 사이("혼합")로 떨어지는 것이 정상입니다. 7곡에서 가성 우세로 잡힌 구간은 곡당 2.5–3.5 %(상위 음역의 B4–F5 등)로, 청취 검수 대상 목록으로 쓰는 것이 현재 용도입니다. 사람 청취 라벨로 정확도를 검증한 상태가 아니므로 화면 문구는 "우세/신뢰도"로만 표현합니다. 2단계로 GTSinger(falsetto·mixed·breathy 음소 단위 라벨, 한국어 포함) 기반 학습 모델을 같은 출력 스키마로 비교할 계획입니다.

### [9] 격자 정렬·조성·원곡 채보 악보 (`pitch_engine._align_notes_to_beats`, `_estimate_key`, `engine._write_filtered_midi`, `_create_musicxml`)
- 격자: **박 단위**로 16분(4분할) 또는 셋잇단(3분할) 선택(셋잇단은 그 박에 2음 이상이 뚜렷히 더 잘 맞을 때만) → 시작 충돌 시 더 긴 음 유지, 끝 = 다음 음 시작, 첫 마디가 비지 않도록 재앵커링
- 조성: 길이 가중 피치클래스 히스토그램 × Krumhansl–Schmuckler 프로파일 → 조표(플랫 조는 플랫 표기)
- MIDI(`pretty_midi`, 조표·박자표 포함) → MusicXML(`music21`, 마디·쉼표·붙임줄 자동) — **원곡 채보 악보** `vocal.musicxml`
- 음표명 표기: 각 음표 아래 `D3`, `F♯4` 형식(음이름 → 임시표 → 옥타브, 실음 기준)
- 표기 옥타브: 멜로디 중앙값이 F4보다 낮으면(남성 음역) 음표를 **한 옥타브 위에 그리고** "8vb" 안내 표시(라벨·MIDI는 실음 유지). 화면에서 자동/옥타브 위/실음 선택 가능

### [10] 가사 텍스트 — Whisper turbo (`lyrics_engine.transcribe_lyrics`)
- 사용자가 가사를 입력하면 **이 단계는 생략**됩니다(권장 경로).
- 모델: `openai-whisper` `turbo`(large-v3-turbo), 보컬 stem(16 kHz) 입력, `language=ko`, beam 5, temperature 0, `condition_on_previous_text=False`
- 환각 필터: 한글 없는 구간, `no_speech_prob > 0.7`, `avg_logprob < −1.2`, 직전과 같은 문장 제거
- 한계: 노래 받아쓰기라 오탈자가 남습니다(예: "목적지"→"목적질"). 정확한 가사가 있으면 입력하는 것이 정렬 품질에 결정적입니다. 약 20–50 s.

### [11] 가사 음절 강제 정렬 — wav2vec2 한국어 CTC (`lyrics_engine.align_lyrics`)
- 모델: `kresnik/wav2vec2-large-xlsr-korean` — vocab이 **한글 음절 1,202자 + 단어 구분자 `|`** 라 음절 하나가 CTC 토큰 하나
- 처리: 보컬 stem을 ~20 s 단위(경계는 ±4 s 안의 가장 조용한 프레임)로 잘라 log-softmax 확률 계산 → 20 ms 프레임 격자에 이어 붙임 → `torchaudio.functional.forced_align`으로 **전곡을 한 번에** 정렬 → 토큰별 시작/끝 프레임 → 초 단위 음절 타이밍
- 한글이 아닌 구간(영어·숫자)은 `[UNK]` 한 토큰으로 넣어 순서만 유지(타이밍은 근사)
- 출력: `{음절, 시작, 끝, 점수, 줄 번호, 단어 시작 여부}` 목록, 평균 점수. 약 12 s.

### [12] 노래방 기준 멜로디 (`karaoke.build_karaoke_notes`)
[8]–[9]의 원곡 채보 음표와 [11]의 음절 타이밍을 합쳐 "부를 수 있는 기준 멜로디"를 만듭니다. 순서:
1. **음절 기준 분절**: 한 음표 안에 음절 온셋이 2개 이상이면 온셋에서 분할(같은 음 반복 음절 복원)
2. **음절 부착**: 음절 시작과 가장 가까운(≤ 150 ms) 미배정 음표에 부착, 없으면 그 시각을 포함하는 음표에 부착
3. **장식음 정리**: 16분음표보다 짧고(≤ 130 ms) 가사가 없는 음은 3반음 이내 이웃에 흡수 또는 삭제 / 짧은 음이 3반음 이내의 더 긴 음으로 이어지면 **스쿱**으로 보고 합침 / 가사가 없는 짧은 멜리스마(2반음 이내)는 앞 음에 합침
4. **8분음표 격자**: 시작은 8분음표 격자로, 8분에서 0.17박 이상 벗어나면 16분 격자로. 시작이 겹치면 긴 음 유지, 간격 ≤ 100 ms면 앞 음이 다음 음 시작까지 이어짐(레가토)
- 출력: `karaoke.mid`(한글 가사 이벤트 포함), `karaoke.musicxml`(**1행 가사 음절, 2행 음이름**). 원곡 채보는 그대로 보존되어 두 악보를 비교할 수 있습니다.
- 실측(신호등): 원곡 채보 547음 → 노래방 493음(465음에 가사), 첫 마디 `이·제·야·목·적·질·정·했`

---

### [13] 부른 노래 채점 — 서브시퀀스 DTW (`compare_engine.compare_jobs`, 2026-08-31 추가)

- **입력**: 원곡 job(`job.json` + `pitch_frames.npz`)과 사용자가 부른 녹음 job. 부른 녹음은 `POST /jobs/{원곡id}/compare`로 올리며 **원곡과 같은 파이프라인**([0]~[9], `skip_lyrics=True`로 [10]~[11]만 생략)을 거친다 — 반주와 함께 녹음한 파일이면 Mel-Band 분리 후 목소리만 평가.
- **프레임 F0 저장**: [4]~[6]의 10 ms 보정 MIDI 곡선·유성 마스크·confidence·dBFS를 `pitch_frames.npz`로 남긴다(`extract_vocal_melody(return_frames=True)`). 없던 옛 job은 음표를 계단 곡선으로 되살려 대체.
- **이조(키) 탐색**: 50 ms(긴 곡 100 ms) 격자로 내린 뒤, 피치클래스 이동 −6…+5 각각에 대해 저해상도(≤ 3M 셀) 옥타브-폴딩 DTW를 돌려 비용 최소 후보를 고르고, 정렬 쌍의 원시 MIDI 차 중앙값으로 옥타브를 확정. `allowTranspose=false`면 0.
- **정렬**: 자체 구현 서브시퀀스 DTW(`compare_engine.subsequence_dtw`, 행 단위 벡터화·prefix-sum/cummin 트릭) — 부른 노래가 쿼리(전부 소비), 원곡은 임의 구간 매칭(일부만 불러도 되고, 녹음이 원곡보다 길어도 됨). librosa `dtw(subseq=True)`는 쿼리가 원곡보다 길면 행렬을 전치하며 경로 열 순서가 뒤바뀌어 IndexError가 나서 교체(2026-08-31). 비용 = 옥타브 폴딩 |Δ| (4 st에서 포화) / 유성·무성 불일치 0.7 / 둘 다 무성 0.1, 삽입·삭제 스텝 +0.25. 정렬 신뢰도 = 유성 쌍 중 1 st 이내 비율(< 45 % → `reliable=false` 경고).
- **음표 판정**(기준 = 노래방 멜로디 `karaokeNotes`, 없으면 채보 `notes`; 목표 음높이는 원가수의 `actualMidi`): 워핑 경로로 매핑한 구간에서 사용자 F0 중앙값(앞 15 % 어택 제외) → 오차 cents. `good` ≤ 45¢ · `sharp/flat` ≤ 150¢ · `wrong` > 150¢ · `missed` = 유성 비율 < 35 %. 박자 = 경로 오프셋 − ±8 s 로버스트 선형 트렌드(무반주 템포 드리프트 흡수) + 10 ms 온셋 미세 탐색(매핑 시각에 가장 가까운 ±0.6 st 진입 전이, 50 ms 지속, 이동 ≤ 0.25 s). `good` ≤ 120 ms · `early/late` · `off` > 450 ms.
- **점수**: 음표 길이(0.1–1.5 s) 가중. 음정 100 %→0 %: 30¢→150¢, 박자 60 ms→400 ms, 안 부른 음 0. 종합 = 음정 0.65 + 박자 0.35 → S ≥ 95 / A 88 / B 78 / C 65 / D 50 / F. 부른 구간 밖 음표는 `unsung`으로 점수에서 제외(완창률 별도 표시).
- **출력**(`result.comparison`): `transposeSemitones`, `startOffsetSeconds`, `tempoRatio`, `coverage`, `scores`, `pitchStats/timingStats`(경향), `notes[]`(음표별 판정), `measures[]`(마디 히트맵), `issues[]`(연속 문제 구간 심각도순), `curves`(원곡 시간축 50 ms 원곡/사용자 곡선 + 사용자 녹음 시각 매핑 → 차트·재생 위치 동기화).
- **검증(합성)**: 신호등 보컬 스템 21–80 s를 +2키 이조·1.2 s 지연·40–46 s −80¢ 디튠·60–64 s 200 ms 지연·68–71 s 무음 → 이조 +2, 오프셋 −19.8 s, 템포 1.00, 해당 구간이 각각 `flat −86¢ 11음`, `late +156 ms 9음`, `missed 4음`으로 검출, 나머지 음 평균 |Δt| 47 ms, 종합 89(A). 무반주 경로와 MR 믹스(분리) 경로 결과 동일. 실제 사람 가창으로는 아직 미검증.
- **CLI**: `python model-server/compare_engine.py --reference <job dir> --performance <job dir> [--no-transpose] [--no-octave] [--out file]`.

## 산출 지표(품질 판단용)

| 지표 | 의미 | 근거 단계 |
|---|---|---|
| `vocalStart` | 첫 보컬 시각 — 이전 구간에는 음표 없음 | [3] |
| `voicedRatio`, `voicedSeconds` | 유성 비율·유효 가창 시간 | [3] |
| `f0AgreementRatio` | 두 경로(CREPE·pYIN) F0 합의율 — 0.6 미만이면 "품질 낮음" 표시 | [5] |
| `octaveMismatchRatio` | 옥타브 불일치율 — 0.1 초과면 "품질 낮음" 표시 | [5] |
| `tuningCents` | 기준 피치 편차 | [6] |
| `separation.backend`, `separation.fallbackReason` | 실제 사용된 분리 모델과 폴백 사유 | [1] |
| `register.bimodal`, `register.ashmanD` | 두 성구가 관측되었는지(Ashman D ≥ 2)와 분리도 | [8b] |
| `register.ratios`, `register.modalRange`, `register.falsettoRange` | 진성 우세/가성 우세/혼합/불명확 발성 시간 비율과 성구별 음역 | [8b] |
| `notes[].register`, `notes[].registerConfidence` | 음표별 성구 라벨과 신뢰도(확정 판정이 아님) | [8b] |
| `key`, `tempoBpm`, `timeSignature` | 조성·BPM·박자표(신뢰도 포함) | [7][9] |
| `lyrics.alignmentScore` | 음절 정렬 평균 확률(Whisper 텍스트일 때 0.2 전후, 정확한 가사면 상승) | [11] |
| `karaoke.unassignedSyllables` | 음표에 붙지 못한 음절 수 | [12] |

## 처리 시간 (RTX 4050, 4분 곡 실측)

| 단계 | 시간 |
|---|---|
| [1] Mel-Band RoFormer (기본) / HTDemucs (폴백) | ≈ 42 s / 13–18 s |
| [2]–[9] 멜로디·악보 | 33–57 s (CREPE ≈ 20 s, pYIN ≈ 9–30 s, 성구 ≈ 3 s) |
| [10] Whisper turbo | 20–50 s (가사 입력 시 0) |
| [11] wav2vec2 정렬 | ≈ 12 s |
| [12] 노래방 악보 | ≈ 3 s |
| **합계** | **약 90–150 s** (가사 입력 시 약 70–100 s) |

## 5곡 검증 결과 (2026-08-27)

| 곡 | 첫 보컬(엔진 / 에너지 기준) | 조기 음표 | F0 합의 | 옥타브 불일치 | 조성 | BPM |
|---|---|---|---|---|---|---|
| 이무진 · 신호등 | 21.77 / 21.78 s | 0 | 87 % | 0.2 % | B major | 99 |
| 이무진 · 뱁새 | 1.19 / 1.19 s | 0 | 86 % | 0.1 % | G major | 99 |
| 이무진 · 청춘만화 | 22.87 / 22.88 s | 0 | 88 % | 0.2 % | G major | 94 |
| 아이유 · LILAC | 20.58 / 20.30 s | 0 | 89 % | 1.2 % | D minor | 117 |
| 아이유 · Celebrity | 2.87 / 1.02 s | 0 | 91 % | 1.7 % | A major | 99 |

청춘만화는 공개 악보(원곡 G Key 명시, 연주 편의상 C Key·E♭ 색소폰 기보)와 첫 3마디 22음 중 21음이 일치함을 확인했습니다.

## 한계와 주의
- 자동 채보는 100 % 일치를 보장하지 않습니다. 코러스·더블링이 겹치거나 분리 stem에 반주가 남으면 합의율이 떨어지고 화면에 "품질 낮음"이 표시됩니다.
- Whisper 받아쓰기 가사는 오탈자가 있어 노래방 수준의 가사 정확도는 **정확한 가사 입력이 전제**입니다.
- 코드(화음) 인식과 곡 구조(전주/후렴) 표시는 현재 범위에 없습니다.
- 매우 높은 이상치(예: 청춘만화 A5)는 코러스나 분리 잔여음일 수 있어 편집기에서 검수가 필요합니다.
