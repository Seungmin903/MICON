# BE 운영 컨테이너 실행

Jira: S15P21B110-153. 기준일: 2026-09-10 KST.

Spring BE는 `backend/Dockerfile`로 빌드하고 `infra/compose/backend.compose.yml`로 실행한다. MySQL은 기존 외부 Docker 네트워크 `micon_backend`에서만 접근하며, S3 outbound를 위해 별도 egress 네트워크를 함께 사용한다. 후보 슬롯은 `127.0.0.1:18081`, 대기 슬롯은 `127.0.0.1:18082`로 정하고 외부 방화벽 포트를 열지 않는다.

## 보안 및 시작 계약

- 이미지는 커밋 SHA 태그를 사용하고 `latest`를 배포 식별자로 사용하지 않는다.
- 앱은 UID/GID `10001:10001`, read-only root filesystem, Linux capability 없음, `no-new-privileges`로 실행한다.
- 원본 비밀은 `/etc/micon/secrets/*`의 `root:root 0600`을 유지한다. `prepare_backend_host.sh`가 앱에 필요한 네 파일만 `/etc/micon/backend-secrets/`에 UID/GID `10001:10001`, mode `0400`으로 복제한다. Docker Compose의 로컬 file secret은 `uid/gid/mode`를 적용하지 않으므로 이 준비 단계를 생략하지 않는다.
- 비밀값은 이미지·Git·환경 예제·명령행 인자에 넣지 않는다. 비밀 파일을 회전한 뒤에는 준비 스크립트를 다시 실행해 앱 전용 사본을 갱신한다.
- Flyway는 앱보다 먼저 전용 `micon_migrator` 계정으로 V1~현재 migration을 검증·적용한다. 성공한 경우에만 앱을 시작하고 앱 자체 Flyway는 끈다. `repair`, `baseline`, 볼륨 삭제는 자동화하지 않는다.
- readiness는 DB를 포함한 `/actuator/health/readiness`, liveness는 DB를 제외한 `/actuator/health/liveness`다. 둘 다 상세 component를 노출하지 않는다.
- AI·추천 서비스가 준비되기 전에는 관련 feature flag를 `false`로 유지한다. 현재 BE는 추천 작업을 JSON으로 제출하지만 `ai/recommend-server`는 8788 포트에서 multipart 음원을 받으므로 계약이 일치하지 않는다. 두 API 계약과 서비스 인증을 맞추고 통합 테스트를 통과하기 전에는 `AI_RECOMMENDATION_POLLING_ENABLED`를 켜지 않는다. SMTP가 실제로 준비되지 않은 동안 비밀번호 복구 메일을 정상 기능으로 표시하지 않는다.

## 최초 호스트 준비

저장소 checkout에서 실행한다. 기존 `backend.env`는 덮어쓰지 않는다.

```sh
sudo infra/deploy/prepare_backend_host.sh
sudo stat -c '%n %u:%g %a' /etc/micon/backend-secrets/*
```

다음 원본이 먼저 존재해야 한다.

- `/etc/micon/secrets/mysql_app_password`
- `/etc/micon/secrets/mysql_migration_password`
- `/etc/micon/secrets/aws_credentials`

JWT와 AI 서비스 토큰은 없을 때만 무작위 값으로 생성된다. 비밀 아닌 설정은 `/etc/micon/runtime/backend.env`에서 관리하고, 저장소의 `infra/compose/backend.env.example`과 새 변수 여부를 비교한다.

## 후보 빌드 및 기동

`COMMIT_SHA`는 테스트할 정확한 Git 커밋으로 대체한다. 아래 변수는 현재 shell에만 두고 전체 Compose config나 container inspect 환경을 로그에 출력하지 않는다.

```sh
export MICON_BACKEND_IMAGE="micon-backend:${COMMIT_SHA}"
export MICON_BACKEND_SLOT=blue
export MICON_BACKEND_BIND_PORT=18081
export MICON_BACKEND_ENV_FILE=/etc/micon/runtime/backend.env

sudo -E docker compose -f infra/compose/backend.compose.yml config --quiet
sudo -E docker compose -f infra/compose/backend.compose.yml build backend
sudo -E docker compose -f infra/compose/backend.compose.yml up -d --no-build backend
python3 infra/deploy/check_health.py \
  --url http://127.0.0.1:18081/actuator/health/readiness \
  --deadline 60 --interval 2 --timeout 3
```

Flyway 로그에는 현재 schema 버전과 `Schema is up to date` 또는 적용된 migration만 있어야 한다. 앱 로그는 최근 범위만 확인하고 환경 전체나 비밀 파일 내용을 출력하지 않는다.

## 2026-09-10 후보 실검증

커밋 `1349c42c578a` 이미지를 SSAFY 서버의 loopback `18081`에 기동해 다음을 확인했다. 후속 secret/Flyway 수정은 같은 브랜치에 포함했다.

| 검사 | 결과 |
|---|---|
| Flyway | MySQL 8.4.11, V1~V10 검증, 현재 V10, 추가 변경 없음 |
| BE | prod profile, Java 21, MySQL 연결 및 JPA validate 성공 |
| 보안 | UID/GID `10001:10001`, read-only rootfs, loopback publish, restart `unless-stopped` |
| health | readiness·liveness 각각 HTTP 200, 본문 `{"status":"UP"}` |
| catalog 인증 | 토큰 없음 HTTP 401, 서버 AI 토큰 HTTP 200 |
| S3 실제 GET | song_id 1의 44,304,432바이트 WAV 다운로드, API 메타데이터 SHA-256과 일치 |
| 재시작 | 앱 컨테이너 단독 restart 후 readiness healthy 복귀 |

Nginx는 변경하지 않았으므로 이 결과는 외부 서비스 배포 완료가 아니다. 관심 장르·아티스트 API, AI 컨테이너, SMTP, 사용자 녹음 업로드/삭제, Android 실기기 흐름도 별도 검증이 필요하다. 후보 검증 후에는 Compose `down`으로 후보만 제거하고 MySQL·Nginx·Runner는 건드리지 않는다.

## 중단 및 정리

전환 전 후보 실패 시 기존 Nginx 라우팅을 유지한다. 후보만 정리할 때는 같은 네 변수를 설정한 shell에서 다음을 실행한다. `-v`를 붙이지 않는다.

```sh
sudo -E docker compose -f infra/compose/backend.compose.yml down
```

실서비스 전환과 원복은 S15P21B110-154·156의 Nginx upstream 및 release gate가 리뷰된 뒤 수행한다. 이 문서만 보고 현재 Nginx를 reload하지 않는다.
