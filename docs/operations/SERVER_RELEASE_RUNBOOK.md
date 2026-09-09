# 서버 배포·롤백 Runbook (게이트 구현, 라우팅·자동복구 준비 중)

Jira: S15P21B110-156 / 연계: 153·154·155. 2026-09-10 기준 Flyway V1~V10과 운영 DB 적재는 완료됐고, 후보 BE의 DB·S3 검증까지 수행했다.

**현재 자동 배포/롤백이 모두 구성되었다는 뜻은 아니다.** 후보 readiness와 카탈로그 S3 실제 GET 게이트는 구현·실검증했지만 Nginx 전환·원복 및 Jenkins 연결은 아직이다. 앱 Release APK는 179에서 별도 관리한다. 기존 Android MR CI 작업을 서버 CD로 덮어쓰지 않는다.

2026-09-09 저장소 계약: BE·MySQL은 SSAFY 서버, S3는 개인 AWS 계정의 비공개 버킷을 사용한다. [S3 운영 계약](S3_VOICE_STORAGE.md)의 실제 버킷·권한·인증 준비가 배포 선행 조건이다. 아래 기존 health 도구는 S3 연결을 검사하지 않으며 DB readiness 계약의 후속 구현도 [BE 실행 계약](BACKEND_RUNTIME_CONTRACT.md)과 구분한다.

## 적용 전 충족할 조건

- 검증한 BE 이미지의 커밋 SHA 태그와 digest, 이전 정상 이미지/digest를 확보한다. latest 태그만으로 롤백하지 않는다.
- 운영 Compose/서비스명, 후보 컨테이너의 **별도 loopback 포트**, Nginx upstream 전환·원복 파일/명령을 153·154에서 확정한다. 이 문서의 절차만 보고 지금 임의 포트를 열지 않는다.
- 운영 환경파일은 Git 밖에서 최소 권한으로 관리한다. 원문 출력, shell tracing, docker inspect 전체 출력, docker compose config 전체 출력을 CI 로그에 남기지 않는다.
- 개인 S3의 버킷·리전·voice-recordings/ 정책, BE 전용 프로필·읽기 전용 자격 증명 파일·실행 UID/GID를 확인한다. 컨테이너 환경에서 개인 계정 신원이 선택되어야 하며 SSAFY 기본 역할 또는 오래된 상위 우선순위 AWS 키가 선택되면 중단한다.
- BE의 DNS·outbound HTTPS와 AI의 분석용 URL 접근을 확인한다. 런타임 AWS 키는 이미지/빌드 인자/Android MR CI에 전달하지 않는다. catalog/ 업로드와 DB 초기 적재는 별도 승인된 작업으로 준비한다.
- 기존 서버/DB 데이터·백업·마이그레이션 호환성과 현재 Flyway 버전을 확인한다. 후보에서 V1~V10 검증 후 추가 migration 없음과 JPA validate 성공을 확인했지만, 이후 migration이 추가되면 다시 판단한다.
- Python 3와 후보 포트에서 `/actuator/health/readiness`가 `application/json`, HTTP 200, `{"status":"UP"}`를 반환하는지 확인한다. 외부 Nginx `/healthz`와 구분한다.

## 배포 순서와 중단 조건

1. main 대상 CD의 checkout SHA 기록 → BE 테스트. 실패하면 이미지 배포/SSH 단계에 진입하지 않는다.
2. 테스트한 소스로 이미지 생성 → SHA 태그/digest 기록. 배포 중 새 소스를 다시 가져오지 않는다.
3. 이전 정상 버전과 라우팅 설정을 보존하고 후보 컨테이너를 기존 서비스와 별도 포트에 준비한다. DB 초기화·migration 자동 실행 여부는 BE/DB 계약 확정 전 반드시 확인한다.
4. 아래 readiness gate와 카탈로그 S3 실제 GET gate를 충족해야 다음 단계에 진입한다. 사용자 녹음 쓰기 경로는 별도로 합성 녹음 업로드 → presigned URL 실제 GET → 해당 테스트 녹음 삭제를 검증해야 한다. URL 발급만으로 권한 검증을 대신하지 않는다. 실패하면 기존 Nginx 라우팅을 유지한다.
5. [Nginx BE 라우팅](NGINX_BACKEND_ROUTING.md)의 switch 도구로 대상 readiness 재확인 → 설정 백업 → `nginx -t` → reload → 공개 401 smoke를 실행한다. 실패 시 도구가 이전 site·slot을 복원한다.
6. HTTPS 경로의 상태와 대표 API 기능을 별도로 검사한다. Actuator UP만으로 곡 데이터·추천·인증 전체가 정상이라고 판단하지 않는다.
7. 위 검증 완료 후에만 현재 정상 버전을 갱신한다. 이전 이미지·컨테이너·설정은 정해진 보존기간 동안 유지한다.

