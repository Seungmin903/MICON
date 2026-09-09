# BE 운영 실행 계약 — 인프라 결정 및 구현 인계

Jira S15P21B110-150 / 담당 byoungwooheo / 기준 develop `5be1792` / 2026-09-08.

2026-09-09 저장소 계약 갱신: develop `fcc00d2`의 S3·AI 환경변수를 반영한다. 운영 구조는 **SSAFY 서버(BE·MySQL) + 개인 AWS 계정 S3**로 선택했다. 실제 버킷·리전·전용 IAM·서버 키 파일은 [S3 운영 계약](S3_VOICE_STORAGE.md) 0절을 따른다. S15P21B110-211에서 서버 CLI 업로드/GET/삭제 검증까지 완료했으나 BE 컨테이너 마운트·앱/DB/AI 통합은 미완료다. 아래 2026-09-08 결정은 당시 계약이며 이번 S3 설정에서 SQL·라우팅·BE 배포는 실행하지 않았다.

**사용자의 결정 위임에 따라 인프라 계약을 정한 리뷰본이다.** 현재 코드 사실과 앞으로 적용할 계약을 분리한다. MR은 문서·예제 검토용이며 팀원 승인 또는 운영 검증 완료를 뜻하지 않는다. SQL 및 본인 프런트 구현 보류를 유지한다. 150의 범위는 계약 문서화이며 실제 구현·배포 검증은 149·153·154·155·156 및 FE 후속 작업에 인계한다.

### 2026-09-08 사용자 결정 (정책 확정, 구현·운영 검증 전)

- 기존 BE 코드·OpenAPI 경로를 기준으로 맞춘다. 외부 기준 주소는 `https://j15b110.p.ssafy.io/api/v1/`로 유지하고 Nginx에서 `/api/v1/` 접두어만 제거해 전달한다. BE에 동일 context-path를 추가하지 않는다.
- 토큰 갱신은 BE의 `POST /auth/tokens/refresh`를 유지한다. 앱의 상대 경로는 `auth/tokens/refresh`로 맞추는 방향이다. 이번에 FE를 수정하거나 담당자 인수·일정을 확정한 것은 아니다.
- DB 연결·기본 쿼리 성공은 배포 성공의 필수 조건이다. DB가 비정상이면 후보 버전으로 트래픽을 전환하지 않는다. liveness는 DB 장애로 앱 재시작을 반복시키는 용도로 사용하지 않는다.
- 아래 상세 정책까지 인프라 결정으로 채택한다. 실제 동작과 FE 반영 등 구현 인계 항목은 별도로 추적한다.

## 1. 환경변수 (현재 코드 사실)

예제: [backend/.env.example](../../backend/.env.example). 모든 값은 의도적으로 비웠다. 실제 값을 이 추적 파일에 채우지 않는다. Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로 승인된 실행기/Compose에서 명시적으로 주입해야 한다. 실제 환경파일은 저장소 밖에 보관하고 환경/설정 전체를 로그에 출력하지 않는다.

