# 음성 온보딩 앱 흐름

> 아래 내용은 2026-09-14 시점의 설계·검증 기록이다. 현재 `develop`의 첫 화면은
> `guided-range-v8` 자유 음역 측정 후 `holo-v1` 노래 녹음을 진행한다.
> 개인 기준 발성 유지 점수(`personal-acoustic-v1`)는 기기에서 계산해 최종 그래프에 표시하고,
> 백엔드 세션에 선택적 필드로 저장한다. 최신 API 계약과 배포 순서는
> [통합 목소리 분석 API](../../../docs/VOICE_ONBOARDING_API.md)를 참고한다.

2026-09-14 사용자 선택: **최신 develop과 온보딩을 함께 유지하고, 확인된 API만 연결한다.**
통합 기준은 develop 커밋 4e2d7b4이다. backend/ 및 AI 소스는 변경하지 않는다.

## 현재 동작

리포트 분석 버튼 → 기기 guided-range-v7 음역 측정 → 곡 검색 → 등록된 시작 구간 선택 → 노래 녹음 → 측정 음역 확인 → 회원의 저장 준비 중 안내 / 비회원 로그인 안내.

- 일반 앱은 기존 간편 분석의 임의 음역 계산과 분석 방식 선택 창을 통과하지 않는다.
- 개발용 guided-range preview 검증 경로는 유지한다. 일반 온보딩의 음역 원음은 서버로 전송하지 않는다.
- MR이 실제 재생되는 동안 녹음한 시간이 30초 이상이며, 녹음이 유효하고 일시정지 상태일 때 종료할 수 있다. 카운트인·일시정지·버퍼링·탐색은 유효 시간에서 제외한다.
- 곡이 끝나면 일시정지하며 자동으로 녹음을 완료하지 않는다.
- 가사·반주가 없으면 다른 곡을 안내한다. 처음은 0초, 2절·하이라이트는 등록된 구간만 사용한다. VOCAL 블록이나 곡 길이 비율로 구간을 추정하지 않는다.
- 남은 반주가 30초 미만이면 시작할 수 없다. 마이크 오류와 앱 전환은 진행 중 녹음을 무효화한다.
- 비회원 파일은 별도 임시 디렉터리에서 관리하고 화면 이탈·로그인 전환·다음 앱 실행 시 정리한다. 회원의 로컬 임시 기록은 계정별로 분리한다.
- 로컬 임시 기록은 draftId/revision, 녹음의 연결은 onboardingDraftId를 사용한다. 이는 서버 sessionId/resultVersion이 아니다.
- develop의 공통 JSON 처리, 인증 갱신, 추천·찜·세트리스트·설정·연습 관련 코드를 유지한다. 로그인 복귀 경로도 develop의 숫자형 세트리스트 ID에 맞췄다.
- 기존 리포트 데이터가 있으면 '이전에 확인한 분석'으로 표시하고 develop의 곡 적합도 카드를 유지한다. 최신 분석을 자동으로 선택 저장 프로필로 취급하지 않는다.

## 연결한 API와 준비 중 기능

온보딩에서 사용하는 확인된 API:
- GET /songs: 공개 곡 검색, 토큰 없이 호출.
- GET /songs/{songId}: 곡 상세, 회원 토큰 선택.
- GET /songs/{songId}/practice-assets: 공개 MR·가사·등록 구간 조회, 토큰 없이 호출.

다음 기능은 **준비 중**이다:
- 서버 세션 생성·복구 및 기기 음역 결과 제출
- 음색 녹음 업로드·분석 시작·상태 조회·부분 리포트
- 실패한 작업의 서버 재시도·서버 취소
- 명시적 프로필 저장 및 최신 저장 프로필 조회

AppContainer.voiceOnboarding은 PendingVoiceOnboardingGateway를 주입한다. 이 구현은 네트워크를 호출하지 않는다. 미확정 JSON DTO와 임시 업로드 어댑터는 제거했다. 다른 회원 전용 분석 API로 자동 우회하지 않으며, 작업 상태·가수 결과·프로필 저장 성공을 만들어내지 않는다. 일반 온보딩 결과 화면은 음역을 보여주고 음색·저장 기능은 준비 중으로 표시한다.

