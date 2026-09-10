# Nginx BE blue/green 라우팅

Jira: S15P21B110-154. 연계: 153·156. 기준일: 2026-09-10 KST.

외부 기준 주소는 `https://j15b110.p.ssafy.io/api/v1/`이고 BE 내부에는 `/api/v1` context path를 추가하지 않는다. Nginx가 이 접두사만 한 번 제거해 현재 healthy 슬롯의 내부 경로로 전달한다.

## 경로와 노출 정책

| 외부 요청 | 처리 |
|---|---|
| `/healthz` | Nginx edge 생존만 나타내는 기존 JSON 200 |
| `/api/v1/...` | 활성 슬롯 `127.0.0.1:18081` 또는 `18082`로 전달하면서 `/api/v1/` 제거 |
| `/actuator...`, `/api/v1/actuator...` | 404, 외부 노출 금지 |
| `/internal...`, `/api/v1/internal...` | 404, AI 서비스 전용 API 외부 노출 금지 |
| dot-file 경로 | 404 |
| 그 밖의 경로 | 기존 fail-closed JSON 503 |

Authorization과 요청 본문은 BE로 전달한다. Host·client IP·scheme 전달 헤더를 명시하고 connect timeout 3초, read/send timeout 130초, 요청 본문 상한 27MiB를 둔다. 애플리케이션은 WAV 파일 자체 25MiB 제한을 별도로 적용한다.

## 슬롯 파일

- blue: `infra/nginx/backend-slots/blue.conf` → `127.0.0.1:18081`
- green: `infra/nginx/backend-slots/green.conf` → `127.0.0.1:18082`
- 활성 링크: `/etc/nginx/micon/backend-active.conf`

두 슬롯 파일은 loopback만 가리킨다. Docker Compose도 같은 포트를 `127.0.0.1`에만 publish하므로 UFW 우회 외부 포트가 생기지 않는다.

## 적용 전 검사

저장소 후보 설정만 임시 디렉터리에서 검사한다. 활성 `/etc/nginx` 설정이나 서비스를 수정하지 않는다.

```sh
sudo infra/nginx/verify_candidate_config.sh
```

2026-09-10 SSAFY 서버의 nginx 1.24에서 syntax/test successful을 확인했다. 후보 BE가 꺼진 상태에서 switch를 실행했을 때 60초 readiness gate에서 종료됐고, 활성 site SHA-256·공개 503·active link 부재가 그대로임도 확인했다.

## 전환

전환 전에 후보 Compose 기동, readiness, 카탈로그 S3 gate를 먼저 통과시킨다. 다음 명령 자체도 대상 슬롯 readiness 3회 연속 성공을 다시 확인한다.

```sh
sudo infra/nginx/switch_backend_slot.sh blue
```

스크립트는 다음 순서로만 동작한다.

1. 허용된 blue/green 값과 연결된 loopback 포트 선택
2. 대상 readiness 3회 연속 성공
3. 기존 site와 active slot을 `/var/backups/micon/nginx-backend-route-<UTC>.<random>/`에 보존
4. site와 active link 준비 → `nginx -t`
5. Nginx reload
6. 공개 `/api/v1/songs`가 BE의 `401 UNAUTHENTICATED`를 반환하는지 확인
7. 4~6 실패 시 이전 site·slot 복원 → `nginx -t` → reload

성공 로그에는 슬롯·포트·이전 슬롯·백업 디렉터리만 남긴다. Authorization, 환경변수 전체, 요청 본문은 기록하지 않는다.

## 원복

이전 슬롯 컨테이너가 아직 실행 중이고 현재 DB schema와 호환되며 readiness와 필요한 S3 gate를 통과하는지 먼저 확인한다. 그 뒤 이전 슬롯으로 같은 스크립트를 실행한다.

```sh
sudo infra/nginx/switch_backend_slot.sh green
```

이미 종료됐거나 unhealthy인 슬롯으로 무조건 연결하지 않는다. 이미지 롤백은 DB migration, S3 객체, AWS 키를 되돌리지 않는다. `docker compose down -v`, Flyway repair/baseline, DB restore는 이 라우팅 원복에 포함하지 않는다.

## 아직 실행하지 않은 항목

- 리뷰 전인 후보 설정으로 실서비스 Nginx reload 및 성공 경로 전환
- 전환 중 진행 요청의 graceful drain 실측
- 인증 사용자 대표 API와 Android 실기기 흐름
- 이전 슬롯로 실제 원복한 뒤 외부 기능 재검증

따라서 후보 syntax와 fail-closed 실패 경로는 검증됐지만 154 전체를 완료로 표시하지 않는다.
