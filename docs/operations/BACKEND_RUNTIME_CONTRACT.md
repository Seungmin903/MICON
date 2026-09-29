# BE 운영 실행 계약 — 인프라 결정 및 구현 인계

Jira S15P21B110-150 / 담당 byoungwooheo / 기준 develop `5be1792` / 2026-09-08.

2026-09-10 구현 갱신: S15P21B110-153 브랜치에서 [BE 운영 컨테이너](BACKEND_CONTAINER_RUNTIME.md), prod health 설정과 비-root secret 준비를 구현했다. SSAFY 서버 후보 포트에서 MySQL 8.4.11/V10, readiness/liveness, 카탈로그 S3 실제 GET·SHA-256 및 컨테이너 단독 재시작을 검증했다. Nginx 실서비스 전환·앱/AI 통합은 아직 미완료다. 아래 2026-09-08 항목 중 “미구현” 표시는 역사적 계약 설명이며, 현재 구현 상태는 이 갱신과 운영 컨테이너 문서를 우선한다.

**사용자의 결정 위임에 따라 인프라 계약을 정한 리뷰본이다.** 현재 코드 사실과 앞으로 적용할 계약을 분리한다. MR은 문서·예제 검토용이며 팀원 승인 또는 운영 검증 완료를 뜻하지 않는다. SQL 및 본인 프런트 구현 보류를 유지한다. 150의 범위는 계약 문서화이며 실제 구현·배포 검증은 149·153·154·155·156 및 FE 후속 작업에 인계한다.

### 2026-09-08 사용자 결정 (정책 확정, 구현·운영 검증 전)

