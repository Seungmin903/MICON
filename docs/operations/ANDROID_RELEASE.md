# 스토어 없는 Release APK 준비

Jira S15P21B110-179. 서버 CD/SQL 설정과 별도 작업이다. 현재는 코드 준비 단계이며 실제 서명·게시·실기기 QA는 미완료다.

## 현재 상황과 이번 MR 범위 (2026-09-08)

- 최초 Release 서명키는 담당자가 로컬에서 생성했다. 공개 인증서 지문 일치, RSA 3072 / SHA256withRSA, 만료일 2054-01-24를 확인했다. 실제 키 파일·비밀번호·개인 로컬 경로는 이 문서와 Git에 넣지 않는다. 키 생성 완료와 APK 서명 빌드 성공은 별개다.
- 독립 암호화 백업 및 복구 가능성 확인, Jenkins Release용 자격 증명 등록은 아직 미완료다. 담당자가 기존 키를 계속 보관하며 이후 작업을 위해 새 키를 다시 만들지 않는다.
- MR !23의 리뷰 범위는 Release 설정 검사, 버전·산출물 규칙, 서명/APK 검증 코드와 운영 안내다. 준비 코드를 공유하기 위해 Draft를 해제하는 것이며 실제 배포 승인이나 Jira 179 전체 완료를 뜻하지 않는다.
- 최신 develop의 Android CI 및 health gate/Runbook 준비 변경을 반영했다. 기존 MR CI는 Debug 검증 용도로 유지하며 Release 키를 바인딩하지 않는다.
- 우선순위는 추천 프런트 구현 → 준비된 API부터 개발 서버/Debug APK 연결 → 승인된 배포 후보의 Release 서명·게시·실기기 QA다. BE/FE 전체 완성 전에도 준비 코드 리뷰와 개발 연결 테스트는 가능하다. SQL 설정 보류는 유지한다.

### 실제 배포 전 남은 확인

1. 키·비밀번호의 안전한 보관과 독립 백업을 확인한다.
2. 신뢰된 Release 실행 범위와 최소 권한 자격 증명을 준비한다. 기존 Android MR 작업을 Release 작업으로 덮어쓰지 않는다.
3. BE/AI와 외부 HTTPS 주소 및 응답 계약을 합의하고 배포 대상 기능을 연결 검증한다. 도메인/TLS만 준비되었다고 API 연동 완료로 보지 않는다.
4. 팀 리뷰된 커밋과 증가 버전을 선택한 뒤 실제 Release 빌드, 인증서·패키지·체크섬 검사를 수행한다.
5. APK 게시 위치와 접근 대상을 확정하고 검증된 산출물만 게시한다. 실기기 2대 이상 설치·동일 키 업데이트 및 데이터 보존을 확인한다.

위 항목은 현재 미완료다. MR 병합 자동화가 Jira 179를 Done으로 전환하더라도 전체 완료 기준 충족 전에는 진행 중으로 유지/복원한다. 이 준비 MR에는 SQL 실행, 서버 변경, 키 업로드, APK 게시가 포함되지 않는다.

## 서명키 관리

- 기존 Release 키가 있으면 동일 키를 사용한다. 없을 때만 사용자가 생성·보관 책임자와 안전한 백업 위치를 정한다. SSH PEM과 Android keystore는 별개다.
- 실제 keystore·비밀번호를 Git/채팅에 올리지 않는다. 생성·Jenkins 등록은 사용자가 직접 수행한다. 저장소 밖에 암호화 백업을 두고 복구 가능성을 확인한다.
- 직접 APK 배포에서 서명키를 잃으면 같은 앱을 정상 업데이트하기 어렵다. Debug APK와 Release APK는 서명이 다를 수 있어 기존 앱 삭제를 먼저 요구하지 말고 데이터 보존을 확인한다.
- 서명 인증서의 공개 SHA-256을 최초 키 확인 시 고정하고 매 빌드 비교한다. PEM/개인키 원문은 공개하지 않는다.

## Jenkins에 필요한 입력 (실제 값은 문서에 기입하지 않음)

| 환경변수 | 주입 방식 / 의미 |
|---|---|
| RELEASE_STORE_FILE | 기존/새 Secret file Credential, 체크아웃 저장소 밖 임시 파일 경로 |
| RELEASE_STORE_PASSWORD | Secret text Credential |
| RELEASE_KEY_ALIAS | 별도 설정 또는 Credential |
| RELEASE_KEY_PASSWORD | Secret text Credential |
| RELEASE_CERT_SHA256 | 승인된 서명 인증서 SHA-256, 공개 정보 |
| RELEASE_VERSION_CODE | 이번 배포의 양의 정수 (상한 2100000000) |
| PREVIOUS_RELEASE_VERSION_CODE | 마지막 **공개 배포** 코드. 최초 실제 배포일 때만 0 |
| RELEASE_VERSION_NAME | 예: 0.1.0, 0.1.1-rc.1 |
| RELEASE_APPROVED_SHA | 승인·선택한 실제 체크아웃 commit 전체 SHA |
| API_BASE_URL / ENGINE_BASE_URL | 합의된 외부 HTTPS 주소. 끝에 / 필요 |
| ANDROID_HOME | 승인된 SDK 37·Build Tools 36.0.0 설치 위치 |

