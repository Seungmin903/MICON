# stem-pitch 배치 결과 → 앱 데이터 (import_stem_pitch.py)

2026-09-08. 보컬 스템(`보컬분리/<가수>__<제목>/vocals.wav`)만으로 돌린 오프라인 음역 추출 배치(`stem-pitch`)를
엔진·DB·Android 앱이 그대로 쓰는 `workspace/tj-<n>/` 작업 폴더로 바꾸는 절차와 데이터 계약을 적는다.
배치를 만든 스크립트 원본은 남아 있지 않다. **결과물 폴더가 유일한 소스**이므로 그 형식을 여기서 고정한다.

## 1. 입력: `vocal-scope/outputs/stem-pitch/` (git 제외)

| 파일 | 내용 |
|---|---|
| `manifest.json` | `catalog`(원본 카탈로그 경로·sha256·890행), `stemFolders: 384`, `matched[211]{folder, vocals, catalogRow, matchMethod}`, `review[173]{folder, reason, candidates}` |
| `pitch_metadata.jsonl` | 곡당 1행, 211행 (51 MB). 아래 레코드 형식 |
| `status.json` / `run.log` | 배치 진행 상태·로그 (`done=211 failed=0`) |
| `tracks/<16hex>/metadata.json` | jsonl 의 해당 행과 동일한 JSON |
| `tracks/<16hex>/pitch_frames.npz` | `midi, voiced, confidence, dbfs, frame_seconds` — 엔진 `pitch_frames.npz` 와 같은 배열 |

레코드(1행) = 카탈로그 앱용 10필드 + `pitchAnalysis` + `pitchExtraction`:

```
title, artists[{name, role}], album_name, release_date, display_image_url, image_type, credit_relations,
languages (kor|eng|jpn|mul|zxx), lyrics{plain, synced(LRC)|null}, normalized_genres[]
pitchAnalysis: notes[], tempoBpm, tempoRawBpm, tempoCandidates, timeSignature, meterConfidence, timeSignatureCandidates,
               vocalStart, vocalEnd, voicedRatio, voicedSeconds, f0AgreementRatio, octaveMismatchRatio, comparedFrames,
               tuningCents, key{tonic, mode, sharps, name, confidence}, beatGrid{beatTimes[], offsetBeats}, pitchMethod,
               register{...}, timings, lowestMidi, highestMidi, lowest, highest, noteCount
pitchExtraction: folder, vocals, catalogRow(1-based), matchMethod, catalogSha256, audioFingerprint{bytes, mtimeNs},
               device, seconds, analyzedAt(epoch), frames(절대경로, 다른 PC 기준 — 폴더명만 사용), notes(주의문)
```

`notes[]` 원소 22키: `sourceStart, sourceEnd, duration, midi, actualMidi, cents, confidence, register, registerScore,
registerConfidence, h1h2Db, hrfDb, cppDb, breathy, lightness, scoreStartBeat, scoreEndBeat, scoreStart, scoreEnd, start, end, name`
(= `pitch_engine.extract_vocal_melody` 의 음표 + `name`).

`pitchAnalysis` 는 `engine.run_pipeline` 결과의 **부분집합**이다. 없는 것: `coreRange, ratios, confidence(음역 신뢰도),
voicedDuration, karaokeNotes, karaokeVersion, karaoke, lyrics{source,text,...}, artifacts, separation, scoreOctave, model`.

## 2. 조인 키

`pitchExtraction.catalogRow` 는 **1-based** 로 v6 보강 카탈로그
(`data_tj_ballad_top100/metadata_enrichment/results/enriched_all_final_languages_v6_lyrics_normalized.jsonl`, 890행)의 행을 가리킨다.
그 행의 `track_id`(`강산에__TJ-04582_official__...`) 에서 `catalog_paths.job_id_for_track` 으로 `tj-04582` 를 얻는다.
이것이 엔진 작업 id = `songs.song_id` = Android 가 반주를 요청하는 id 다. 제목·가수는 검증용으로만 비교한다(`--strict` 시 불일치 건너뜀).
211곡 전부 일치, TJ 번호 211개 유니크.

## 3. 변환 규칙 (`import_stem_pitch.py`)

