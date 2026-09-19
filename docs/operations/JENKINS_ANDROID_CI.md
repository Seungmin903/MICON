# Android Jenkins CI

## 개인 S3와 CI 자격 증명 경계 (2026-09-09)

BE·MySQL은 SSAFY 서버, 파일은 개인 AWS 계정 S3를 사용하는 [운영 계약](S3_VOICE_STORAGE.md)을 따른다. AWS 키는 BE 런타임의 외부 자격 증명 파일에서만 주입하고 Android MR/Debug/Release 빌드·BuildConfig·APK·산출물에 포함하지 않는다. 저장소 checkout 자격 증명, APK 서명키, AWS 런타임 키는 별개다. 향후 서버 CD의 파일 배치 권한도 신뢰된 배포 작업에만 부여한다. 개인 S3 결정이 기존 Android CI에 AWS 키를 등록하라는 뜻은 아니다.

Jira: `S15P21B110-129`

## 2026-09-08 MR CI 전환 (자동 재빌드 검증 완료, 리뷰 대기)

- Freestyle 진입점은 `sh ci/android-ci.sh`로 관리한다. 아래 2026-09-07 기록은 이전 수동 구성이다.
- Jenkins SCM은 토큰 없는 저장소 URL과 팀 Credentials를 사용한다. Git Username and Password 바인딩으로 LFS 다운로드에도 같은 read-only 인증을 제공한다. 비밀값은 파일/명령 인자에 쓰지 않으며 Gradle 시작 전 인증 환경변수를 해제한다.
- SCM 기본 브랜치는 `*/develop`이다. `ci/jenkins-freestyle.sh` 내용을 Execute shell에 복사한다. 고정 저장소 fetch 후 MR 이벤트의 40자리 SHA가 로컬 commit인지 확인하고 해당 SHA를 detached checkout한다. 수동 실행은 develop을 사용한다. 이후 SHA를 다시 비교하여 다르면 즉시 실패한다. 웹훅의 저장소 URL은 fetch에 사용하지 않는다.
- #6에서 Git 플러그인이 웹훅 revision을 다른 저장소로 판단하여 develop을 checkout한 현상을 확인했다. SHA 검사가 실패시켜 잘못된 소스의 성공을 방지했다. 보정 checkout 이후 실제 빌드 소스는 콘솔의 `[MICON] verified_build_sha`와 `source_sha`를 기준으로 확인한다. Jenkins Git Build Data/초기 changelog는 bootstrap develop SHA일 수 있어 MR 검증 근거로 단독 사용하지 않는다.
- MR 대상은 `develop`으로 제한한다. MR 생성과 새 커밋 update를 검증하며, 댓글/승인/merge/close 이벤트는 빌드하지 않는다. 최초 구성은 모든 해당 MR을 검사한다(Android 경로 외 변경도 실행 가능).
- Jenkins의 `Opened Merge Request Events`와 `Build only if new commits were pushed to Merge Request`를 켜고, `Rebuild open Merge Requests`를 `On push to source branch`로 지정한다. `Never`이면 MR update도 제외되므로 사용하지 않는다. 일반 `Push Events`는 끄고 GitLab의 MR update webhook으로 실행한다. 대상 브랜치 push로 다른 MR을 검색하는 API 연동은 사용하지 않는다.
- JDK 21을 작업 디렉터리에 설치하며 공식 Temurin 바이너리의 고정 URL과 SHA-256을 검사한다. 공용 Java 설치는 바꾸지 않는다. SDK 37·Build Tools 36.0.0은 기존 승인된 설치를 사용한다.
- 매 빌드마다 빈 LFS storage로 다운로드하여 기존 캐시가 인증 문제를 숨기지 않도록 한다. 임시 LFS 캐시는 `.ci-tools/lfs-auth-check.*`에 남으므로 운영 용량 점검 대상으로 관리한다.
- `API_BASE_URL=https://j15b110.p.ssafy.io/api/v1/`를 명시 주입하고 생성 BuildConfig와 단위 테스트로 확인한다. 이 빌드는 Debug이며 실제 API 가용성/AI 주소/Release 배포 검증을 대신하지 않는다.
- `CiConfigurationTest`로 URL 형식과 CI 환경변수 반영을 검사한다. 스크립트가 develop에 병합되기 전 수동 develop 빌드는 진입점 부재로 실패할 수 있으므로 이 MR의 이벤트 빌드로 먼저 검증한다.
- 웹훅 HTTP 200은 통신 검증이다. 실제 MR의 Jenkins URL·SHA·테스트 수·소요 시간은 검증 후 별도 기록한다. 이 문단 작성만으로 Jira를 완료 처리하지 않는다.

