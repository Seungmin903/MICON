# SSAFY 서버 + 개인 AWS S3 운영 계약

Jira: S15P21B110-210. 결정일: 2026-09-09. 기준 코드: develop `fcc00d2`. 서버는 SSAFY 제공 자원을 유지하고, 파일 저장소만 인프라 담당자의 개인 AWS 계정에 만든다. 이 문서는 설정·인수 절차이며 **개인 버킷 생성, 키 발급, 서버 주입, 실제 업로드는 아직 완료하지 않았다.**

## 1. 구성과 담당 범위

| 구성 요소 | 위치 / 인증 | 역할 |
|---|---|---|
| Nginx·Spring BE | SSAFY 서버 `j15b110.p.ssafy.io` | 앱 API, 인증·인가, 파일 업로드·삭제·다운로드 URL 발급 |
| MySQL | SSAFY 서버의 비공개 Docker 네트워크 | 사용자·곡·녹음 메타데이터. DB 포트 외부 공개 없음 |
| S3 | 개인 AWS 계정 / 서울 `ap-northeast-2` | 비공개 객체 저장. 실제 버킷 이름·계정 ID는 생성 시 확정 |
| BE의 AWS 인증 | 개인 계정의 전용 `micon-backend-prod` IAM 사용자 | 해당 버킷의 `voice-recordings/`만 읽기·쓰기·삭제 |
| 팀원 데이터 업로드 | 개인 계정의 별도 업로더 권한 / 개인별 프로필 | 지정된 `catalog/` 경로에 로컬 데이터 업로드 |
| Android·AI 분석 요청 | BE가 발급한 만료 있는 presigned GET URL | AWS 키 없이 허용된 객체 다운로드. AI 배포 위치는 별도 확정 |

앱 → HTTPS BE → MySQL(메타데이터) / 개인 S3(파일)로 나눈다. 파일을 S3에 올리는 것은 서버 프로그램 배포나 DB import와 별개다. 곡 CSV·음원 등을 올린 뒤 DB에 연결하려면 BE/AI 담당자가 object key와 곡 식별자 매핑, 검증, 적재 절차를 준비해야 한다. 장기 참조에는 object key를 저장하고 만료되는 presigned URL을 영구 주소로 저장하지 않는다.

SSAFY 서버와 S3의 소유 계정이 달라도 접속할 수 있다. 이번 방식에서는 요청을 서명하는 IAM 사용자와 버킷이 모두 개인 계정 소속이다. SSAFY 역할에 교차 계정 권한을 주거나 개인 계정 역할을 SSAFY 인스턴스에 직접 붙이는 구성이 아니다. SSAFY의 기존 `AmazonLightsailInstanceRole`을 수정하거나 공유하지 않는다. 추후 양쪽 계정 관리자의 협조가 가능하면 임시 자격 증명 방식으로 전환을 검토한다. [AWS 계정 간 권한 평가](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies-cross-account-resource-access.html)

2026-09-09 서버 조회에서 기본 AWS 신원은 SSAFY 역할이었고 버킷 전체 목록 조회가 거부되었다. 이는 그 요청의 권한 부족이며, 모든 S3 동작이 불가능하다는 증명은 아니다. 개인 자원 사용은 사용자의 선택에 따른 운영 결정이다.

## 2. 인프라 담당자가 준비하고 공유할 정보

| 항목 | 전달 대상 / 내용 |
|---|---|
| 개인 AWS 계정 ID, 비용·소유 책임자 | 인프라 운영 기록에 실제 값 확정. 루트 로그인 공유 금지 |
| 실제 버킷 이름, 리전 | BE·AI·업로더에게 공유. 이 문서의 `<BUCKET_NAME>`을 확정값으로 대체 |
| 파일 경로 | BE `voice-recordings/`, 초기 카탈로그 `catalog/<batch-id>/` |
| 업로더 인증 | 팀원별 별도 자격 증명 또는 임시 세션을 안전한 경로로 전달. BE 키 재사용 금지 |
| BE 런타임 설정 | 아래 프로필 이름·컨테이너 경로·환경변수를 배포 담당자에게 전달 |
| 데이터 계약 | 허용 파일, 용량, 데이터 사용 권한, 곡 ID↔object key 목록, DB 적재 담당자 |
| 운영 정책 | 비용 알림 수신자·금액, 보관/삭제, 키 회전, 프로젝트 종료·인수 책임자 |

버킷 이름·리전만 알려주는 것으로는 업로드할 수 없다. 해당 경로에 대한 인증과 쓰기 권한, HTTPS 통신도 필요하다. 키 원문을 Git·MR·채팅·스크린샷·로그·APK에 넣지 않는다. SSH PEM, DB 암호, JWT 키, Android 서명키, AWS 키는 서로 별개다.

