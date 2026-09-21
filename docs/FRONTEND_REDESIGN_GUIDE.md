# MICON Android 프론트 리디자인 가이드

> 작성일 2026-09-19 · 기준 기기 Galaxy S10 (360×760dp) + Galaxy S23 Ultra (384×824dp)
> 시안 캔버스: "MICON 리디자인 — 점검 · 시안 · 분배" (Claude 디자인 캔버스, 총 40장)
> 이 문서는 캔버스 보드의 설계 기준을 모은 참고 문서입니다. 토큰·원자·분자·유기체와 디자인 카탈로그 구현이 시작되었으며, 실제 적용 상태는 현재 Android 코드와 MR을 기준으로 판단합니다.

---

## 목차

1. 프로젝트 현황 요약
2. 1차 점검 — Material 3 · Android 가이드 대비 (코드 기준)
3. 기기 기준 — S10 · S23 Ultra
4. 디자인 방향 — 소프트 & 라운드 토큰
5. CD 컴포넌트 · CD 플레이어
6. 하단 내비게이션
7. 음 이름 표기 규칙 (도레미)
8. UX 원칙 4개
9. 컴포넌트화
10. 정보 다이어트 — 화면에서 빼야 할 것
11. 전 화면 구조 시안 (21화면)
12. 작업 분배 — 줄기 A · B · C · D
13. 다음 단계 · 미결 사항

---

## 1. 프로젝트 현황 요약

**스택**
- Kotlin + Jetpack Compose, 단일 Activity, Compose Navigation
- `frontend/android/app/src/main/java/com/micon/app/` — `.kt` 264개
- 앨범아트는 백엔드가 Spotify 연동으로 채움 (`SongImageEnrichmentService`) → 앱은 `Song.imageUrl` 사용

**지금까지 한 일**
- 코드 전체 점검 (글자 크기 · 터치 영역 · 색 대비 · 고정 높이 · 인셋)
- 추천 페이지 · 곡 상세 hi-fi 시안
- 나머지 19화면 구조·문구 확정용 시안
- 토큰 · 컴포넌트 · 내비 · 음 이름 · 정보 다이어트 규격 보드
- 작업 분배표
- `ui/design/` 아래 토큰·원자·분자·유기체·디자인 카탈로그 구현

**상태 확인 원칙**
- 이 문서의 화면별 제안은 목표 설계이며 구현 완료표가 아니다.
- 화면별 적용 여부와 남은 작업은 현재 코드·테스트·MR에서 확인한다.
- 아래 미결 사항은 팀 합의 전까지 유지한다.

---

## 2. 1차 점검 — Material 3 · Android 가이드 대비

### 2-1. 우선순위 높음 (5)

**① 타이포 시스템 부재**
- `Type.kt`: `MiconTypography = Typography()` 기본값, Pretendard 미적용
- `fontSize = N.sp` 하드코딩 464곳 (9~96sp 23종), `MaterialTheme.typography` 참조 1곳
- 조치: 8단계 타입 스케일 정의 → 치환

**② 최소 글자 크기 미달**
- 9sp 2곳, 10sp 9곳, 11sp 38곳
- 예: `PracticeScreen` Stat 라벨 10sp, 이어폰 안내 11sp, 시트 라벨 11sp
- 조치: 하한 12sp

**③ 터치 타깃 48dp 미달**
- `IconButton(Modifier.size(24.dp))` — LoopChip 닫기 (`PracticeScreen:317`)
- `IconButton(Modifier.size(32.dp))` — 레인 토글 (`:356`)
- `Text(...).clickable` — 키 −/+ (`:242, :256`), 로그인 링크 (`OnboardingScreens:129,133,199`), "나중에 할게요" (`QuickAnalysisScreen:421`), "저장하지 않고 나가기" (`SingAnalysisScreen:459`)
- Section/Toggle 칩 높이 29dp, 간격 6dp
- 조치: IconButton/TextButton으로 교체, M3 Chip(32dp + 48 터치)

**④ 색 대비 미달** (텍스트 4.5:1, 그래픽 3:1 기준)
- Grey `#8C8C96` on white = 3.33 (보조 글자 전반)
- GreenChipText on GreenChipBg = 2.72
- Pink `#FF5C8A` = 2.94
- 비활성 버튼 (white on Purple 45%) = 1.88
- Orange 2.03 · Teal 1.90 · GreenBar 1.73
- 조치: 아래 대체 토큰 (`Theme.kt` 한 곳)

**⑤ 다크 모드 상태바 아이콘 안 보임**
- `enableEdgeToEdge()` 인자 없음 + 앱은 라이트 고정 + 배경 흰색
- 시스템 다크 모드 사용자 → 흰 배경 위 흰 상태바 아이콘
- 조치: `enableEdgeToEdge(SystemBarStyle.light(TRANSPARENT, TRANSPARENT), SystemBarStyle.light(...))`

### 2-2. 우선순위 중간 (3)

