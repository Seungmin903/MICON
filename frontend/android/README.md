# MICON Android (Kotlin · Jetpack Compose)

음역·음색 분석, 곡 추천, 키별 반주와 실시간 음정 피드백을 제공하는 MICON Android 앱이다.
팀원이 같은 환경에서 개발할 수 있도록 빌드·라이브러리 버전과 검증 명령을 이 문서에서 관리한다.

- 버전: `gradle/libs.versions.toml` 한 곳에서만 관리한다.
- Figma: https://www.figma.com/design/gD9LvKcDIDlSXf5Kds76LJ (참고용 와이어프레임, 확정 디자인 아님)
- 위치: 팀 저장소 `S15P21B110/frontend/android/` (모노레포. 루트 `README.md` 에 전체 구조)
- 앱 문서: [docs/README.md](docs/README.md). `docs/screenshots/`는 현재 UI 여부를 확인 중인 과거 스켈레톤 참고 자료다.
- 엔진·데이터 계약 문서: develop의 [VocalScope](../../vocal-scope/README.md), [배치·DB 인수](../../vocal-scope/docs/BATCH_HANDOFF.md)
- 운영 저장 구조: [SSAFY BE·MySQL + 개인 S3](../../docs/operations/S3_VOICE_STORAGE.md). AWS 키는 BuildConfig/APK에 넣지 않는다. 곡·키별 반주·녹음은 BE API와 발급된 만료 URL을 사용한다.

## 고정 버전

| 항목 | 값 | 비고 |
|---|---|---|
| Gradle Wrapper | 9.5.0 | |
| Android Gradle Plugin | 9.3.2 | Kotlin 2.2.10 내장. kotlin-android 플러그인 추가 금지 |
| Kotlin Compose 플러그인 | 2.2.10 | AGP 내장 Kotlin 과 같은 버전 |
| JDK | JetBrains Runtime 21 | `gradle/gradle-daemon-jvm.properties` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 | minSdk 24 = Android 7.0, Galaxy S10(API 28~31) 포함 |
| Java 호환성 | 11 + desugar_jdk_libs 2.1.5 | |
| Compose BOM | 2025.06.01 | ui, material3, material-icons-extended |
| activity-compose / lifecycle / navigation-compose | 1.10.1 / 2.9.1 / 2.9.0 | |
| core-ktx / appcompat / material | 1.19.0 / 1.7.1 / 1.14.0 | |
| security-crypto | 1.1.0-alpha06 | 토큰 암호화 저장 |
| Media3 (ExoPlayer) | 1.7.1 | 반주 스트리밍 |
| Retrofit / OkHttp / Moshi | 2.11.0 / 4.12.0 / 1.15.2 | 엔진·백엔드 통신, JSON |
| Coroutines | 1.10.2 | |
| Coil | 2.7.0 | 앨범 아트 |
| Kakao / Google / Naver 로그인 | 2.20.1 / 21.2.0 / 5.9.1 | 카탈로그에만 고정, 기본 미포함 |
| JUnit / androidx.test / Espresso | 4.13.2 / 1.3.0 / 3.7.0 | |

버전 출처: Compose·Media3·Moshi 계열은 프로토타입에서, OkHttp·security-crypto·SDK 레벨·테스트·소셜 로그인은
이전 프로젝트 PickUs(S15P11B211)에서 검증된 조합이다. 두 곳이 다르면 Kotlin 2.2/Compose 와 함께 빌드된 쪽을 택했다.

## 시작하기

0. 클론 전에 `git lfs install` 을 한 번 실행한다(저장소의 `*.npy` 등 LFS 파일용). 앱에는 더 이상 LFS 자산이 없다.
   이미 클론했다면 저장소 루트에서 `git lfs install && git lfs pull`.
   예전에 `git config core.hooksPath .githooks` 를 실행했다면 `git config --unset core.hooksPath` 로 되돌린다(그대로 두면 LFS 훅이 돌지 않는다).
1. `frontend/android/` 를 Android Studio 로 연다(저장소 루트가 아니라 이 폴더). `local.properties.example` 을 `local.properties` 로 복사하고 `sdk.dir` 을 채운다.
2. `gradle.properties` 의 `org.gradle.java.home` 이 Android Studio 설치 경로와 다르면 수정하거나 지운다.
3. 빌드와 단위 테스트를 실행한다.