## 3. 개인 계정 버킷 및 IAM 설정

아래는 실행 전 검토할 설정이다. 이미 존재하는 버킷의 정책·버전 관리·수명 주기를 통째로 덮어쓰지 않는다. 개인 계정 관리자가 계정 ID를 확인한 뒤 전용 버킷을 생성한다.

- 리전 `ap-northeast-2`. SSAFY 서버에서 관측한 리전과 일치한다.
- 계정/버킷의 Block Public Access 네 항목 모두 켬. 정적 웹사이트와 public-read ACL 사용 안 함.
- Object Ownership은 Bucket owner enforced(ACL 사용 안 함), 기본 암호화는 SSE-S3(`AES256`). SSE-KMS를 쓰려면 별도 KMS 권한·비용 검토가 필요하다.
- 이 신규 녹음 버킷은 버전 관리를 켜지 않는다. 현재 코드의 DeleteObject는 버전 관리 버킷에서 과거 버전을 영구 삭제하지 않으므로 기존 버전 관리 버킷을 재사용하지 않는다. 이미 버전 관리를 사용한 버킷에서 일시 중지만 해도 과거 버전은 남는다.
- 자동 수명 주기로 정상 녹음을 임의 삭제하지 않는다. 보관/동의·삭제 요구와 맞춘 별도 정책이 필요하다. 미완료 multipart 업로드 정리는 별도로 검토할 수 있다.

보안 설정 근거: [S3 보안 모범 사례](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html), [버전 관리와 삭제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html).

### BE 전용 사용자 정책