**⑥ 고정 높이 레이아웃**
- EmptyReport 560dp (`ReportScreens:234`) — S10 가용 466dp
- 연습 가사 하단 Spacer 190/330dp (`PracticeScreen:188`) — 시트 실측 150dp
- SingAnalysis Spacer 232dp (`:361`)
- 로그인 총 713dp — S10 3버튼 내비 가용 682dp
- 조치: weight / BoxWithConstraints / onSizeChanged 측정

**⑦ 글자 크게 설정(fontScale) 대응**
- 헤더 `height(64.dp)/height(60.dp)`, 검색창 54dp, 버튼 56dp 고정 + 내부 sp 텍스트
- 조치: `heightIn(min=)`

**⑧ 비활성 상태 표현**
- `disabledContainerColor = Purple.copy(alpha = 0.45f)`, 글자 흰색
- 조치: M3 disabled (컨테이너 onSurface 12%, 글자 38%)

### 2-3. 우선순위 낮음 (4) · 통과 (1)

- 하단 내비: 높이 80 ✓, 라벨 12sp ✓, 아이콘 26dp(M3 24), 인디케이터 투명
- 4dp/8dp 그리드: 54/27, 18, 14, 6/5/10, 60 등 이탈 값 다수
- 문자열 리소스: `stringResource` 0회, 전부 Kotlin 하드코딩 (한국어 MVP면 보류 가능)
- 밀도 무관 단위: `strokeWidth = 3f`(px) — S10 FHD+/WQHD+ 에서 두께 달라짐
- **통과** 인셋·컷아웃: 루트 Scaffold 패딩, 곡 상세 시스템바 처리, IME 패딩 모두 정상

### 2-4. 색 대비 대체 토큰

| 용도 | 현재 | 대비 | 대체 | 대비 |
|---|---|---|---|---|
| 보조 글자 | `#8C8C96` | 3.33 | `#6B6B78` | 5.25 |
| 보조 글자 on GreyBg | `#8C8C96` | 2.98 | `#6B6B78` | 4.70 |
| 보조 글자 on Lavender | `#8C8C96` | 2.85 | `#66667A` | 4.79 |
| 안정 칩 글자 | `#4EA72E` | 2.72 | `#2E7D32` | 4.58 |
| 핑크 글자 | `#FF5C8A` | 2.94 | `#C92A5E` | 5.28 |
| 오류 글자 | `#E8556A` | 3.54 | `#D23B52` | 4.68 |
| KeyBadge 글자 | `#6C5CE7` on `#DCD6FF` | 3.49 | `#5546C9` | 4.86 |
| 막대 Orange (그래픽) | `#F5A623` | 2.03 | `#C77D00` | 3.30 |
| 막대 Green (그래픽) | `#9BD65A` | 1.73 | `#559226` | 3.80 |
| 막대 Teal (그래픽) | `#2ED3B0` | 1.90 | `#0E9C80` | 3.45 |

---

## 3. 기기 기준 — S10 · S23 Ultra

**Galaxy S10**
- 논리 해상도 360×760dp, 비율 19:9
- 물리 FHD+ 2280×1080 (2.625x, 기본) / WQHD+ 3040×1440 (4.0x) — dp 값은 같음
- 상태바 ≈ 30dp, 3버튼 내비 48dp (제스처 ≈ 16dp)
- 탭 화면 콘텐츠 가용 높이: 760 − 30 − 48 − 80(내비바) − 64(헤더) = **538dp**
- 전체 화면(연습·분석): **682dp**

**Galaxy S23 Ultra**
- 논리 해상도 384×824dp
- 물리 FHD+ 2316×1080 (≈2.8x) / WQHD+ 3088×1440 (3.75x)
- 탭 화면 콘텐츠 가용 높이: **602dp** · 전체 화면 **746dp**

**두 기기에서 달라지는 것**
- 로그인(713dp): S10 스크롤 발생 → S23U 여유
- 빈 리포트 560dp: S10 118dp 초과 → S23U 54dp 초과 (여전히 스크롤)
- 연습 가사 영역: S10 ≈ 212dp → S23U ≈ 276dp
- 그 외 점검 항목 10개는 기기 무관

**규칙**
- 레이아웃 검증 기준 폭은 360dp 유지 (큰 화면만 보면 고정값이 숨음)
- Compose Preview 두 개: `360×760`, `384×824`

---

## 4. 디자인 방향 — 소프트 & 라운드 토큰

### 4-1. 방향
- 라이트 모드 고정, 한 톤으로 통일
- 제거: 추천 탭 남보라 세로 그라데이션(`RecommendBackground`), 곡 상세 다크 팔레트(`SongDetailColors`)
- 배경은 순백 대신 라벤더 틴트 화이트 → 흰 카드가 떠 보임

### 4-2. 색 역할 (M3 colorScheme 매핑)

