# 독립 음역 측정 테스트

기존 통합 브랜치 `feat/ai/guidedRange`의 `ac0fb3052f4821441a6951df430ba6af45ca4ce1`에서 Android 측정과 PC 모델 분석만 분리했다. MR !80, 기존 태그, 운영 백엔드와 DB는 변경하지 않는다.

연결 이슈는 [S15P21B110-123](https://ssafy.atlassian.net/browse/S15P21B110-123)이다. Jira에서 확인한 기존 값은 FE 작업, 에픽 [음성 측정(S15P21B110-109)](https://ssafy.atlassian.net/browse/S15P21B110-109), SP 3, Week3 스프린트다. 이슈는 진행 중이며, 이 기록으로 완료 처리하지 않는다.

## 실행 경로

- 일반 앱: develop의 로그인·간편 분석·리포트 경로. 새 Spring 음역 API를 사용하지 않는다.
- 독립 debug 앱: 휴대폰 측정·WAV 기록 → USB 연결 → PC의 `/preview/guided-range` → CREPE full·pYIN 검증 → 휴대폰 결과 표시.
- `GUIDED_RANGE_STANDALONE` 기본값은 `false`다. release에서는 항상 `false`이며 PC 모델 주소와 진단 토큰은 비어 있다. 정식 앱 서명·HTTPS·버전 검사를 유지한다.
- PC 모델 연결이 끊기면 대기 녹음을 보존한다. 기기 판정을 모델 검증 결과로 대체하지 않으며, 자료가 부족한 새 결과는 이전 정상 결과를 덮어쓰지 않는다.

## 로컬 설정

Android SDK와 JDK는 [기존 개발 안내](LOCAL_DEVELOPMENT.md)를 따른다. `frontend/android/local.properties`에 필요한 SDK 경로를 지정하고 다음 설정을 추가한다. 비밀키·서버 계정은 필요하지 않다.

```properties
GUIDED_RANGE_STANDALONE=true
RANGE_PREVIEW_BASE_URL=http://127.0.0.1:8789/
```

PC 모델은 `ai/vocal-scope/model-server/requirements-range.txt`의 환경과 CREPE 가중치가 필요하다. 기존 검증 환경은 Python 3.11이다. 모델 서버는 loopback에만 바인딩한다.

```text
python -m uvicorn range_preview_app:app --app-dir ai/vocal-scope/model-server --host 127.0.0.1 --port 8789
adb reverse tcp:8789 tcp:8789
```

기존 서버와 동시에 확인할 경우 새 서버를 `18789` 등 별도 PC 포트에서 실행하고 `adb reverse tcp:8789 tcp:18789`로 연결한다. 기존 프로세스를 종료할 필요가 없다.

PC 결과의 기본 위치는 모델 폴더의 `.range-preview/`다. 작업 공간을 분리하면서 기존 추가 측정 결과를 이어 쓰려면 `RANGE_PREVIEW_STORAGE` 환경변수에 기존 `.range-preview` 절대 경로를 지정한다. 기존 결과를 자동 복사하거나 삭제하지 않는다. 휴대폰의 `guided-range-preview` 파일 형식과 경로도 유지한다.

`GUIDED_RANGE_DIAGNOSTIC_TOKEN`은 비워 둔다. 진단 기록은 명시적인 별도 요청이 있을 때만 활성화하며, 기존 진단 원음·로그의 보존 표시를 유지한다.

## 유지하는 동작과 계약

- `guided-range-v7`: 시작음 확인 → 6반음 상승 탐색·중간 경계 확인 → 하강 탐색·경계 확인 → 기본 결과 또는 추가 확인.
- 완충 영역·감점·현재 점수식, 인정 시간 1초 이후 발성 종료 또는 최대 2.5초 평가 완료 시 이동을 유지한다. 음별·전체 시간 제한은 추가하지 않는다.
- 기준음·건반·반원·소리 길이 원과 현재 결과 카드 디자인을 유지한다. 음별 그래프는 실제 측정값을 사용하고, 전체·안정·추천 음역 지도는 기존 연속 표시를 유지한다.
- v1~v7 결과·대기 기록을 읽으며, 서로 다른 측정 버전의 추가 측정을 합치지 않는다. 서버 선택 구간 검증과 `model_verified` 결과 계약을 유지한다.
- 독립 저장소는 로그인·`BackendApi`·공통 `UserStore`에 의존하지 않는다. 공통 차트의 동작을 바꾸지 않도록 측정용 차트는 별도 이름으로 분리했다.

## 검증

Android 공통 판정 fixture는 저장소 `tests/fixtures`에서 직접 로드한다. 새 checkout에서도 별도 수동 복사가 필요하지 않다.

```text
frontend/android/gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python -m unittest discover -s ai/vocal-scope/model-server/tests -v
```

Gradle은 `frontend/android`에서 해당 플랫폼의 wrapper를 사용한다. 실제 모델 테스트에는 `RUN_RANGE_MODEL_TESTS=1`을 지정한다. 개인 진단 자료를 사용하는 선택 테스트는 `RANGE_DIAGNOSTIC_WAV`·`RANGE_RECENT_REPORT`가 있는 경우에만 실행하며 원음을 커밋하지 않는다.

실기기에는 기존 앱을 삭제하지 않고 업데이트 설치한다. `RangeStandaloneRepositoryTest`는 별도 임시 파일을 사용해 연결 실패·재시도·버전 불일치·이전 결과 및 원음 보존을 검증한다. `RangeModelConnectionTest`는 합성 음성으로 실제 PC 모델 연결을 검증한다.

일반 앱의 MR 자동 검증은 기존 [Android Jenkins CI](JENKINS_ANDROID_CI.md)를 사용한다. 최종 커밋 SHA와 Jenkins의 실제 빌드 SHA를 대조하고 결과를 기록한다. GitLab의 알림 작업 성공은 빌드·테스트 성공 근거가 아니다.

### 분리 검증 기록 — 2026-09-10

- 일반·독립 debug 빌드와 Android 단위 테스트 각각 185개 통과, 실패·건너뜀 없음. 기존 진단 WAV를 읽는 회귀 검사도 포함했다.
- Python 58개 검사 통과. 먼저 실제 모델 포함 55개를 통과했고, Android에서 내보낸 계약과 기존 E3 기록을 제공한 뒤 나머지 3개를 별도로 통과했다. 610초·19,520,044바이트 녹음의 구간별 모델 처리도 확인했다.
- SM-S921N 실기기: 화면·그래프·글자 확대·녹음 일시 정지 13개, 독립 저장소 4개, 실제 모델 연결 v3~v7 5개, 피아노·진단 보존 3개를 통과했다. 초기 화면 검사는 사용자 대기 녹음을 보존하도록 임시 파일 공간으로 분리했고, 삭제된 설명을 기대하던 테스트를 현재 표시 상태에 맞췄다.
- 일반 APK에서 기존 이메일·비밀번호 로그인 화면 진입을 확인했다. 운영 서버에 로그인하거나 새 분석을 제출한 검증은 아니다.
- 측정·결과 디자인은 실기기 이미지로 확인했다. 기존 휴대폰 대기 녹음·결과·진단 원음을 삭제하지 않았다.
- develop 대비 백엔드·DB·infra·CI 설정 및 일반 앱의 분석 API·사용자 저장소·측정·리포트·설정·공통 차트 변경은 없다. 측정 핵심 Kotlin 16개와 모델 공통 Python 11개 파일은 기존 브랜치와 동일하다.
- Jira 필수 정보는 확인했다. 새 MR의 최종 커밋과 일치하는 Jenkins 검증은 별도 확인 대상으로 남긴다. 이 로컬 검증 기록으로 Jenkins 성공이나 운영 배포 완료를 대신하지 않는다.

## 후속 운영 연동

일반 사용자가 PC 없이 모델 결과를 받고 계정에 보관하는 기능은 이번 변경에 포함하지 않는다. 운영 연동은 모델 배포·인증·가용성, 기존 저장소 재사용, 긴 녹음 전송과 재시도, 계정별 저장·삭제 및 DB 호환성을 별도 검토한 뒤 진행한다.
