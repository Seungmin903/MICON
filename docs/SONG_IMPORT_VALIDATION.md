# 노래 데이터 INSERT 검증 결과

## 검증 범위

- 원본: `section_metadata.jsonl`
- 기준 스키마: `MICON_COMPLETE_DDL.sql`
- 생성 결과: `section_metadata_insert.sql`
- 원본 384건의 `trackId`는 모두 고유하다.
- 노래 제목과 가수명은 384건 모두 존재한다.
  - 카탈로그 메타데이터 사용: 211건
  - `sourceFolder`의 `가수__곡명`에서 복구: 173건
- 복구한 173건은 `metadata_review_status = NEEDS_REVIEW`로 저장한다.

## 테이블별 INSERT 수

| 테이블 | 실행 단위 | 적재 내용 |
|---|---:|---|
| `SONG` | 384 | 곡명, 정규화 곡명, 재생시간, 상태 |
| `SONG_METADATA` | 384 | 카탈로그 ID, 앨범, 발매일, 이미지, 언어, 가사, 크레딧, 원본 장르 |
| `SINGER` | 384회 upsert | 384곡의 대표 가수, 중복 가수는 정규화 이름으로 통합 |
| `SONG_SINGER` | 384 | 곡과 대표 가수 관계 |
| `GENRE` | 28 | `genre_seed.sql`의 표준 장르 |
| `SONG_GENRE` | 221 | 장르 원본값이 있는 211곡의 표준 장르 관계 |
| `SONG_ANALYSIS` | 384 | 구간 분석 버전, 보컬 시작·종료, 원본 분석 JSON, 출처 JSON |
| `SONG_SECTION` | 384회, 1,968행 | 인트로, 보컬, 간주, 아웃트로 구간 |

`SONG_NOTE`와 `SONG_MEDIA`는 원본 JSONL에 음표 또는 S3 객체 경로가 없으므로 삽입하지 않는다.

## 메타데이터 대응

| JSONL 값 | DDL 저장 위치 |
|---|---|
| `trackId` | `SONG_METADATA.catalog_track_id` |
| `title` | `SONG.title` |
| `artists[].name` | `SINGER.name`, `SONG_SINGER` |
| `album_name` | `SONG_METADATA.album_name` |
| `release_date` | `SONG_METADATA.release_date`, `release_date_raw` |
| `display_image_url`, `image_type` | `SONG_METADATA` 동명 컬럼 |
| `credit_relations` | `SONG_METADATA.credits` |
| `languages` | `SONG_METADATA.primary_language`, `languages` |
| `lyrics.plain`, `lyrics.synced` | `SONG_METADATA.lyrics_plain`, `lyrics_synced` |
| `normalized_genres` | `SONG_METADATA.source_genres`, `SONG_GENRE` |
| `catalogMatch` | `SONG_METADATA.external_ids` |
| `sectionAnalysis` | `SONG_ANALYSIS.timings`, `SONG_SECTION` |
| `sectionExtraction` | `SONG_ANALYSIS.provenance` |

## 장르명 정리

외부 장르명은 `GENRE.name`으로 직접 넣지 않는다. 아래처럼 제공된 Bugs 기준 표준 장르의 `normalized_name`으로 연결한다.

| 외부 장르명 | 국내 곡 | 해외 곡 |
|---|---|---|
| `Ballad` | 발라드 | 발라드 |
| `Dance`, `K-Pop` | 댄스/팝 | 댄스/팝 |
| `Pop` | 댄스/팝 | 팝 |
| `Folk`, `Singer-Songwriter` | 포크/어쿠스틱 | 월드뮤직 |
| `Hip-Hop` | 랩/힙합 | 랩/힙합 |
| `R&B` | 알앤비/소울 | 알앤비/소울 |
| `Rock`, `Alternative` | 락/메탈 | 락 |
| `Jazz` | 재즈 | 재즈 |
| `Trot` | 성인가요 | 성인가요 |
| `Soundtrack` | OST | OST |
| `J-Pop` | J-POP | J-POP |
| `Country` | 월드뮤직 | 월드뮤직 |
| `Holiday` | 캐롤 | 캐롤 |

검증 결과 원본 장르 16종은 모두 변환되며, `genre_seed.sql`에 없는 장르 키 참조는 0건이다.