| 역할 | 값 | 용도 |
|---|---|---|
| background | `#F7F5FC` | 화면 배경 |
| surface | `#FFFFFF` | 카드 · 시트 · 플레이어 |
| primary | `#6C5CE7` | 버튼 · 활성 칩 (기존 유지) |
| onPrimaryContainer | `#5546C9` | 틴트 위 보라 글자 |
| primaryContainer | `#EEEBFF` | 인디케이터 · 웰 · 배너 |
| secondaryContainer | `#DCD6FF` | 키 배지 · 선택 칩 |
| onSurface | `#1E1B33` | 본문 |
| onSurfaceVariant | `#6B6B78` | 보조 글자 (5.25:1) |
| outlineVariant | `#E4E2EE` | 칩 테두리 · 구분선 |
| 안정 (success) | `#2E7D32` / `#E1F3E6` | 글자 / 컨테이너 |
| 도전·경고 (tertiary) | `#C92A5E` / `#FFE3EC` | 글자 / 컨테이너 |
| error | `#D23B52` / `#FBE3E6` | 글자 / 컨테이너 |

### 4-3. 타입 스케일 (Pretendard · 464곳 → 8단계)

| 이름 | 크기 | 용도 |
|---|---|---|
| headlineMedium | 28 / 36 Bold | 리포트 수치 |
| titleLarge | 22 / 30 Bold | 화면 제목 (22/21/24 통합) |
| titleMedium | 18 / 26 Semibold | 카드 제목 (17/18/19 통합) |
| bodyLarge | 16 / 24 | 입력 · 가사 |
| bodyMedium | 14 / 20 | 가수명 · 안내 (13/15 통합) |
| labelLarge | 14 / 20 Semibold | 버튼 · 칩 |
| labelMedium | 12 / 16 Medium | 캡션 — **최소 크기** (9~11 전부 여기로) |

### 4-4. 형태 · 간격
- 라운드: 카드·시트 20~28, 버튼·필드 14~16, 칩·배지 full
- 주 버튼 52dp r16 · 보조 버튼 outlined · 칩 32dp full
- 간격: 4 · 8 · 12 · 16 · 20 · 24 · 32
- 화면 좌우 여백 20 (기존 28/32 통일)
- 그림자: 항상 보라 틴트 `0 10 28 rgba(76,64,160,0.10)`, 회색 그림자 금지
- 테두리: outlineVariant 한 종류

---

## 5. CD 컴포넌트 · CD 플레이어

### 5-1. 왜 LP → CD 인가
- `lp_ring.png`가 260×260px 단일 파일(drawable-nodpi) → 150dp에서 400~600px로 늘려 그려 픽셀 깨짐
- CD로 바꾸되 PNG 없이 **Compose Canvas** 로 그려 어떤 밀도에서도 선명

### 5-2. CD 구조 (지름 D 기준)
- 아트 = D (Crop, Circle)
- 무지개 허브 링 0.28D (conic-gradient 파스텔)
- 홀 0.12D
- 광택: 위쪽 넓은 하이라이트 + 좁은 반사 2줄
- **동심원 홈 링 없음** (LP처럼 보여 제거)
- 그림자 `0 4 12 ink 22%` + 1dp 흰 70% 테두리

### 5-3. 크기
- 플레이어 중앙: S10 220 / S23U 244dp
- 공식: `디스크 = min(폭 − 140, 가용높이 × 0.42)`
- 랙 미니: S10 52 / S23U 56dp
- 목록 행(SongRow): 56dp (기존 76)
- 플레이어 카드: 128dp (기존 150)

### 5-4. 앨범아트 없을 때 — D안 "인쇄 라벨" (권장)
- 제목을 위쪽 원호에, 가수를 홀 아래에 인쇄
- 배경은 제목 해시로 고른 파스텔 2색 그라데이션 (같은 곡 = 같은 색)
- 랙 미니는 글자 없이 색·허브만 + 아래 12sp 캡션
- 폐기: A안(첫 글자 한 개), B안(장르 라벨 — 곡 구분 안 됨), C안(음역 파형 — 톤 튐)
- 대안 E(음역 링): 회전하면 의미 사라져 정지 화면용, F(컬러 블롭): 랙 미니 전용 가능

### 5-5. 포터블 CD 플레이어 (추천 페이지)
- 본체: 둥근 사각 r40, 투톤 플레이트, 왼쪽 힌지 너클 2개, 오른쫑 볼륨 휠·이어폰 잭, MICON 음각 로고
- 뚜껑: 금속 링 + 스모크 유리 창 + 사선 반사
- 앞면: OPEN 슬라이더 · LCD "TRK 01 / 07" · ⏮ ⏭ 물리 버튼
- **재생 버튼 없음** — 중앙 CD 탭 → 곡 상세 화면으로 이동하며 재생 시작
- 상단 일자 힌지 선 제거 (바텀시트 손잡이로 오해)

### 5-6. 랙
- 추천 7곡 · 5개 보이고 6번째가 오른쪽에 30dp 걸쳐 보임 + 페이드
- 랙 CD 탭 · ⏮ ⏭ · 가로 스냅 스크롤 세 가지가 모두 같은 "갈아끼우기"
- 선택 링이 잘리지 않게 위 6dp 여유

