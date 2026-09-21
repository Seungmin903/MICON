# Jenkins BE main 배포

Jira: S15P21B110-155. 연계: 153·154·156. 최초 기준일: 2026-09-10 KST, 운영 상태 갱신: 2026-09-21 KST.

`ci/Jenkinsfile.backend`는 Android CI와 분리된 BE 전용 Pipeline 정의다. 리뷰된 `main` checkout만 서버 배포를 허용하며 MR 소스·develop·수동 detached branch는 branch guard에서 중단한다. GitLab로 commit status를 다시 전송하지 않는 기존 팀 결정도 유지한다.

## Jenkins에 필요한 준비

새 Pipeline 또는 Multibranch Pipeline 작업에서 Script Path를 `ci/Jenkinsfile.backend`로 지정한다. 기존 Android Freestyle/Pipeline 작업을 이 파일로 교체하지 않는다.

| Jenkins credential ID | 종류 | 내용 |
|---|---|---|
| `micon-server-ssh` | SSH Username with private key | 배포 전용 서버 SSH 키와 사용자 `ubuntu`. Git·문서·환경변수 파일에 키 원문 저장 금지 |
| `micon-server-known-hosts` | Secret file | 관리자가 지문을 별도로 확인한 `j15b110.p.ssafy.io` known_hosts 한 줄 |

Jenkins agent에는 Git, Python 3, Java 21, `tar`, `sha256sum`, OpenSSH client가 필요하다. 서버에는 Docker Compose v2+, nginx, Python 3, util-linux의 `flock`과 배포 사용자에 필요한 제한된 sudo 권한이 필요하다.

현재 로컬 PEM을 Jenkins workspace나 저장소로 복사하지 않는다. Jenkins credential은 관리자 화면에서 대상·지문·키를 확인해 관리하며 저장소나 빌드 로그에 원문을 남기지 않는다. Backend 배포 Jenkins 작업은 Android CI와 분리해 구성되어 있다.

## Pipeline 순서

1. Jenkins SCM checkout 후 `BRANCH_NAME`, `GIT_BRANCH`, GitLab source branch 중 하나가 정확히 `main`인지 확인하고, checkout의 HEAD가 로컬 `refs/remotes/origin/main`과 정확히 같은 SHA인지 다시 검사한다. MR target이 main이라는 이유만으로 배포하지 않는다.
2. BE contract, deploy gate 전체 Python 테스트와 Gradle `test bootJar`를 실행한다.
3. 정확한 40자리 commit SHA의 `git archive`를 만들고 SHA-256을 기록한다.
4. host key 검증을 끄지 않고 archive를 서버 `/home/ubuntu/micon-releases/incoming/`으로 전송한다.
5. 서버 installer가 archive SHA-256과 경로 traversal을 검사한다. 같은 SHA 재시도 시 기존 release를 `superseded/` 아래에 보존하고 깨끗한 새 경로에 푼다.
6. root-owned source로 바꾼 뒤 비활성 blue/green 슬롯에서 이미지 build → Flyway → readiness 3회 → 카탈로그 S3 실제 GET을 실행한다.
7. 모든 gate가 성공하면 Nginx slot switch를 실행하고 공개 API의 BE 401 응답을 확인한다.
8. 서버의 공통 non-blocking lock으로 Jenkins·수동 배포·원복의 동시 실행을 거부한다. 전환 전 실패 시 후보 Compose만 `down`하고 준비 중인 상태 파일을 제거한다. 볼륨·DB·기존 active slot은 삭제하지 않는다.
9. 성공 시 `/var/lib/micon/backend-release.env`에 SHA, image ID, slot, port, UTC 시각만 기록하고, 기존 정상 상태는 `/var/lib/micon/backend-release.previous.env`로 한 세대 보존한다.

## 직전 정상본 원복

직전 슬롯 컨테이너가 계속 실행 중이고 변경된 DB migration이 직전 BE와 호환된다고 담당자가 확인한 경우에만 아래 명령을 실행한다.

```bash
sudo /path/to/release/infra/deploy/rollback_backend_release.sh --confirm-db-compatible
```

스크립트는 현재·직전 상태 파일과 Nginx 활성 링크, 직전 컨테이너의 실제 image ID를 대조한다. 이어서 직전 슬롯의 readiness 3회와 카탈로그 S3 실제 GET을 다시 통과한 뒤에만 라우팅을 전환하고 두 상태 파일을 맞바꾼다. DB 호환성은 자동 판정할 수 없으므로 확인 인자 없이 실행되지 않는다. DB·S3 객체·AWS 키·볼륨은 변경하거나 되돌리지 않는다.

서버에서 읽는 `/etc/micon/runtime/backend-deploy.env`는 비밀이 아닌 gate 입력이며 root 0600이다. 기본 파일은 song_id 1과 현재 버킷·리전·승인 prefix를 사용한다. AWS/DB/JWT/AI 토큰 값은 이 파일에 넣지 않는다.

## 자동 실행 연결 기준

- main push/merge 완료 이벤트만 이 작업을 호출한다. MR open/update 이벤트는 Android CI 또는 검토 작업에만 사용하고 BE 배포 job을 실행하지 않는다.
- 동시 배포를 금지한다. 앞선 배포를 새 빌드가 중간 취소하지 않으며 Jenkinsfile의 `disableConcurrentBuilds()`로 직렬화한다.
- 전체 Pipeline timeout은 30분이다. image build·S3 다운로드가 이 시간을 넘으면 route 전환 없이 실패해야 한다.
- 첫 자동 실행 전 서버 SSH credential, known_hosts, main branch checkout 값, sudo 범위를 Jenkins 관리자와 함께 확인한다.
- Pipeline 성공만으로 관심 장르·아티스트 API, AI, SMTP, Android 통합이 완성된 것은 아니다.

## 검증 상태

- 로컬 `infra/deploy` 테스트 37개에서 main guard, host key 강제, SHA archive, gate 순서, 전환 전 후보 정리를 검사했다.
- SSAFY 서버에서 세 shell script의 syntax, root 0600 `backend-deploy.env` 설치, 잘못된 release SHA의 무변경 종료를 확인했다.
- 각 build/Flyway/readiness/S3 단계와 Nginx 후보 syntax·fail-closed는 앞선 수동 후보 검증에서 개별 통과했다.
- Backend Jenkins 작업과 `main` 트리거 연결은 구성되었고 2026-09-21 배포 실행 시작까지 확인했다. 최신 실행의 최종 결과·배포 SHA·활성 슬롯·공개 API 검증 결과는 Jenkins 기록을 확인해 [배포 준비 문서](DEPLOYMENT_READINESS.md)에 남긴다. 이 근거 없이 배포 완료로 판정하지 않는다.