```powershell
cd frontend\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. 백엔드를 준비한다. 곡·반주·연습 기록·음성 분석이 전부 백엔드(`API_BASE_URL`)에서 온다 — 로컬 BE(`infra/compose/mysql.local.compose.yml` + bootRun,
   실기기는 `adb reverse tcp:8080 tcp:8080`) 또는 운영 `https://j15b110.p.ssafy.io/api/v1/`. 로컬 VocalScope 엔진(8787)은 2026-09-13 부터 앱이 쓰지 않는다.

개발용 딥링크: `adb shell am start -a android.intent.action.VIEW -d "micon://app/result/<practiceId>"`
(`song/<songId>`, `practice/<songId>` 도 가능). `micon://app/dev/seed-profile` 은 음성 프로필 예시를 채우고 리포트 탭을 연다
(간편 분석을 실제로 끝내지 않고 "분석 결과 있을 때" 화면을 볼 때 사용).

에뮬레이터에 옛 프로토타입(`com.example.pjt`)이 남아 있으면 같은 `micon://` 스킴을 두 앱이 받아 선택 창이 뜬다.
`adb uninstall com.example.pjt` 로 지운다.

런처 아이콘은 adaptive icon(보라 배경 + 흰 파형 바, `res/drawable/ic_launcher_*.xml`)이다.
API 25 이하용 `mipmap-*/ic_launcher.webp` 는 아직 Android 기본 이미지라 디자이너 에셋으로 교체가 필요하다.

프로젝트 경로에 한글이 있으면 AGP 가 빌드를 거부하고 단위 테스트 워커도 실패하므로 영문 경로에 둔다.

## 구조 (`app/src/main/java/com/micon/app/`)

| 패키지 | 내용 | 상태 |
|---|---|---|
| `MiconApplication`, `AppContainer` | 수동 DI. backend 클라이언트와 Repository(songs·practices·favorites·setlists·settings·voiceAnalyses·recommend)를 화면에 공급 | 완료 |
| `MainActivity` | 단일 Activity, `MiconRoot()` 호출 | 완료 |
| `core/auth` | `TokenManager` (EncryptedSharedPreferences) | 완료 |
| `core/network` | `BackendClient` (인증 인터셉터, 401 토큰 갱신), `SessionEvents` | 완료, 백엔드 계약 대기 |
| `data/backend/dto/SongDataDtos` | 음표(`KaraokeNote`)·비트 그리드(`BeatGrid`)·라이브 음표 변환 — 백엔드 practice-assets 가 주는 곡 데이터 형식. 엔진 패키지(`data/engine`)는 2026-09-14 완전히 제거 | 완료 |
| `data/backend` | `BackendApi` + `dto/` — 인증·곡 카탈로그·연습 데이터·연습 기록·음성 분석·찜·세트리스트 REST | 완료 |
| `data/` | `SongRepository` 인터페이스 + `backend/RemoteSongRepository`(백엔드 `/songs`, `/songs/{id}`, `/practice-assets`) | 완료. 곡 id = 백엔드 `song_id`(Long) |
| `scoring/` | 순수 Kotlin. YIN 피치, 라이브 채점, 채점 상수·색 | 완료 (프로토타입, 테스트 있음) |
| `audio/` | `PitchCapture` (AudioRecord 16 kHz + WAV 저장), `InstrumentalPlayer` (ExoPlayer) | 완료 (프로토타입) |
| `ui/theme` | Figma 팔레트(보라 `#6C5CE7` 포인트, 연보라 `#EEEBFF`, 핑크 `#FF5C8A`), 타이포(Pretendard 자리) | 완료 |
| `ui/navigation` | `Routes`, `BottomTab`(5탭), `MiconRoot`/`MiconNavHost`, 하단 바 표시 규칙 | 완료 |
| `ui/common` | `MiconLogo`, `MiconHeader`, `BackHeader`, `MiconSearchBar`, `VinylArt`(LP 아트), `SongRow`, `KeyBadge`, 버튼·입력·카드·칩, `PlayerCard`, `DashedBox` | 완료 |
| `ui/charts` | `RangeMapChart`, `StabilityLineChart`, `PianoRangeChart`, `BpmGaugeRow`, `PhraseSegments`, `RingProgress`, `MetricBar`, `RadarHexagon`, `KeyChangeGraph`, `PitchGauge`, `WaveformBars` | 완료 (Canvas) |
| `ui/onboarding` | 시작(스플래시), 랜딩(로그인), 회원가입, 가수/장르 선호 조사 | UI 완료, 백엔드 인증 TODO |
| `ui/recommend` | 추천곡(버블·캐러셀 전환), song-match-detail(적합도·음역 매칭·키/난이도·집중 구간) | 완료 (백엔드 `/recommendations`·`/songs/{id}/fit`, 서버 추천 없으면 기기 음역 계산 폴백) |
| `ui/report` | 음성 분석 리포트(결과 없음/오버레이/결과 카드 6개), 음성 간편 분석(마이크 확인 → G3 게이지 3초 유지, 실제 마이크 사용) | UI 완료, 엔진 정밀 분석 연동 TODO |
| `ui/singanalysis`, `data/singanalysis` | 불러서 분석하기(`report/sing`, `report/sing/{songId}`): 곡 선택 → 범위 카드(전곡·2절부터·하이라이트만) → 카운트인 → 가사만 보며 부르기(WAV 녹음, 일시정지·여기까지 마치기) → 저장 완료. 녹음은 `filesDir/sing-analysis/<id>.wav`+`.json`(`SingRecordingMeta`, take 구간 포함). 분석 요청은 `SongAnalysisSubmitter` 인터페이스(기본 로컬 저장만) | 프론트·녹음 완료, 분석 연동은 담당자가 `SongAnalysisSubmitter` 구현으로 교체 |
| `ui/songlist`, `ui/songdetail`, `ui/practice` | 연습 탭: 곡 선택(LP 행·key 배지·찜) → 상세(플레이어·키 변화 그래프) → 연습(가사 싱크·마이크 시트·라이브 채점) | 완료, 실기기 마이크 검증 필요 |
| `ui/evaluation` | 노래 연습 분석: 날짜 배지 + 아코디언(점수/상태, 미확인 빨간 점), 펼치면 상세 카드 | 완료 (백엔드 `GET /practices` 기준, 삭제 API 는 BE 요청) |
| `ui/result` | 노래 연습 분석 상세: 한줄 평가 · 점수 요약 · 구간 평가 · 연습 정보 | 완료 (백엔드 `/practices/{id}` + `/report`) |
| `ui/favorites` | 찜 목록(전체/연습 중/내 세트리스트), 세트리스트 세부(이름 변경·삭제·곡 추가/제거) | 완료 (백엔드 `/users/me/favorites`, `/setlists`) |
| `ui/settings` | 설정(녹음·개인정보 섹션, 토글, 회원 탈퇴 핑크) + 개발자 정보 | UI 완료 |
| `data/local/UserStore` | 음성 프로필·선호 조사의 **임시 인메모리 저장소**(찜·세트리스트·연습 기록은 백엔드로 이관) | 남은 항목 연동 시 삭제 |