### 5-7. 모션
- 회전 12초/1바퀴 linear, 화면 이탈·백그라운드 정지, 시스템 애니메이션 축소 시 정지
- 교체: 기존 CD 240ms 위로 40dp·scale 0.92·fade → 새 CD 280ms 아래에서 올라옴 (겹침 80ms, 총 440ms)
- 중앙 CD 탭: scale 0.97 → 곡 상세로 공유 요소 전환, 그 화면에서 미리듣기 시작

### 5-8. 구현 메모
- `CompactDisc(imageUrl, title, size, spinning)` 하나로 VinylArt 전부 치환
- `AsyncImage.clip(CircleShape)` + Canvas 로 링·홀·반사광
- 회전: `rememberInfiniteTransition` → `graphicsLayer { rotationZ }`
- 대체 아트 색: `title.hashCode() mod 7` → 파스텔 팔레트
- Spotify 이미지 640px 크기 요청 여부 확인 (300px이면 256dp에서 흐림)

---

## 6. 하단 내비게이션

### 6-1. 플로팅 바
- 전체 폭 80dp 바 → 좌우 16dp 띄운 **64dp 알약**
- 시스템 바 위 12dp, 라운드 32(full)
- 그림자 `0 10 28 보라 16%` + 1dp 보라 6% 테두리
- 아이콘 22 stroke 1.9, 라벨 12 Medium (활성 Bold)
- 활성 표시: 아이콘 뒤 44×26 Lavender 알약 + primary 색 (색 없이도 구분)
- 콘텐츠는 바 아래로 스크롤 (contentPadding 88) → S10에서 실질 +16dp
- 항상 표시, 전체 화면 라우트(연습 실행)에서만 제거
- Compose: Scaffold bottomBar 대신 Box 오버레이 + `Surface(shape=CircleShape)`

### 6-2. 탭 이름

| 현재 | 제안 | 이유 |
|---|---|---|
| 리포트 | **분석** | 목소리 분석 결과 + 새 분석 시작. "리포트"는 연습 결과와 헷갈림 |
| 추천 | 추천 | 유지 (CD 플레이어) |
| 연습 | 연습 | 유지 |
| 평가 | **기록** | 지난 연습 점수·상세. "평가"는 누가 무엇을 평가하는지 불명확 |
| 찜 | 찜 | 유지 (세트리스트 포함) |

- 5탭 유지로 확정 (4탭 대안: 기록을 연습 탭 세그먼트로 — 보류)

---

## 7. 음 이름 표기 규칙 (도레미)

### 7-1. 문제
- "G2 – E4", "C Major", "1옥타브 (Low)", MIDI 번호 → 페르소나가 읽지 못함

### 7-2. 규칙 1 · 음 하나 = 높낮이 말 + 도레미

| 옥타브 | 말 |
|---|---|
| 2 | 아주 낮은 |
| 3 | 낮은 |
| 4 | **중간** (가온 도) |
| 5 | 높은 |
| 6 | 아주 높은 |

- 반음은 "도♯" + 미니 키보드 검은 키 강조 (♯ 정도는 허용으로 확정)
- TalkBack은 "도보다 반 높은 음"으로 읽음
- MIDI 번호는 화면에 절대 노출 안 함

### 7-3. 규칙 2 · 범위 = 말 + 미니 키보드 + 비교 한 문장
- "낮은 라 ~ 중간 솔"
- 미니 키보드: 내 범위 채움(라벤더), 곡 범위 테두리(핑크), 중간 도에 항상 라벨
- 비교 문장: "여성 평균보다 조금 낮아요" / "아이유 곡은 대부분 편하게 들어와요" / "다섯 음 높아요"

### 7-4. 치환표

| 지금 | 바꿀 말 |
|---|---|
| G2 – E4 | 아주 낮은 솔 ~ 중간 미 |
| C Major → D Major | 원래 키 → 한 키 위 |
| −1 key / +2 key | 한 키 내려 / 두 키 올려 |
| 1옥타브 (Low) | 축 라벨: 낮음 · 중간 · 높음 + 도 눈금 |
| 안정 음역 / 불안정 | 편한 음역 / 힘든 음역 |
| N반음 위/아래 | N음 높아요/낮아요 |
| 2옥타브 솔 (노래방) | 기본 숨김, 설정에서 켜면 괄호 병기 |

### 7-5. 구현
- `NoteNames.friendly(midi)`, `rangeText(low, high)` 하나로 통일
- 기존 `ReportNoteNames` · `RangeNoteNames` · `karaokeOctaveName` 세 곳 교체
- 미니 키보드는 `GuidedRangeKeyboard` 축소 재사용

---

## 8. UX 원칙 4개 (첨부 진단 → MICON 적용)

**① 초기 진입의 가중치 — 화면마다 주 행동은 하나**
- 지금: 추천 탭에 검색·성별·시대·장르·인기 칩 + LP 버블 8개 동등 배치, 리포트 탭 큰 원 버튼 2개 경쟁
- 적용: 추천은 중앙 CD 하나, 필터는 검색 뒤로. 리포트는 버튼 1개

