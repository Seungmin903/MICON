# Android Jenkins CI

Jira: `S15P21B110-129`

Android 단위 테스트와 debug APK 빌드를 검증한다. 서버 배포와 Release APK 배포는 이 작업에 포함하지 않는다.

## Jenkins 작업 설정

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
팀 범위 Credentials 등록이 가능해지면 Username/Password 방식으로 전환하고 노출된 토큰을 교체해야 한다.
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
4. LFS 객체 무결성과 `micon_catalog.sqlite`의 SQLite 헤더를 검증한다. 포인터 파일인 상태로 APK를 만들지 않는다.
5. `clean :app:testDebugUnitTest :app:assembleDebug`를 실행한다.
6. JUnit XML을 Jenkins 테스트 결과로 게시한다.
7. APK ZIP 무결성, APK 내부 SQLite 헤더, `apksigner verify`를 검사하고 SHA-256을 기록한다.
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
- 검증 브랜치의 MR 검토·머지 및 운영 브랜치 선정
- GitLab push/MR 자동 트리거 연결
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