Pixel 7 Pro 에뮬레이터(API 37)에서 찍은 화면은 `docs/screenshots/` 에 있다(2026-09-07, Figma 대비 확인용).

## 화면 ↔ 패키지 (Figma 프레임 기준)

| 하단 탭 | Figma 프레임 | Route | 패키지 |
|---|---|---|---|
| (진입) | 시작 → 랜딩(로그인) → 회원가입 → 랜딩 페이지 - 가수/장르 선호 조사 | `splash`, `login`, `signup`, `survey` | `ui/onboarding` |
| 추천 | 추천곡, 추천곡 - 캐러샐 | `recommend` | `ui/recommend` |
| 추천 | song-match-detail | `match/{songId}` | `ui/recommend` |
| 리포트 | 음성 분석 리포트 (결과 없음/분석 중/결과 있음/다시 분석), 리포트 명 변경, 캘린더로 보기 | `report` | `ui/report` |
| 리포트 | 음성 분석 리포트 - 음성 간편 분석 (4 프레임) | `report/quick` | `ui/report` |
| 연습 | 노래 연습 - 곡 선택 | `practice` | `ui/songlist` |
| 연습 | 노래 연습 - 노래 상세정보 | `song/{songId}` | `ui/songdetail` |
| 연습 | 노래 연습 - 노래 연습전 / 연습중 | `practice/{songId}` | `ui/practice` |
| 평가 | 노래 연습 분석 | `evaluation` | `ui/evaluation` |
| 평가 | 노래 연습 분석 상세 | `result/{jobId}` | `ui/result` |
| 찜 | 노래 찜 목록 | `favorites` | `ui/favorites` |
| 찜 | 노래 찜 목록 - 세트리스트 / 세트리스트 세부 | `setlist/{setlistId}` | `ui/favorites` |
| 헤더 ⚙ | 설정 | `settings` | `ui/settings` |