**② 과정의 분절 — 온보딩 5단계 한 톤**
- 지금: 로그인 → 설문 → 음성 분석 → 리포트 → 추천 모두 배경·헤더·버튼 다름
- 적용: 배경 `#F7F5FC`, 헤더 64, 주 버튼 52/r16, 카드 r20 동일. StepHeader 하나

**③ 피드백의 모호함 — 오류는 원인 + 해결 버튼**
- 지금: 세션 만료 시 이유 없이 로그인으로, 권한 거부 안내 없음
- 적용: 오류 카드 3줄 규격 (무슨 일 · 왜 · 다음 행동 1개)

**④ 마지막 단계의 마찰 — 녹음·재생·저장**
- 지금: "반주 렌더 중… 10초" 11sp 문구, 저장 실패 12sp 빨간 글자, 이어폰 경고 매번
- 적용: 10초 이상 작업은 진행률+취소, 저장은 자동 재시도 후 스낵바, 이어폰 안내 첫 1회

---

## 9. 컴포넌트화

### 9-1. 개념
- "디자인 통일" = 화면이 직접 색·크기를 정하지 못하게 만드는 것
- 버튼·칩·CD·카드가 각각 한 곳에만 정의 → 모든 화면이 빌려 씀 → 바꿀 때 한 곳만

### 9-2. 5단계

| 단계 | 뜻 | MICON |
|---|---|---|
| 1 토큰 | 색·글자·간격·라운드 값 | `MiconTheme` · colors · type · space · shape |
| 2 원자 | 더 못 쪼개는 부품 | `MiconButton` · `MiconChip` · `CompactDisc` · `MiconIcon` |
| 3 분자 | 원자 몇 개의 고정 조합 | `SongRow` · `RangeVerdict` · `ErrorCard` · `StepHeader` |
| 4 유기체 | 화면의 큰 덩어리 | `CdPlayer` · `FloatingNavBar` · `MiconTopBar` · `ScoreSummary` |
| 5 화면 | 유기체를 배치만 | `RecommendScreen = Header + Chips + CdPlayer + Rack` |

### 9-3. 지금 코드의 중복 → 새 컴포넌트

| 새 컴포넌트 | 지금 흩어져 있는 것 | 쓰는 화면 |
|---|---|---|
| `MiconButton(kind)` | PrimaryButton · OutlinePurpleButton · GreyButton · TextButton, 높이 44/46/56 혼용 | 21 |
| `MiconChip(kind)` | PillChip · SoftChip · ToggleChip · SectionChips · KeyBadge · 서브 배지 (6벌) | 12 |
| `CompactDisc(size)` | VinylArt(lp_ring.png) · SongDetailTurntable(tonearm.png) · BubbleCluster | 7 |
| `MiconCard` | MiconCard · SubCard · DashedBox · Detail.card, 라운드 12/14/16 혼용 | 15 |
| `MiconTopBar(kind)` | MiconHeader(64) · BackHeader(60) · GuestReportHeader(72) | 21 |
| `SongRow` | SongRow · 검색 버블 · ReportFitCard 행 · 기록 행 | 6 |
| `RangeVerdict` | SongFitCard · RangeMatchingCard · MatchBody · RangeReportCards (같은 판정 4벌) | 4 |
| `ErrorCard` · `EmptyState` | RetryBlock · EmptyRecommend · EmptyReport · Text+TextButton 9곳 | 14 |
| `StepHeader` | StepBar("n / m") · 설문 단계 · 측정 "확인된 음 3 / 8" | 4 |
| `FloatingNavBar` · `CdPlayer` · `ScoreSummary` | MiconTabBar · (신규) · ScoreBoard + FinishedPanel | 5 · 2 · 3 |

### 9-4. 팀 규칙 5개
1. 화면 파일에는 숫자와 색이 없다 — dp·sp·hex가 보이면 토큰/컴포넌트로 올린다
2. 같은 정보는 같은 컴포넌트
3. 상태(로딩·비어 있음·오류·비활성)는 컴포넌트 안에
4. Preview 두 개 필수 (360×760, 384×824)
5. 새 모양은 캔버스 규격 보드에 먼저 → 코드

### 9-5. 패키지 · 순서
```
ui/design/
  tokens/     MiconTheme.kt · Colors.kt · Type.kt · Space.kt
  atoms/      MiconButton.kt · MiconChip.kt · CompactDisc.kt · MiconCard.kt
  molecules/  SongRow.kt · RangeVerdict.kt · ErrorCard.kt · StepHeader.kt
  organisms/  CdPlayer.kt · FloatingNavBar.kt · MiconTopBar.kt · ScoreSummary.kt
  catalog/    DesignCatalogScreen.kt (debug 전용)
```
- 1주: 토큰 + 원자 4 + 카탈로그 (기존 Components.kt는 그대로, 옆에 새로)
- 2주: 추천 화면을 새 컴포넌트로 재조립
- 3~4주: 화면 하나씩 옮기며 옛 컴포넌트 삭제 + 정보 다이어트 동시 적용

참고: Android 공식 "Custom design systems in Compose", 아토믹 디자인(Brad Frost) 5단계

---

## 10. 정보 다이어트 — 화면에서 빼야 할 것

