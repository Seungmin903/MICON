# 팀원용 로컬 실행 안내

기준: 2026-09-07, develop `61d2c84` + 이 MR의 Android CI 변경. 규칙 원본은 [JIRA_GIT_RULES.md](../../JIRA_GIT_RULES.md) V3.0이다.

2026-09-09 갱신: develop `fcc00d2`에는 BE API·S3 연동 코드와 `vocal-scope/`가 있다. **코드 존재와 운영 통합 완료는 다르다.** BE·MySQL은 SSAFY 서버, 파일 저장은 개인 AWS S3를 사용한다. [S3 운영 계약](S3_VOICE_STORAGE.md)의 버킷·BE 전용 IAM·서버 키 저장과 CLI 직접 연결은 검증했으나 BE 배포/통합과 팀원 로컬 업로드 권한은 아직 준비 중이다. 운영 BE 키를 로컬 개발용으로 공유하지 않는다. 아래 명령은 Windows PowerShell 기준이다.

## 누가 무엇을 준비하나

| 담당 | 로컬 준비 | 불필요한 설정 |
|---|---|---|
| 전원 | Git·Git LFS, 최신 develop 기반 작업 브랜치 | 폐기된 `.githooks` 설치, 운영 비밀정보 복사 |
| FE | Android Studio, SDK 37, JBR 21, 개인 `local.properties`, 에뮬레이터/실기기 | 운영 Nginx·Certbot·UFW 설치, AWS 키 수령/앱 삽입 |
| BE | JDK 21, 프로젝트 Gradle Wrapper; 로컬 MySQL·스키마·앱 계정, 승인된 개발용 S3 인증 | 운영 DB 외부 공개, 서버 PEM·운영 AWS 키 공유 |
| AI | 담당 브랜치 README의 Python 환경·모델·데이터 준비 | Android 개발자 전원에게 모델 환경 설치 강제 |
| 인프라 | SSAFY 서버·Jenkins 관리, 개인 S3 버킷·최소권한·비용·키 회전 관리 | 팀원 PC에 서버 환경을 그대로 복제 |
| 데이터 업로더 | 승인된 파일만 모은 폴더, AWS CLI, 팀원별 catalog/ 업로더 인증 | BE 런타임 키, DB 공개 접속, 루트 계정 공유 |

MySQL은 [운영 구성](MYSQL_RUNTIME.md)의 8.4.11을 기준으로 하며 초기 스키마·마이그레이션 적용 범위는 BE/DB 담당자와 확인한다. `compose.smoke.yaml`은 설치 점검 전용이고 DB Compose가 아니다. 버킷·리전·권한 전달, 팀원 로컬 파일 업로드 및 DB 적재 구분은 [S3 운영 계약](S3_VOICE_STORAGE.md)을 따른다.

## 1. 저장소와 V3 준비

새 클론은 `C:\dev` 같은 영문 경로를 권장한다. Android Gradle Plugin의 비ASCII 경로 오류를 피하기 위한 것으로, 기존 작업 폴더를 삭제하거나 강제 이동하지 않는다.

```powershell
# 준비한 영문 상위 폴더에서 실행
git lfs install
git clone https://lab.ssafy.com/s15-ai-speech-sub1/S15P21B110.git
cd S15P21B110
git status -sb
git fetch origin
# 아래 이름은 예시. 자신의 작업에 맞는 미사용 타입/camelCase 이름을 쓴다.
git switch -c feat/myTask origin/develop
```

기존 클론은 변경 파일을 먼저 확인하고 보존한다. 다른 작업 중인 브랜치를 덮어쓰거나 `reset --hard`하지 않는다. 이 안내가 아직 develop에 머지되지 않았다면 MR 브랜치에서 문서를 읽을 수 있지만, 새 기능 작업은 최신 develop에서 시작한다.

이전 규칙에서 `.githooks`를 지정했던 사람만 해당 로컬 설정을 해제한 뒤 LFS 훅을 정상 위치에 설치한다. 다른 사용자 정의 훅 경로는 임의로 바꾸지 않는다.

```powershell
if ((git config --local --get core.hooksPath) -eq '.githooks') {
    git config --local --unset core.hooksPath
}
git lfs install --local
git lfs pull
git lfs fsck
```

