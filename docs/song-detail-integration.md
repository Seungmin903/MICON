# 통합 곡 상세 계약 (2026-09-18)

추천 LP, 연습 목록, 찜/세트리스트의 곡 진입은 `SongDetailScreen` 하나를 사용한다.
디자인은 기존 LP·톤암·곡선형 검은 시트를 유지하며 상세 카드만 통합한다.
다른 탭의 밝은 테마와 기존 화면 주소는 유지한다. MR 생성과 운영 병합·배포는 별도 단계로 취급한다.

## 화면·이동

| 진입 | 주소 | 보존하는 상태 |
|---|---|---|
| 추천 | `match/{songId}` | LP 목록/현재 곡, 추천 탭 선택, 로그인 복귀 시 현재 곡 |
| 연습 | `song/{songId}` | 연습 탭 선택 |
| 찜·세트리스트 | `song/{songId}?source=favorites` | 찜 탭 선택/로그인 복귀 |
| 연습 시작 | `practice/{songId}?selectedKey=-2` | 선택한 곡/키, 생략 시 원키 |

- 음역대 매칭 분석만 남기고 중복된 “이 노래, 나한테 맞을까?” 카드는 제거한다.
- 내 음역은 저장 프로필의 전체 측정 음역(`lowMidi`/`highMidi`)이다. 미측정 상태에 기본 음역을 만들어 표시하지 않는다.
- 권장 키·음색 적합도는 별도 카드로 표시한다. 추천이 없는 곡은 원키 추천/음색 점수로 임의 대체하지 않는다.
- 그래프는 음표 원본의 시간·최고음·간격을 유지한다. 개인 음역 밖(분홍)과 곡 자체 주의 구간(주황)을 구분한다.
- 구간 선택은 설명만 연다. “이 위치 듣기”를 눌러야 해당 시각으로 이동하고 재생한다.
- 미지원 키(422)·반주/음표 미준비(409) 시 상태를 표시하고 해당 키 연습을 막는다. 원키 반주로 조용히 대체하지 않는다.

## API와 데이터 소유권

앱의 `/api/v1` 프록시 접두 기준이다. 백엔드 컨트롤러에는 접두가 없다.

| 요청 | 용도/주의 |
|---|---|
| `GET /songs/{id}` | 제목·가수·이미지·원곡 음역·연습 준비 상태 |
| `GET /songs/{id}/practice-assets?selectedKey=N` | 해당 키 반주 URL, 원키 목표 음표, 곡 구간, 조성/BPM/박자 |
| `GET /users/me/voice-profile` | 적합도 요청에 사용하는 저장 프로필 ID(기존 1분 캐시, 재측정 시 갱신) |
| `GET /songs/{id}/fit?analysisId=P&keyOffset=0` | 권장 키·음색 점수/설명. `analysisId`는 저장 프로필 ID |
| BE → AI `POST /song-fit` | AI 적합도 기능이 켜진 경우. 요청 식별자와 선택 키가 일치하는 응답만 수용 |
| `GET /favorites`, `PUT/DELETE /favorites/{songId}` | 최신 develop의 찜 컨트롤러와 일치 |
| `/setlist`, `/setlist/{setlistId}` | 세트리스트 목록·생성·상세·수정·삭제 |
| `POST/DELETE /setlist/{setlistId}/{songId}` | 최신 develop의 세트리스트 곡 추가·제거와 일치 |

키 변경은 `practice-assets`만 다시 요청한다. `targetNotes`는 항상 원키이며
그래프와 연습 채점에서 선택 키를 **한 번만** 적용한다. 시간축은 전주를 포함한 곡 전체 시각이다.
일반 곡 구간은 AI 개인 부담 예측으로 표현하지 않으며, 개인 음역 밖 구간은 앱 표시용 비교다.
저장된 추천 순서·점수 계산식·추론 모델은 바꾸지 않는다.

### 선택적 음색 설명

`SongFitResponse.recommendationScores`에 다음 두 선택 필드를 추가했다.

```json
{
  "overall": 0.88,
  "range": 0.9,
  "timbre": 0.77,
  "rhythm": null,
  "algorithmVersion": "fit-v1",
  "timbreAxis": "ECAPA",
  "timbreLabel": "목소리 물성이 가까운 가수"
}
```