개인 계정에 콘솔 로그인 없는 전용 `micon-backend-prod` IAM 사용자를 만들고 아래 최소권한 정책을 연결한다. 루트 키나 `AmazonS3FullAccess`는 사용하지 않는다. `<BUCKET_NAME>`은 실제 버킷 이름으로 치환한다. 경로를 바꾸면 환경변수와 정책을 함께 바꿔야 한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadBucketLocation",
      "Effect": "Allow",
      "Action": "s3:GetBucketLocation",
      "Resource": "arn:aws:s3:::<BUCKET_NAME>"
    },
    {
      "Sid": "ManageVoiceRecordings",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::<BUCKET_NAME>/voice-recordings/*"
    }
  ]
}
```

현재 `S3VoiceStorage`의 업로드·재생/AI 분석용 URL·삭제에 대응한다. BE에 전체 버킷 열람, 버킷 생성·삭제, `catalog/` 접근 권한은 주지 않는다. Presigned URL 생성은 서명 작업이므로 URL 발급 성공만으로 객체 접근 권한이 검증되지는 않는다.

### 카탈로그 업로더 정책 (BE와 분리)

팀원별 업로더 신원에 다음 정책을 적용한다. DB 덤프·음성 개인정보를 임의로 이 공통 경로에 올리지 않는다. 담당자별 접근 분리가 필요하면 `catalog/<담당경로>/`로 정책을 더 좁힌다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadBucketLocation",
      "Effect": "Allow",
      "Action": "s3:GetBucketLocation",
      "Resource": "arn:aws:s3:::<BUCKET_NAME>"
    },
    {
      "Sid": "ListCatalogOnly",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<BUCKET_NAME>",
      "Condition": {"StringLike": {"s3:prefix": ["catalog/", "catalog/*"]}}
    },
    {
      "Sid": "UploadAndReadCatalog",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
      "Resource": "arn:aws:s3:::<BUCKET_NAME>/catalog/*"
    }
  ]
}
```

`GetBucketLocation`은 버킷 ARN에 적용하고 `s3:prefix` 조건을 붙이지 않는다. prefix 조건은 ListBucket에만 적용한다. 삭제 권한이 없어도 PutObject로 같은 key를 덮어쓸 수 있으므로 신규 batch 경로를 사용한다. [S3 API별 정책 동작·리소스](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html)

위 허용은 같은 개인 계정의 IAM 정책으로 부여한다. 버킷을 공개하거나 SSAFY 계정/역할을 버킷 정책에 Allow로 추가할 필요는 없다. 별도 명시적 Deny·조직 정책 등이 있으면 해당 제한도 확인한다. HTTPS만 허용하려면 다음 Deny 문장을 기존 버킷 정책과 병합해 검토한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "DenyInsecureTransport",
    "Effect": "Deny",
    "Principal": "*",
    "Action": "s3:*",
    "Resource": ["arn:aws:s3:::<BUCKET_NAME>", "arn:aws:s3:::<BUCKET_NAME>/*"],
    "Condition": {"Bool": {"aws:SecureTransport": "false"}}
  }]
}
```

`Principal: "*"`는 이 Deny 문장에서 HTTP 접근을 막는 대상이며 공개 Allow가 아니다.

## 4. SSAFY 서버에 BE 인증 연결

현재 BE의 AWS SDK for Java 2.x 클라이언트와 presigner는 기본 자격 증명 체인을 사용한다. 전용 shared credentials 파일을 지정하면 기존 S3 코드를 바꾸지 않고 개인 계정 신원으로 요청할 수 있다. Spring profile과 AWS profile은 별개다.

인프라 담당자가 키를 안전하게 전달받아 저장소 밖 서버 파일 `/etc/micon/secrets/aws_credentials`에 다음 형식으로 저장한다. 아래는 자리표시자이며 실제 키가 아니다. 키 값을 명령행 인자·shell history에 남기지 않는다.

```ini
[micon-backend]
aws_access_key_id = <BACKEND_ACCESS_KEY_ID>
aws_secret_access_key = <BACKEND_SECRET_ACCESS_KEY>
```

운영 이미지의 실행 UID/GID를 먼저 확인한다. root 프로세스라면 파일 `root:root 0600`, non-root라면 해당 프로세스가 읽을 수 있는 제한된 소유자/그룹과 모드(예: `root:<APP_GID> 0440`)로 관리한다. `chmod 644/777`로 해결하지 않는다. non-root 컨테이너에서 root 소유 `0600` 파일이 읽힌다고 가정하지 않는다. 호스트 디렉터리 접근도 운영 관리자만 허용한다.

아래는 향후 BE Compose 서비스에 병합할 **부분 예시**다. 완성된 운영 Compose나 현재 배포 결과가 아니다. `VOICE_STORAGE_BUCKET`과 DB/JWT/AI/메일 등 나머지 설정은 별도로 주입한다.

```yaml
services:
  backend:
    environment:
      AWS_SHARED_CREDENTIALS_FILE: /run/secrets/aws_credentials
      AWS_PROFILE: micon-backend
      AWS_EC2_METADATA_DISABLED: "true"
      VOICE_STORAGE_BUCKET: ${VOICE_STORAGE_BUCKET:?Set the personal S3 bucket name}
      VOICE_STORAGE_REGION: ap-northeast-2
      VOICE_STORAGE_PREFIX: voice-recordings
      VOICE_STORAGE_PLAYBACK_URL_TTL: 10m
      VOICE_STORAGE_MAX_FILE_BYTES: "26214400"
    volumes:
      - type: bind
        source: /etc/micon/secrets/aws_credentials
        target: /run/secrets/aws_credentials
        read_only: true
        bind:
          create_host_path: false
```

파일은 이미지에 COPY하지 않는다. 로컬 파일 기반 Compose secrets의 uid/gid/mode 지정으로 호스트 파일 권한이 자동 변경된다고 가정하지 않는다. 읽기 전용 마운트와 실제 실행 사용자로 읽을 수 있는지를 함께 검증한다. [Compose 파일 secrets 제약](https://docs.docker.com/reference/compose-file/services/#secrets)

환경변수 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_SESSION_TOKEN`, Java `aws.accessKeyId`/`aws.secretAccessKey` 및 web identity 설정은 shared profile보다 먼저 선택될 수 있다. BE에 예전 인증 설정이 남아 있지 않게 확인한다. `AWS_EC2_METADATA_DISABLED=true`로 SSAFY EC2 메타데이터 자격 증명으로의 fallback을 막고, 다른 컨테이너 자격 증명 설정도 혼용하지 않는다. 이 환경변수들은 BE 서비스에만 지정하며 호스트·다른 서비스의 기본 인증은 변경하지 않는다. [Java SDK 인증 순서](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)

BE 컨테이너에는 S3·AI 등 외부 HTTPS/DNS 통신 경로가 필요하다. DB 전용 internal 네트워크만 연결해 인터넷이 차단되지 않게 설계한다. S3를 위해 서버 인바운드 포트나 MySQL 3306을 열 필요는 없다. 브라우저 직접 S3 업로드는 이번 계약에 없으며, Android 네이티브/BE/AI 통신만을 위해 와일드카드 S3 CORS를 추가하지 않는다.

## 5. 팀원 로컬 데이터 업로드와 DB 적재

1. 인프라가 실제 버킷·리전·대상 prefix·팀원별 인증을 전달한다. 업로더 PC에 AWS CLI를 준비하고 별도 프로필 `micon-catalog-upload`를 등록한다. 루트 계정이나 BE 런타임 키를 공유하지 않는다.
2. `aws sts get-caller-identity --profile micon-catalog-upload`로 개인 계정의 허용된 업로더 신원인지 확인한다. 계정 확인만으로 S3 쓰기 권한이 검증되지는 않는다.
3. 업로드할 파일만 전용 폴더에 모아 권리·민감정보·예상 용량을 확인한다. 다음 PowerShell 예시의 로컬 폴더와 버킷을 확정값으로 바꾸고 dry-run 결과를 검토한다.

```powershell
aws s3 sync 'C:\micon\catalog-export' 's3://<BUCKET_NAME>/catalog/import-20260909/' --profile micon-catalog-upload --region ap-northeast-2 --no-follow-symlinks --dryrun
```

4. 대상·파일 목록·용량을 확인한 후 같은 명령에서 `--dryrun`만 제거해 업로드한다. `--delete`는 사용하지 않는다. 기존 key 덮어쓰기를 피하려면 매번 새 batch-id를 사용한다. dry-run은 쓰기 권한이나 업로드 성공을 보장하지 않는다.
5. 파일 수·크기·별도 체크섬 manifest와 실제 다운로드 표본을 확인한다. multipart 객체의 ETag를 항상 MD5라고 간주하지 않는다. 완료 목록은 `곡 ID, object key, 파일 크기, 체크섬, 데이터 버전`으로 BE/AI에 전달한다.
6. BE/AI 담당자가 검토된 스키마·적재 도구로 SSAFY MySQL에 메타데이터를 적재하고 API 조회·파일 다운로드 연결을 검증한다. SQL 실행·기존 DB 덮어쓰기·자동 import는 이 문서 변경에 포함하지 않는다.

`catalog/`는 초기 적재용 경로다. 현재 BE 런타임에는 이 경로의 읽기 권한이 없고 자동 적재/음원 제공을 구현했다고 가정하지 않는다. 실제 음원 제공 주체·필요 권한은 담당자와 결정 후 별도 리뷰한다. [AWS CLI sync](https://docs.aws.amazon.com/cli/latest/reference/s3/sync.html)

## 6. 실제 연결 완료 조건

- [ ] 개인 계정·실제 버킷 이름 확정, 비공개·암호화·소유권·IAM 정책 적용.
- [ ] 비용 알림 금액/수신자와 보관·프로젝트 종료 책임 확정. AWS Budgets 알림은 자동 과금 차단이 아니다. 저장량·요청·인터넷 전송·multipart 잔여 데이터 비용을 확인한다. [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html)
- [ ] BE 실행 사용자로 마운트 파일 읽기 성공, 런타임 profile 신원이 개인 계정 전용 사용자임을 확인. 호스트 기본 프로필의 STS 결과로 대신하지 않음.
- [ ] 신규 합성 테스트 녹음 업로드 → DB 메타데이터 조회 → 발급 URL 실제 GET → 해당 테스트 녹음 삭제 검증. BE IAM에는 ListBucket이 없으므로 버킷 전체 목록 검사를 필수 gate로 쓰지 않음.
- [ ] AI 실행 위치에서 분석용 URL 접근 및 만료/오류 처리 검증. URL 전체·서명 query·음성 원문을 운영 로그에 남기지 않음.
- [ ] 사용자/소유권에 따른 재생·삭제 거부와 private 객체의 무인증 접근 거부 확인. 다운로드 URL은 보유자가 만료 전 사용할 수 있으므로 민감정보처럼 취급.
- [ ] 업로더의 신규 batch 업로드·검증 및 별도 DB 적재 검증. BE 키로 catalog 접근하거나 업로더가 녹음 경로에 접근하지 못하도록 정책 확인.

현재 코드 기본값은 재생 URL 10분, 분석용 URL 30분, S3 파일 제한 26,214,400바이트다. multipart 요청 자체 제한도 25MB여서 본문 부가정보를 포함한 경계 크기는 실제 시험이 필요하다. 설정값과 검증 결과를 혼동하지 않는다. DB readiness 성공이나 presign 발급만으로 S3 연결 완료로 표시하지 않는다.

키 회전은 새 키 준비 → 서버 파일 교체/필요한 재시작 → 실제 업로드·재생·삭제 확인 → 이전 키 비활성화·폐기 순서로 수행한다. 이미지 롤백과 자격 증명 회전은 별개이며 폐기/노출된 키를 복구하지 않는다. 키 노출 시 해당 키를 신속히 차단하고 영향 확인·교체 후 재검증한다. 개인 계정에는 MFA를 적용하고 프로젝트 종료 시 데이터 인수·삭제와 비용 책임을 정리한다.

관련 문서: [BE 실행 계약](BACKEND_RUNTIME_CONTRACT.md), [환경변수 예제](../../backend/.env.example), [MySQL 운영 구성](MYSQL_RUNTIME.md), [서버 릴리스](SERVER_RELEASE_RUNBOOK.md), [배포 체크리스트](DEPLOYMENT_READINESS.md).