훅 충돌이 뜨면 `--force`로 덮어쓰지 말고 담당자에게 확인한다. 곡 카탈로그는 백엔드 API에서 조회하며 앱에 `micon_catalog.sqlite`를 포함하지 않는다. 저장소의 다른 LFS 객체는 다운로드 인증과 포인터 상태를 확인한 뒤 빌드한다.

V3: `main`/`develop` 직접 push 금지, 브랜치는 `타입/camelCase`, 커밋은 `타입: 설명`(전체 이슈 키 선택), MR은 `[전체 Jira 키] (파트) 요약`. MR 대상은 develop, squash·소스 브랜치 삭제 옵션, 다른 팀원의 리뷰·머지가 필요하다. A0 이슈 자동 생성은 문서상 설계 중이므로 존재하는 이슈 키를 사용한다. 폐기된 `JIRA_GIT_RULES_V*.md`와 `.githooks/`를 다시 커밋하지 않는다.

## 2. Android 실행

1. Android Studio에서 저장소 루트가 아닌 `frontend/android`를 연다.
2. SDK Manager에서 Android SDK Platform 37, Build Tools 36.0.0, Platform Tools를 준비한다. 에뮬레이터 또는 Android 7.0/API 24 이상 실기기를 준비한다.
3. Gradle JVM은 JBR 21 기준으로 맞춘다. 저장소의 `gradle/gradle-daemon-jvm.properties`가 JetBrains 21을 지정한다. 개인 PC의 절대 JDK 경로를 공용 `gradle.properties`에 넣지 않는다.
4. `frontend/android/local.properties`가 없다면 `local.properties.example`을 복사한다. Studio가 이미 만든 파일이 있으면 덮어쓰지 않고 필요한 항목만 추가한다. `sdk.dir`은 자신의 실제 SDK 경로로, 구분자는 `/`로 적는다.

에뮬레이터용 설정 예시(각 서비스는 PC에서 별도 실행해야 한다):

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
ENGINE_BASE_URL=http://10.0.2.2:8787/
API_BASE_URL=http://10.0.2.2:8080/api/v1/
```

주소 우선순위: **local.properties → 환경변수 → 코드 기본값**. 마지막 `/`를 유지한다. 이 값은 BuildConfig에 들어가므로 수정 후 다시 빌드·설치한다. 환경변수를 바꿨는데 반영되지 않으면 local.properties의 기존 값을 먼저 확인한다.

```powershell
cd frontend\android
# 설치된 JBR 경로가 아래와 다르면 실제 경로로 변경
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --version
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

산출물: `frontend/android/app/build/outputs/apk/debug/app-debug.apk`. Studio의 Run으로 선택한 장치에 실행할 수 있다. 소셜 로그인 키와 Release 서명 값은 Debug UI 빌드에 필수가 아니다. 키가 없으면 해당 인증 기능은 사용할 수 없으며, 현재 소셜 SDK 의존성은 주석 처리돼 있다.

공유 CI 근거는 Jenkins #4의 단위 테스트 21개 통과, Debug APK 생성·LFS·서명 검증이다. **이 안내 자체를 새 PC에서 끝까지 재현하거나 새 통합 브랜치를 재빌드한 결과는 아니다.** 최신 코드 재현 중 오류가 나면 커밋·JDK/SDK 버전·실패 task를 남기되 비밀값은 가린다.

2026-09-07 결정: GitLab MR의 자동 빌드 결과 표시는 구현하지 않는다. 자동 빌드 트리거는 유지하며 결과는 Jenkins에서 확인한다. MR 리뷰 때 소스 커밋 SHA가 일치하는 빌드 URL·번호·테스트 결과를 기록하고 확인한다. `Ready to merge`만으로 CI 통과를 판단하지 않는다. [상세 CI 범위](JENKINS_ANDROID_CI.md)

### USB 실기기에서 내 PC 연결

휴대폰에서 직접 개발자 옵션·USB 디버깅을 허용하고 PC 연결을 승인한다. `adb`가 PATH에 없다면 SDK의 `platform-tools` 폴더에서 실행한다.