화면 요소별 데이터 대응(✅/🟡/❌)은 `docs/FIGMA_PRACTICE_SCREENS.md`, `vocal-scope/docs/MICON_DATA_PLAN.md` §3.

하단 바는 Figma 와 같이 탭 5개 + 노래 상세정보 + 세트리스트 세부에서만 보인다(`Routes.withBottomBar`).
Figma 에 없는 요소는 최소로 두었다: 연습 화면 READY 상태의 키 ±, 목록 끝의 엔진 연결 오류 문구, 설정의 개발자 섹션.
Figma 가 아직 정하지 않은 값(적합도 공식, 추천 키, 난이도, 집중 구간, 음성 프로필 수치)은 `UserStore`/각 화면의 TODO 주석에 임시 계산식으로 표시했다.

## 설정값 주입

`local.properties` 값이 Gradle 을 거쳐 `BuildConfig`, `resValue`, `manifestPlaceholders` 로 들어간다.
키가 비어 있어도 빌드된다.

| 키 | 주입 위치 |
|---|---|
| `API_BASE_URL` | `BuildConfig.API_BASE_URL` (기본 `http://10.0.2.2:8080/api/v1/`) |
| `GOOGLE_WEB_CLIENT_ID`, `KAKAO_NATIVE_APP_KEY`, `NAVER_CLIENT_ID/SECRET` | `@string/...`, Kakao Manifest scheme |
| `RELEASE_*` 4개 | 모두 있을 때만 release signingConfig |

소셜 로그인을 켤 때: `app/build.gradle.kts` 의 `libs.bundles.login` 주석 해제 → `MiconApplication` 의 Kakao init 주석 해제
→ Manifest 의 `AuthCodeHandlerActivity` 주석 해제. 콘솔 등록(SHA-1, Key Hash) 절차는 PickUs 의 `OAUTH_DEVELOPMENT_SETUP.md` 참고.

## 테스트

`app/src/test/` — YIN 4건, LiveScorer 골든 6건, ScoringMath 6건, 엔진 JSON 파서 4건(`resources/fixtures/` 의 실제 job.json).
새 Repository/ViewModel 을 만들 때 같은 폴더 구조로 테스트를 추가한다.

## 데이터 소스 (현재 → 목표)

- 곡 목록·상세·음표·가사·구간: 백엔드 `GET /songs`, `GET /songs/{id}`, `GET /songs/{id}/practice-assets` (`RemoteSongRepository`).
  곡 식별자는 백엔드 `song_id` 하나. TJ 번호·카탈로그 track_id·로컬 sqlite 는 쓰지 않는다(2026-09-10 팀 결정).
- 반주: `practice-assets` 의 `instrumental.playbackUrl`(백엔드 발급 만료 URL)을 ExoPlayer 로 스트리밍. **백엔드 응답에 아직 없는 필드** — 추가될 때까지 연습 화면은 "반주 준비 중" 안내.
- 정밀 채점·연습 기록: 백엔드 `/recordings` → `/practices` → `/practices/{id}/performance` 로 옮기는 중. 평가 탭은 그때까지 엔진(`ENGINE_BASE_URL`) 작업을 읽는다.
- 찜·세트리스트·음성 프로필: `UserStore` 인메모리(임시) → 백엔드 `/users/me/favorites`, `/setlists`, 음성 분석 API 로 교체 예정.

## 프로토타입 출처

2026-09-03 프로토타입(`com.example.pjt`, 라임 포인트 테마)에서 scoring/audio/engine 코드와 테스트를 패키지명만
`com.micon.app` 으로 바꿔 옮겼다(로직 동일). 프로토타입 원본은 팀 저장소에 올리지 않았다(로컬 `Desktop/PJT/_prototype/`).
Kotlin 포팅의 원본 대조가 필요하면 `feat/vocalScope` 브랜치의 웹·엔진 소스를 본다(`docs/README.md` 대응표).

## Git / Jira

커밋 `[이슈키] 타입: 내용`, 브랜치 `타입/간단한설명`(camelCase, 이슈 키 없음), MR 제목 `[이슈키] (FE) 요약`, SP ≤ 3.
`develop` 직접 push 금지. 상세는 저장소 루트의 `JIRA_GIT_RULES.md`.
