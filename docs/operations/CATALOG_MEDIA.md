# 384곡 보컬 stem S3–MySQL–BE 연결

Jira: S15P21B110-228. 대상 배치: `section-384-v1`. 이 문서는 보컬만 남긴 stem을 다루며 원곡과 반주 MR은 포함하지 않는다.

## 검증된 입력

| 항목 | 값 |
|---|---|
| 버킷 / 리전 | `micon-b110-prod-apne2-20260909` / `ap-northeast-2` |
| S3 prefix | `catalog/정영주/section-384-v1/vocals/` |
| 보컬 객체 | 384개, 16,013,862,076바이트 |
| 전체 배치 | 보컬 384개 + 매핑·검증 파일 2개, 16,014,073,752바이트 |
| 식별자 | `song_metadata.catalog_track_id` 384개 |
| DB–S3 매핑 파일 SHA-256 | `fead18b83dbfaf1e7945043731a65487d435d91658dc5397794bcb8684c41b97` |

2026-09-10 인프라 재검증에서 운영 DB의 384개 `catalog_track_id`–`song_id` 쌍이 매핑과 전부 일치했다. 업로더 신원으로 S3 384개를 각각 HEAD해 크기와 SSE-S3 `AES256`도 일치했다. SHA-256은 HEAD/ETag로 검증할 수 없으며 정영주의 완료 보고에 기록된 전체 파일 검증을 인수한다.

## 운영 적용 결과 (2026-09-10 KST)

- 운영 MySQL 8.4.11에 Flyway V8~V10을 적용했다. 전체 V1~V10 validate가 성공했고 두 번째 migrate는 변경 0건이었다.
- 적재 전·후 덤프를 `/var/backups/micon/`에 `root:root 0600`으로 보관하고 각각 gzip 및 SHA-256 검증을 통과했다.
- `catalog_media_importer@172.18.0.1`에는 `song_metadata` SELECT와 `song_media` SELECT·INSERT만 부여했다. 비밀번호 파일은 `/etc/micon/secrets/catalog_media_importer_password`의 `root:root 0600`이다.
- 고정 SHA-256 매핑 384줄을 적재했다. 첫 실행은 `inserted=384`, 두 번째 실행은 `inserted=0`, 두 실행 모두 `s3_heads_verified=384`였다.
- 최종 DB는 `VOCAL_STEM/READY` 384건, 서로 다른 곡 384건, 총 16,013,862,076바이트다. 잘못된 prefix·고아 행·기본 stem 중복은 모두 0건이다.
- BE IAM `MiconCatalogMediaReadOnly`를 적용했다. 승인 보컬 객체 HEAD는 성공하고 배치의 매핑·체크섬 및 ListBucket은 403으로 거부됐다.
- BE 애플리케이션 배포와 내부 API가 발급한 URL의 실제 GET smoke는 아직 남아 있다.

## 저장 계약

- Flyway `V10`에서 `song_media.kind`에 `VOCAL_STEM`을 추가한다. `SOURCE_AUDIO`는 원곡, `INSTRUMENTAL`은 반주이므로 대신 사용하지 않는다.
- DB에는 S3 object key, MIME, 바이트, SHA-256만 저장한다. `s3://` URI나 만료되는 presigned URL은 저장하지 않는다.
- 한 곡의 기본 보컬은 `(song_id, kind='VOCAL_STEM', semitones=0)` 한 건이다.
- importer는 기존 행이 입력과 완전히 같을 때만 재실행을 성공시킨다. 다른 경로·해시·크기가 있으면 덮어쓰지 않고 롤백한다.
- 원곡·반주 MR·다음 배치는 새 승인과 별도 prefix·매핑으로 처리한다.

## 등록 절차

먼저 병합된 Flyway V1~V10을 적용하고 validate한다. 운영 DB 백업과 현재 `song_media` 보컬 0건을 확인한 뒤 저장소 밖의 승인 매핑 파일을 사용한다. 비밀번호는 명령행에 쓰지 않는다.

```bash
export MICON_CATALOG_BUCKET=micon-b110-prod-apne2-20260909
export MICON_CATALOG_REGION=ap-northeast-2
export MICON_CATALOG_DB_HOST=127.0.0.1
export MICON_CATALOG_DB_PORT=3306
export MICON_CATALOG_DB_NAME=micon
export MICON_CATALOG_DB_USER=catalog_media_importer
export MICON_CATALOG_DB_PASSWORD_FILE=/run/secrets/catalog_media_importer_password
export MICON_CATALOG_DB_CA_FILE=/run/secrets/mysql-ca.pem

python -m ai.catalog_media register \
  --mapping /secure/db-s3-id-mapping.jsonl \
  --mapping-sha256 fead18b83dbfaf1e7945043731a65487d435d91658dc5397794bcb8684c41b97
```

실제 register는 모든 S3 객체 HEAD 검증을 통과한 뒤 한 DB 트랜잭션으로 실행한다. DB 계정 템플릿은 `infra/mysql/catalog-media-importer-grants.sql`이며 `song_metadata` 조회와 `song_media` 조회·추가만 허용한다. 스키마 변경·DELETE·기존 미디어 UPDATE 권한은 없다.

완료 조건은 `registered=384`, 첫 실행 `inserted=384`, 두 번째 실행 `inserted=0` 및 `already_registered=true`다.

## BE 내부 제공

`GET /internal/catalog-media/vocal-stems/{songId}`에 `Authorization: Bearer <AI_SERVICE_TOKEN>`을 사용한다. 일반 회원 JWT로는 접근할 수 없다. 응답은 `Cache-Control: no-store`이며 DB 메타데이터와 최대 10분의 presigned GET URL을 반환한다.

BE IAM에는 기존 `voice-recordings/*` 읽기·쓰기·삭제와 분리된 인라인 정책 `MiconCatalogMediaReadOnly`를 적용한다. [정책 템플릿](../../infra/iam/backend-catalog-media-reader-policy.json)의 버킷만 실제 값으로 치환한다. 허용 범위는 이 배치의 `vocals/*` GetObject뿐이며 ListBucket, PutObject, DeleteObject와 다른 팀원·배치 경로는 허용하지 않는다.

IAM `PolicyDocument`는 printable ASCII만 허용하므로 템플릿은 `정영주`를 JSON 유니코드 이스케이프(`\uC815\uC601\uC8FC`)로 기록한다. 퍼센트 인코딩 ARN은 정책 생성은 되지만 실제 한글 object key와 매칭되지 않으므로 사용하지 않는다.

운영 환경 변수:

```dotenv
CATALOG_MEDIA_ALLOWED_PREFIX=catalog/정영주/section-384-v1/vocals/
CATALOG_MEDIA_DOWNLOAD_URL_TTL=10m
```

URL 발급 성공만으로 다운로드 성공을 대신하지 않는다. 후보 BE에서 실제 URL GET·Content-Length·SHA-256 표본 검증을 완료한 뒤 서비스 연결 완료로 표시한다.
