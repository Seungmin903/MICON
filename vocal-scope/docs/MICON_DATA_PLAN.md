# MICON 데이터 적재·모델 배치 계획 (2026-09-02)

2026-09-09 운영 저장소 갱신: **SSAFY 서버의 BE·MySQL + 개인 AWS 계정 비공개 S3**를 사용합니다. 아래 로컬 배치·SQLite·파일 경로 설명은 개발 산출물 계약입니다. 운영 인수는 [S3 운영 계약](../../docs/operations/S3_VOICE_STORAGE.md)과 [배치 인수의 운영 적재 절차](BATCH_HANDOFF.md)를 따릅니다. 버킷·BE 전용 IAM·서버 키 저장과 CLI 연결은 검증했지만 팀원 업로더·DB import·음원 제공 연동은 아직 미완료이며 기존 로컬 경로를 그대로 운영에 사용하지 않습니다.

Figma 와이어프레임(MICON 부제), Notion 요구사항 명세서 v2.0·장르/메타데이터 기준·용어집·리포트 설계·연습 기능 설계, TJ 890곡 카탈로그 보강 결과를 대조해 **화면 → 필요한 데이터 → 출처 → DB** 를 정리한 문서입니다. 파이프라인이 내보내는 필드의 전수 목록은 `DATA_INVENTORY.md`, 스키마 DDL은 `micon_schema.sql`.

## 1. 오늘 확정한 것

| 항목 | 결정 | 근거 |
|---|---|---|
| MR(반주) 제공 방식 | **Mel-Band RoFormer 분리 + Rubber Band 키 시프트**(현 파이프라인)로 확정 | 연습 기능 설계 문서의 미결 표 중 1a(AI 반주 생성)에 해당, 이미 구현·검증됨 |
| 곡 일괄 분석 | **50곡 시범 배치 먼저**, 결과 확인 후 890곡 | `model-server/batch_analyze.py`, 후보는 한국어·솔로·가사 보유·장르 분산 |
| 가사 소스 | 카탈로그 가사(889/890곡)를 넣고 **Whisper 생략**, wav2vec2 강제정렬만 | 곡당 −50 s, 오탈자 제거 |
| "편안한 음역" 정의 | coreRange(5–95 % 발성 시간)로 대체하지 않고 **안정 음역**(반음별 안정도 통과 연속 구간)으로 구현 | 용어집 §5 "안정 음역 = 편안 음역의 기술적 근사", 요구사항 ANL-003 P0. 구현 전까지 coreRange는 "핵심 음역(테시투라)"로만 표기 |
| DB | **MySQL 8**(팀 결정, 2026-09-02) + 오디오는 DB 밖(경로만). 개발 초기엔 같은 컬럼의 SQLite(`outputs/micon_catalog.sqlite`), MySQL용 INSERT 덤프는 `--mysql-dump` | JSON 컬럼으로 음표·마디 저장, ngram FULLTEXT로 한국어 검색, 음색 임베딩은 후속(MySQL 9 VECTOR 또는 별도 저장소) |
| 추천 키 기본값 근거 | 트레이너 답변 "최고음보다 3키 낮게" | 공식은 요구사항 §15 미확정 항목 |

## 2. 카탈로그 현황 (`Desktop\노래 모음`)

- 890곡 mp3(5 GB, 570 아티스트), 파일명 `아티스트__TJ-번호_official__제목.mp3`, **ID3 태그 없음** → 메타데이터는 파일이 아니라 `metadata_enrichment/results/enriched_all_final_languages_v6_lyrics_normalized.jsonl`에서 가져온다.
- 채움 비율: 제목·가수·이미지·장르·언어 890, 가사 889(synced 446), 앨범·발매일·길이 ~810, ISRC 193, MusicBrainz 486. **bpm·조성·박자표·분위기 0/890** → VocalScope 분석이 채운다.
- 조인 키: `track_id`(보강) ↔ `song_id = tj-<번호>`(파이프라인 job id). 같은 제목이 TJ 번호만 다른 쌍 61개는 별개 곡으로 둔다(장르·메타데이터 기준서 §9.2 버전 구분).
- 가사 라이선스는 전부 `unverified`, `distribution_allowed=false` → 화면 노출 전 확인 필요(요구사항 §11).

## 3. 화면 ↔ 데이터 대응

범례: ✅ 지금 있음 · 🟡 계산되지만 노출 안 됨(소규모 수정) · ❌ 없음