### 자동 빌드 검증 기록

- MR !20 생성으로 #6 자동 시작을 확인했다. 잘못된 bootstrap SHA를 검사가 차단한 뒤 위 checkout 보정을 적용했다.
- MR 새 커밋으로 #7과 #8이 자동 실행되었다. #8은 **SUCCESS**, 전체 1분 15초(Gradle 1분 11초), JUnit **23개 통과 / 실패 0 / 건너뜀 0**(2.1초)이다.
- #8 실제 소스: `27142422e826f41d2f29ebd69f83fbc0b60f9e52`. JDK `21.0.12.1+1`, SDK 37, Build Tools 36.0.0. 빈 LFS storage 인증 다운로드·객체 무결성·현재 커밋의 SQLite SHA-256 일치·APK 압축/서명 검증을 통과했다.
- `local.properties` 없이 `API_BASE_URL=https://j15b110.p.ssafy.io/api/v1/` 주입을 생성 BuildConfig와 테스트로 확인했다. API 호출 성공이나 AI 엔진 연결을 검증한 것은 아니다.
- APK 59.15 MiB, SHA-256: `05179b8010a5a102237cd72d52ec532390e970286d424165c1d407fb415b7c5e`.
- [#8 빌드](https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/8/) · [테스트 보고서](https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/8/testReport/). APK와 함께 `app-debug.apk.sha256`, `build-metadata.properties`(실제 소스 SHA·빌드 번호·API 주소)를 보관한다.
- V3.1에 따라 브랜치를 `chore/infra/androidMrCi`로 정정한다. 기존 !20은 검증 이력으로 보존하고 교체 MR에서 최종 생성 이벤트 빌드를 확인한다. 이 기록 커밋의 빌드 결과는 MR에 추가한다. 팀원 리뷰·병합 전 Jira Done으로 전환하지 않는다.

Android 단위 테스트와 debug APK 빌드를 검증한다. 서버 배포와 Release APK 배포는 이 작업에 포함하지 않는다.

## 2026-09-07 범위 결정: 결과는 Jenkins에서 확인

사용자 결정으로 **Jenkins → GitLab 빌드 상태 전송 및 MR 성공/실패 표시를 구현 범위에서 제외**한다. 나중에 반드시 해야 할 보류 과제로 남기지 않는다. 이 기능을 위한 GitLab 전역 Connection 설정과 `api` 권한 토큰 발급도 요청하지 않는다.

| 기능 | 결정 |
|---|---|
| GitLab → Jenkins MR/push 이벤트 전달 | 유지. 인증된 webhook으로 자동 빌드를 시작하는 후속 작업 |
| Jenkins → GitLab 빌드 결과 표시 | 제외. Commit Status API·MR 결과 댓글·상태 publisher를 추가하지 않음 |
| Jenkins 내부 테스트 보고서·빌드 상태·APK 보관 | 유지. 결과 확인의 기준 |
| SSAFY Outbound WebHook 알림 | 유지. GitLab 빌드 상태 전송과 다른 기존 연동 |
| GitLab ↔ Jira 이슈 연동·V3 MR 규칙 | 유지. 이번 변경 대상 아님 |

소스 checkout용 `read_repository` 자격 증명은 여전히 필요하며 GitLab 결과 전송용 인증과 구분한다. 기존 공용 Connection·자격 증명·알림을 삭제하거나 권한을 변경하라는 뜻은 아니다.

### 변경된 완료 기준 (S15P21B110-129)

- [ ] `frontend/android/` 변경이 포함된 MR 생성 시 해당 소스로 `:app:assembleDebug :app:testDebugUnitTest`가 자동 실행된다.
- [ ] 성공·실패를 Jenkins 빌드 상태와 테스트 보고서/실패 로그에서 확인하고, 빌드 URL·번호·소스 커밋 SHA를 기록한다.
- [ ] JDK 21·Android SDK 37이 CI 실행 환경에 준비되고 빌드 시간(분)을 기록한다.
- [ ] `local.properties` 없이 환경변수(`API_BASE_URL` 등)가 주입되는 빌드를 확인한다.

위 체크리스트는 목표다. 범위 변경만으로 자동 트리거/환경변수 검증이 끝난 것은 아니며 기존 #4 수동 성공과 구분한다.

### 리뷰 시 결과 확인

1. [Jenkins 기본 작업](https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/)에서 해당 소스 커밋 SHA의 빌드를 찾는다. 단순히 마지막 성공 빌드를 현재 MR의 결과로 간주하지 않는다.
2. 빌드 상태, 테스트 보고서의 통과/실패 수, 실패 단계와 빌드 시간을 확인한다. 빌드가 없거나 실패한 상태를 통과로 처리하지 않는다.
3. MR 설명의 확인란에 빌드 URL·번호·소스 SHA·테스트 수·소요 시간을 사람이 기록하고 리뷰어가 대조한다. 민감정보가 든 콘솔 원문을 복사하지 않는다.
4. GitLab의 `Ready to merge` 표시는 Jenkins 테스트 통과 증거가 아니다. GitLab 결과 배지에 의존한 자동 머지 대신 V3에 따른 팀원 리뷰를 유지한다.

예시(실제 확인한 값으로 채우며 토큰·비밀번호는 포함하지 않는다):

```text
Jenkins 빌드 URL / 번호:
소스 커밋 SHA:
상태 / 테스트 통과·실패 수 / 빌드 시간:
검증 APK SHA-256 (성공 시):
```

인증 방향 구분은 [Jenkins GitLab 플러그인 공식 안내](https://plugins.jenkins.io/gitlab-plugin/#plugin-content-jenkins-to-gitlab-authentication)를 참고한다.

## 이전 수동 설정 기록 (2026-09-07)

아래 설정·미완료 항목은 당시 기록이다. 현재 운영 설정과 검증 상태는 문서 상단의 2026-09-08 절을 우선한다.

- 운영 작업: `s15-ai-speech-sub3/S15P23B110`
- URL: `https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/`
- 유형: Freestyle (SSAFY 제공 기본 작업 유지)
- 저장소: `https://lab.ssafy.com/s15-ai-speech-sub1/S15P21B110.git`
- 현재 검증 브랜치: `*/chore/androidCi`
- Execute shell에서 도구 준비 → Git LFS 다운로드 → Gradle 테스트·APK 빌드를 수행한다.
- `ci/Jenkinsfile.android`는 참고용 Pipeline 정의이며 현재 Freestyle 작업이 자동으로 읽지 않는다.
- 빌드 후 JUnit XML 게시 및 성공한 Debug APK 보관·fingerprint를 수행한다.
- SSAFY Outbound WebHook 알림은 유지한다. GitLab push/MR 자동 트리거는 아직 활성화하지 않았다.
- 중복 작업 `S15P23B110-android-ci`는 사용자 승인 후 2026-09-07 삭제했다.
- 현재 팀 폴더에서 사용할 수 있는 Linux 내장 노드에서 실행한다.

## 작업 전용 도구 준비

공용 서버에 전역 패키지를 설치하거나 전역 환경변수를 변경하지 않는다.

- Git LFS 3.7.1: `$WORKSPACE/.ci-tools/git-lfs-3.7.1-amd64`
- Android SDK: `$WORKSPACE/.ci-tools/android-sdk`
- Command-line tools 패키지: `15859902` (공식 ZIP SHA-256 검증)
- SDK Platform: 37 (`platforms;android-37` 또는 공식 저장소의 `platforms;android-37.0`)
- Build Tools: 36.0.0 (AGP 9.3 기본 버전), Platform Tools
- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`는 해당 빌드 셸에서만 설정한다.
- Android SDK 이용약관은 2026-09-07 사용자가 동의했다. 필요한 패키지 설치에만 적용한다.
- Gradle 캐시: `$WORKSPACE/.gradle`

### 인증 보안 주의

SSAFY 기본 작업의 저장소 URL에 포함된 기존 토큰으로 Git checkout은 성공했다.
그러나 이 방식은 Jenkins 빌드 로그에 토큰을 표시할 수 있다. 토큰이나 인증 포함 URL을 문서·Git·공개 채팅에 복사하지 않는다.
팀 전용 Username/Password Credentials로 전환하고 노출된 토큰을 교체해야 한다.
사용자가 폴더 Credentials Create·View·Update 권한 적용과 등록 화면 접근을 완료했다고 전달했다. 실제 팀 전용 자격 증명 생성·선택·연결 성공 여부는 재개 시 확인한다. GitLab 전역 Connection 접근은 이번 범위에서 필요하지 않다.
공유 Credentials의 사용 범위를 확인하기 전에는 덮어쓰지 않는다.

## 에이전트 필수 조건

- JDK는 프로젝트의 Gradle/AGP와 호환되어야 한다. 현재 SSAFY 노드에는 Java 25.0.3이 있고 Gradle 9.5.0 실행 및 앱 컴파일 진입까지 확인했다. 전역 JDK는 변경하지 않았다.
- Android SDK의 `ANDROID_SDK_ROOT` 또는 `ANDROID_HOME`이 설정되어 있어야 한다.
- `$ANDROID_SDK_ROOT/platforms/android-37` 또는 `android-37.0`이 설치되어 있어야 한다.
- Git LFS CLI가 설치되어 있어야 한다.
- 저장소의 `frontend/android/local.properties`는 생성하지 않는다.

현재 Freestyle 작업은 작업 전용 Git LFS와 SDK를 준비하고 도구체인을 검사한다. 공용 시스템은 변경하지 않는다. `local.properties`가 없어도 Gradle 환경변수와 앱의 안전한 기본값으로 빌드되는지를 함께 검증하며, 실제 비밀값은 Debug 빌드에 주입하지 않는다.

## 실행 단계

1. Jenkins가 Git 저장소를 checkout한다.
2. JDK를 확인하고 작업 전용 Git LFS 및 Android SDK 37을 준비한다.
3. 해당 Git 명령에만 LFS 필터를 지정해 `git lfs pull`을 수행한다. 전역 또는 영구 Git 필터 설정은 변경하지 않는다.
4. LFS 객체 무결성을 검증한다. 곡 카탈로그는 백엔드 API를 사용하므로 제거된 `micon_catalog.sqlite`를 요구하지 않는다.
5. `clean :app:testDebugUnitTest :app:assembleDebug`를 실행한다.
6. JUnit XML을 Jenkins 테스트 결과로 게시한다.
7. APK ZIP 무결성, 로컬 카탈로그 미포함, `apksigner verify`를 검사하고 SHA-256을 기록한다.
8. 성공한 `app-debug.apk`를 fingerprint와 함께 보관한다.

## 2026-09-07 검증 결과

- 최종 검증 빌드: `S15P23B110 #4`, **SUCCESS**
- 소스: `chore/androidCi`, 커밋 `4140be0614b8de3c18b827636f226fa0519698c7`
- Jenkins 전체 소요 시간: 1분 44초 (Gradle 1분 39초)
- JUnit: 21개 통과, 실패 0개, 건너뜀 0개 (테스트 실행 2.9초)
- Git LFS 객체 무결성 및 실제 SQLite 데이터 파일 확인: 통과
- APK 압축 무결성, 내부 SQLite 데이터, APK 서명 확인: 통과
- APK 크기: 59.15 MiB
- APK SHA-256: `5e6ddb74eac4349cd4581d11de06297fa7c6496fe187061b5769862548d0c327`
- 산출물: `https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/4/artifact/frontend/android/app/build/outputs/apk/debug/app-debug.apk`
- 테스트 보고서: `https://jenkins-1.ssafy.com/job/s15-ai-speech-sub3/job/S15P23B110/4/testReport/`

이전 #3은 Gradle 빌드 자체는 성공했지만 LFS checkout 생략 경고가 있었으므로 배포/QA 대상으로 사용하지 않는다. 데이터 검증이 통과한 #4를 사용한다.

### 아직 완료하지 않은 항목

- 실기기 설치·기능 QA (빌드 성공과 구분)
- 실서비스 API/AI 주소 설정: 현재 코드 기본값은 Android 에뮬레이터 호스트 `10.0.2.2` 기준이다.
- Release 서명키 등록, 버전 관리, 직접 배포 경로와 업데이트 정책
- MR !12의 초기 설정·문서는 develop `ec1afe1`에 머지됐다. Jenkins가 빌드할 소스 브랜치/커밋 선택과 자동 실행 검증은 별도로 남아 있다.
- GitLab push/MR 자동 트리거 연결 및 Jenkins 내부 결과 확인 (GitLab 결과 전송은 제외)
- 로그에 노출되는 URL 내장 토큰의 Credentials 이전 및 토큰 교체

## 로컬 동등 검증

Windows 개발 환경에서는 다음 명령으로 Jenkins 빌드 단계와 동일한 작업을 실행한다.

```powershell
cd frontend\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

## Jenkins에서 확인할 기록

- 전체 빌드 시간(분)
- 단위 테스트 성공/실패 건수
- debug APK fingerprint
- 실패한 경우 실패 단계와 Gradle task