`workspace/tj-<n>/job.json` 은 `batch_analyze.py` 가 쓰는 봉투와 같다
(`id, kind:"reference", filename:"<track_id>.mp3", createdAt=analyzedAt, sourceType:"vocal", status, progress, message, result, error, catalog{...}`).

`result` 채우기:

| 필드 | 값 |
|---|---|
| `pitchAnalysis` 전체 | 그대로 복사 |
| `lowest, highest, coreRange, lowestMidi, highestMidi, ratios, confidence, noteCount, voicedDuration` | `range_summary._analyze_range(notes)` (engine.py 와 같은 구현) |
| `karaokeNotes, karaoke, karaokeVersion:1` | `karaoke.build_karaoke_notes(notes, syllables, make_time_to_beat(beatGrid), tempoBpm, midi_to_name)` |
| `register.modalRange / falsettoRange` | `modalRangeMidi / falsettoRangeMidi` 를 음이름 문자열로 (engine 과 동일) |
| `lyrics` | `{source: catalog-lrc \| catalog \| none, text: lyrics.plain, syllables, alignmentScore:null, ...}` |
| `separation` | `{backend:"pre-separated-stem", model:null, seconds:0, source: folder}` |
| `model` / `device` / `scoreOctave` | `"pre-separated stem + crepe-full + pyin"` / `pitchExtraction.device` / `{mode:"auto", shift:0}` |
| `artifacts` | `{"pitch-frames":"pitch_frames.npz", "instrumental":"instrumental.opus"}` (opus 생성 성공 시) |
| `stemPitch` | `pitchExtraction` 전체 + 카탈로그 제목·가수 (출처 추적용, DB/앱은 무시) |

파일: `tracks/<id>/pitch_frames.npz` 복사, `보컬분리/<folder>/no_vocals.wav` → `compact.encode_opus` 로 `instrumental.opus`(128 kbps, 곡당 ≈ 4–5 MB, 스레드 병렬).

`status`: **`complete`** 는 병합 후 `karaokeNotes ≥ 10` · `voicedSeconds ≥ 5` · `instrumental.opus` 존재를 모두 만족할 때. 아니면 **`review`**
(→ Android 목록·`GET /jobs`·`/compare` 에서 제외). 이유: Android 는 karaokeNotes < 10 이면 `notes` 로 폴백하는데 SQLite 경로에는 없어 오류가 나고,
연습 화면은 반주 유무를 검사하지 않고 스트리밍을 시작한다. 2026-09-07 배치: 210 complete, 1 review(`tj-23444` 10,000 Hours — 음표 4개, 발성 0.9초).

가사(`--assign-lyrics`, 1차 근사 — 이후 `align_catalog_lyrics.py` 가 덮어쓴다): `lyrics.synced`(LRC, 줄 단위 시간) 가 있고 줄 텍스트의 70 % 이상이 `lyrics.plain` 에 그대로 있으면
각 줄을 음절(한·일: 글자, 영: 단어)로 나눠 줄 구간 안에 균등 배치한 뒤 `build_karaoke_notes` 의 `syllables` 로 넘긴다.
정밀 정렬(wav2vec2)이 아니므로 `lyricScore 0.5 / lyricStatus low-confidence` 로 표시된다. 122/211 곡에 적용됨.
Android `LyricLines` 는 `lyrics_plain` 에서 음절을 `indexOf` 로 찾아 줄바꿈을 복원하므로 plain 과 다른 LRC 는 가사 배정을 생략한다.

## 4. 실행 순서 (이 PC)

```powershell
cd S15P21B110\vocal-scope\model-server
$py = "..\.venv-model\Scripts\python.exe"      # 정션: mklink /J .venv-model <기존 venv>
$v6 = "C:\Users\SSAFY\Desktop\data_tj_ballad_top100\metadata_enrichment\results\enriched_all_final_languages_v6_lyrics_normalized.jsonl"

& $py import_stem_pitch.py --stem-pitch ..\outputs\stem-pitch --catalog $v6 `
      --stems "C:\Users\SSAFY\Desktop\data_tj_ballad_top100\보컬분리" --assign-lyrics --jobs 6 --run-name stem-pitch-2026-09-07
# → workspace/tj-*/{job.json, pitch_frames.npz, instrumental.opus}, workspace/_batch/stem-pitch-2026-09-07.jsonl (요약)