| 화면 요소 | 데이터 | 출처 | 상태 |
|---|---|---|---|
| 리포트 · 나의 음역대, 음역대 지도 | 전체 음역 / 안정(편안) 음역 / 추천곡 음역 | 사용자 녹음 `lowest/highest`, **안정 음역(구현 예정)**, 곡 `song_analysis.lowest/highest` | ✅ / 🟡 / ✅ |
| 리포트 · 음역별 안정성 곡선 | 반음별 안정도 | `pitch_frames.npz`(저장만 됨) | 🟡 (ANL-004 P2) |
| 리포트 · 내 목소리에 맞는 음악 | 편안 음역, 적정 BPM, 가사 밀도, 프레이즈 길이, 장르, 보컬 스타일 | 음역 ✅ · 프레이즈 🟡(유성 구간 목록) · BPM/가사 밀도/장르 ❌(사용자 프로필 로직) · 스타일 부분 ✅(`lightness`, `breathy`, 성구 비율) | 혼합 |
| 곡 적합도 %, 추천 키 | 음역/음색/리듬 적합도, 추천 키 | 음역·추천 키: 데이터 있음, **공식 미확정** · 음색·리듬 ❌ | 🟡/❌ |
| 종합 보컬 특성 육각형 | 발음·음량·고음·호흡·리듬·음색 | 고음 ✅ · 음량 🟡(`dbfs`) · 호흡 🟡 · 리듬 ✅(연습 후) · 발음 🟡(`lyricScore` 대용) · 음색 부분 ✅ | 혼합 |
| 간편 분석 (G3 게이지 3초 유지) | 실시간 F0, 입력 음량 | `public/pitch-worklet.js` | ✅ |
| 연습 · 곡 선택 (+key 배지, 찜) | 카탈로그, 사용자별 추천 키, 찜 | `songs` ✅ · `song_fit.recommended_key`(공식 필요) · `favorites` 신규 | ✅/🟡 |
| 연습 · 상세정보 키 변화 그래프 | 곡 멜로디 곡선 + 내 음역 | `song_notes.karaoke_notes` + 프로필 | ✅ |
| 연습 전·중 (MR, 키, 가사 싱크, 실시간 음정) | 반주, ±키 렌더, 음절 타이밍, 목표 음정 | `instrumental.wav`, `/instrumental-key`, `karaokeNotes.lyricStart`, `live-scoring` | ✅ |
| 퍼펙트스코어식 악보 | 음표 격자 + 가사 | `karaoke.musicxml` / `karaokeNotes` | ✅ (모바일 렌더러 필요) |
| 연습 분석 상세 | 음표별 판정, 마디 점수, 문제 구간 | `comparison.notes/measures/issues` → `practice_sessions` | ✅ |
| 요구사항 10.3 `sections`(1절·후렴), 난이도, 분위기 | 구간 구조 | 없음 → `song_sections` 빈 테이블로 시작 | ❌ (가장 큰 공백) |

## 4. DB 구성 (`micon_schema.sql`)

곡 쪽(적재기가 채움): `songs` · `song_analysis` · `song_notes`(JSON 한 덩어리) · `song_artifacts` · `song_sections`(빈 상태).
사용자 쪽(앱 백엔드): `users` · `user_recordings` · `user_voice_profiles` · `song_fit` · `practice_sessions` · `favorites` · `setlists` · `setlist_items`.

개발용 산출물 생성: `model-server/build_catalog_db.py --catalog <v6.jsonl> --out outputs/micon_catalog.sqlite --mysql-dump outputs/micon_catalog.mysql.sql` (재실행 가능, `workspace/tj-*/job.json`을 읽음). 기존 덤프·`micon_schema.sql`을 운영 MySQL에 직접 실행하지 않습니다. 운영은 object key 매핑과 현재 BE 스키마 호환성 검토 후 전용 계정/적재 절차로 반영합니다. S3 업로드만으로 테이블/곡이 생성되지 않습니다.
시범 측정(2026-09-02, 890곡 + 분석 3곡, 7 MB): 곡 선택 목록 조인 2 ms, 제목·가수 검색 6 ms, 음표 1,152개 로드 12 ms.

`task_support`는 초기 규칙: `f0AgreementRatio ≥ 0.6 ∧ octaveMismatch ≤ 0.1 ∧ 유성 30 s↑ → pitch_range pass`, 그 외 review. 음색은 review 고정(청취 검증 전), vocal_identity는 not_analyzed.

## 5. 배치와 모델 최적화

측정(RTX 4050, 6분 곡): 분리 72 s · CREPE 29 s · pYIN 16 s · Viterbi 5 s · 성구 5 s · 정렬 ~12 s → **163 s**. 4분 곡은 약 95 s. 50곡 ≈ 80분, 890곡 ≈ 24시간(Whisper 생략 기준; 포함 시 약 40시간).