### 10-1. 원칙 4개
1. 화면 하나에 숫자 1개 · 문장 1개 · 행동 1개. 나머지는 "자세히"
2. 없는 데이터는 카드도 없다 ("미측정", "데이터 부족" 카드 금지)
3. 진단 어휘 금지: 재현 불안정 · 통과 음 · 인정 유지 · ¢ · 반음 · 등급 · 65%/35% · 프로필 · 임베딩
4. 시스템 사정은 말하지 않는다 ("서버가 분석 중", "곡 데이터가 갱신되어" → 진행률과 다음 행동만)

### 10-2. 화면별 판정

**곡 상세 (+ 추천 매치 상세)**
- 제거: SongFitCard · RangeMatchingCard · MatchBody — 같은 판정 3벌 → `RangeVerdict` 1장
- 제거: 턴테이블 히어로 + 톤암 PNG + 캐러셀, 다크 팔레트, "반주 준비 중…" 버튼 문구
- 쉬운 말: "G2–E4", "3반음 위", "원키보다 1반음 낮게" → 키 조절 한 줄 + 도레미
- 쉬운 말: "찰떡궁합! / 잘 맞아요 / 도전해 보세요" → 편해요 · 살짝 도전 · 고음 도전
- 접기: 음색 적합도 78점, 조성/BPM/박자, 음정 그래프 244dp, 주의 구간 4종 → "자세히"

**분석 탭**
- 제거: 통과 음 / 미통과·불안정 / 재현 불안정 / 반복 확인 / 측정 불가 / 재측정 필요 / 안정성 87점 / 인정 유지
- 제거: 미측정 카드 6장 (템포·가사 밀도 / 프레이즈 길이 / 추천 장르 / 보컬 특성 / 호흡 특성 / 추천 key), ECAPA 임베딩 카드
- 접기: 음역대 지도 3층 막대, 음역별 안정성 차트 → "자세히"
- 첫 화면: 편한 음역 카드 + 닮은 가수 3명 + 추천곡 보기 버튼

**연습 결과 · 기록**
- 제거: "100점 만점. 라이브 채점(음정 65% + 박자 35%)…", "음 유지와 호흡은 … 미측정", "곡 데이터가 갱신되어…"
- 쉬운 말: 등급 삭제, "곡 #1234" → 제목·가수·CD, "구간 #3" → 1절/후렴, "+12¢" 삭제
- 접기: 구간 평가 / 가사별 판정 / 음정 곡선 / 연습 정보 카드 4장 → 잘한 구간 1 + 아쉬운 구간 1 만

**연습 실행**
- 제거: ScoreBoard 등급·음정·박자·정확·놓침·보정 ms, 판정 칩 "+12¢ 빠름" → 점수 1개 + 가사 색
- 접기: "반주 렌더 중… 10초", 이어폰 경고(첫 1회), 설정 칩 4개 → ⚙ 시트

**목소리 측정**
- 쉬운 말: "주변 소음: 보통", "입력 상태 불안정", "C3 대신 D3", "한 옥타브 정도 낮아요" → 지시 1개 + 도레미 + 문제일 때만 아이콘
- 접기: 행동 4개(기준음 들어보기 / 건너뛰기 / 그만하기 / 나중에) → 주 1 + 보조 1, 큰 원 버튼 2개 → 버튼 1개

**취향 설문**
- 접기: 사진 출처 / 라이선스 / 이용 조건 → ⓘ 뒤, "임시 저장됨 / 동기화 재시도" → 실패 시 스낵바

**설정**
- 제거: 백엔드 주소(debug 빌드만), "원본 녹음 저장 — 비활성화시 분석 후 삭제" → "내 녹음 보관하기 — 끄면 분석이 끝난 뒤 지워요", 탈퇴 핑크 → 회색 텍스트

### 10-3. 어휘 치환표 (앱 전체)

| 지금 | 바꿀 말 |
|---|---|
| 안정 음역 / 불안정 | 편한 음역 / 힘든 음역 |
| 프로필 · 측정 음역 | 내 목소리 · 내 음역 |
| N반음 위/아래 | N음 높아요/낮아요, 키는 "한 키 내려/올려" |
| ¢ · 등급 · % 가중치 | 삭제, 점수 하나(0~100) |
| 미측정 · 데이터 부족 | 항목 자체 숨김 |
| 서버가 … 하고 있어요 | "분석 중 · 약 1분" + 진행 표시 |

### 10-4. 예시 — 곡 상세 전/후
- 전: 앱 유일 다크 화면, LP·톤암 히어로, 카드 5장, 문장 9개, 숫자 11개, 스크롤 3화면
- 후: 추천 CD가 커져 들어옴(공유 요소) + 미리듣기 진행 링 → 제목·가수 → 판정 카드 1장("내 편한 음역보다 다섯 음 높아요 — 한 키 내리면 편하게 불러요" + 미니 키보드 + 하이라이트 칩 1) → 키 조절 한 줄 → "한 키 내려서 연습 시작" 버튼. 스크롤 없음, 숫자 0개

---

## 11. 전 화면 구조 시안 (21화면)

