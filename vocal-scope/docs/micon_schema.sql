-- MICON 곡 카탈로그 + 분석 결과 스키마 (MySQL 8.0+, InnoDB, utf8mb4)
-- 2026-09-02. 곡 쪽 5개 테이블은 model-server/build_catalog_db.py 가 채운다
--   (SQLite 개발용 DB와 `--mysql-dump` 로 만든 INSERT 덤프가 같은 컬럼을 쓴다).
-- 사용자 쪽 테이블은 요구사항 명세서 v2.0 §10 을 옮긴 제안이며 앱 백엔드가 소유한다.
--
-- 규칙
--  * 모양이 자주 바뀌거나 배열인 분석 결과는 JSON 한 컬럼. 화면이 필터·정렬에 쓰는 값만 일반 컬럼으로 뽑는다.
--  * 결측 상태는 팀 기준(장르·메타데이터 기준서 §9.3): NULL = 해당 없음, 'unknown', 'unverified', 'not_analyzed'.
--  * 오디오 파일은 DB 밖(서버 디스크/오브젝트 스토리지). 경로·해시만 저장.
--  * JSON 컬럼 안의 값을 자주 조건으로 쓰게 되면 생성 컬럼 + 인덱스로 뽑는다 (예: song_analysis.task_pitch).

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS micon CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE micon;

CREATE TABLE songs (
  song_id                 VARCHAR(32)  NOT NULL,          -- 'tj-14260' : TJ 곡번호 기반, model-server workspace job id 와 동일
  tj_number               VARCHAR(16)  NULL,              -- '14260'
  track_id                VARCHAR(255) NOT NULL,          -- metadata_enrichment track_id
  title                   VARCHAR(255) NOT NULL,
  title_normalized        VARCHAR(255) NULL,
  artist_name             VARCHAR(255) NOT NULL,          -- 대표 표기 (artists[0].name)
  artists                 JSON NULL,                      -- [{name, role, external_ids}]
  credit_type             VARCHAR(32)  NULL,              -- solo | featuring | collaboration | group | NULL(unknown). 보강 원본의 출처 라벨은 넣지 않음
  album_name              VARCHAR(255) NULL,
  release_date            DATE NULL,                      -- 부분 날짜는 월/일을 01로 채움 (원문은 release_date_raw)
  release_date_raw        VARCHAR(10)  NULL,              -- '2008', '2002-01', '2004-11-19'
  duration_ms             INT UNSIGNED NULL,
  display_image_url       VARCHAR(1024) NULL,
  image_type              VARCHAR(20)  NULL,              -- album_cover | video_thumbnail
  primary_language        VARCHAR(8)   NULL,              -- kor | eng | jpn | mul | zxx
  languages               JSON NULL,
  primary_genre           VARCHAR(40)  NULL,              -- normalized_genres[0] (팀 대분류 코드로 재매핑 예정)
  genres                  JSON NULL,                      -- normalized_genres 전체
  market_context_tags     JSON NULL,                      -- ["market:kr","tj_chart:가요", ...]
  chart_entries           JSON NULL,                      -- [{chart_category, rank}] (TJ 2026-08 장르 top100)
  external_ids            JSON NULL,                      -- isrc, itunes_track_id, musicbrainz_recording_id, lrclib_track_id, bugs_track_id ...
  lyrics_plain            MEDIUMTEXT NULL,
  lyrics_synced           MEDIUMTEXT NULL,                -- LRC 형식, 없으면 NULL
  lyrics_source           VARCHAR(40)  NULL,              -- lrclib | bugs_track_xmp | manual
  lyrics_license_status   VARCHAR(20)  NULL,              -- unverified (배포 전 확인 필요)
  lyrics_distribution_allowed TINYINT(1) NOT NULL DEFAULT 0,
  audio_path              VARCHAR(1024) NULL,             -- 원본 mp3 (서버 로컬 경로 또는 오브젝트 키)
  audio_sha256            CHAR(64)     NULL,
  audio_bytes             BIGINT UNSIGNED NULL,
  source_url              VARCHAR(512) NULL,              -- 유튜브 원본
  metadata_review_status  VARCHAR(20)  NOT NULL DEFAULT 'unverified',
  schema_version          SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  created_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (song_id),
  UNIQUE KEY uk_songs_track_id (track_id),
  UNIQUE KEY uk_songs_tj_number (tj_number),
  KEY ix_songs_artist (artist_name),
  KEY ix_songs_genre (primary_genre),
  KEY ix_songs_language (primary_language),
  FULLTEXT KEY ft_songs_search (title, artist_name) WITH PARSER ngram    -- 한국어 부분 검색: MATCH(title, artist_name) AGAINST('사랑' IN BOOLEAN MODE)
) ENGINE=InnoDB;