| 이름 | 코드의 로컬 기본값 | 형식 및 운영 계약안 |
|---|---|---|
| DB_URL | localhost:3306/micon JDBC URL (TLS 끔 옵션 포함) | 운영에서는 명시적 JDBC MySQL URL 필수. URL에 암호를 넣지 않음. DB 버전/서버명/TLS 옵션은 149·153에서 합의 |
| DB_USERNAME | root | 운영 전용 최소권한 계정, root 기본값 사용 금지 권고 |
| DB_PASSWORD | 빈 값 | 운영에서는 별도 비밀값 주입, 빈 값 사용 금지 권고 |
| JWT_SECRET | 기본값 없음 | 코드가 UTF-8 32바이트 이상 요구. 충분한 무작위성의 비밀값 사용; PEM/APK 서명키와 별개 |
| MAIL_FROM | no-reply@micon.local | SMTP 제공자가 허용한 발신 이메일 주소 |
| MAIL_HOST | localhost | 합의된 SMTP 호스트 |
| MAIL_PORT | 25 | 1~65535 정수, 제공자와 일치 |
| MAIL_USERNAME | 빈 값 | 인증 SMTP 사용 시 계정 |
| MAIL_PASSWORD | 빈 값 | 인증 SMTP 사용 시 별도 비밀값 |
| MAIL_SMTP_AUTH | false | true/false. 제공자 요구와 일치; 운영 false를 임의 채택하지 않음 |
| MAIL_STARTTLS | false | true/false. 제공자 TLS 방식과 일치; enable=true만으로 TLS 필수 강제가 되었다고 보지 않음 |
| AI_BASE_URL | http://localhost:8000 | BE에서 접근 가능한 AI 서비스 주소. AI 위치·네트워크는 별도 확인; 운영 localhost fallback 금지 |
| AI_SERVICE_TOKEN | 빈 값 | AI 서비스 인증용 비밀값. 실제 AI 인증 계약 확인 후 별도 주입 |
| AI_VOICE_ANALYSIS_GUIDE_VERSION | v1 | AI 담당자와 맞춘 분석 가이드 버전 |
| AI_VOICE_ANALYSIS_DOWNLOAD_URL_TTL | 30m | 분석용 S3 presigned GET URL 만료 Duration. 실제 다운로드 시간·보안 요구 검증 |
| AI_SONG_FIT_ENABLED | true | true/false. AI 준비 상태에 따라 명시적으로 선택 |
| AI_RECOMMENDATION_BASE_URL | http://localhost:8788 | BE에서 접근 가능한 추천 AI 서비스 주소. 운영에서는 명시적으로 주입하고 localhost fallback을 사용하지 않음 |
| AI_RECOMMENDATION_SERVICE_TOKEN | AI_SERVICE_TOKEN 값 | 추천 AI 서비스 인증용 비밀값. 별도 값이 없으면 공통 AI 서비스 토큰을 사용하며 추적 파일에 기록하지 않음 |
| AI_RECOMMENDATION_CONNECT_TIMEOUT | 3s | 추천 AI 연결 제한 시간인 양의 Spring Duration |
| AI_RECOMMENDATION_READ_TIMEOUT | 10s | 추천 작업 접수·상태 조회 응답 제한 시간인 양의 Spring Duration |
| AI_RECOMMENDATION_POLLING_ENABLED | true | true/false. 추천 작업 상태를 주기적으로 조회할지 명시적으로 선택 |
| AI_RECOMMENDATION_POLL_INTERVAL | 1500ms | 추천 작업별 상태 조회가 끝난 뒤 다음 폴링까지 기다리는 양의 Spring Duration |
| AI_RECOMMENDATION_POLL_INITIAL_DELAY | 5s | 애플리케이션 시작 후 첫 추천 폴링까지 기다리는 음수가 아닌 Spring Duration |
| VOICE_RECORDING_MAX_SIZE | 25MB | multipart 파일 및 전체 요청 제한. 부가정보 포함 경계 크기는 시험 필요 |
| VOICE_STORAGE_BUCKET | 빈 값 | 개인 계정 비공개 버킷 `micon-b110-prod-apne2-20260909`. 운영 실행기에 명시 주입; 추적 예제는 빈 값 유지 |
| VOICE_STORAGE_REGION | ap-northeast-2 | 개인 버킷과 일치하는 서울 리전 |
| VOICE_STORAGE_PREFIX | voice-recordings | 객체 prefix. IAM의 voice-recordings/*와 함께 변경; catalog/와 분리 |
| VOICE_STORAGE_PLAYBACK_URL_TTL | 10m | 녹음 재생 presigned GET URL 만료 Duration |
| VOICE_STORAGE_MAX_FILE_BYTES | 26214400 | S3 업로드 전 파일 크기 상한(바이트), 프록시·multipart 제한과 함께 검증 |
| CATALOG_MEDIA_ALLOWED_PREFIX | catalog/정영주/section-384-v1/vocals/ | S15P21B110-228에서 승인한 보컬 stem 경로. DB object key와 정확히 일치해야 하며 다른 catalog 경로는 서명 거부 |
| CATALOG_MEDIA_DOWNLOAD_URL_TTL | 10m | AI 전용 보컬 stem presigned GET URL 만료 Duration. 1시간 초과 설정 거부 |
| AWS_SHARED_CREDENTIALS_FILE | SDK 기본 탐색 경로 | Spring placeholder 아님. BE 컨테이너의 /run/secrets/aws_credentials로 명시; 호스트 파일 읽기 전용 마운트 |
| AWS_PROFILE | SDK default 프로필 | Spring profile 아님. 개인 계정 BE 전용 프로필 micon-backend 사용 |
| AWS_EC2_METADATA_DISABLED | SDK 기본값 false | BE에 true 명시하여 SSAFY 인스턴스 역할로의 fallback 차단 |

빈 예제는 실행용 기본값 목록이 아니다. 실제 환경파일에서 값을 비워 주입하면 코드 기본값이 적용되지 않거나 타입 변환에 실패할 수 있다. 운영 담당자가 위 형식과 필수값을 확인해 채우거나, 선택 항목은 의도적으로 생략한다.

AWS 키 원문은 환경변수 예제·이미지·Git에 넣지 않고 서버 외부 파일에서 SDK가 읽는다. 상위 우선순위 AWS 환경변수/Java 시스템 속성/web identity 설정과 혼용하지 않는다. 파일 권한은 BE 실행 UID/GID와 맞춰 검증한다. 자세한 IAM 정책·Compose 부분 예시·비용·회전 절차는 [S3 운영 계약](S3_VOICE_STORAGE.md)을 따른다. S3 설정만으로 DB import나 AI 서비스 배포가 완료되지는 않는다.

운영 계약: DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET은 명시 주입하고 빈 값·root 계정·로컬 fallback 사용을 배포 전 검사에서 거부한다. 메일 기능을 제공할 때는 MAIL_FROM/HOST/PORT 및 AUTH/STARTTLS를 명시하고 인증 사용 시 USERNAME/PASSWORD도 필수다. SMTP 제공자와 호환되는 인증·필수 TLS 및 유한 타임아웃 검증 없이 운영 메일 기능을 정상으로 처리하지 않는다. 이 표는 현재 fallback 제거나 fail-fast 구현을 뜻하지 않는다. JWT issuer=micon-backend, access TTL=30m, refresh TTL=30d는 현재 properties의 값이다. 기본 profile은 local이며 별도의 production profile 파일은 아직 없다. `SPRING_PROFILES_ACTIVE=prod`만 지정해도 로컬 fallback이 사라진다고 가정하지 않는다.

SMTP는 비밀번호 복구 기능에 필요하다. `MailConfig`는 STARTTLS enable만 설정하고 required/SMTPS 전용 설정은 현재 구현하지 않았다. 제공자 인증·TLS·타임아웃 계약과 해당 설정 보완 후 실제 발송을 검증해야 한다. 메일 서버를 준비하지 않은 상태에서 비밀번호 복구 완료를 주장하지 않는다.

## 2. 외부 API 경로 (사용자 결정 — 아직 미적용)

채택한 외부 기준 주소는 `https://j15b110.p.ssafy.io/api/v1/`이다. 도메인은 기존 서버 기록에 있으나 마지막 확인된 Nginx 구성은 `/healthz` 외 요청에 의도적으로 503을 반환한다. 이 주소는 동작 확인된 API 주소가 아니다.

결정: BE 내부 경로는 현 코드를 유지하고 Nginx에서 `/api/v1/` prefix만 제거해 전달한다. HTTP 메서드·쿼리·본문은 유지하며 `auth/refresh`를 `auth/tokens/refresh`로 별도 치환하는 규칙은 추가하지 않는다. BE context-path까지 동시에 추가하지 않는다.

| 외부 경로 계약 / 미해결 항목 | 현재 BE 내부 경로 | 상태 |
|---|---|---|
| /api/v1/songs | /songs | 곡 조회 코드 존재, 운영 호출 미검증 |
| /api/v1/users/me | /users/me | 회원 조회 코드 존재, 운영 호출 미검증 |
| /api/v1/auth/tokens/refresh | /auth/tokens/refresh | BE/OpenAPI 경로 일치; 앱의 auth/refresh와는 불일치 |
| /api/v1/recordings 및 /api/v1/recordings/{id}/content | /recordings 및 /recordings/{id}/content | 업로드·조회·삭제·재생 URL 코드 존재. content는 URL을 담은 JSON이며 S3 공개 주소/리다이렉트 계약이 아님; 운영 미검증 |
| AI 내부 보컬 stem 조회 | /internal/catalog-media/vocal-stems/{songId} | AI 서비스 토큰 전용. DB의 검증된 object key에만 단기 URL 발급; 일반 JWT 접근 불가 |
| /api/v1/health | 해당 앱용 컨트롤러 없음 | 앱은 ApiResponse 봉투 기대. Actuator를 그대로 연결해 해결되었다고 보지 않음 |

프런트는 이번에 변경하지 않는다. 실제 FE 담당자는 BE 명세 기준으로 갱신 경로/DTO 및 인증 예외 경로 목록을 함께 맞추고 health 사용 여부를 확인해야 한다. 현재 결정에는 BE 하위 호환 alias 추가가 포함되지 않는다. AI 주소/포트는 BE와 별도 미결 계약이다.

## 3. health/readiness (채택한 계약 — 구현 인계)

확정한 배포 원칙: DB 연결·기본 쿼리 성공 없이는 배포 성공으로 처리하지 않는다. 배포 gate는 `/actuator/health/readiness`로 정하고 `readinessState,db`를 포함한다. 아직 설정에 적용하지 않았다. DB 연결 성공만으로 migration·테이블·추천 데이터·주요 API 동작까지 검증된 것은 아니므로 별도 검증도 필요하다.

| 대상 | 현재 사실 | 채택한 검증 계약 |
|---|---|---|
| Nginx /healthz | edge만 나타내는 JSON 200 | edge 생존 확인 전용. DB/API 정상 증거로 사용 금지 |
| BE /actuator/health | SecurityConfig가 정확한 경로를 permitAll; 기존 156 gate 대상 | 내부 종합 진단용. 최종 배포 gate는 아래 readiness로 변경 |
| BE /actuator/health/readiness | 별도 probes 명시 설정/무인증 허용 없음 | 내부 무인증 GET. readinessState와 db 포함. HTTP 200 + application/json + status=UP만 성공; DOWN/OUT_OF_SERVICE는 503. 그 밖의 상태·응답 오류·timeout은 실패. 상세 구성/예외 원문 숨김 |
| BE /actuator/health/liveness | 별도 명시 허용 없음 | 내부 무인증 GET, livenessState만 검사. UP은 200, DOWN/BROKEN은 503. DB/SMTP는 제외하여 외부 의존성 장애로 재시작 반복 방지 |

무인증 probe 허용은 관리 API 전체 공개가 아니다. 154에서 외부 `/actuator` 노출을 차단하고 내부 네트워크/loopback에서만 검사하도록 한다. 지금 보안 matcher나 방화벽을 변경하지 않았다. Actuator 기본/자동 구성과 실제 HTTP 동작은 따로 검증해야 하며, 이 표는 기동 시험 결과가 아니다.

후속 구현에서 적용할 properties 계약 (현재 설정 파일 아님):

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState,db
management.endpoint.health.group.liveness.include=livenessState
```

SMTP는 readiness에서 제외한다. 메일 장애는 비밀번호 복구 기능 실패로 별도 감지하고 사용자에게 재시도를 안내하며, 다른 기능 전체의 트래픽을 차단하지 않는다. 메일 발송 검증 없이 복구 기능을 정상으로 보고하지 않는다. 현재 156의 gate는 정확히 `/actuator/health`만 허용하므로 readiness 설정·보안 matcher·검사 도구·테스트를 함께 수정한 뒤 사용해야 한다. 기존 gate를 바꿨다고 가정하지 않는다.

gate 실행 계약: 개별 요청 timeout 5초, 최대 60초 내 2초 간격 재시도, 연속 3회 성공 후에만 전환한다. 시간 내 미충족이면 전환하지 않고 기존 버전을 유지한다. 전환 후 주요 API smoke 실패 시 이전 이미지·upstream으로 복구한다. 156에서 이 조건의 모의/실제 검증을 담당한다.

## 4. CORS 정책 (채택한 계약)

현 코드에는 `.cors(Customizer.withDefaults())`만 있고 별도 allowlist 또는 `CorsConfigurationSource`가 없다. 이것이 모든 Origin 허용을 뜻하지 않는다. Android 네이티브 HTTP 클라이언트에 브라우저 CORS 문제를 그대로 적용하지 않는다.

결정: 현재 Android BE에는 교차 Origin allowlist를 추가하지 않는다(허용 목록 없음). Swagger UI를 제공할 때는 같은 HTTPS 도메인의 동일 Origin으로 제공한다. 별도 웹/AI 도구에 BE 접근이 필요하면 별도 변경 리뷰에서 정확한 scheme/host/port만 추가한다. `*` + credentials 허용 또는 임의 Origin 반사는 금지한다. CORS는 인증/인가나 네트워크 보안 대체재가 아니다. 현재 보안 코드 수정은 하지 않았다.

## 5. 포트·시작 순서 (채택한 계약)

| 서비스 | 코드/기존 기록 | 운영 연결 계약 |
|---|---|---|
| Nginx | HTTPS 443, HTTP 80→443 | 외부 진입점 유지 |
| BE | Java 21 / Boot 4.1.1, server.port override 없음 | 컨테이너 내부 8080. 호스트 Nginx가 연결할 경우 127.0.0.1:18081/18082를 두 배포 슬롯으로 사용. 153에서 점유 확인 후 사용하며 충돌 시 서비스를 종료하지 않고 매핑 계약을 갱신 |
| MySQL | JDBC 기본값 3306; 2026-09-09 서버 조회에서 8.4.11 healthy 확인 | 컨테이너 내부 3306, 외부 publish 없음. 운영 구성 문서를 따르고 스키마·계정·실제 데이터는 별도 검증. 이번 문서 변경에서 DB 설치/SQL 실행 없음 |
| 개인 S3 | 현재 S3VoiceStorage가 SDK 기본 자격 증명 체인 사용 | BE에서 개인 버킷으로 outbound HTTPS. 버킷 비공개, 추가 inbound 포트 없음. DB-only internal 네트워크로 BE의 S3/DNS 통신을 차단하지 않음 |
| Redis | build.gradle/런타임 코드에 의존성 없음 | 이번 BE 운영 구성에서는 제외. 6379 포트/컨테이너/필수 시작 의존성 없음. 후속 BE 기능에서 필요해지면 별도 계약 변경 |

DB/스키마 준비 및 DB health 성공 → 단일 migration 단계 성공 → BE 시작/JPA validate → 후보 readiness → Nginx 전환 → 외부 기능 smoke 순서로 정한다. 이것은 실행된 절차가 아니다. 단순 depends_on만으로 readiness가 보장되지 않으며 DB health와 앱 readiness를 구분한다. SQL 보류 중 bootRun을 실행하지 않는다.

[MySQL 운영 구성](MYSQL_RUNTIME.md)의 DB healthy는 스키마/초기 데이터/API 검증을 대신하지 않는다. 후보 배포에서는 S3의 실제 업로드·URL GET·삭제도 별도 smoke로 확인하고 실패 시 전환하지 않는다. S3 health indicator를 구현했다는 의미는 아니다.

## 6. 종료·migration (채택한 계약)

- 종료 계약은 `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`, Compose `stop_grace_period=60s`로 정한다. Nginx 신규 트래픽 제거 후 SIGTERM을 보내 진행 중 요청을 마무리한다. 단계별 30초가 전체 종료 30초 보장은 아니므로 156에서 모든 종료 단계·진행 요청을 실측하고 60초 초과 위험 시 유예 시간을 늘린 후 배포한다. 앱 자체 설정은 아직 변경하지 않았다.
- 현재 Flyway starter, MySQL 모듈 및 `V1__create_auth_tables.sql`, `V2__create_password_reset_requests.sql`이 있다. disable 설정이 없어 DB 연결 기동 시 migration이 실행될 수 있다. JPA validate는 SQL 실행 방지 스위치가 아니다.
- 운영은 전용 일회성 migration 단계 성공 후 앱 시작으로 정한다. 여기서 migration 실행기는 DB 변경용 프로세스이며 GitLab Runner 설치를 의미하지 않는다. BE/149의 검토된 버전 SQL을 인프라 CD가 같은 릴리스 단위로 한 번 실행하고 동시 배포는 직렬화한다. 전용 DDL 계정과 최소권한 앱 계정을 분리하며 앱의 Flyway 실행은 별도 실행 단계가 준비된 이후에만 끈다. 실패 시 기존 버전 유지/배포 중단, 파괴적 변경은 별도 승인 및 백업 필요. 현재 Flyway 설정 변경이나 SQL 실행은 없다.
- 기존 migration만으로 곡 카탈로그/추천 테이블과 초기 데이터까지 준비된다고 가정하지 않는다. 149 담당자와 적용 범위를 확인한다. 실패 시 배포 중단, 자동 repair/baseline/볼륨 삭제 금지. 호환성 없는 DB 변경은 이미지 롤백만으로 복구되지 않는다.

## 7. 계약 리뷰와 후속 구현 구분

- [x] 2026-09-09 기준 properties 환경변수 22개와 SDK 전용 3개를 예제/표로 정리 (실제 값 없음).
- [x] 앱/BE 경로 차이, readiness 허용 미비, Redis 의존성 부재를 코드로 확인.
- [x] 사용자 결정: BE 경로 유지, Nginx에서 외부 `/api/v1/` prefix 제거, 앱 갱신 경로를 BE 명세에 맞추는 방향 채택.
- [x] 사용자 결정: DB 연결·기본 쿼리 성공을 배포 성공 필수 조건으로 채택.
- [x] 위임된 인프라 결정: readiness/liveness 경로·응답·노출 범위, CORS, 포트/Redis 제외, 시작·migration·종료 계약 문서화.
- [x] 최초 150 계약 문서는 MR !28로 2026-09-08 병합됨. 이번 개인 S3 후속 문서 변경은 별도 리뷰 대상이며 최초 승인과 구분한다.

아래는 150의 문서 MR을 Draft로 유지할 조건이 아니라 후속 구현 인계 항목이다.

- FE 후속: 갱신 경로·DTO·인증 예외 목록 및 앱 health 사용 여부 확인(본인 FE 보류 유지).
- 149·153: DB 버전/계정·migration 실행기·Compose/포트/영속성 준비(SQL 보류 유지).
- 154: Nginx prefix 전달, `/actuator` 및 `/api/v1/actuator` 외부 차단, 동일 Origin Swagger 라우팅 검증.
- 155·156: readiness 설정/무인증 matcher/검사 도구 동시 변경, DB 실패 시 전환 차단, SIGTERM/복구 실검증.
- 운영 비밀값은 저장소 밖 파일/자격 증명으로 주입하고 MR·로그에 출력하지 않는다. 실제 SMTP 공급자/자격 증명/TLS 구성과 발송 시험은 운영 기능 준비 단계에서 확인한다.

`python -m unittest discover -s backend/contract_tests -v`는 파일 내용만 검사한다. 애플리케이션·DB·SMTP·S3에 연결하거나 비밀값을 읽지 않으며, 팀 승인이나 운영 성공을 증명하지 않는다. 후속 문서 MR은 Ready for review로 제출하며 직접 병합하지 않는다. 최초 150은 완료 상태이고, 이 문서 변경만으로 후속 런타임 구현 이슈를 완료 처리하지 않는다.

## 근거

코드: `backend/src/main/resources/application.properties`, `auth/config/SecurityConfig.java`, `AuthProperties.java`, `MailConfig.java`, `backend/build.gradle`, `docs/openapi.yaml`, 앱 `BackendApi.kt`, `infra/nginx/micon-https.conf`.

공식 참고: [Actuator health/probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [Graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html), [Flyway 기동 시 migration](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html). 프레임워크 기본값은 공식 안내, 이 저장소의 설정 여부는 코드 기준이다.