`versionCode`를 Jenkins 실행 번호로 자동 대체하지 않는다. 마지막 공개 기록과 비교해 증가시킨다. 실패/폐기한 후보 번호를 임의로 재사용하지 않는 운영 방식을 권장한다. 스크립트는 전달받은 이전 코드와 비교할 뿐, 실제 공개 이력을 자동 조회하지 않는다.

## 실행 흐름

별도 신뢰된 Release 실행 환경에서 수행한다. Android MR CI에는 Release Credentials를 바인딩하지 않으며, 현재 운영 Freestyle 작업을 이 스크립트로 교체하지 않는다.

1. 팀 리뷰된 커밋을 깨끗한 checkout에 준비하고 JDK 21, SDK 37, Build Tools 36.0.0, Python 3.11+, Git LFS를 확인한다. 필요한 LFS 객체를 read_repository 인증으로 받아 둔다.
2. 입력·서명 파일 존재/저장소 외부 위치·공개 HTTPS 주소·증가 버전·승인 SHA 검사. `local.properties`가 있으면 중단한다.
3. LFS 무결성 및 실제 SQLite 해시 대조 → `testReleaseUnitTest`와 `assembleRelease` 실행. Configuration/build cache는 끄며, raw Gradle 진단은 비밀값 보호를 위해 출력하지 않는다.
4. `apksigner verify`와 `aapt dump badging`으로 서명 인증서, `com.micon.app`, 버전, debuggable 아님을 검사한다. APK 내 SQLite 해시도 검사한다.
5. 모든 검사가 통과하면 저장소의 무시된 `build/releases/` 아래 **새 디렉터리**에 APK·체크섬·release.json만 생성한다. 기존 후보 파일은 덮어쓰지 않는다.

```sh
set +x
python3 ci/android-release.py
```

실제 키가 없는 상태에서 이 명령이 실패하는 것은 정상이다. 무서명 APK나 Debug 키로 통과시키지 않는다. 실패 시 자동 게시하지 않는다. 문제 분석이 필요하면 제한된 실행 환경에서 로그를 확인하고 비밀값을 가린 뒤 공유한다.

## 산출물 규칙

- 파일명: `micon-{versionName}-{versionCode}-{commitSHA앞12자리}.apk`
- 같은 이름의 `.apk.sha256`, 공개 메타데이터 `release.json` 생성.
- 메타데이터: 앱 ID, 버전, 전체 commit SHA, APK SHA-256, 서명 인증서 SHA-256, API/AI 주소, UTC 생성 시각, `published=false`.
- `published=false`는 로컬 검증 후보라는 뜻이다. 검증된 APK가 곧 사용자 배포본이라는 뜻은 아니다.
- 수집은 생성된 후보 디렉터리의 APK·체크섬·JSON으로 제한한다. 작업 디렉터리 전체, 환경파일, 키 파일, Gradle 캐시를 artifact로 보관하지 않는다.

## 게시·실기기 검증 (아직 실행하지 않음)

- 실제 배포 경로와 접근 대상을 정한 뒤 GitLab Release 또는 HTTPS 경로에 **검증된 산출물만** 게시한다. read_repository 토큰으로 업로드할 수 있다고 전제하지 않는다. 별도 쓰기 권한이 필요하면 사용자에게 요청한다.
- 권고 파일 경로는 버전별 불변 경로다. 공개 여부와 인증 방식을 정하기 전 Nginx 다운로드 경로나 외부 링크/QR을 만들지 않는다. QR에는 나중에 실제 검증한 URL을 넣는다.
- 기기 2대 이상에서 기종/OS·APK 해시·네트워크별 설치·실행 성공 횟수와 시간을 기록한다. 동일 서명 이전 Release 위의 업데이트와 사용자 데이터 보존을 확인한다.
- 서버 API/AI가 아직 준비되지 않은 기능은 미검증으로 남긴다. HTTPS 주소 형식 통과는 서버 연결 성공이 아니다.
- 앱 복구는 이전 정상 소스를 더 높은 versionCode로 다시 서명/배포한다. 낮은 코드 APK 강제 설치나 사용자 데이터 삭제를 기본 복구 절차로 삼지 않는다.

## 개발 검증

`python3 -m unittest discover -s ci -p test_android_release.py -v`는 가짜 설정 파일/검증 문자열로 정책만 시험하며 실제 키나 서명 APK를 생성하지 않는다. Debug 회귀 빌드는 별도로 수행한다. 전체 179 완료는 실제 키 주입·서명·게시·기기 2대 검증 이후다.

2026-09-08 준비 검증: Python 정책 테스트 13개 통과(0.022초). 실제 Gradle `validateReleaseConfiguration`이 서명 입력 부재로 실패하는 것을 확인했다. Windows 한글 경로에서는 AGP 경로 제한과 테스트 ClassNotFound가 발생했으며, 동일 Android 소스를 ASCII 임시 디렉터리에 복사하고 수정 Gradle 파일의 SHA-256 일치를 확인한 뒤 `testDebugUnitTest assembleDebug`가 1분 3초에 성공했다. JUnit은 21개 통과/실패 0/오류 0/건너뜀 0, 테스트 0.83초였다. 경로 우회 설정은 저장소에 추가하지 않았다. Windows에서는 ASCII 작업 경로를 사용한다. 이는 Release 서명/게시 검증이 아니라 Debug 회귀 검증이다.

공식 근거: [Android 앱 서명](https://developer.android.com/studio/publish/app-signing), [버전 관리](https://developer.android.com/studio/publish/versioning), [apksigner](https://developer.android.com/tools/apksigner).
