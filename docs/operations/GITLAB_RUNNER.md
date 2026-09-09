# GitLab 프로젝트 전용 Runner 운영

Jira: `S15P21B110-212`

## 2026-09-09 적용·검증 기록

- GitLab Runner ID `2102`, 설명 `micon-ssafy-protected`, 버전 `18.11.0`을 등록했다.
- GitLab API 기준 `online`, `project_type`, Locked·Protected 활성화, untagged 비활성화, 태그 `micon-protected`, 최대 600초를 확인했다.
- 서버의 `config.toml`은 `root:root 0600`이며 `concurrent=1`, `request_concurrency=1`, 비특권, 1 CPU·1 GiB, 허용 이미지 고정을 확인했다.
- Controller는 포트를 게시하지 않는다. Controller의 Docker 소켓은 작업 컨테이너 생성용이며 작업 컨테이너의 허용 마운트는 `/cache`뿐이다.
- `.gitlab-ci.yml`은 GitLab CI Lint에서 오류·경고 없이 통과했다. 동일 명령으로 로컬 테스트 23개(7+16)가 통과했다.
- 설치 뒤 MySQL healthy, Nginx 설정 검사 성공, `/healthz` 200, 개인 S3 리전 조회 성공을 확인했다. `/api/v1/health` 503은 아직 BE 컨테이너가 없는 기존 상태와 같다.
- 실제 GitLab 작업 실행은 이 문서와 `.gitlab-ci.yml`이 리뷰 후 `develop`에 병합된 뒤 처음 검증한다. 현재 설정은 MR 소스 브랜치 코드를 실행하지 않는다.

## 역할과 실행 범위

이 Runner는 GitLab 프로젝트 `s15-ai-speech-sub1/S15P21B110`의 보호된 `develop` 브랜치에서 경량 검증만 수행한다.

- Runner 태그: `micon-protected`
- 실행 조건: `develop` 브랜치에 반영된 커밋
- 실행 작업: BE 런타임 계약 테스트 7개, 배포 health 검사 테스트 16개
- 동시 실행: 1개
- 작업 제한 시간: 10분
- Jenkins 역할: Android MR CI와 향후 `main` 배포는 기존 Jenkins 구성을 유지한다.

MR 소스 브랜치의 코드를 이 서버에서 실행하지 않는다. MR은 Jenkins·리뷰로 먼저 확인하고, GitLab Runner 검증은 MR이 `develop`에 병합된 뒤 실행한다.

## 서버 배치

| 항목 | 값 |
|---|---|
| 호스트 | `j15b110.p.ssafy.io` |
| 실행 방식 | Docker executor, 비특권 작업 컨테이너 |
| Compose | `/opt/micon/gitlab-runner/compose.yml` |
| Runner 설정 | `/etc/gitlab-runner/config.toml` (`root:root`, `0600`) |
| Controller | `micon-gitlab-runner` |
| 기본 작업 이미지 | `python:3.13-alpine` |

Controller가 작업 컨테이너를 생성하려고 Docker 소켓을 사용한다. 작업 컨테이너 자체에는 Docker 소켓, `/etc/micon`, MySQL 볼륨, AWS 자격 증명을 마운트하지 않는다. Runner 설정은 다음 제한을 유지한다.

```toml
concurrent = 1

[[runners]]
  request_concurrency = 1
  executor = "docker"

  [runners.docker]
    image = "python:3.13-alpine"
    privileged = false
    volumes = ["/cache"]
    allowed_images = ["python:3.13-alpine"]
    cpus = "1"
    memory = "1g"
    memory_swap = "1g"
```

GitLab 프로젝트 설정은 `project_type`, `locked=true`, `run_untagged=false`, `access_level=ref_protected`, 최대 실행 시간 600초로 유지한다. 인증 토큰은 GitLab에서 생성 직후 한 번만 받아 서버 설정에 저장하며 문서·Git·채팅에 복사하지 않는다.

## 일상 점검

```bash
sudo docker ps --filter name=micon-gitlab-runner
sudo docker logs --tail 50 micon-gitlab-runner
sudo docker exec micon-gitlab-runner gitlab-runner verify
sudo stat -c '%U:%G %a %n' /etc/gitlab-runner/config.toml
```

GitLab의 **Settings > CI/CD > Runners**에서도 다음을 확인한다.

- 상태 `online`
- 태그 `micon-protected`
- Locked, Protected 활성화
- Run untagged jobs 비활성화

토큰이나 `config.toml` 원문은 로그·이슈·MR에 첨부하지 않는다.

## 재시작과 업데이트

설정 변경 전 GitLab에서 Runner를 pause하고 실행 중인 작업이 없는지 확인한다.

```bash
cd /opt/micon/gitlab-runner
sudo docker compose up -d
sudo docker exec micon-gitlab-runner gitlab-runner verify
```

Runner 버전은 서버 GitLab의 minor 버전과 맞춰 `infra/gitlab-runner/compose.yml`에서 고정한다. 이미지 태그를 바꿀 때는 별도 MR로 검토하고, 서버 파일과 저장소 파일을 동일하게 반영한다.

## 장애 격리와 복구

의심스러운 작업, 반복 실패, 비정상적인 CPU·메모리 사용이 있으면 GitLab에서 먼저 Runner를 pause한 다음 Controller만 중지한다.

```bash
cd /opt/micon/gitlab-runner
sudo docker compose stop runner
```

이 명령은 MySQL·Nginx를 중지하지 않고 Runner 설정도 삭제하지 않는다. 원인을 확인한 뒤 `docker compose up -d`로 재개한다. Runner를 제거해야 할 때는 GitLab 프로젝트에서 Runner를 삭제해 토큰을 폐기한 뒤 Controller를 내린다. `/etc/gitlab-runner` 삭제는 별도 백업·승인 없이 수행하지 않는다.

## 공존 위험

Runner 전용 호스트가 가장 안전하다. 현재는 서비스와 같은 SSAFY 서버에서 Controller가 Docker 소켓을 사용하므로, Runner 자체나 신뢰한 이미지가 침해되면 호스트 영향 가능성이 남는다. 그래서 실행 대상을 보호된 `develop`으로 제한하고 프로젝트 Maintainer가 리뷰·병합한 코드만 실행한다. 별도 서버가 확보되면 동일 설정을 이전하고 기존 Runner 토큰을 폐기한다.
