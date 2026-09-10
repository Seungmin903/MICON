# 노래 데이터 INSERT 검증 결과

## 검증 범위

- 원본: `section_metadata.jsonl`
- 운영 기준 스키마: `backend/src/main/resources/db/migration/V1~V6` (Flyway)
- 참고 DDL: `MICON_COMPLETE_DDL.sql` — 운영/Flyway DB에 직접 실행하지 않는다.
- 생성 결과: `section_metadata_insert.sql`
- 원본 384건의 `trackId`는 모두 고유하다.
- 노래 제목과 가수명은 384건 모두 존재한다.
  - 카탈로그 메타데이터 사용: 211건
  - `sourceFolder`의 `가수__곡명`에서 복구: 173건
- 복구한 173건은 `metadata_review_status = NEEDS_REVIEW`로 저장한다.

## 실행 순서와 안전 조건

1. MR 리뷰·병합 후 `mysql-song-import` 파이프라인 성공을 확인한다.
2. 운영 DB 백업/현재 상태와 Flyway 이력을 확인하고, 승인된 배포 절차로 migration을 적용한다.
3. `genre_seed.sql` → `section_metadata_insert.sql` 순서로 적재하고 아래 건수를 확인한다.

기존 추천 V5는 변경하지 않고, 중복되었던 미적용 곡 V5를 V6로 옮겼다.
이미 로컬 DB에서 예전 곡 V5를 적용했다면 자동 `repair`/baseline이나 V6 강제 실행을 하지 않는다.
먼저 이력을 확인하고, 버려도 되는 개발 DB만 새로 만들어 재검증한다.
운영 DB에 이력이 있다면 별도 migration 계획이 필요하다.

### CI에서 검증하는 내용

- Java 21과 운영과 동일한 MySQL 8.4.11 digest를 사용한다.
- `sh infra/mysql/verify-song-import.sh`가 `backend:verifySongImport` Gradle 작업을 실행한다.
- 백엔드 의존성의 실제 Flyway로 전체 migration을 적용·validate하고 재적용이 0건인지 확인한다.
- 장르 seed와 384곡 SQL을 각각 두 번 실행하고, 아래 건수·곡/가수/분석/구간 ID 유지·구간과 분석의 곡 소유 관계를 assert한다.
- `1969-02`, `2008`의 DATE는 NULL, 원문은 `release_date_raw`에 유지되는지 검사한다.
- MySQL 준비 대기는 최대 90초, 작업 제한은 10분이다. SQL/건수 오류는 작업 실패로 처리한다.

테스트는 새 일회용 MySQL에만 실행한다. `MICON_MYSQL_TEST=1`,
`MICON_TEST_DB_HOST=mysql`, `MICON_TEST_DB_PASSWORD`가 필요하다.
허용 호스트는 `mysql`, `localhost`, `127.0.0.1`뿐이며, 비어 있지 않은 DB는 거절하고 DB 삭제/clean은 하지 않는다.
이 제한만 믿고 운영 포트 포워딩을 연결하지 말 것. 테스트 컨테이너에는 운영 볼륨·네트워크·비밀을 연결하지 않는다.
CI의 공개된 비밀번호는 일회용 서비스 전용이며 운영에 사용하지 않는다.
`MYSQL_USER=root`는 MySQL 이미지의 서비스 초기화를 실패시키므로 설정하지 않는다.

이 검증은 Spring/JPA 전체 기동, 운영 적재, 음색 임베딩 연동 검증을 의미하지 않는다.
`SONG_ANALYSIS.status=PARTIAL`인 구간 데이터이며 추천용 음색 임베딩 적재는 별도 작업이다.

## 2026-09-09 격리 MySQL 실행 결과 (219 후속 수정)

- Java 21·MySQL 8.4.11, 각각 1 CPU·1 GiB인 컨테이너에서 검증했다.
- 전체 Flyway V1~V6 적용·validate 성공, 재실행 migration 0건.
- 두 번 모두 곡 384 / 메타데이터 384 / 가수 263 / 장르 28 / 곡-가수 384 /
  곡-장르 221 / 부분 분석 384 / 구간 1,968 / 미디어 0 / 음표 0.
- 곡·가수·분석·구간 ID 유지, 검토 상태 211/173건, 불완전 날짜 2건 보존 검증 통과.
- 첫 실행(Gradle·의존성 다운로드 포함) 1분 49초.
- 실패 회귀 검증: 비어 있지 않은 DB(테이블 21개), 중복 V6, 빈 적재 SQL을 각각 실패 처리하는 것을 확인했다.
- 운영 DB에는 migration/seed/import를 실행하지 않았다. 실제 GitLab 작업은 MR 병합 후 확인한다.

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