- 실행: `.venv-model\Scripts\python.exe model-server\batch_analyze.py --songs pilot50.json --catalog <v6.jsonl>` (엔진 8787과 동시 실행 금지, GPU 공유). 로그 `workspace/_batch/<run>.jsonl`.
- **다른 PC 인수(2026-09-03)**: 곡 목록은 `make_song_list.py --catalog <v6.jsonl> --audio-root <audio> --out batch/all890.json`으로 그 PC에서 생성하고, `batch_analyze.py`·`build_catalog_db.py`는 `--audio-root`로 음원을 다시 찾는다(`catalog_paths.py`, 순위 접두 파일명 100곡 포함). 절차·검증·문제 해결은 `docs/BATCH_HANDOFF.md`.
- 다음 단축 후보: pYIN 교차검증을 배치에서 표본 검증용으로만 돌리기(−16 s), wav2vec2 상주(−5 s/곡), 분리 청크를 ckpt 기본 8 s → VRAM 여유 시 확대.
- **저장 정책(2026-09-02 적용, `model-server/compact.py`)**: 곡 폴더에 남기는 것 = `instrumental.opus`(128 kbps VBR, 4분 곡 ≈ 4 MB) · `pitch_frames.npz` · `vocal.mid` · `karaoke.mid` · `job.json`. 삭제 = `input.wav`, `separated/`, `vocals.wav`, `instrumental.wav`, MusicXML 2종, 배치 작업의 `original.*` 복사본(카탈로그 원본을 직접 읽음). 6분 곡 314 MB → 8 MB. 배치는 `run_pipeline(compact=True)`로 항상 적용, 웹 업로드는 `VOCALSCOPE_COMPACT=1`일 때만.
  - MusicXML은 `/files/musicxml`·`karaoke-musicxml` 요청 시 MIDI + 음표에서 재생성(≈1 s). 키 변경 반주는 Opus → WAV 디코드 → Rubber Band → Opus로 `instrumental_key±N.opus` 캐시(6분 곡 9 s). 보컬 스템이 없으므로 가사 재정렬(`POST /lyrics`)은 재분석이 필요하다고 422로 안내.
  - 로컬 앱/엔진은 곡 정보·음표·조성·BPM·가사 타이밍을 DB에서 읽고, 오디오는 `song_artifacts.path`의 Opus를 파일 서버에서 스트리밍한다(DB에 바이너리 미저장). 운영에서는 개인 S3 object key 매핑 및 허가된 다운로드/스트리밍 제공을 별도로 연결해야 하며, 버킷 공개나 만료 URL의 영구 저장으로 대체하지 않는다.
- 배치를 다시 돌리기 전에 넣을 가치가 있는 🟡 항목: 반음별 안정도, 프레이즈(유성 구간) 목록, dbfs 통계, 조성 후보 top-3 (`DATA_INVENTORY.md` §4).

## 5-1. 50곡 시범 배치 결과 (2026-09-02)

- 50/50 완료, 실패 0. 곡당 중앙값 83 s(44–163 s), 총 70분. 단계 중앙값: 분리 42.6 s · CREPE 18.5 s · pYIN 10.7 s · Viterbi 3.0 s · 성구 2.4 s.
- 분석값 분포: BPM 중앙값 134(82–156), 박자표 4/4 48곡·3/4 2곡, 장조 33·단조 17, 음표 수 중앙값 533, 최저음 중앙값 MIDI 48.5(C3), 최고음 중앙값 75(E♭5), 음역 폭 중앙값 25반음.
- 품질: f0 합의율 중앙값 0.86(p25 0.79), 옥타브 불일치 최대 0.12. 초기 규칙(`task_pitch`)으로 49곡 pass · 1곡 review(거북이 빙고, 옥타브 불일치 0.12). 적재기의 `or 0` 버그(0.0을 결측으로 취급)를 잡은 뒤 수치.
- 가사 정렬: alignmentScore 중앙값 0.21(p25 0.17, p75 0.28), 미배정 음절 중앙값 28. 0.1 미만 3곡(이승철 말리꽃 2건, 박군 한잔해는 미배정 185개 → 가사 원문과 실제 가창 구조가 다를 가능성).
- 의심 결과: 성시경 "너의 모든 순간" 음역 F2–G♯6(4옥타브 이상) → 코러스/옥타브 오류 의심, 청취 확인 필요.
- 작업 폴더 19 GB(50곡, 정리 전) → 저장 정책 적용 후 약 0.3 GB.
- MySQL `micon`에 890곡 + 50곡 분석 적재 완료(덤프 5초).

## 6. 확인 필요 / 미결

0. `Metadata_1차 완료.jsonl`(890행, 앱용 10필드)에 **곡 식별자(tj_number/track_id)가 없음** → 재내보내기 요청. 현재는 v6와 행 순서가 같아 위치로만 연결 가능.

1. 가사 정렬 점수가 낮음(시범 3곡 alignmentScore 0.15–0.34, 중앙값 lyricScore ≈ 0). 음절 시각은 타당해 보이나 **청취 검증** 필요. 재정렬은 `POST /jobs/{id}/lyrics`로 곡당 12 s.
2. 요구사항 §15: 적합도 공식·가중치, 추천 키 범위(현재 ±6), 음계 측정 프로토콜, 품질 게이트 수치.
3. `sections`(1절·후렴) 생성 방법 — 가사 반복 구조 + 음역/에너지로 규칙 기반 1차 안 검토.
4. 원본 녹음 기본값: 명세 본문은 ON, 표 SET-002는 OFF로 상충.
5. 가사·이미지 이용 권한(§11) — 화면 노출 전 확인.