- 기존 `recommendation_song.axis/label`을 읽는다. DB 마이그레이션은 필요 없다.
- 현재 사용자·저장 프로필에 속하고 프로필 수정 이후 생성된 추천만 읽는 기존 조건을 유지한다.
- AI는 전달받은 점수 객체를 그대로 반환한다. 기존 필드만 있는 요청도 정상 처리한다.
- 프런트는 설명이 없거나 알 수 없는 축이면 일반 점수 설명을 표시한다. 없는 점수는 생성하지 않는다.
- 점수의 기존 0..1/0..100 호환 정규화는 유지하고 NaN/Infinity는 버린다.
- 곡·프로필·선택 키가 다른 적합도 또는 반주 응답을 현재 화면에 적용하지 않는다.

## 재생 수명

`PlaybackLifecycleEffect`는 현재 화면의 `NavBackStackEntry` 수명을 따른다.

- `ON_PAUSE`부터 정지한다. 전환 애니메이션/`ON_STOP`/ViewModel 제거까지 기다리지 않는다.
- 뒤로가기·설정·로그인·연습 이동·다른 LP 선택은 이동 콜백 전에 정지한다.
- 상세 플레이어는 이탈 시 재생 예약까지 취소하고 해제한다. 숨겨진 화면의 늦은 반주 응답은 재생기를 만들지 않는다.
- 복귀 시 선택 키/위치를 보존하고 만료 가능성이 있는 URL을 다시 조회하지만 자동 재생하지 않는다.
- 연습 화면도 이탈 시 반주·카운트인·녹음 다시 듣기를 정지한다. 기존 녹음/저장 규칙은 변경하지 않는다.
- 상세 플레이어는 인터페이스로 분리해 취소·지연 응답·키 전환을 단위 테스트한다.

## 검증

- Android: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
- Backend: `gradlew test`
- AI: 추천 서버 환경에서 `python -m unittest discover -s tests -v`
- 기기: `SongDetailScreenTest`(레이아웃·큰 글꼴·결측·키·구간·캐러셀·탭 색상),
  `SongDetailPlaybackTest`(합성 WAV + 실제 Media3 + Navigation/Activity lifecycle).
- 테스트 음원/분석은 합성 fixture이며 운영 계정·데이터·음원을 사용하지 않는다.
- 실제 로그인·운영 S3 signed URL·물리 기기 스피커/블루투스까지의 종단 검증은 배포 전 별도 수행한다.

### 로컬 실행 결과 (2026-09-18)

| 검증 | 결과 |
|---|---|
| Android 전체 단위 테스트 | 376개 중 373 통과, 진단 음원 파일을 별도 요구하는 기존 테스트 3개 제외 |
| Android debug 앱/테스트 APK | 빌드 성공 |
| Backend 전체 테스트 | 209 통과 |
| AI 추천 서버 전체 테스트 | 92 통과 |
| Android API 36 에뮬레이터 | 9 통과(화면 7 + 실재생/이탈 2), 최종 묶음 실행 `OK (9 tests)` |
| 변경 검사 | `git diff --check` 통과 |

실재생 검증은 로컬 HTTP 합성 WAV를 Media3에서 재생하고 시간 진행을 확인한 뒤,
Navigation 이동·Activity 백그라운드·Composition 제거 시 정지/해제와 복귀 시 비자동 재생을 검사했다.
테스트 초기의 준비 시간 초과는 MockWebServer의 주소 조회를 UI 스레드에서 실행한 테스트 코드 문제였으며,
주소를 테스트 스레드에서 미리 생성한 후 실재생 2개 및 전체 9개 재실행이 통과했다.

### develop 대상 MR 준비

MR 준비 시 최신 `develop`(`22463f6`)의 찜·세트리스트 API 변경 4개 커밋을 fast-forward로 반영했다.
이전 로컬 검증 때 `/users/me/favorites`였던 백엔드가 `/favorites`로 바뀌었으므로
기존 프런트 경로를 유지하고 테스트·명세를 최신 주소로 정리했다.
세트리스트는 `/setlists` → `/setlist`, 곡 추가는 `PUT /{id}/songs/{songId}` → `POST /{id}/{songId}`로
최신 백엔드에 맞췄다. 서버 컨트롤러 변경 자체는 이 MR이 아니라 develop에 이미 포함된 변경이다.