## 구현된 읽기 전용 health gate

후보가 이미 안전하게 준비된 **서버에서** 실행한다. 포트는 예시가 아니라 153·154에서 확정한 실제 값을 환경변수에 넣어야 한다.

```sh
set +x
: "${CANDIDATE_PORT:?Set the approved candidate loopback port}"
python3 infra/deploy/check_health.py \
  --url "http://127.0.0.1:${CANDIDATE_PORT}/actuator/health/readiness" \
  --deadline 60 --interval 2 --timeout 5 --required-successes 3
```

- 종료 코드 0: 제한 시간 안에 3회 연속 readiness 통과. 1: 실패, 라우팅 전환 금지. 2: 잘못된 설정, 배포 중단.
- 리다이렉트·외부 주소·URL 내 자격 증명/쿼리·HTML·잘못된 JSON·DOWN 응답을 통과시키지 않는다.
- 응답 본문/원시 예외/비밀값을 출력하지 않는다. 시도 번호·경과 시간·정해진 결과 코드만 기록한다.
- 이 도구는 라우팅/컨테이너/DB를 바꾸지 않는다. **종료 코드 1을 받았을 때 다음 배포 단계가 실행되지 않도록 155에서 연결해야 한다.**
- 요청별 timeout은 소켓 I/O 제한이다. 느린 응답이 조금씩 계속 오는 상황까지 프로세스 전체 실행시간을 엄밀하게 보장하지 않으므로 Jenkins 단계에도 별도 timeout을 둔다.

## 구현된 카탈로그 S3 실제 GET gate

토큰·presigned URL·SHA-256 원문을 출력하지 않는다. 후보 API의 무인증 401, 인증 응답 계약, 정확한 버킷·리전·object key를 가진 HTTPS S3 URL, 다운로드 바이트와 SHA-256을 모두 확인한다.

```sh
set +x
: "${CANDIDATE_PORT:?Set the approved candidate loopback port}"
sudo python3 infra/deploy/check_catalog_media.py \
  --base-url "http://127.0.0.1:${CANDIDATE_PORT}" \
  --song-id 1 \
  --token-file /etc/micon/secrets/ai_service_token \
  --bucket micon-b110-prod-apne2-20260909 \
  --region ap-northeast-2 \
  --allowed-prefix 'catalog/정영주/section-384-v1/vocals/' \
  --timeout 30
```

- 종료 코드 0: 인증 강제와 다운로드 바이트·SHA-256 일치. 1: 계약·HTTP·해시 실패. 2: 안전하지 않거나 잘못된 입력.
- 후보 base URL은 명시적 loopback 포트만 허용한다. S3 URL은 지정한 AWS host와 object key를 벗어나거나 서명 query가 없으면 다운로드하지 않는다.
- 이 검사는 기존 read-only 카탈로그 객체를 읽기만 한다. 사용자 녹음 `voice-recordings/`의 Put/Delete 검사를 대신하지 않는다.

## 로그 확인·재시작·롤백