-- VocalScope 파이프라인 결과 (곡당 1행). 화면 필터에 쓰는 값은 컬럼, 나머지는 JSON.
CREATE TABLE song_analysis (
  song_id             VARCHAR(32) NOT NULL,
  analysis_version    VARCHAR(64) NOT NULL,               -- 파이프라인 버전 태그 (batch run name)
  model               VARCHAR(255) NULL,                  -- "mel-band-roformer-kim + crepe-full + pyin + wav2vec2-ko"
  device              VARCHAR(16) NULL,
  analyzed_at         DATETIME(3) NOT NULL,
  status              VARCHAR(16) NOT NULL,               -- complete | failed
  error               TEXT NULL,
  -- 조성·박자
  key_tonic           TINYINT UNSIGNED NULL,              -- 0–11
  key_mode            VARCHAR(8)  NULL,                   -- major | minor
  key_name            VARCHAR(16) NULL,                   -- "D major"
  key_confidence      FLOAT NULL,
  tuning_cents        SMALLINT NULL,
  tempo_bpm           SMALLINT UNSIGNED NULL,
  tempo_raw_bpm       FLOAT NULL,
  tempo_candidates    JSON NULL,
  time_signature      VARCHAR(8)  NULL,                   -- "4/4"
  meter_confidence    FLOAT NULL,
  -- 음역 (원곡 보컬)
  lowest_midi         TINYINT UNSIGNED NULL,
  highest_midi        TINYINT UNSIGNED NULL,
  core_low_midi       TINYINT UNSIGNED NULL,              -- coreRange 하한 (발성 시간 5 백분위) = 테시투라
  core_high_midi      TINYINT UNSIGNED NULL,              -- coreRange 상한 (95 백분위)
  range_ratios        JSON NULL,                          -- {low, middle, high} 정수 %
  vocal_start_sec     FLOAT NULL,
  vocal_end_sec       FLOAT NULL,
  voiced_seconds      FLOAT NULL,
  voiced_ratio        FLOAT NULL,
  note_count          INT UNSIGNED NULL,
  -- 품질
  range_confidence    TINYINT UNSIGNED NULL,              -- 0–100 (길이 가중 F0 신뢰도)
  f0_agreement        FLOAT NULL,                         -- CREPE·pYIN 합의율 0–1
  octave_mismatch     FLOAT NULL,
  task_support        JSON NULL,                          -- {pitch_range: pass|review|fail, timbre, rhythm, vocal_identity: not_analyzed}
  task_pitch          VARCHAR(16) GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(task_support, '$.pitch_range'))) STORED,
  -- 묶음
  register            JSON NULL,                          -- 성구 요약 (ratios, lightnessMean, modalRange, falsettoRange, caveats ...)
  lyrics_alignment    JSON NULL,                          -- {source, syllables, alignmentScore, unknownTokens, error}
  karaoke_stats       JSON NULL,                          -- {sourceNotes, karaokeNotes, notesWithLyric, melismaNotes, unassignedSyllables ...}
  separation          JSON NULL,                          -- {backend, model, seconds, fallbackReason ...}
  beat_grid           JSON NULL,                          -- {beatTimes[], offsetBeats}
  score_octave_shift  TINYINT NULL,                       -- 악보 8vb 표기 여부
  timings             JSON NULL,                          -- 단계별 소요 초
  PRIMARY KEY (song_id),
  KEY ix_analysis_key (key_name),
  KEY ix_analysis_range (lowest_midi, highest_midi),
  KEY ix_analysis_tempo (tempo_bpm),
  KEY ix_analysis_task_pitch (task_pitch),
  CONSTRAINT fk_analysis_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 음표 배열. 연습 화면·악보·채점이 통째로 읽으므로 JSON 한 덩어리(곡당 300–1,200개, 0.3–1 MB).
