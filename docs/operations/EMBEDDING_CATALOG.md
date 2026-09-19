# 곡 음색 임베딩 S3 적재·활성화 계약

관련 이슈: [S15P21B110-227](https://ssafy.atlassian.net/browse/S15P21B110-227).
범위는 **곡 카탈로그 ECAPA·Singer-BYOL 임베딩**이다. 사용자 녹음/사용자 분석 embedding 컬럼을 옮기는 작업이 아니다.

## 1. 저장소와 역할

| 대상 | 저장/권한 |
|---|---|
| 곡별 segment 임베딩·구간 정보·완료 manifest | 개인 계정 비공개 S3 `catalog/<owner>/<batch-id>/` |
| 곡 ID 매핑·모델 계약·파일 SHA-256·활성 revision | SSAFY MySQL, Flyway V7의 4개 테이블 |
| 추천 연산 | AI 워커가 S3 파일을 한 번 내려받아 검증한 로컬 캐시/메모리 사용 |
| 지유림 — 임베딩 업로더 | `catalog/지유림/<batch-id>/`의 조건부 업로드·재시도 검증 읽기. DB 쓰기 권한 불필요 |
| 정영주 — 곡 데이터 담당 | 곡 메타데이터 DB 적재 + `catalog/정영주/<batch-id>/`의 음원·MR 제거 음원 업로드 |
| 인프라 — 임베딩 등록·활성화 | 카탈로그 S3 읽기 + 임베딩 등록 전용 DB importer 권한 |
| AI 워커 | 카탈로그 S3 읽기 + BE 내부 snapshot 조회, DB 쓰기 권한 없음 |
| BE | 기존 AI 서비스 토큰을 검증한 뒤 활성 메타데이터만 반환. 카탈로그 S3 권한 불필요 |

S3는 벡터 검색 엔진이 아니다. 추천 요청마다 모든 곡을 S3에서 읽지 않는다.
MySQL에 NPY/BLOB/큰 벡터 JSON이나 만료 presigned URL을 넣지 않는다.
기존 `voice-recordings/` IAM과 삭제 정책, 버킷 Versioning OFF 설정은 변경하지 않는다.

S3 manifest는 분석을 완료한 전체 원본 집합이고, `song.status='ACTIVE'`인 DB 곡이 현재 서비스 집합이다.
BE snapshot과 AI 인덱스는 S3 원본 중 ACTIVE 곡에 대응하는 부분집합만 사용한다.
`track_count`는 snapshot의 활성 곡 수, `source_track_count`는 불변 S3 batch의 전체 곡 수다.
ACTIVE 집합이 바뀌어도 S3 객체나 기존 batch metadata를 덮어쓰지 않는다.

배치 흐름: **prepare → dry-run → upload → validate → register → activate → AI cache refresh**.
S3 업로드만으로 DB 등록/추천 전환이 일어나지 않는다. 모든 활성화는 인프라가 명시적으로 수행한다.

정영주의 **곡 메타데이터 적재**와 인프라의 **임베딩 참조 등록·활성화**는 별도 작업이다.
이 문서의 `catalog_importer` 계정은 후자용이며 정영주에게 전달할 곡 메타데이터 적재 계정이 아니다.
정영주는 검토된 219 SQL에 필요한 곡 관련 테이블 권한을 별도 계정으로 받으며, 지유림에게는 DB 적재 권한을 주지 않는다.
두 사람의 AWS 신원/키는 분리한다. 각자 본인 prefix만 사용하고 상대방 키·BE 키를 공유하지 않는다.

## 2. 최초 운영 준비 — 이번 코드 변경과 별개로 실행할 항목

1. 검토/병합한 BE 버전으로 Flyway **V1~V7**을 적용한다. 기존 V1~V6 체크섬은 변경하지 않는다.
2. 인프라의 스키마 준비 후 정영주가 곡 메타데이터를 검증된 219 적재 절차로 먼저 넣는다. 운영 DB가 비어 있으면 임베딩 등록은 실패해야 정상이다.
3. 지유림의 [임베딩 업로더 IAM](../../infra/iam/catalog-uploader-policy.json)에 버킷 이름과 `<OWNER>=지유림`을 치환해 개인 신원에 적용한다. 광범위한 기존 정책을 함께 붙이지 않는다. 정영주의 음원 업로드 정책은 [일반 카탈로그 안내](S3_CATALOG_UPLOAD_GUIDE.md)와 분리한다.
4. importer/AI에는 [reader IAM](../../infra/iam/catalog-reader-policy.json)을 각각 별도 신원에 적용한다. 필요하면 허용 owner 목록으로 더 좁힌다.
5. DBA가 제한된 호스트의 importer 계정을 생성하고 [최소 DB grants](../../infra/mysql/catalog-importer-grants.sql)를 검토·적용한다. 비밀번호는 접근 제한된 파일로 별도 전달한다.
6. 워커에 지속되는 캐시 디스크와 승인한 모델 계약 파일을 준비한다. BE와 같은 `AI_SERVICE_TOKEN`을 비밀 설정으로 주입한다.
7. 테스트용 배치로 실제 IAM 허용/거부, BE 조회, 워커 refresh를 확인한 뒤 실제 배치를 승인한다.

코드/모의 S3 테스트 통과가 **실제 IAM 발급·운영 migration·실제 483곡 적재 완료**를 뜻하지 않는다.
모델 생성/추천 서버 배포는 AI 담당자의 기존 작업을 사용하고, BE 사용자 추천 입력 구성(214)은 별도 기능이다.

## 3. 팀원 산출물 계약

곡 ID는 반드시 운영 `song_metadata.catalog_track_id`와 정확히 같아야 한다.
TJ/POOL/팀원 ID를 제목·파일명 추측이나 CSV 행 순서로 맞추지 않는다.
222의 483곡, 223의 665행, 219 SQL의 384곡은 서로 같은 집합이라는 보장이 없다.
미등록 ID 하나라도 있으면 전체 DB 등록을 취소한다. 운영에 없는 곡은 메타데이터를 먼저 보완하거나 승인된 제외 목록으로 새 CSV를 만든다.

입력 CSV(UTF-8; 상대 파일 경로는 CSV 위치 기준):

```csv
catalog_track_id,title,artist,ecapa_path,singer_byol_path,segments_path
<실제-카탈로그-ID>,곡 제목,가수,embeddings/track.ecapa.npy,embeddings/track.singer_byol.npy,embeddings/track.segments.json
```

- NPY: pickle 없는 C-order little-endian float32, `(1..9, 192)`와 `(1..9, 1000)`. 두 모델의 구간 수/순서는 동일해야 한다.
- segments.json: `models`에 dimension/checkpoint_sha256/preprocessing, `segments`에 start_seconds/end_seconds가 필요하다. 각 구간은 4초다.
- 모델 계약의 선택적 code_revision이 있으면 원본 segments의 checked_out_code_revision도 일치해야 한다.
- [모델 계약 예시](../../ai/embedding_catalog/models.example.json)는 기존 recommend-algo 체크포인트 기준이다. **실제 파일의 원본 provenance와 비교한 뒤** 업로더·워커가 동일 파일을 사용한다. 파일에 맞추려고 검증 값을 임의로 바꾸지 않는다.
- prepare가 각 row를 L2 정규화하고 공개 불필요한 원본 로컬 경로/device 필드를 제거한다.
- 모델 checkpoint/전처리가 바뀌면 새 모델 계약과 새 batch-id로 인수한다.
- batch-id는 소문자 영문·숫자로 시작하는 1~64자의 `a-z0-9._-`. 예: `timbre-20260909-v1`. 임베딩 owner는 `지유림`이다.
- 곡당 3개 파일 + 전체 manifest 1개. 전체 1~5000곡, manifest ≤8 MiB, 개별 파일 ≤512 KiB. 원본 음원·모델 가중치는 이 업로더에 넣지 않는다.

## 4. 로컬 준비와 업로드 (PowerShell)

저장소 루트에서 Python 3.13 환경을 만든다. 산출물은 Git 밖 또는 무시되는 `catalog-releases/`에 둔다.

```powershell
py -3.13 -m venv .venv-embedding-catalog
.\.venv-embedding-catalog\Scripts\python.exe -m pip install -r ai/embedding_catalog/requirements.txt
.\.venv-embedding-catalog\Scripts\python.exe -m ai.embedding_catalog prepare --csv C:/catalog/tracks.csv --models C:/catalog/models.json --owner 지유림 --batch-id timbre-20260909-v1 --output catalog-releases/timbre-20260909-v1
.\.venv-embedding-catalog\Scripts\python.exe -m ai.embedding_catalog upload --directory catalog-releases/timbre-20260909-v1 --dry-run
```

prepare 출력 폴더는 새 경로여야 한다. dry-run은 AWS 인증 없이 로컬 경로·크기·SHA-256을 검사한다.
실제 업로드는 기존 안내대로 개인 AWS 프로필 신원을 확인한 뒤 실행한다.

```powershell
$env:AWS_PROFILE = 'micon-catalog-upload'
$env:MICON_CATALOG_BUCKET = 'micon-b110-prod-apne2-20260909'
$env:MICON_CATALOG_REGION = 'ap-northeast-2'
$env:MICON_CATALOG_PREFIX = 'catalog/지유림/'
.\.venv-embedding-catalog\Scripts\python.exe -m ai.embedding_catalog upload --directory catalog-releases/timbre-20260909-v1
```

출력의 batch_id, manifest_key, manifest_sha256, objects와 실패/제외 목록을 인프라에 전달한다.
키 자체나 원본 녹음은 전달하지 않는다.

임베딩 배치는 **`aws s3 sync`를 사용하지 않는다**. 이 도구는 AES256 암호화 + `If-None-Match: *` 조건부 쓰기로 기존 객체를 보호하고 manifest를 마지막에 올린다.
같은 바이트 재시도는 허용하되 같은 key에 다른 내용은 거부한다. 실패 시 같은 로컬 패키지로 재시도할 수 있다.
내용을 수정하려면 새 batch-id를 만든다. 기존 배치를 삭제/덮어써서 고치지 않는다.
조건부 쓰기 권한의 근거: [AWS S3 공식 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes-enforce.html).

## 5. 검증·등록·활성화 (인프라 전용)

리눅스 예시. 먼저 가상환경을 활성화하고 저장소 루트로 이동한다.
AWS는 importer용 카탈로그 reader 신원, DB는 전용 importer를 사용한다.
DB는 서버 내부 Docker 네트워크에서 접근하거나 로컬 SSH 터널을 사용한다. 인터넷에 3306을 열지 않는다.

```bash
export AWS_PROFILE=micon-catalog-reader
export MICON_CATALOG_BUCKET=micon-b110-prod-apne2-20260909
export MICON_CATALOG_REGION=ap-northeast-2
export MICON_CATALOG_PREFIX=catalog/
export MICON_CATALOG_DB_HOST=127.0.0.1
export MICON_CATALOG_DB_PORT=13306
export MICON_CATALOG_DB_NAME=micon
export MICON_CATALOG_DB_USER=catalog_importer
export MICON_CATALOG_DB_PASSWORD_FILE=/secure/catalog-importer-password

python -m ai.embedding_catalog validate --manifest-key 'catalog/지유림/timbre-20260909-v1/manifest.json' --manifest-sha256 '<담당자가-전달한-SHA256>'
python -m ai.embedding_catalog register --manifest-key 'catalog/지유림/timbre-20260909-v1/manifest.json' --manifest-sha256 '<담당자가-전달한-SHA256>'
```

13306은 이미 준비한 SSH 터널의 로컬 포트 예시이며 자동으로 터널을 만들지 않는다.
원격 DB에 직접 접속하는 환경은 검증된 CA 파일을 `MICON_CATALOG_DB_CA_FILE`로 지정한다. 평문 인터넷 DB 연결은 금지한다.

register는 모든 S3 파일의 SHA-256/shape/모델 계약을 다시 검사하고, 정확한 ID 매핑과 metadata를 한 트랜잭션으로 넣는다.
동일한 batch+manifest 재등록은 멱등적이다. 등록 실패 시 활성 배치는 변하지 않는다.

활성 revision을 확인한다. 최초에는 활성 배치가 없어 snapshot/API가 실패하므로 DBA가 초기 행을 확인한다.

```sql
SELECT channel, batch_id, revision FROM embedding_catalog_active;
```

최초 revision=0을 확인한 경우:

```bash
python -m ai.embedding_catalog activate --batch-id timbre-20260909-v1 --expected-revision 0 --actor 허병우
python -m ai.embedding_catalog snapshot --output /secure/catalog-active.json
```

activate는 S3 전체를 재검증한 뒤 활성 행을 잠근다. 현재 revision이 다르면 **덮어쓰지 않고 실패**한다.
운영자가 최신 snapshot과 변경자를 확인하고 다시 승인해야 한다. 변경 시 event에 이전/새 batch, 증가한 revision, actor를 남긴다.

롤백 예시: 현재 v2/revision=2에서 이전에 등록한 v1으로 되돌리면 revision은 3이 된다.

```bash
python -m ai.embedding_catalog activate --batch-id timbre-20260909-v1 --expected-revision 2 --actor rollback-by-infra
```

DB 이전 배치와 S3 이전 객체를 유지해야 롤백할 수 있다. 워커 refresh까지 확인해야 전환 완료다.

## 6. BE snapshot과 AI 캐시

`GET /internal/embedding-catalog/active`, `Authorization: Bearer <AI_SERVICE_TOKEN>`.
HTTPS로 접근한다. 사용자 JWT나 AWS Access Key는 인증 수단이 아니다.
응답 `ApiResponse.data`는 schema_version/channel/revision/batch_id/bucket/region/manifest_key/manifest_sha256/manifest_bytes/models/source_track_count/track_count/tracks를 포함한다.
각 track은 song_id, catalog_track_id, 두 모델의 key/sha256/bytes/rows/dimension을 가진다.
`tracks`에는 ACTIVE 곡만 포함되며 `track_count`는 그 배열 길이와 같다. S3 manifest는 이 집합의 상위 집합일 수 있다.
활성 곡에 모델 누락이 있거나 활성 배치가 없으면 503 `EMBEDDING_CATALOG_UNAVAILABLE`, 인증 실패는 401이다. 응답 캐시는 no-store다.

워커는 DB 비밀번호 없이 reader AWS 설정과 AI_SERVICE_TOKEN만 가진다.

```bash
python -m ai.embedding_catalog refresh --backend-url https://<BE-HOST>/internal/embedding-catalog/active --models /secure/models.json --cache /var/lib/micon/catalog-cache
```

프록시가 경로 앞에 `/api`를 붙이는 배포라면 실제 라우팅에 맞춰 URL을 지정한다.
파일별 checksum·배열·모델·DB 매핑을 전부 검증한 후에만 `current.json`을 원자적으로 바꾼다.
워커는 manifest 전체를 검증하되 snapshot에 포함된 ACTIVE 곡의 파일만 내려받아 인덱스를 만든다.
모델 계약 불일치, 다운로드 실패, 손상, 낮은 revision이면 예외를 내고 기존 메모리/포인터를 보존한다.
워커 재시작 시 `CatalogCache.restore()`는 네트워크 없이 기존 캐시 전체를 재검증한다. 유효한 이전 캐시도 없으면 추천을 준비되지 않은 상태로 유지해야 하며 임의의 빈 추천으로 성공 처리하지 않는다.

서비스 연동은 워커 시작 시 restore(캐시가 있으면) → 주기/운영 신호에 따라 snapshot fetch/refresh → 검증된 객체로 전환 순서다.
같은 프로세스의 `cache.current.embeddings(song_id, "ecapa")`로 읽으면 요청마다 S3 IO가 발생하지 않는다.
동기화 실패는 로그/알림으로 남기되 이미 로드한 정상 카탈로그를 유지한다. 로그에 토큰·서명 URL·원본 음성을 넣지 않는다.
프로세스별 메모리는 각각 refresh해야 한다. 로컬 캐시 writer는 OS 파일 잠금으로 직렬화한다.
명시적인 DB 롤백은 **더 높은 revision**의 이전 batch로 처리한다.

### 곡 비활성화 절차

카탈로그 곡을 서비스에서 제외할 때는 `song` 행과 `song_embedding`을 삭제하지 않는다.
한 트랜잭션에서 대상 곡을 `INACTIVE`로 바꾸고 활성 카탈로그 revision을 1 증가시킨 뒤,
같은 batch와 새 revision을 `embedding_catalog_event`에 기록한다. 이 revision 증가는 같은 batch 안에서
서비스 가능한 곡 집합이 바뀌었다는 의미다. AI의 카탈로그 연결은 요청 또는 상태 조회 시 최대 30초마다
BE snapshot을 다시 확인하므로 프로세스 재시작 없이 새 ACTIVE 집합을 반영한다.

이미 곡과 embedding을 물리 삭제한 경우에는 S3를 수정하거나 `embedding_catalog_batch.track_count`를
줄이지 않는다. ACTIVE 곡과 남은 두 모델 embedding이 완전한지 확인한 뒤 BE/AI 부분집합 지원 버전을
배포하고 revision만 증가시킨다. `embedding_catalog_batch.track_count`는 S3 원본 수를 유지한다.

### 기존 추천 알고리즘 인덱스

AI 환경에 기존 `timbre_recommender` 패키지가 설치되어 있으면 위 refresh에
`--recommender-output /var/lib/micon/indexes/<새-release>`를 추가할 수 있다.
저장소의 알고리즘 코드/가중치를 이 작업에서 복제하거나 스코어링 공식을 변경하지 않는다.

어댑터는 검증된 캐시를 기존 `build_catalog_index` 형식으로 내보내고, 결과 track_id는 **MySQL song_id의 문자열**로 만든다.
원본 catalog_track_id와 batch-id는 track metadata에 보존한다.
기존 `CatalogIndex.load(path, verify_hashes=True)`로 인덱스를 검증한 뒤 추천 서비스의 인덱스 참조를 교체한다.
인덱스 생성 실패 시 기존 서비스 인덱스를 유지한다. 캐시 refresh와 별개인 이 서비스 내 전환/스케줄링은 AI 서버 기동 코드에서 연결해야 한다.
사용자 쿼리 임베딩도 동일한 체크포인트·전처리 계약으로 생성해야 한다.

## 7. 검증과 운영 경계

```bash
python -m pip install -r ai/embedding_catalog/requirements-test.txt
python -m pytest ai/embedding_catalog/tests -q
cd backend
./gradlew test
```

일반 로컬 실행에서는 격리 MySQL과 외부 추천 패키지 테스트를 skip한다.
CI `embedding-catalog-tests`는 임시 MySQL 8.4.11 + moto S3를 사용해 등록/활성화/롤백을 검증한다.
테스트 전용 환경변수 MICON_MYSQL_TEST/MICON_TEST_DB_PASSWORD를 운영 작업에 재사용하지 않는다.
실제 추천 패키지 검증은 `MICON_RECOMMENDER_SOURCE`에 설치된 model-server 경로를 주고 실행한다.

실제 인수 체크: 개인 IAM 허용/거부 → 모델/ID 집합 확정 → 운영 V7 → 메타데이터 선적재 → 배치 등록 → 활성화 → 워커 refresh → 동일 song_id 추천 결과 확인.
미사용 배치 정리는 참조/롤백 보존 기간을 확인한 별도 승인 작업으로 한다. S3 Lifecycle이나 DB DELETE를 자동 추가하지 않는다.

## 8. 2026-09-09 구현 검증 기록

- 시작/최종 확인 develop: `7debcc41189365e65798cc9ac08ecc6675bbc87f`. 기존 V1~V6 내용은 변경하지 않았다.
- BE 전체 JUnit **96개 성공, 실패/skip 0**. 새 API 인증 5개·실제 JDBC snapshot 3개를 포함한다. H2 MySQL mode에서 실제 V1~V7 Flyway와 JPA validate를 사용했고 테스트 JVM 256 MiB로 재확인했다.
- SSAFY 서버의 **별도 임시 MySQL 8.4.11 컨테이너 + Python 3.13/moto S3**: **42개 성공, 추천 패키지 테스트 1개 skip**. 운영 DB/실제 S3에는 연결하지 않았다.
- 최소 권한 importer로 register → activate → snapshot 성공 및 임베딩 UPDATE/DELETE·곡 INSERT·DDL 거부를 확인했다. 배치 조회는 FOR SHARE, 활성 포인터 변경만 FOR UPDATE를 사용한다.
- Windows의 별도 환경에서는 **35개 성공**, MySQL 전용 8개 skip. 여기에는 실제 `recommend-algo`의 `a42d01795734abc304b9c2fdb5a3a2a1e435ce87` 코드를 읽기 전용 추출하여 수행한 추천 인덱스 호환성 테스트 1개가 포함된다. 스코어링/모델 추론 품질 시험은 아니다.
- 기존 런타임 계약 7개 + 배포 health 16개 + MySQL 운영 정책 6개 성공.
- GitLab CI Lint: valid=true, errors/warnings 없음, 3개 job 인식. 보호된 develop에서만 실행하므로 새 MR 브랜치의 GitLab 파이프라인 성공을 주장하지 않는다. 병합 후 실제 파이프라인 결과는 별도로 확인한다.

운영 IAM 적용/키 발급, 운영 Flyway 및 실제 데이터 등록, AI 서버 기동 코드의 캐시·인덱스 전환 연결은 이 검증으로 수행되지 않았다.