> 완성도: 구조·문구 확정용. 배치·문구·버튼 개수는 실제 제안대로, 질감은 공통 컴포넌트 수준.
> hi-fi 완료: 추천 홈 · 곡 상세 (2)

### 11-1. 온보딩 (7)

| 화면 | 핵심 결정 |
|---|---|
| 스플래시 | 로고 + 한 줄 태그라인, 세션 확인 1.5초 넘으면 로그인으로 |
| 로그인 | 주 행동 1(로그인), 링크는 48dp TextButton, 구글은 보조 버튼, "먼저 둘러보기" 유지 |
| 회원가입 · 비밀번호 찾기 | 같은 틀(필드 + 버튼 1), 오류는 필드 아래 한 줄 |
| 취향 설문 1/2 장르 | StepHeader, 칩 FlowRow 다중 선택, "n개 선택", 건너뛰기 |
| 취향 설문 2/2 가수 | 같은 틀 + 검색 필드, 사진 출처는 ⓘ 뒤 |
| 목소리 분석 · 시작 | 버튼 1개(측정 시작), 준비 체크 3줄, "노래를 불러서 분석" 은 텍스트 버튼 |
| 목소리 분석 · 측정 중 | 지시 1개("이 소리를 듣고 따라 '아—'"), 음 이름 도레미 크게, 점 8개 진행, 다시 듣기 / 이 음은 어려워요 |
| 목소리 분석 · 결과 | "측정 끝!" + 편한 음역 카드(키보드·비교 문장) + 닮은 가수(준비되면 채움) + "내 목소리로 추천곡 보기" |

### 11-2. 분석 탭 (3)

| 화면 | 핵심 결정 |
|---|---|
| 분석 · 결과 있음 | 편한 음역 카드 + 닮은 가수 3명 + "이 가수들 노래 추천받기". 숫자 0개, 미측정 카드 없음 |
| 분석 · 측정 전 | 빈 상태 weight 가운데, CD 아웃라인, "목소리 분석하기" 버튼 1개 |
| 노래로 분석 | 선택 곡 SongRow + 부를 부분 칩 + 큰 녹음 버튼 1개 |
| (자세히 시트) | 음역 지도 · 안정성 차트 — 기존 차트 재사용, 라벨 도레미 |

### 11-3. 추천 탭 (2 · hi-fi 완료)

| 화면 | 핵심 결정 |
|---|---|
| 추천 홈 | 추천 종류 칩 4 + 포터블 CD 플레이어 + 곡 정보(제목·가수·키 칩·적합 칩) + 랙 7곡 + 플로팅 내비 |
| 곡 상세 (매치 상세 합침) | 10-4 참고 |

### 11-4. 연습 탭 (4)

| 화면 | 핵심 결정 |
|---|---|
| 곡 선택 | 검색 필드 52 + 칩 3(추천곡·찜한 곡·최근 연습) + SongRow 목록(CD 56 · 키 칩) |
| 연습 · 시작 전 | CD 88 헤더 + 키 칩·적합 칩, 구간 칩 행, 가사 목록, 하단 시트(처음부터 · 큰 마이크 · 키). 설정은 ⚙ 시트 |
| 연습 · 부르는 중 | 상단 제목+진행바, 점수 1개 크게, 현재 가사 색 판정 + 다음 줄, 시트(처음부터 · 일시정지 · 마치기) |
| 연습 결과 | "연습 끝!" + 점수 + 한 줄 평 + 잘한 구간/아쉬운 구간 카드 2 + "다시 연습하기". 등급·채점 설명 없음 |

### 11-5. 기록 · 찜 · 설정 (5)

| 화면 | 핵심 결정 |
|---|---|
| 기록 | 날짜 그룹, 행(CD 48 + 곡·가수·시각 + 점수), 행 탭 → 연습 결과 |
| 찜 | 칩(곡 n · 세트리스트 n) + SongRow(북마크) |
| 세트리스트 | 커버(CD 겹침) + 번호 행 + "이 순서로 연습", heightIn(380) 제거 |
| 설정 | 카드 4묶음(내 취향 / 목소리 데이터 / 알림·권한 / 계정), 탈퇴는 회색 텍스트 |
| 취향 수정 | 설문 화면 재사용, 헤더만 "저장" |

---

## 12. 작업 분배 — 줄기 A · B · C · D

> 규칙: 화면 줄기(B·C·D)는 A의 원자 4개가 머지된 뒤 시작.
> 새 모양이 필요하면 캔버스 규격 보드에 먼저 추가하고 A 담당과 합의.
> 각 화면 PR에 S10 · S23U Preview 스크린샷 2장.

### A · 공통 컴포넌트 (1주 안에)