회원 로컬 임시 기록 복구는 서버 활성 세션 복구와 별개다. 서버 취소가 구현되지 않은 상태에서 화면의 로컬 작업 중단을 서버 취소 성공으로 표시하지 않는다.

## API 계약에서 확인한 차이

사용자 표는 POST /voice-analysis-sessions/{sessionId}/recording 이후 POST /voice-analysis-sessions/{sessionId}/analysis를 호출하는 구조다.

현재 develop의 DirectVoiceAnalysisController는 다음 다른 구조를 사용한다:
- POST /voice-analysis-sessions/{sessionId}/analysis: multipart의 file 및 meta를 함께 받는다. 회원 인증이 필요하다.
- GET /voice-analysis-sessions/{sessionId}/analysis: 개별 분석 작업 상태를 반환한다.
- POST /voice-analysis-sessions/{sessionId}/analysis/{analysisId}/cancel: 개별 분석 취소.

현재 develop에는 온보딩 세션 생성·활성 세션 복구·음역 제출 컨트롤러와 프로필 선택 저장 컨트롤러가 없다. 따라서 기기의 draftId를 서버 sessionId로 보내거나, 두 API 구조가 같다고 가정해 연결하지 않는다.

별도 BE 작업 브랜치 71a886d의 세션 생성·복구·음역 제출 DTO와 X-Guest-Session-Token 헤더는 참고 자료다. 해당 코드가 develop에 통합되었거나 배포되었다고 간주하지 않는다.

추가 확인:
1. 온보딩의 세션 생성부터 저장까지 사용할 최종 경로와 JSON 예시.
2. 게스트 소유권 확인 및 복구·만료 처리.
3. 개별 작업 상태를 묶는 응답 구조와 서버 저장 허용 필드.
4. 부분 리포트·명시적 저장·최신 저장 프로필의 요청/응답 및 오류 형식.
5. 곡의 음색 측정 지원 여부와 2절·하이라이트 미지원 사유의 필드.
6. 현재 직접 분석 업로드와 사용자의 별도 업로드 표 중 최종 계약.

## 앱 내부 상태와 검증 경계

앱 내부 모델은 HTTP JSON 계약과 구분한다.
- 개별 작업 상태: PENDING, QUEUED, RUNNING, COMPLETED, FAILED, INSUFFICIENT_DATA, CANCELED.
- 세션 상태: CREATED, RANGE_IN_PROGRESS, TIMBRE_RECORDING, UPLOADING, ANALYZING, PARTIAL_READY, READY_TO_SAVE, SAVING, SAVED, INSUFFICIENT_DATA, FAILED, CANCELED, EXPIRED.
- SINGER_BYOL 작업의 재시도 대상은 BYOL이며 가수 검색은 ARTIST_SEARCH이다.
- 미래의 서버 어댑터가 상태를 제공할 때만 이를 표시한다. 준비 중은 서버 작업 상태로 위조하지 않는다.
- 저장은 서버의 명시적 허용, READY_TO_SAVE, 음역 AVAILABLE, 필수 두 작업 COMPLETED를 모두 요구한다. 비회원은 저장하지 않는다.
- 로컬 요청 세대, 서버 세션 ID와 결과 버전을 구분해 계정 변경·새 녹음·이전 응답이 현재 결과를 덮지 못하게 한다.
- 저장 완료 응답 검증 전에는 캐시를 교체하지 않는다. 서버 저장 후 캐시 쓰기만 실패한 경우에는 서버 저장 성공과 기기 갱신 실패를 구분한다.

## 검증

2026-09-14 병합 후 검증: Debug 앱 및 Android 테스트 APK 빌드 성공. JVM 테스트 288개 중 285개 통과, 진단용 원음이 필요한 기존 조건부 테스트 3개 제외, 실패 0개. Android API 36 가상 기기 테스트 7개 모두 통과. Git 충돌 항목 0개 및 변경 파일의 공백 오류 검사를 확인했다.
서버 분석 및 실제 사람의 목소리를 사용한 종단 간 테스트는 미확정 연동 범위에 포함되므로 수행하지 않는다.