```powershell
adb devices
# 장치가 하나일 때. 여러 개면 각 명령에 -s <선택한 장치 ID> 사용
adb reverse tcp:8787 tcp:8787
adb reverse tcp:8080 tcp:8080
```

앱 로컬 설정을 다음과 같이 바꾸고 다시 빌드한다:

```properties
ENGINE_BASE_URL=http://127.0.0.1:8787/
API_BASE_URL=http://127.0.0.1:8080/api/v1/
```

`10.0.2.2`는 Android 에뮬레이터 전용이다. USB reverse는 개발 연결이며 APK 외부 배포용 주소가 아니다. 현재 앱 HTTP 허용은 `10.0.2.2`, `127.0.0.1`, `localhost`로 제한돼 있으므로 임의 LAN HTTP IP로 바꾸지 않는다. 서명이 달라 업데이트 설치가 실패하면 기존 앱을 삭제하지 말고 데이터 보존·서명 정책을 먼저 확인한다.

## 3. 백엔드 실행 준비

코드 기준: Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.7.1, MySQL/JPA/Flyway/Security/Actuator. 로컬에 별도 전역 Gradle을 설치할 필요는 없다.

저장소 루트에서 다음 단위 테스트를 실행할 수 있다. 테스트 설정은 H2 메모리 DB를 사용하므로 별도 MySQL 접속 정보가 필요하지 않다. 통과 여부는 실제 명령 결과로 확인하며 운영 MySQL 호환성 검증을 대신하지 않는다.

```powershell
cd backend
# JAVA_HOME은 설치된 JDK 21의 실제 경로로 설정
.\gradlew.bat --version
.\gradlew.bat test
```

실제 `bootRun` 전에는 BE 담당자가 **로컬 MySQL 버전·micon DB·초기 스키마/마이그레이션·최소권한 앱 계정**을 준비해야 한다. JPA는 `ddl-auto=validate`지만 Flyway migration 파일은 존재하며 기동 시 DB를 변경할 수 있다. SQL 보류/승인 조건과 migration 범위를 먼저 확인하고 임의 SQL 덤프를 운영 DB에 실행하지 않는다.

설정은 터미널 프로세스 환경변수 또는 IDE Run Configuration으로 제공한다:

| 변수 | 로컬 예시/입력 |
|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/micon?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` |
| `DB_USERNAME` | BE 담당자와 정한 로컬 앱 계정; 기본 root 사용을 피한다 |
| `DB_PASSWORD` | 개인 로컬 DB 비밀번호, Git·MR·로그에 기록 금지 |
| `JWT_SECRET` | 개발 전용 무작위 비밀값, UTF-8 32바이트 이상; 운영 키 복사 금지 |
| `AI_*` | 실제 접근할 AI 주소·인증·분석 설정. 전체 이름은 BE 실행 계약 참고 |
| `VOICE_STORAGE_*`, `VOICE_ANALYSIS_REQUEST_MAX_SIZE` | 승인된 개발용 버킷·리전·prefix·URL 만료·용량 |
| `AWS_SHARED_CREDENTIALS_FILE`, `AWS_PROFILE`, `AWS_EC2_METADATA_DISABLED` | 개발 전용 AWS 프로필·로컬 파일 경로, metadata 비활성화 true. 서버 컨테이너 경로를 PC에 그대로 쓰지 않음 |

`.env` 파일만 만들면 현재 Spring 설정이 자동으로 읽어주는 것은 아니다. 환경변수가 실행 프로세스에 전달되도록 설정하고, 위 준비가 끝난 후 `backend` 폴더에서 `.\gradlew.bat bootRun`한다. 운영용 DB URL/계정은 이 예시와 별도로 안전하게 관리한다.

BE API·Security·S3 연동 코드는 있지만 프로세스 시작만으로 로그인·추천·녹음이 정상이라고 판단하지 않는다. 전체 환경변수와 health 계약은 [BE 실행 계약](BACKEND_RUNTIME_CONTRACT.md), 값 없는 검토 템플릿은 [backend/.env.example](../../backend/.env.example)을 참고한다. 메일 기능을 시험하면 SMTP도 준비한다. S3 연결은 합성 파일로 업로드·발급 URL 실제 GET·삭제를 별도 검증한다.

## 4. AI와 주소 계약

로컬 VocalScope 코드는 [vocal-scope/README.md](../../vocal-scope/README.md)에 있다. 추천/모델 작업의 실제 사용할 커밋은 AI 담당자와 맞춘다. AI 담당자가 정한 README에 따라 Python 환경·모델을 준비하며 서로 다른 브랜치의 모델·스키마를 임의로 섞지 않는다. FE 화면 개발을 위해 모든 팀원이 모델 환경을 설치할 필요는 없다. 로컬 workspace 저장을 S3 자동 동기화로 오해하지 않는다.

현재 앱 기본 엔진 포트는 `8787`이지만 [OpenAPI 초안](../openapi.yaml)은 AI 기본 포트 `8000`을 적고 있다. BE도 OpenAPI 기본 URL에는 `/api/v1/`이 없으나 앱은 이 접두사를 기대한다. **BE·AI·FE·인프라가 경로/포트/응답 계약을 확정해야 하며, 이 문서에서 임의로 바꾸지 않았다.**

## 5. 운영 서버와 비밀정보

- 운영 Nginx·TLS·Docker는 서버에 설정돼 있다. 팀원 PC에 Nginx·Certbot·UFW를 설치하거나 운영 PEM을 받을 필요가 없다.
- `https://j15b110.p.ssafy.io/healthz`의 200은 Nginx만 정상이다. 초기 API 503 기록 이후 MySQL 컨테이너 healthy는 확인했지만 BE/AI 통합 배포 성공은 별도 검증 대상이다. 앱 주소를 도메인으로 바꾼 것만으로 서버 연동이 완료되지 않는다.
- AWS 자격 증명 파일도 저장소·이미지·APK 밖에 둔다. 개인 S3는 비공개이며 Android는 BE와 발급된 URL을 사용한다. 팀원 데이터 업로더 권한과 BE 녹음 권한을 공유하지 않는다.
- `local.properties`, `.env`, PEM/개인키, JKS/keystore, 비밀번호·토큰 포함 URL, 운영 DB 덤프·사용자 음성은 커밋하지 않는다. `.gitignore`는 이미 추적된 비밀값까지 제거하지 못하므로 push 전에 diff도 확인한다.
- Debug APK와 Release 배포 APK는 다르다. Release 서명키·버전 증가·다운로드 경로·업데이트 QA는 별도 구축 대상이다. 서버는 별도 패치하고 APK는 별도 배포하되 기존 앱 API 호환성을 유지한다.

## 빠른 문제 확인

| 증상 | 먼저 확인 |
|---|---|
| SQLite/곡 목록 오류 | `git lfs pull`, LFS 객체 다운로드·헤더 검증 |
| SDK location not found | 자신의 `sdk.dir` 또는 `ANDROID_HOME` |
| JDK/Gradle 오류 | 프로젝트 Wrapper, Gradle daemon JBR 21, 개인 절대 경로가 공용 파일에 들어갔는지 |
| 실기기에서 localhost 접속 실패 | USB 승인, 장치 선택, adb reverse, 127.0.0.1 설정 후 재빌드 |
| 운영 API 503 | 현재 미배포 상태가 맞는지 확인; 인증서 오류와 구분 |
| 백엔드 시작 실패 | DB 접속/앱 계정/스키마 준비; validate를 create로 바꿔 데이터 손실을 유발하지 않기 |
| S3 AccessDenied / 인증 없음 | 실제 AWS 프로필 신원·파일 읽기 권한·인증 우선순위·버킷/리전/prefix 일치. 버킷 전체 목록 실패만으로 녹음 API 불가 판정하지 않기 |
| 업로드했는데 곡이 안 보임 | catalog/ 파일 업로드와 DB 메타데이터 import·API 연결이 각각 완료됐는지 확인 |

다음 작업은 [배포 준비 체크리스트](DEPLOYMENT_READINESS.md), 서버 적용 증거는 [서버 설정 기록](SERVER_BOOTSTRAP.md), Jenkins 상세는 [Android CI](JENKINS_ANDROID_CI.md)를 따른다.