- 로그: 확정된 Compose 파일/서비스를 대상으로 제한된 최근 로그만 조회하고 민감정보를 가린다. 사용자 데이터나 JWT/DB 환경파일을 복사하지 않는다.
- 재시작: 원인과 영향 범위를 확인한 뒤 해당 앱 서비스만 대상으로 한다. DB/Redis나 서버 전체를 일괄 재시작하지 않는다. 재시작 후 health gate와 HTTPS 기능 검사를 다시 한다.
- 전환 전 실패: 이전 서비스가 계속 트래픽을 받게 유지한다. 실패한 후보를 정상 버전으로 기록하지 않는다.
- 전환 후 실패: 이전 슬롯의 health 및 스키마 호환성을 먼저 확인 → `sudo infra/nginx/switch_backend_slot.sh <이전-slot>` → HTTPS 기능 재검증. 이전 이미지가 없거나 health가 나쁘면 무조건 롤백을 강행하지 않는다.
- 이전 정상 이미지로 재생성이 필요하면 보존된 digest·환경설정으로 반대 슬롯에 준비하고 같은 health·S3 gate를 거친다. 라우팅 원복 도구는 구현했지만 실제 성공 전환·원복은 리뷰 후에 검증한다.
- `docker compose down -v`, DB 볼륨 삭제, 임의 SQL undo, 이전 버전 APK 강제 설치는 복구 절차로 사용하지 않는다.
- 이미지 롤백으로 AWS 키·S3 데이터·DB 데이터가 되돌아가지는 않는다. 이전 이미지도 현재 유효한 전용 자격 증명으로 검증하며, 폐기/노출된 키를 복구하지 않는다. S3 전체 삭제나 catalog/ 덮어쓰기는 복구 절차가 아니다. 키 회전은 S3 운영 계약을 따른다.

## DB migration 실패 판단

- Compose의 일회성 Flyway 단계가 실패하면 앱 시작과 라우팅 전환을 중단한다. 실패한 SQL을 자동 재실행하거나 이력을 repair/baseline 처리하지 않는다.
- 이전 앱이 변경된 스키마와 호환되는지 BE/DB 담당자가 판단한다. 비호환이면 이미지 롤백만으로 복구되지 않는다.
- 데이터 복원은 검증된 백업과 명시적 승인/복구 계획이 있어야 하며, 백업 시점 이후 데이터 손실 가능성을 함께 판단한다.
- 현재 운영 DB는 MySQL 8.4.11, Flyway V10이다. 후속 스키마 변경·복원은 별도 리뷰와 백업을 요구한다.

## 남겨야 할 배포·복구 기록

| 항목 | 기록할 값 |
|---|---|
| 배포 식별 | commit SHA, 이미지 태그와 digest, Jenkins 번호/URL, UTC 시각 |
| health | 후보 포트, 시도 수, 성공/실패, 소요 시간 |
| S3 | 버킷·리전·배포 프로필 식별, 업로드/GET/삭제 결과와 시간. 키·URL 서명 query·음성 내용 제외 |
| 라우팅 | 이전/신규 버전, nginx 검사·reload 결과, HTTPS 기능 검사 |
| 복구 | 복구 대상 digest, 사유, 승인/수행자, 시작·종료 시각, 소요 시간 |
| 재현 | 다른 팀원 1명, 사용한 문서 버전, 재현 결과·소요 시간 |

## 현재 검증 범위

2026-09-10 Windows Python 3.13의 로컬 HTTP 모의 서버에서 health와 카탈로그 gate **24개 통과 / 실패 0**를 확인했다. readiness 연속 성공·streak 초기화, 인증 강제, 버킷/리전/key 이탈 거부, 해시 불일치를 포함한다. 테스트 실행:

```sh
python3 -m unittest discover -s infra/deploy -p 'test_*.py' -v
```

SSAFY 서버 후보 포트 18081에서도 readiness 3회 연속 성공과 song_id 1의 44,304,432바이트 S3 WAV·SHA-256 일치를 확인했다. Nginx 후보 syntax와 BE가 꺼진 fail-closed 전환도 확인했다. 성공 전환·실제 원복, 사용자 녹음 쓰기 smoke 및 팀원의 복구 재현은 별도 미검증 항목이므로 156 전체를 Done으로 표시하지 않는다.
