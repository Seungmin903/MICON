# 실시간 키 조절 · 1절/2절 통일 — 변경 내용과 남은 작업

브랜치 `feat/fe/realtimeKeyShift` (develop `836eb57` 기준, 2026-09-18 최신 · 곡 식별자 문자열 전환 포함). **아직 커밋하지 않았다** (스테이징만).

## 1. 키 조절 — 무엇을 바꿨나

반음마다 반주 파일을 만들어 S3 에 올리는 방식을 버리고, **원키 반주 하나만 받아 재생기가 실시간으로
음높이를 옮기도록** 바꿨다. 저장 부담이 사라지고 키 전환이 즉시 일어난다. 범위는 **−3 … +3**,
연습 화면 전용이며 **노래 시작 전에만** 바꿀 수 있다(재생 중에는 잠김).

| 파일 | 변경 |
|---|---|
| `audio/InstrumentalPlayer.kt` | `keyShift` 속성 추가. 속도 1.0 고정, `PlaybackParameters` 의 pitch 만 2^(반음/12) |
| `scoring/ScoringConstants.kt` | `KEY_SHIFT_MIN/MAX` −6…+5 → **−3…+3** (`RangeFit.KEY_SHIFTS` 가 참조 → 추천 키도 ±3) |
| `ui/practice/PracticeViewModel.kt` | `changeKey` 에서 서버 호출 제거, 반주·채점 기준을 같이 옮김. `keyBusy` 삭제. 자산은 항상 `selectedKey = 0` 으로 요청하고 `initialKey`(곡 상세에서 고른 키)를 재생기에 바로 적용 |
| `ui/practice/PracticeScreen.kt` | 키 렌더 대기 표시·진행 인디케이터 제거 |
| `ui/songdetail/SongPreviewPlayer.kt` | 인터페이스에 `keyShift` 추가(Media3 구현은 `InstrumentalPlayer.keyShift` 위임) |
| `ui/songdetail/SongDetailViewModel.kt` | develop 의 곡 상세 키 선택(−6…5, 키별 자산 재요청)을 실시간 방식으로 교체: `selectKey` 는 ±3 으로 자르고 미리듣기 재생기 pitch 만 바꿈, 자산은 원키 한 번만 요청 |
| `ui/songdetail/SongAnalysisCards.kt` | 추천 키 표시 범위 −6…5 → `KEY_SHIFT_MIN..MAX` (범위 밖 추천은 고를 수 없으므로 숨김) |
| 테스트 | `SongDetailViewModelTest` 3건 교체(원키만 요청·로컬 시프트·범위 클램프), `SongSectionsTest` 8건 |

### 왜 ±3 인가
Media3 내부 시프트(Sonic)는 음성용이라 폭이 커질수록 배경이 뭉개진다. 세 반음 안쪽은 거의 티가 나지
않지만 여섯 반음까지 열면 음질 불만이 나올 구간이다.

### 왜 시작 전에만 바꾸나
재생 중에 바꾸면 오디오 버퍼가 비워지면서 소리가 튀고, 시프트 처리로 생기는 출력 지연이 달라져
채점 타이밍 기준이 흔들린다. 시작 전에 고정하면 그 지연은 상수라 자동 지연 보정이 흡수한다.
`changeKey` 가 `isRunning` 이면 그대로 반환하는 이유다.

## 2. 절 구간 — 무엇을 바꿨나 (`scoring/SongSections.kt`)

- 모든 곡을 **1절·2절 둘로만** 나눈다. 보컬 묶음 사이 간주 중 **가장 긴 곳**이 경계. 간주가 없는 곡은
  보컬 구간 가운데 25–75 % 안의 가장 긴 음표 공백(≥ 2 s)에서 나누고, 그것도 없으면 1절만 둔다.
- 전주·간주는 개수 그대로 남긴다(간주 건너뛰기 대상). 짧은 보컬 조각(< 12 s, 애드리브 등)은 앞 절에 붙인다.
- 절·하이라이트·간주 건너뛰기 **출발점은 그 구절이 시작하는 마디의 한 마디 앞 첫 박**
  (`SectionMap.leadBefore`). 비트 그리드가 없으면 2.5 s 앞.
- 연습 화면은 그 위에 **한 마디 카운트인**(곡 템포, 박당 0.45–0.75 s)을 더 준다 → 보컬 전 준비 시간은
  약 한 마디 반주 + 한 마디 카운트인 ≈ 4–6 s.

## 3. 남은 작업

### 3-1. 연습 기록 저장 — 앱은 BE `fix/be/practiceSessionKey`(ffe85d7) 계약에 맞췄다

BE 계약(V29): `practice_session.initial_key`(세션 생성 키) · `selected_key`(최종 키) · `key_change_count`.
완료 요청에 `finalSelectedKey`·`keyChangeCount`(선택), 세션·이력·리포트 응답에 `initialKey`·`keyChangeCount`·`keyChanged`.
생성과 완료 **둘 다** `hasReadyInstrumental(songId, key)` 로 그 반음의 반주 파일이 READY 인지 검사한다.

앱 동작(`PracticeViewModel`):
1. 세션은 늘 **원키(0)** 로 연다 → 생성 검사 통과.
2. 완료 때 `finalSelectedKey = 고른 키`, `keyChangeCount = (키≠0 ? 1 : 0)` 을 보낸다(서버 규칙: 최종≠최초면 횟수 ≥ 1).
3. 서버가 422 `UNSUPPORTED_PRACTICE_KEY` 를 주면 키 없이 다시 완료해 기록은 남기고, 저장 메시지에 "키 정보는 원키로 남았다" 고 알린다.
4. DTO·모델(`PracticeRecord`/`PracticeSummary`/`PracticeReport`)에 `initialKey`·`keyChanged` 추가(기본값 = 원키·변경 없음).

**BE 에 남는 요청 하나**: 원키 반주만 두는 정책상 `hasReadyInstrumental` 이 키와 무관하게
`semitones = 0 AND kind = 'INSTRUMENTAL' AND generation_status = 'READY'` 만 확인해야 한다. 그때까지 키를 바꿔 부른 연습은
저장은 되지만 키가 0 으로 남는다(3번 폴백). 검사가 풀리면 앱 코드 변경 없이 2번 호출이 그대로 통과한다.
`@Min(-6) @Max(5)` 는 앱 범위(±3)를 포함하므로 그대로여도 된다.

### 3-2. song_section 데이터
`practice_assets.sections` 는 현재 비어 있다(TJ 975 적재 SQL 에 song_section 없음). 앱은 서버 구간이
없어도 음표에서 1절·2절을 스스로 나누므로 동작에는 지장 없다.

## 4. 검증 상태
- `:app:compileDebugKotlin` · `:app:compileDebugUnitTestKotlin` 통과 (develop `836eb57` 병합 후).
- `:app:testDebugUnitTest` 는 58개 테스트 클래스가 모두 실행 단계에서 `ClassNotFoundException` 으로 실패한다.
  **이 변경과 무관하다** — 변경을 stash 로 되돌리고 돌려도 같다. 별도 확인이 필요하다.
- 실기기 확인 아직 안 함: 시프트 출력 지연이 채점 타이밍에 주는 영향, ±3 끝에서의 음질, 곡 상세 미리듣기에서 키 전환.