- 기존 BE 코드·OpenAPI 경로를 기준으로 맞춘다. 외부 기준 주소는 `https://j15b110.p.ssafy.io/api/v1/`로 유지하고 Nginx에서 `/api/v1/` 접두어만 제거해 전달한다. BE에 동일 context-path를 추가하지 않는다.
- 2026-09-10 인증 경로 갱신: 로그인·회원가입·로그아웃·갱신을 각각 `POST /auth/login`, `POST /auth/signup`, `POST /auth/logout`, `POST /auth/refresh`로 통일했다. Android 호출부와 인증 예외 처리도 함께 반영했다. 배포 검증은 별도다.
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
| GOOGLE_WEB_CLIENT_ID | 빈 값 | Google ID 토큰의 `aud`와 일치해야 하는 OAuth 2.0 Web client ID. Android의 Google 로그인 설정과 같은 Google Cloud 프로젝트에서 발급하며, client secret이 아니므로 런타임 환경에 명시 주입 |
| GOOGLE_JWK_SET_URI | `https://www.googleapis.com/oauth2/v3/certs` | Google ID 토큰 서명 검증에 쓰는 공개 JWK Set HTTPS 주소. 기본 Google 주소를 사용하면 생략 가능하며, 임의 URL로 바꾸기 전에는 발급자·키 회전 계약을 함께 검토 |
| ACCESS_LOG_LEVEL | INFO | `micon.access` 전용 로그 수준. DEBUG는 정상 health/background DB 조회까지 포함. 토큰·본문·SQL 값은 기록하지 않음. [조회 방법](BACKEND_ACCESS_LOGS.md) |
| DB_ACCESS_LOG_ENABLED | true | DataSource 쿼리/연결 실패 메타데이터 수집 여부. 변경 시 BE 재생성 필요 |
| DB_ACCESS_SLOW_QUERY_MS | 1000 | 이 시간 이상 걸린 쿼리는 WARN. SQL 원문/파라미터/결과는 제외 |
| MAIL_FROM | no-reply@micon.local | SMTP 제공자가 허용한 발신 이메일 주소 |
| MAIL_HOST | localhost | 합의된 SMTP 호스트 |
| MAIL_PORT | 25 | 1~65535 정수, 제공자와 일치 |
| MAIL_USERNAME | 빈 값 | 인증 SMTP 사용 시 계정 |
| MAIL_PASSWORD | 빈 값 | 인증 SMTP 사용 시 별도 비밀값 |
| MAIL_SMTP_AUTH | false | true/false. 제공자 요구와 일치; 운영 false를 임의 채택하지 않음 |
| MAIL_STARTTLS | false | true/false. 제공자 TLS 방식과 일치; enable=true만으로 TLS 필수 강제가 되었다고 보지 않음 |
| AI_SERVICE_TOKEN | 빈 값 | AI 서비스 인증용 비밀값. 실제 AI 인증 계약 확인 후 별도 주입 |
| AI_VOICE_ANALYSIS_ENABLED | false | true/false. BE와 음성 분석 AI의 요청·callback 계약을 통합 검증한 뒤에만 true |
| AI_ARTIST_SEARCH_ENABLED | true | 운영 AI·BE 계약 검증이 완료되어 닮은 가수 작업·callback을 활성화. 호스트 준비 시 기존 false도 true로 교정 |
| AI_ARTIST_SEARCH_SCORING_VERSION | ecapa-singer-byol-absolute-fusion-v3 | AI의 ARTIST_SEARCH callback `modelVersion`과 정확히 일치해야 함. 버전 변경은 신규 작업에만 적용됨 |
| AI_VOICE_ANALYSIS_CONTRACT_VERSION | v1 | AI 작업 접수·컴포넌트 콜백 계약 버전. AI 서버와 같은 값 사용 |
| AI_VOICE_ANALYSIS_GUIDE_VERSION | v1 | AI 담당자와 맞춘 분석 가이드 버전 |
| AI_VOICE_ANALYSIS_DOWNLOAD_URL_TTL | 30m | 기존 다운로드 URL 경로의 만료 Duration. WAV를 직접 전달하는 음성 분석 경로에서는 사용하지 않음 |
| AI_VOICE_ANALYSIS_MAX_FILE_BYTES | 262144000 | 직접 분석 WAV의 크기 상한(바이트). multipart 및 프록시 제한도 함께 적용됨 |
| AI_VOICE_ANALYSIS_CONNECT_TIMEOUT | 3s | 음성 분석 AI 접수 연결 제한 시간인 Spring Duration |
| AI_VOICE_ANALYSIS_READ_TIMEOUT | 30s | 음성 분석 AI 접수 응답 제한 시간인 Spring Duration |
| AI_VOICE_ANALYSIS_MAX_WAIT | 10m | 접수된 음성 분석의 완료 대기 상한인 Spring Duration |
| AI_VOICE_ANALYSIS_TIMEOUT_CHECK_INTERVAL | 1m | 미완료 음성 분석의 timeout 검사 주기인 Spring Duration |
| AI_SONG_FIT_ENABLED | false | true/false. AI 준비 상태에 따라 명시적으로 선택 |
| AI_RECOMMENDATION_BASE_URL | http://localhost:8788 | 음성 분석·곡 적합도·닮은 가수·추천이 함께 사용하는 recommend-server 주소. 운영 Compose에서는 `http://micon-recommendation:8788`을 주입 |
| AI_RECOMMENDATION_ENABLED | false | true/false. 추천 작업 제출·조회 계약과 서비스 인증을 통합 검증한 뒤에만 true |
| AI_RECOMMENDATION_SERVICE_TOKEN | AI_SERVICE_TOKEN 값 | 추천 AI 서비스 인증용 비밀값. 별도 값이 없으면 공통 AI 서비스 토큰을 사용하며 추적 파일에 기록하지 않음 |
| AI_RECOMMENDATION_CONNECT_TIMEOUT | 3s | 추천 AI 연결 제한 시간인 양의 Spring Duration |
| AI_RECOMMENDATION_READ_TIMEOUT | 10s | 추천 작업 접수·상태 조회 응답 제한 시간인 양의 Spring Duration |
| AI_RECOMMENDATION_REQUIRED_SHELVES_VERSION | 빈 값 | 빈 값이면 기존 완료 추천을 숨기지 않음. BE와 AI의 동일 선반 정책 반영을 확인한 뒤 `shelves-v6-stability-soft-score`로 설정하면 구형 완료/빈 latest를 404로 돌려 새 추천을 요청하게 함. 기존 결과 삭제나 생성 중 작업의 강제 종료는 하지 않음 |
| AI_RECOMMENDATION_POLLING_ENABLED | false | true/false. 추천 작업 상태를 주기적으로 조회할지 명시적으로 선택 |
| AI_RECOMMENDATION_POLL_INTERVAL | 1500ms | 추천 작업별 상태 조회가 끝난 뒤 다음 폴링까지 기다리는 양의 Spring Duration |
| AI_RECOMMENDATION_POLL_INITIAL_DELAY | 5s | 애플리케이션 시작 후 첫 추천 폴링까지 기다리는 음수가 아닌 Spring Duration |
| AI_RECOMMENDATION_POLL_MAX_CONCURRENT | 4 | 추천 상태 조회의 프로세스 내 최대 동시 실행 수인 양의 정수. 여러 BE 인스턴스의 전역 한도는 아님 |
| AI_RECOMMENDATION_DISPATCH_RETRY_INTERVAL | 2s | AI 큐 포화(접수 전 503)로 보류된 추천 재접수 작업의 실행 간격인 양의 Spring Duration |
| AI_RECOMMENDATION_DISPATCH_RETRY_INITIAL_DELAY | 5s | 애플리케이션 시작 후 첫 추천 재접수 작업까지 기다리는 음수가 아닌 Spring Duration |
| AI_RECOMMENDATION_RECOVERY_INTERVAL | 1m | 추천 콜백 유실 복구 작업의 실행 간격인 양의 Spring Duration |
| AI_RECOMMENDATION_RECOVERY_INITIAL_DELAY | 10s | 애플리케이션 시작 후 첫 추천 복구 작업까지 기다리는 음수가 아닌 Spring Duration |
| SCHEDULED_TASK_POOL_SIZE | 4 | Spring 예약 작업 실행 스레드 풀 크기인 양의 정수. 추천 재접수·폴링 외 예약 작업도 이 풀을 공유 |
| VOICE_ANALYSIS_REQUEST_MAX_SIZE | 250MB | multipart 파일 및 전체 요청 제한. 운영 Compose 예제는 기존 의도대로 25MB를 명시하며 프록시·부가정보 크기도 함께 고려. 이전 VOICE_RECORDING_MAX_SIZE 이름은 더 이상 읽지 않음 |
| VOICE_STORAGE_BUCKET | 빈 값 | 개인 계정 비공개 버킷 `micon-b110-prod-apne2-20260909`. 운영 실행기에 명시 주입; 추적 예제는 빈 값 유지 |
| VOICE_STORAGE_REGION | ap-northeast-2 | 개인 버킷과 일치하는 서울 리전 |
| VOICE_STORAGE_PREFIX | voice-recordings | 객체 prefix. IAM의 voice-recordings/*와 함께 변경; catalog/와 분리 |
| VOICE_STORAGE_PLAYBACK_URL_TTL | 10m | 녹음 재생 presigned GET URL 만료 Duration |
| VOICE_STORAGE_MAX_FILE_BYTES | 26214400 | S3 업로드 전 파일 크기 상한(바이트), 프록시·multipart 제한과 함께 검증 |
| VOICE_STORAGE_DELETION_RETRY_INTERVAL | 30s | 삭제 큐에 남은 저장 녹음의 삭제 재시도 주기인 Spring Duration |
| CATALOG_MEDIA_ALLOWED_PREFIX | catalog/정영주/section-384-v1/vocals/ | S15P21B110-228에서 승인한 보컬 stem 경로. DB object key와 정확히 일치해야 하며 다른 catalog 경로는 서명 거부 |
| CATALOG_MEDIA_DOWNLOAD_URL_TTL | 10m | AI 전용 보컬 stem presigned GET URL 만료 Duration. 1시간 초과 설정 거부 |
| SONG_PREVIEW_OBJECT_PREFIX | catalog/정다운/source-preview-pilot-20260922/ | 881곡 원곡 60초 미리듣기 MP3의 고정 catalog prefix. songId-preview-60s.mp3 외 객체는 앱에 노출하지 않음 |
| SONG_PREVIEW_DOWNLOAD_URL_TTL | 10m | 원곡 미리듣기 presigned GET URL 만료 Duration. 1시간 초과 설정 거부 |
| SPOTIFY_IMAGE_ENABLED | true | true/false. 곡 이미지 조회를 비활성화할 때 false. 자격 증명이 없으면 외부 호출을 수행하지 않음 |
| SPOTIFY_ACCOUNTS_BASE_URL | https://accounts.spotify.com | Spotify Client Credentials 토큰 발급 API의 HTTPS 기준 주소 |
| SPOTIFY_API_BASE_URL | https://api.spotify.com | Spotify Web API의 HTTPS 기준 주소 |
| SPOTIFY_CLIENT_ID | 빈 값 | Spotify 서버 애플리케이션 Client ID. 저장소 밖의 런타임 환경 또는 GitLab 보호 변수로만 주입 |
| SPOTIFY_CLIENT_SECRET | 빈 값 | Spotify 서버 애플리케이션 비밀값. 추적 파일·로그에 기록하지 않고 GitLab 보호 변수로 주입 |
| SPOTIFY_MARKET | KR | 이미지 검색에 사용할 ISO 3166-1 alpha-2 시장 코드 |
| SPOTIFY_MAX_LOOKUPS_PER_REQUEST | 20 | 한 요청에서 허용하는 Spotify 조회 횟수 상한인 양의 정수 |
| SPOTIFY_IMAGE_CACHE_TTL | 6h | 조회 성공 결과의 로컬 캐시 만료 시간인 양의 Spring Duration |
| SPOTIFY_IMAGE_MISS_CACHE_TTL | 10m | 조회 실패 결과의 로컬 캐시 만료 시간인 양의 Spring Duration |
| SPOTIFY_IMAGE_CACHE_MAX_ENTRIES | 5000 | Spotify 이미지 캐시의 최대 항목 수인 양의 정수 |
| SPOTIFY_CONNECT_TIMEOUT | 2s | Spotify 연결 제한 시간인 양의 Spring Duration |
| SPOTIFY_READ_TIMEOUT | 3s | Spotify 응답 제한 시간인 양의 Spring Duration |
| VOICE_DATA_POLICY_VERSION | UNCONFIRMED | 공개 음성 데이터 정책 버전. 정책 승인 전에는 `UNCONFIRMED`를 유지하고 확정 버전처럼 표시하지 않음 |
| VOICE_DATA_COLLECTION_PURPOSE | 코드의 한국어 기본값 | 공개 수집 목적 문구. 기획·개인정보 정책 승인 후 운영 환경에 명시 |
| VOICE_DATA_RETENTION_PERIOD | 정책 확정 전 | 공개 보관 기간 문구. 실제 삭제·보관 동작과 일치하도록 승인 후 명시 |
| VOICE_DATA_DELETION_POLICY | 코드의 한국어 기본값 | 공개 삭제 정책 문구. 개별·전체 삭제와 원본 보관 동작을 바꿀 때 함께 갱신 |
| AWS_SHARED_CREDENTIALS_FILE | SDK 기본 탐색 경로 | Spring placeholder 아님. BE 컨테이너의 /run/secrets/aws_credentials로 명시; 호스트 파일 읽기 전용 마운트 |
| AWS_PROFILE | SDK default 프로필 | Spring profile 아님. 개인 계정 BE 전용 프로필 micon-backend 사용 |
| AWS_EC2_METADATA_DISABLED | SDK 기본값 false | BE에 true 명시하여 SSAFY 인스턴스 역할로의 fallback 차단 |

빈 예제는 실행용 기본값 목록이 아니다. 실제 환경파일에서 값을 비워 주입하면 코드 기본값이 적용되지 않거나 타입 변환에 실패할 수 있다. 운영 담당자가 위 형식과 필수값을 확인해 채우거나, 선택 항목은 의도적으로 생략한다.

AWS 키 원문은 환경변수 예제·이미지·Git에 넣지 않고 서버 외부 파일에서 SDK가 읽는다. 상위 우선순위 AWS 환경변수/Java 시스템 속성/web identity 설정과 혼용하지 않는다. 파일 권한은 BE 실행 UID/GID와 맞춰 검증한다. 자세한 IAM 정책·Compose 부분 예시·비용·회전 절차는 [S3 운영 계약](S3_VOICE_STORAGE.md)을 따른다. S3 설정만으로 DB import나 AI 서비스 배포가 완료되지는 않는다.

운영 계약: DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET은 명시 주입하고 빈 값·root 계정·로컬 fallback 사용을 배포 전 검사에서 거부한다. 메일 기능을 제공할 때는 MAIL_FROM/HOST/PORT 및 AUTH/STARTTLS를 명시하고 인증 사용 시 USERNAME/PASSWORD도 필수다. SMTP 제공자와 호환되는 인증·필수 TLS 및 유한 타임아웃 검증 없이 운영 메일 기능을 정상으로 처리하지 않는다. 이 표는 현재 fallback 제거나 fail-fast 구현을 뜻하지 않는다. JWT issuer=micon-backend, access TTL=30m, refresh TTL=30d는 현재 properties의 값이다. 기본 profile은 local이며 별도의 production profile 파일은 아직 없다. `SPRING_PROFILES_ACTIVE=prod`만 지정해도 로컬 fallback이 사라진다고 가정하지 않는다.

SMTP는 비밀번호 복구 기능에 필요하다. `MailConfig`는 STARTTLS enable만 설정하고 required/SMTPS 전용 설정은 현재 구현하지 않았다. 제공자 인증·TLS·타임아웃 계약과 해당 설정 보완 후 실제 발송을 검증해야 한다. 메일 서버를 준비하지 않은 상태에서 비밀번호 복구 완료를 주장하지 않는다.

## 2. 외부 API 경로 (사용자 결정 — 아직 미적용)

채택한 외부 기준 주소는 `https://j15b110.p.ssafy.io/api/v1/`이다. 도메인은 기존 서버 기록에 있으나 마지막 확인된 Nginx 구성은 `/healthz` 외 요청에 의도적으로 503을 반환한다. 이 주소는 동작 확인된 API 주소가 아니다.

결정: BE 내부 경로는 현 코드를 유지하고 Nginx에서 `/api/v1/` prefix만 제거해 전달한다. HTTP 메서드·쿼리·본문은 유지하며 인증 경로를 별도로 치환하지 않는다. BE context-path까지 동시에 추가하지 않는다.

| 외부 경로 계약 / 미해결 항목 | 현재 BE 내부 경로 | 상태 |
|---|---|---|
| /api/v1/songs | /songs | 곡 조회 코드 존재, 운영 호출 미검증 |
| /api/v1/users/me | /users/me | 회원 조회 코드 존재, 운영 호출 미검증 |
| /api/v1/auth/refresh | /auth/refresh | POST, BE/OpenAPI/Android 경로 일치; 운영 미검증 |
| /api/v1/recordings 및 /api/v1/recordings/{id}/content | /recordings 및 /recordings/{id}/content | 업로드·조회·삭제·재생 URL 코드 존재. content는 URL을 담은 JSON이며 S3 공개 주소/리다이렉트 계약이 아님; 운영 미검증 |
| AI 내부 보컬 stem 조회 | /internal/catalog-media/vocal-stems/{songId} | AI 서비스 토큰 전용. DB의 검증된 object key에만 단기 URL 발급; 일반 JWT 접근 불가 |
| /api/v1/health | /health | 앱 연결 확인용 공개 `ApiResponse{data:{status:"UP"}}`. DB 포함 배포 판정은 별도 `/actuator/health/readiness` 사용 |

인증 경로 변경은 Android API 선언과 인증 예외/갱신 처리에도 반영했다. health 사용 여부는 별도로 확인해야 한다. 기존 인증 경로의 하위 호환 alias는 제공하지 않으므로 BE와 앱을 함께 전환해야 한다. 상세 요청·응답은 [인증 API 문서](../AUTH_API.md)를 참고한다. AI는 [추천 AI 운영 컨테이너](RECOMMENDATION_CONTAINER_RUNTIME.md)의 내부 8788 연결 계약을 따른다.

## 3. health/readiness (153 구현·후보 검증 완료)

확정한 배포 원칙: DB 연결·기본 쿼리 성공 없이는 배포 성공으로 처리하지 않는다. `application-prod.properties`에 `/actuator/health/readiness`의 `readinessState,db`와 DB를 제외한 liveness를 적용했고 후보 서버에서 두 경로 모두 HTTP 200 `{"status":"UP"}`를 확인했다. DB 연결 성공만으로 추천 데이터·주요 API 동작까지 검증된 것은 아니므로 별도 검증도 필요하다.

| 대상 | 현재 사실 | 채택한 검증 계약 |
|---|---|---|
| Nginx /healthz | edge만 나타내는 JSON 200 | edge 생존 확인 전용. DB/API 정상 증거로 사용 금지 |
| BE /actuator/health | health 하위 경로만 permitAll, 상세 숨김 | 내부 종합 진단용. 최종 배포 gate는 아래 readiness |
| BE /actuator/health/readiness | prod 설정·무인증 허용·DB 포함 구현, 후보 HTTP 200 확인 | 내부 무인증 GET. readinessState와 db 포함. HTTP 200 + application/json + status=UP만 성공 |
| BE /actuator/health/liveness | prod 설정·무인증 허용·DB 제외 구현, 후보 HTTP 200 확인 | 내부 무인증 GET, livenessState만 검사. 외부 의존성 장애로 재시작 반복 방지 |

무인증 probe 허용은 관리 API 전체 공개가 아니다. 컨테이너 포트는 loopback에만 publish했고 154 브랜치의 Nginx 설정은 외부 `/actuator`, `/internal` 및 `/api/v1` 아래 같은 경로를 명시적으로 404 처리한다. 방화벽 공개 포트는 추가하지 않았다.

적용한 properties 계약:

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState,db
management.endpoint.health.group.liveness.include=livenessState
```

SMTP는 readiness에서 제외한다. 메일 장애는 비밀번호 복구 기능 실패로 별도 감지하고 사용자에게 재시도를 안내하며, 다른 기능 전체의 트래픽을 차단하지 않는다. 메일 발송 검증 없이 복구 기능을 정상으로 보고하지 않는다. 156 브랜치의 검사 도구는 readiness 3회 연속 성공과 실제 카탈로그 S3 GET을 검사하도록 구현·후보 검증했다.

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