| 작업 | 참고 보드 · 파일 | 내용 | 난이도 |
|---|---|---|---|
| 토큰 · 테마 | Tokens · `Theme.kt`, `Type.kt` | 색 역할 12, 타입 8단계, 간격·라운드, 다크 상태바 한 줄 | 중 |
| 원자 4 | CD-Spec · `ui/design/atoms/` | MiconButton · MiconChip · CompactDisc(Canvas) · MiconCard | 중 |
| 분자 4 | Note-Names · `ui/design/molecules/` | SongRow · RangeVerdict(미니 키보드) · ErrorCard · StepHeader + `NoteNames.friendly()` | 상 |
| 유기체 4 · 카탈로그 | Nav-Spec · `ui/design/organisms/` | FloatingNavBar · MiconTopBar · CdPlayer · ScoreSummary · DesignCatalogScreen | 상 |

### B · 온보딩 + 분석 (8화면)

| 화면 | 보드 · 파일 | 핵심 변경 | 난이도 |
|---|---|---|---|
| 스플래시 · 로그인 · 회원가입 | Splash · Login · Signup · `OnboardingScreens.kt` | TextButton 48, 둘러보기 유지 | 하 |
| 취향 설문 | Survey · `PreferenceSurveyScreen.kt` | StepHeader, 칩 FlowRow, 출처 ⓘ, 동기화 스낵바 | 중 |
| 측정 시작 | Measure-Intro · `VoiceOnboardingEntry.kt` | 버튼 1개 | 하 |
| 측정 진행 | Measure-Run · `QuickAnalysisScreen.kt`, `GuidedRange*` | 점 8개, 도레미, 지시 1개 (채점 로직 그대로) | 상 |
| 측정 결과 | Measure-Done · `VoiceOnboardingFlow.kt` | RangeVerdict, 닮은 가수 카드, 저장 자동 | 중 |
| 분석 홈 (결과/빈) | Analysis-Home · Analysis-Empty · `ReportScreens.kt` | 미측정 카드 6 + 임베딩 삭제, "자세히" | 중 |
| 노래로 분석 | SingAnalysis · `SingAnalysisScreen.kt` | 녹음 버튼 1개, Spacer 232 제거 | 중 |
| 분석 자세히 | (시트) `RangeReportCards.kt` | 라벨 도레미, 막대색 3:1 | 하 |

### C · 추천 + 연습 (6화면, 가장 무거움)

| 화면 | 보드 · 파일 | 핵심 변경 | 난이도 |
|---|---|---|---|
| 추천 홈 | Recommend-S10 · `RecommendScreens.kt` | CdPlayer, 랙 7곡, 필터 → 검색 뒤, 그라데이션 삭제 | 상 |
| 곡 상세 | SongDetail-Guide · `SongDetailScreen.kt`, `SongMatchDetail` 삭제 | CD 공유 요소, RangeVerdict, 키 스테퍼, "자세히" 시트, 다크 삭제 | 상 |
| 곡 선택 | Practice-Pick · `SongListScreen.kt` | 검색 52, 칩 3, SongRow | 하 |
| 연습 시작 전 | Practice-Ready · `PracticeScreen.kt` | CD 헤더, 구간 칩, 시트, 190dp 제거 | 중 |
| 연습 중 | Practice-Running · `PracticeScreen.kt`, `Runway.kt` | 점수 1 + 가사 색, ScoreBoard 통계 삭제 | 중 |
| 연습 결과 | Practice-Result · `ResultScreen.kt`, `ReportDetail.kt` | ScoreSummary, 구간 카드 2, "자세히" | 중 |

### D · 기록 + 찜 + 설정 (5화면, 가장 가벼움)

| 화면 | 보드 · 파일 | 핵심 변경 | 난이도 |
|---|---|---|---|
| 기록 | History · `PracticeHistoryScreen.kt` | 날짜 그룹, 행(CD 48 + 곡 + 점수) | 하 |
| 찜 · 세트리스트 | Favorites · Setlist · `FavoritesScreens.kt` | SongRow, 번호 행, heightIn(380) 제거 | 하 |
| 설정 | Settings · `SettingsScreen.kt` | 카드 4묶음, 백엔드 주소 debug만 | 하 |
| 취향 수정 | Survey 재사용 | 헤더만 "저장" | 하 |

---

## 13. 다음 단계 · 미결 사항

**다음 단계**
1. `ui/design/` 공통 컴포넌트가 실제 화면에서 사용되는 범위를 점검한다.
2. 미전환 화면을 정하고 360×760dp·384×824dp Preview를 함께 검증한다.
3. 아래 미결 사항을 팀에서 결정한 뒤 문서와 코드를 함께 갱신한다.

**팀 안에서 확인이 필요한 것**
- 연습 중 점수판을 점수 하나로 줄이는 것 (음정·박자·정확·놓침 삭제)
- 분석 탭의 측정 품질 어휘(재현 불안정 · 통과 음 등) 전부 숨기는 것
- Spotify 이미지 요청 크기 (300px → 640px)
- 맨 위 두 줄의 초기 점검 보드 6장(옛 팔레트) 삭제 여부

**확정된 것**
- 소프트 & 라운드, 라이트 고정
- 5탭 유지: 분석 · 추천 · 연습 · 기록 · 찜
- 앨범아트 없을 때 D안(인쇄 라벨)
- 반음은 "도♯" 표기 허용
- 시안 먼저 → 확정 후 코드
