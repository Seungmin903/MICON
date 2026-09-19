# 기능 사용 현황 감사

작성 기준일: 2026-09-19

## 판정 기준

- Android `BackendApi`·`PreferenceApi`에서 시작해 실제 운영 코드의 호출 지점까지 연결되는 기능은 **앱 사용**으로 분류했다.
- Controller 외에도 스케줄러, 이벤트 리스너, AI 콜백, 브라우저 화면처럼 Android 호출만으로 판단할 수 없는 진입점을 확인했다.
- 테스트에서만 호출되거나 주석 처리된 API라도 OpenAPI·운영 문서·호환 계약에 남아 있으면 보존 대상으로 분류했다.
- 외부 AI 서버, 운영자 브라우저 또는 향후 클라이언트가 사용할 수 있는 공개 API는 Android 호출이 없다는 이유만으로 삭제하지 않았다.
- 프런트엔드 코드는 감사 대상으로만 읽으며 변경하지 않는다.

## 현재 Android 앱에서 사용하는 기능

| 도메인 | 사용 기능 |
| --- | --- |
| auth/user | 회원가입, 로그인, 토큰 갱신, 로그아웃, 비밀번호 재설정, 내 정보 조회, 회원 탈퇴 |
| preference | 장르·가수 조회, 선호 정보 조회·저장 |
| song/practice asset | 곡 검색·상세, 연습 에셋 조회, MR·가이드 리소스 재생 |
| favorite/setlist | 찜 조회·추가·삭제, 세트리스트 조회·생성·이름 변경·삭제, 곡 추가·삭제 |
| practice | 세션 생성·완료·취소, 리포트 저장·목록·상세·삭제 |
| recommendation/songfit | 추천 생성·최근 추천 조회, 곡 적합도 조회 |
| setting | 녹음 보관 설정 조회·변경, 전체 음성 데이터 삭제 요청 |
| voice onboarding | 세션 생성, 기기 음역 저장, 음역·음색 분석 제출, 상태 조회·취소·재시도, 결과 조회, 프로필 저장·조회 |
| recording/direct analysis | 녹음 업로드, 직접 음성 분석 생성·상태·최근 결과·리포트 조회 |

## Android에서 직접 호출하지 않지만 유지한 기능

다음 기능은 현재 앱의 Retrofit 호출만 보면 미사용이지만 다른 명확한 진입점 또는 운영 목적이 있어 유지한다.

- `/callbacks/voice-analysis-components`: AI 서버의 분석 결과 콜백
- `/internal/embedding-catalog/active`, `/internal/catalog-media/**`: AI 서버용 내부 카탈로그
- 추천 폴링·프로필 복구, 분석 타임아웃, 녹음 삭제 Worker: 스케줄러 또는 이벤트 리스너가 실행
- `/homepage`, `/v1/homepage`: 운영자용 브라우저 화면
- `GET /health`: 앱 연결 상태를 확인하는 운영·진단용 API
- `/recordings` 조회·재생·개별 삭제: 현재 앱 화면에서는 미사용이지만 독립적인 사용자 데이터 관리 API
- `GET /users/me/voice-data-deletions/{deletionId}`: 비동기 삭제 상태 확인 API
- `PUT /setlist/{setlistId}/song-order`: 앱에 드래그 정렬 UI가 아직 없어 미호출인 확장 API
- `GET /voice-analysis-sessions/active`: 현재 앱 복구 흐름은 사용하지 않지만 백엔드 계약 테스트가 보장하는 세션 복구 API
- 추천 ID별 조회·추천 곡 상세, 음성 데이터 정책 조회: 현재 앱 화면 밖의 조회 계약

## 미사용처럼 보이지만 보존한 코드

- 연습 세션 상세, 상태 변경, 분석 상태·재시도 관련 코드: 현재 Controller는 주석 상태지만 `openapi.yaml`과 제안 문서에 계약이 남아 있어 보존
- `VoiceDataDeletionService.deleteAnalysis`: 이전 분석 삭제 계약의 호환 경로로 보존
- Android `BackendApi.health`, `BackendApi.activeVoiceSession`: 운영 진단 및 세션 복구 계약이므로 보존
- 프런트엔드의 `unused` 표시 코드: 프런트엔드 비수정 원칙에 따라 보존

## 확인이 필요한 계약 불일치

Android의 노래 분석 흐름은 아래 API를 실제 호출하지만 현재 백엔드에는 대응 Controller가 없다.

- `POST /voice-analyses`
- `GET /voice-analyses/{analysisId}`
- `GET /voice-analyses/latest`
- `GET /voice-analyses/{analysisId}/report`

현재 백엔드는 `/voice-analysis-sessions/{sessionId}/analysis` 기반의 직접 분석 API를 제공한다. 따라서 노래 분석 기능은 기존 호환 Controller를 복구하거나 Android 호출을 세션 기반 계약으로 이관해야 한다. 이 감사에서는 제품 동작을 임의로 바꾸지 않기 위해 어느 한쪽도 삭제하지 않았다.