# (2026-09-09 추가) 가사 강제 정렬 — LRC 줄 단위 근사 대신 보컬 스템에 실제로 정렬하고 karaokeNotes 를 다시 만든다.
#   한국어 우세 텍스트 → wav2vec2-ko(lyrics_engine.align_lyrics), 그 외(일·영·혼합) → MMS_FA + uroman(lyrics_align_mms.py).
#   LRC 가 있는 곡은 정렬 결과를 LRC 줄 시각과 대조해(중앙값 |Δ| ≤ 1.2 s) 어긋나면 예전 LRC 근사 음절로 되돌린다.
#   LRC 가 없는 곡은 정렬된 줄 시작으로 LRC 를 생성해 result.lyrics.syncedGenerated 에 넣고 build_catalog_db 가 songs.lyrics_synced 로 옮긴다.
#   일본 곡 가사에 끼어 있는 한국어 독음 줄은 정렬 전에 제외한다. 처음 실행은 job.json.orig 백업을 남긴다. --force 로 재실행.
& $py align_catalog_lyrics.py --catalog $v6 --stems "C:\Users\SSAFY\Desktop\data_tj_ballad_top100\보컬분리" --device cuda
# → workspace/_batch/align-lyrics-YYYY-MM-DD.jsonl (곡별 aligner·used·lrcCheck·quality). GPU(RTX 4050) 기준 곡당 5–20 s.

& $py build_catalog_db.py --catalog $v6 --out ..\outputs\micon_catalog.sqlite --mysql-dump ..\outputs\micon_catalog.mysql.sql `
      --workspace workspace --audio-root "C:\Users\SSAFY\Desktop\노래 모음\audio"
# → songs=890 analysis_rows=211 (complete=210)

Copy-Item ..\outputs\micon_catalog.sqlite ..\..\frontend\android\app\src\main\assets\micon_catalog.sqlite   # Git LFS (*.sqlite)
..\model-server\start.ps1        # 엔진 :8787 — Android 연습 화면 반주 스트리밍
```

재실행은 안전하다: 기존 `instrumental.opus` 는 유지(`--force` 로 재인코딩), `job.json` 은 다시 쓴다.

## 5. 결과 규모·한계

- SQLite ≈ 114 MB (이전 50곡 자산 33 MB). 음표 JSON 을 무손실로 넣었기 때문(`karaoke_notes` + `source_notes` ≈ 100 MB).
  크기가 문제가 되면 앱이 읽지 않는 음표 키(`h1h2Db, hrfDb, cppDb, registerScore, start, end`)를 빼는 옵션을 `build_catalog_db.py` 에 추가할 것.
- workspace ≈ 1 GB (opus 211개). git 제외.
- `song_artifacts.path` 는 이 PC 절대경로(개발용). 운영 MySQL 적재 시 `docs/SONG_METADATA_DB_GUIDE.md` 대로 오브젝트 키로 치환.
- 기존 50곡 파일럿(`pilot50`) 분석은 이 PC 에 workspace 가 없어 새 DB 에 없다(겹치는 4곡은 stem-pitch 결과로 대체).
- 스템 기준 분석이므로 `separation` 정보가 없다. 가사 타이밍은 `align_catalog_lyrics.py` 실행 후 음절 단위 정렬값이다(정렬 실패 곡만 줄 단위 근사, `lyrics_alignment.quality` 로 구분).
- `karaoke.py` 음절 배정(2026-09-09): 한 음표에 음절이 여러 개 오면 첫 음절만 남기고 버리던 것을 고쳐, 뒤따르는 가사 없는 음표로 나누고 남는 것은 이어 붙인다(`lyricSyllables`). 겹치는 음표가 없는 음절은 0.6 s 안의 음표에 붙인다. Android 는 줄 텍스트를 LRC 에서 만들고 음표 음절은 글자 채움 시각에만 쓴다. MusicXML/MIDI 산출물은 없다(웹 악보 탭은 "악보 없음").
- Android `SqliteSongRepository.ensureCopied()` 는 자산 **크기**만 비교해 재복사한다. 크기가 같은 갱신은 반영되지 않으니 `adb shell pm clear` 또는 버전 비교 로직 추가가 필요하다.