CREATE TABLE song_notes (
  song_id          VARCHAR(32) NOT NULL,
  karaoke_version  INT UNSIGNED NOT NULL DEFAULT 1,
  karaoke_notes    JSON NOT NULL,                         -- 노래방 멜로디 + 가사 음절 + 성구 주석 (연습·악보 표시용)
  source_notes     JSON NOT NULL,                         -- 원곡 채보 원본 (재편집·재정렬용)
  PRIMARY KEY (song_id),
  CONSTRAINT fk_notes_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 파일 산출물. kind: instrumental(opus) | midi | pitch-frames | instrumental-key(±n, 서버 렌더 시) ...
CREATE TABLE song_artifacts (
  song_id     VARCHAR(32)  NOT NULL,
  kind        VARCHAR(32)  NOT NULL,
  semitones   TINYINT      NOT NULL DEFAULT 0,             -- instrumental-key 렌더에만 사용
  path        VARCHAR(1024) NOT NULL,                      -- 로컬 경로 또는 오브젝트 스토리지 키
  media_type  VARCHAR(64)  NULL,
  bytes       BIGINT UNSIGNED NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (song_id, kind, semitones),
  CONSTRAINT fk_artifacts_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 곡 구간 구조 (1절·후렴). 파이프라인 미구현 → 비어 있는 상태로 시작. 요구사항 §10.3 sections, §11.
CREATE TABLE song_sections (
  song_id       VARCHAR(32) NOT NULL,
  idx           SMALLINT UNSIGNED NOT NULL,
  label         VARCHAR(20) NOT NULL,                      -- intro | verse | pre_chorus | chorus | bridge | outro | rap
  start_sec     FLOAT NOT NULL,
  end_sec       FLOAT NOT NULL,
  lowest_midi   TINYINT UNSIGNED NULL,
  highest_midi  TINYINT UNSIGNED NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'unverified',
  PRIMARY KEY (song_id, idx),
  CONSTRAINT fk_sections_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ─────────────────────────────────────────────────────────────────────────────
-- 사용자 쪽 (앱 백엔드 소유, 요구사항 v2.0 §10.1·10.2·10.4·10.5). UUID 는 CHAR(36) 문자열로 저장.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
  user_id            CHAR(36) NOT NULL,
  email              VARCHAR(255) NOT NULL,
  password_hash      VARCHAR(255) NULL,                    -- 복구 가능한 평문 금지 (§12.2)
  nickname           VARCHAR(64) NULL,
  auth_type          VARCHAR(16) NOT NULL DEFAULT 'email',
  keep_raw_recording TINYINT(1) NOT NULL DEFAULT 1,        -- SET-002 (명세 본문은 ON, 표는 OFF → 팀 확정 필요)
  created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at         DATETIME(3) NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB;

-- 사용자 녹음 1건 = 파이프라인 작업 1건 (정해진 음계 측정 또는 노래 녹음)
CREATE TABLE user_recordings (
  recording_id     CHAR(36) NOT NULL,
  user_id          CHAR(36) NOT NULL,
  kind             VARCHAR(20) NOT NULL,                   -- scale_test | song_recording
  song_id          VARCHAR(32) NULL,                       -- 노래 녹음일 때
  audio_path       VARCHAR(1024) NULL,                     -- keep_raw_recording=0 이면 분석 후 NULL
  duration_sec     FLOAT NULL,
  input_quality    JSON NULL,                              -- {rmsDb, clippingRatio, noiseFloorDb, valid, reasons[]} (VOI-006)
  analysis_job_id  VARCHAR(64) NULL,                       -- model-server job id
  status           VARCHAR(16) NOT NULL,                   -- queued | processing | complete | failed
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  delete_after     DATETIME(3) NULL,                       -- 원본 삭제 예정 시각
  PRIMARY KEY (recording_id),
  KEY ix_recordings_user (user_id, created_at),
  CONSTRAINT fk_recordings_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_recordings_song FOREIGN KEY (song_id) REFERENCES songs(song_id)
) ENGINE=InnoDB;

-- 최신 음성 분석 리포트 (사용자당 1행, 재분석 시 갱신; 이력은 user_recordings 로)
CREATE TABLE user_voice_profiles (
  user_id            CHAR(36) NOT NULL,
  recording_id       CHAR(36) NULL,
  analyzed_at        DATETIME(3) NOT NULL,
  lowest_midi        TINYINT UNSIGNED NULL,                -- 발성 가능 음역
  highest_midi       TINYINT UNSIGNED NULL,
  stable_low_midi    TINYINT UNSIGNED NULL,                -- 안정 음역 = 편안 음역의 기술적 근사 (용어집 §5) — 구현 예정
  stable_high_midi   TINYINT UNSIGNED NULL,
  core_low_midi      TINYINT UNSIGNED NULL,                -- 테시투라(5–95 %) — 안정 음역 구현 전 임시
  core_high_midi     TINYINT UNSIGNED NULL,
  pitch_stability    JSON NULL,                            -- {"57": {stabilityCents, seconds, grade}, ...} 반음별 (ANL-004)
  register           JSON NULL,                            -- 성구 비율·lightnessMean·성구별 음역
  vocal_traits       JSON NULL,                            -- 육각 그래프: {pitchAccuracy, highSustain, breath, rhythm, diction, timbre} 각 {value|null, measured, confidence}
  measured_flags     JSON NULL,                            -- 항목별 측정 여부 (ANL-008: 미측정≠0점)
  confidence         FLOAT NULL,
  analysis_version   VARCHAR(64) NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_profiles_recording FOREIGN KEY (recording_id) REFERENCES user_recordings(recording_id)
) ENGINE=InnoDB;

-- 사용자×곡 적합도 캐시 (FIT-001~006). 공식 확정 전까지 timbre/rhythm 은 NULL = 평가 불가.
CREATE TABLE song_fit (
  user_id             CHAR(36) NOT NULL,
  song_id             VARCHAR(32) NOT NULL,
  profile_analyzed_at DATETIME(3) NOT NULL,                -- 어느 프로필 기준인지
  recommended_key     TINYINT NULL,                        -- 반음
  range_fit           FLOAT NULL,                          -- 0–1
  timbre_fit          FLOAT NULL,
  rhythm_fit          FLOAT NULL,
  overall_fit         FLOAT NULL,
  strain_sections     JSON NULL,                           -- 부담 구간 [{start, end, reason}]
  reasons             JSON NULL,                           -- 추천 사유 문장 배열
  computed_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, song_id),
  KEY ix_fit_user_overall (user_id, overall_fit),
  CONSTRAINT fk_fit_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_fit_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 연습 1회 (RPT-004). compare_engine.comparison 을 저장.
CREATE TABLE practice_sessions (
  session_id         CHAR(36) NOT NULL,
  user_id            CHAR(36) NOT NULL,
  song_id            VARCHAR(32) NOT NULL,
  key_shift          TINYINT NOT NULL DEFAULT 0,
  started_at         DATETIME(3) NOT NULL,
  duration_sec       FLOAT NULL,
  section_start_sec  FLOAT NULL,                           -- 구간 연습이면
  section_end_sec    FLOAT NULL,
  score_overall      FLOAT NULL,
  score_pitch        FLOAT NULL,
  score_timing       FLOAT NULL,
  grade              CHAR(1) NULL,                         -- S A B C D F
  stability_cents    FLOAT NULL,
  coverage           JSON NULL,                            -- {notesTotal, notesSung, notesMissed, coveredRatio}
  pitch_stats        JSON NULL,
  timing_stats       JSON NULL,
  measures           JSON NULL,                            -- 마디 히트맵
  issues             JSON NULL,                            -- 문제 구간 (취약 구간 다시 연습 입력)
  comparison_job_id  VARCHAR(64) NULL,                     -- model-server performance job id (음표별 상세는 job.json)
  analysis_version   VARCHAR(64) NULL,
  PRIMARY KEY (session_id),
  KEY ix_sessions_user (user_id, started_at),
  KEY ix_sessions_song (song_id),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_sessions_song FOREIGN KEY (song_id) REFERENCES songs(song_id)
) ENGINE=InnoDB;

CREATE TABLE favorites (
  user_id    CHAR(36) NOT NULL,
  song_id    VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, song_id),
  CONSTRAINT fk_fav_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_fav_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE setlists (
  setlist_id CHAR(36) NOT NULL,
  user_id    CHAR(36) NOT NULL,
  name       VARCHAR(100) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (setlist_id),
  KEY ix_setlists_user (user_id),
  CONSTRAINT fk_setlists_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE setlist_items (
  setlist_id CHAR(36) NOT NULL,
  song_id    VARCHAR(32) NOT NULL,
  position   INT UNSIGNED NOT NULL,
  PRIMARY KEY (setlist_id, song_id),
  KEY ix_setlist_items_order (setlist_id, position),
  CONSTRAINT fk_items_setlist FOREIGN KEY (setlist_id) REFERENCES setlists(setlist_id) ON DELETE CASCADE,
  CONSTRAINT fk_items_song FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 음색 임베딩이 들어오면: MySQL 9 의 VECTOR 타입 또는 별도 벡터 저장소(예: pgvector, Milvus) 검토. 8.x 에서는 JSON 배열로 두고 앱에서 계산.
