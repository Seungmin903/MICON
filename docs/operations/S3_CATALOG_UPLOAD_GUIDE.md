# MICON S3 카탈로그 업로드 안내 — 담당 팀원용

이 문서는 곡 데이터·음원 등 승인된 카탈로그 산출물을 MICON의 비공개 S3에 올리는 담당자용 안내다. AWS 콘솔 로그인은 필요 없다. **액세스 키는 이 문서와 별도로 1:1로 전달받으며, 문서·Git·Jira·메신저 채널에 복사하지 않는다.**

## 전달받을 정보

| 항목 | 값 |
|---|---|
| 버킷 | `micon-b110-prod-apne2-20260909` |
| 리전 | `ap-northeast-2` (서울) |
| AWS 계정 확인값 | `559896294099` |
| AWS CLI 프로필 | `micon-catalog-upload` |
| 할당 경로 | `catalog/<담당자명>/<batch-id>/` — 실제 값은 인프라 담당자에게 확인 |
| 인증 | 담당자 개인용 Access Key ID와 Secret Access Key를 별도 전달 |

서버용 `micon-backend-prod` 키, AWS 루트 계정, 다른 팀원의 키는 사용하거나 공유하지 않는다. 개인 키를 받지 않았거나 할당 경로가 확정되지 않았다면 업로드를 시작하지 말고 인프라 담당자에게 요청한다.

## 1. AWS CLI 프로필 설정

[AWS CLI 공식 설치 안내](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)에 따라 AWS CLI v2를 설치하고 PowerShell에서 버전을 확인한다.

```powershell
aws --version
aws configure --profile micon-catalog-upload
```

프롬프트에는 별도로 전달받은 개인 키와 아래 값을 입력한다.

```text
AWS Access Key ID: 별도로 전달받은 본인 값
AWS Secret Access Key: 별도로 전달받은 본인 값
Default region name: ap-northeast-2
Default output format: json
```

키가 화면에 출력되는 명령, 명령행 인자, 스크립트, `.env` 파일에는 키를 넣지 않는다. 설정 후 본인의 업로더 신원과 계정을 확인한다.

```powershell
aws sts get-caller-identity `
  --profile micon-catalog-upload `
  --query '{Account:Account,Arn:Arn}'
```

`Account`가 `559896294099`이고 `Arn`이 인프라 담당자가 알려준 본인 업로더 사용자여야 한다. 다르면 업로드하지 않는다.

## 2. 업로드 전 파일 확인

업로드할 파일만 별도 폴더에 모은다. 다음 항목은 올리지 않는다.

- AWS 키, `.env`, SSH PEM, DB 비밀번호 등 비밀정보
- 작업공간 전체, 캐시, 가상환경, 빌드 중간 산출물
- 검토되지 않은 DB 덤프나 사용자 녹음·개인정보
- 이용 권한이나 출처가 확인되지 않은 음원·가사

파일명, 곡 ID, 파일 크기, SHA-256을 확인하고 batch-id를 인프라/BE/AI 담당자와 맞춘다. 같은 경로의 기존 파일을 덮어쓰지 않도록 batch마다 새 경로를 사용한다.

## 3. Dry-run 확인

아래 세 값을 실제 값으로 바꾼다.

- `<LOCAL_FOLDER>`: 업로드 전용 로컬 폴더
- `<담당자명>`: 인프라 담당자가 할당한 경로명
- `<batch-id>`: 합의한 배치 ID(예: `import-20260909`)

먼저 `--dryrun`으로 대상 파일과 S3 경로만 확인한다. 이 단계에서는 실제 업로드되지 않는다.

```powershell
aws s3 sync '<LOCAL_FOLDER>' `
  's3://micon-b110-prod-apne2-20260909/catalog/<담당자명>/<batch-id>/' `
  --profile micon-catalog-upload `
  --region ap-northeast-2 `
  --no-follow-symlinks `
  --dryrun
```

출력에 다른 폴더·비밀정보·개인정보가 포함되거나 목적지가 본인 할당 경로와 다르면 중단한다.

## 4. 실제 업로드와 확인

dry-run 결과가 맞으면 같은 명령에서 `--dryrun`만 제거한다. **`--delete` 옵션은 사용하지 않는다.**

```powershell
aws s3 sync '<LOCAL_FOLDER>' `
  's3://micon-b110-prod-apne2-20260909/catalog/<담당자명>/<batch-id>/' `
  --profile micon-catalog-upload `
  --region ap-northeast-2 `
  --no-follow-symlinks
```

업로드된 파일 목록과 크기를 확인한다.

```powershell
aws s3 ls `
  's3://micon-b110-prod-apne2-20260909/catalog/<담당자명>/<batch-id>/' `
  --profile micon-catalog-upload `
  --region ap-northeast-2 `
  --recursive `
  --human-readable `
  --summarize
```

권한 오류, 네트워크 중단, 일부 파일 실패가 있으면 같은 경로에 무작정 재시도하거나 다른 키를 사용하지 말고 오류 시각·명령·HTTP 오류 코드만 인프라 담당자에게 전달한다. 액세스 키와 전체 서명 URL은 보내지 않는다.

## 5. 완료 보고

업로드 완료 후 다음 형식으로 인프라·BE·AI 담당자에게 전달한다.

```text
- 담당자:
- batch-id:
- S3 prefix: catalog/<담당자명>/<batch-id>/
- 파일 수:
- 전체 크기:
- 곡 ID ↔ object key 목록:
- 로컬 SHA-256 목록 또는 manifest 위치:
- 실패/제외 파일:
```

S3 업로드는 파일 보관 완료만 뜻한다. 곡 ID와 object key 매핑 검토, SSAFY MySQL 적재, API 조회·재생·AI 처리 확인은 별도 단계다. 업로드가 끝나면 인프라 담당자에게 알려 개인 업로더 키를 비활성화하거나 삭제할 시점을 합의한다.

문의 시 전달할 내용: 담당자명, batch-id, 실패한 상대 파일 경로, 실행 시각, 오류 코드. **Access Key ID, Secret Access Key, 사용자 음성 원문은 전달하지 않는다.**
