# MICON 프론트 리디자인 — 작업 인수인계 (핸드오프)

> 다른 채팅/작업자에게 이 작업을 넘길 때 읽는 문서입니다.
> **무엇을 어떤 기준으로 결정했고, 어디에 무엇이 있고, 무엇이 남았는지**를 한 곳에 모았습니다.
> 디자인 규격의 상세 본문은 같은 폴더의 `FRONTEND_REDESIGN_GUIDE.md`(13개 장)에 있고, 이 문서는 그 위에 얹는 "맥락 + 기준 + 결정 로그"입니다.

작성일: 2026-09-19 · 작성 주체: 다운(dawon1711) + Claude · 프로젝트: SSAFY 특화 S15P21B110 "MICON" (Android 노래 앱)

---

## 0. 새 채팅에서 바로 이어가기

새 채팅을 열면 아래 두 파일을 첨부하고, 이렇게 시작하면 됩니다.

```
첨부: docs/FRONTEND_REDESIGN_HANDOFF.md, docs/FRONTEND_REDESIGN_GUIDE.md
(필요 시) 바탕화면/MICON_리디자인_캔버스.html, docs/redesign-canvas-src.zip

"MICON Android 앱 리디자인을 이어서 진행합니다. 첨부한 인수인계/가이드 문서의
토큰·규칙·결정 로그를 그대로 따르세요. 현재 단계는 [시안 확정 / 코드 구현 줄기 A]입니다."
```

작업 순서 원칙(사용자 확정): **시안 먼저 → 팀 확정 → 코드**. 이 세션에서 **레포 코드는 한 줄도 수정하지 않았습니다.**
(`docs/` 아래 문서 2개만 추가됨)

---

## 1. 산출물 위치

| 산출물 | 위치 | 비고 |
|---|---|---|
| 디자인 캔버스 (Claude Design, 40 보드) | https://claude.ai/artifact/KAZeY82fNHW42SA1difvYZ | 사용자는 "피그마"라고 부름. 팀 공유용 원본 |
| 리디자인 가이드 (.md, 13장) | `S15P21B110/docs/FRONTEND_REDESIGN_GUIDE.md` | 규격·치수·토큰·분배표 상세 |
| 이 인수인계 문서 | `S15P21B110/docs/FRONTEND_REDESIGN_HANDOFF.md` | |
| 캔버스 전체 HTML 내보내기 | `C:\Users\SSAFY\Desktop\MICON_리디자인_캔버스.html` | 단일 파일 403KB, 줌바 포함, UTF-8 BOM |
| 캔버스 소스 (보드 HTML 40개 + 생성/내보내기 스크립트) | `S15P21B110/docs/redesign-canvas-src.zip` | 새 채팅에서 재생성·수정할 때 사용 |

레포 경로: `C:\Users\SSAFY\Desktop\특화PJT\S15P21B110` · Android 모듈: `frontend/android/app/src/main/java/com/micon/app/`

---

## 2. 작업의 출발점 (사용자 요구 요약)

1. 대상 기기 **Galaxy S10 (360×760dp)** 기준, **S23 Ultra (384×824dp)** 도 자연스럽게 대응.
2. 현재 앱이 **Android/Material 3 기본 가이드에 안 맞는 부분**이 많다 → 전체 점검.
3. 느낌: **밝고(라이트) 부드러운(딱딱하지 않은)** 톤으로 **전 화면 통일**. 색이 화면마다 제각각인 것 해결.
4. **"너무 개발자적인 UI/UX"** 탈피 → 유저 친화. 영어/전문용어/숫자 노출 줄이기.
5. LP 레코드(픽셀 깨지는 `lp_ring.png`) → **CD**. 추천 페이지는 **포터블 CD 플레이어** 콘셉트(핀터레스트 레퍼런스).
6. 앨범아트는 **Spotify**(백엔드 이미 존재). 아트 없는 곡의 자연스러운 대체안.
7. 첨부 이미지의 **UX 함정 4개**(초기 진입 가중치 · 과정의 분절 · 피드백 모호함 · 파일/출력 마찰) 회피.
8. **컴포넌트화** 개념 학습 + 서비스 적용 방법. **정보 다이어트**(빼야 할 것) 정리 + 예시.
9. **21개 전 화면**을 캔버스로 만들어 팀원과 작업 분배.

---

## 3. 참고한 기준 (무엇을 근거로 판정했나)

### 3-1. Material 3 / Android 공식 가이드

| 항목 | 적용 기준값 | 출처 |
|---|---|---|
| 터치 타깃 | 최소 **48×48dp**, 요소 사이 8dp | M3 Accessibility |
| 텍스트 대비 | 본문 **4.5:1**, 큰 글자(18sp Bold/24sp) 3:1 | WCAG 2.1 AA |
| 그래픽·아이콘 대비 | **3:1** | WCAG 1.4.11 |
| 타입 스케일 | Display/Headline/Title/Body/Label 역할 기반 | M3 Typography |
| NavigationBar | 높이 80dp (표준) — 본 프로젝트는 **플로팅 64dp 커스텀**으로 대체 | M3 Navigation bar |
| Chip | 높이 32dp, 라벨 14sp | M3 Chips |
| Button | 높이 40dp(표준) → 주 CTA는 **52–56dp** 채택(노래 앱, 한 손 조작) | M3 Buttons |
| Edge-to-edge | `enableEdgeToEdge()` 사용 시 **status/nav inset 직접 처리**, 다크모드 자동 전환 주의 | Android 15 edge-to-edge |
| 모션 | Emphasized easing, 200–400ms; 무한 회전은 12s/회 (느리게) | M3 Motion |

### 3-2. 기기 치수

| | S10 | S23 Ultra |
|---|---|---|
| 논리 해상도 | 360 × 760dp | 384 × 824dp |
| 밀도 | 2.625x (FHD+) / 4.0x (WQHD+) | 2.75x / 3.5x |
| 상태바 | ~30dp | ~30dp |
| 3버튼 내비 | 48dp | 48dp |
| 탭 화면 콘텐츠(내비바 제외) | **538dp** | **602dp** |
| 풀스크린 콘텐츠 | 682dp | 746dp |

→ 결론: **S10→S23U 전환은 "여백·디스크 지름이 커지는 정도"**로 흡수. 레이아웃 분기 없음. 디스크 지름 = `min(width−140, availH×0.42)` → 220 / 244dp.

### 3-3. 코드 현황 (읽기만 함)

- 단일 Activity + Jetpack Compose + Compose Navigation. `Theme.kt`에 `lightColorScheme` 정의, `Type.kt`는 `Typography()` 기본값 그대로(=**타입 스케일 미정의**, 화면마다 sp 하드코딩 464곳).
- `MainActivity.kt`의 `enableEdgeToEdge()` → 시스템 다크모드일 때 상태바 아이콘/배경이 뒤집힘 → **라이트 고정** 필요.
- `ui/common/Components.kt`에 공통 컴포넌트 일부만 존재. 나머지 화면별 중복 (버튼·칩·카드·헤더).
- 추천/곡상세: `lp_ring.png`(260×260, 저해상 → 확대 시 픽셀) + `tonearm.png`.
- 채점: `scoring/ScoringUi.kt`에 `PitchStatus` 색, `COLOR_PAPER #FBFAF5`, `COLOR_NOW #D7FF43`(형광 연두, 톤 이탈) · `ui/practice/Runway.kt`.
- 백엔드: `SpotifySongImageProvider`, `SongImageEnrichmentService` — 앨범아트 URL 이미 제공됨.

### 3-4. 비주얼 레퍼런스
- 핀터레스트 포터블 CD 플레이어(사용자 제공 링크) → "디스크맨" 디테일: 측면 힌지 너클, 상판 원형 창, LCD 카운터, ⏮⏭ 물리 버튼 느낌.
- 첨부 UX 진단 이미지 4항목(§2-7).

---

## 4. 스타일 가이드 요약 — "소프트 & 라운드"

> 정식 표와 M3 `colorScheme` 매핑은 GUIDE §4. 여기는 코드/시안 어디서든 바로 쓰는 요약.

### 4-1. 색

| 역할 | HEX | 용도 |
|---|---|---|
| background | `#F7F5FC` | 화면 바탕(연한 라벤더 회색) |
| surface | `#FFFFFF` | 카드·시트 |
| primary | `#6C5CE7` | 주 버튼, 활성 아이콘 |
| onPrimaryContainer | `#5546C9` | 라벤더 위 글자/링크 |
| primaryContainer | `#EEEBFF` | 활성 필, 연한 강조 배경 |
| secondaryContainer | `#DCD6FF` | 선택 칩 |
| onSurface | `#1E1B33` | 본문 잉크 |
| onSurfaceVariant | `#6B6B78` | 보조 글자 (4.5:1 충족) |
| outlineVariant | `#E4E2EE` | 구분선·테두리 |
| success / container | `#2E7D32` / `#E1F3E6` | 잘 맞음·완료 |
| tertiary / container | `#C92A5E` / `#FFE3EC` | 찜·강조 포인트 |
| error / container | `#D23B52` / `#FBE3E6` | 오류·삭제 |

원칙: **빨강/형광 사용 금지**(채점 포함), 순검정 금지(잉크는 `#1E1B33`), 그림자는 보라 틴트 `rgba(30,27,51,0.10~0.14)`.

### 4-2. 타이포 (Pretendard, 8단계)

| 토큰 | 크기/행간 | 굵기 | 용도 |
|---|---|---|---|
| display | 28/36 | 700 | 결과 점수, 스플래시 |
| headlineS | 22/30 | 700 | 화면 제목 |
| titleL | 18/26 | 600 | 카드 제목, 곡 제목 |
| titleM | 16/24 | 600 | 리스트 제목 |
| body | 14/20 | 400 | 본문 |
| bodyStrong | 14/20 | 600 | 강조 본문, 칩 |
| label | 12/16 | 500 | 캡션, 탭 라벨 |
| mono | 14 JetBrains Mono | 500 | 시간 표기(0:00) 정도만 |

### 4-3. 형태·간격
- 카드 radius **20–28**, 버튼 **14–16**, 칩 **full**, 디스크 원형.
- 좌우 여백 **20dp**, 카드 내부 14–16dp, 섹션 간 24dp.
- 주 CTA 높이 52–56dp, 폭 전체 − 40. 아이콘 버튼 48dp 히트영역.
- 하단 플로팅 내비: 높이 **64**, 좌우 inset **16**, 하단 inset 16 + 시스템 내비, radius **32**, 활성 필 **44×26 `#EEEBFF`**, 탭 5개: **분석 · 추천 · 연습 · 기록 · 찜**.

### 4-4. CD 컴포넌트 (LP 아님!)
- **동심원 홈(groove) 없음.** 상단 광택(sheen) + 반사 하이라이트 2개.
- 허브 링(무지개 반사) 지름 **0.28D**, 구멍 **0.12D**.
- 크기: 중앙 디스크 220(S10)/244(S23U), 랙 미니 52/56, 리스트 40.
- 아트 없을 때 **D안 "인쇄 라벨"**: 제목을 SVG `textPath`로 원호 배치 + 아티스트 + 제목 해시로 파스텔 그라데이션 7종 중 선택. (첫 글자 1자 표시안은 **기각**)
- 회전: 중앙 디스크만 **12s/회** 선형 무한. 랙·리스트는 정지.

### 4-5. 어휘(정보 다이어트) 핵심 치환
`MIDI/C4/E5` → **"중간 도", "높은 미"** (옥타브 2~6 = 아주 낮은/낮은/중간/높은/아주 높은 + 도레미, ♯ 허용) ·
`리포트`→**분석** · `평가`→**기록** · `내 목소리`→**분석** · `Cents/Hz/RMS/BPM 원값`→숨김 또는 말로 ·
`정확도 83.2%`→**"거의 맞았어요"** 등급 + 접힌 숫자 · `세트리스트`는 유지(팀 용어) ·
전체 치환표는 GUIDE §7-4, §10-3.

---

## 5. 결정 로그 (사용자 피드백 → 확정 사항)

| # | 피드백 | 결정 | 상태 |
|---|---|---|---|
| 1 | 앨범아트 없을 때 첫 글자 1자 표시가 별로 | **D안 인쇄 라벨**(원호 제목 + 파스텔) | 확정 |
| 2 | "리포트"·"평가" 이름 겹침 | **분석 · 기록**으로 개명 | 확정 |
| 3 | 메뉴바 단조롭고 두꺼움, 꽉 차지 않아도 됨 | **플로팅 필 내비 64dp, inset 16**, 5탭 유지 | 확정 |
| 4 | 인쇄 라벨이 LP 같음 | 동심원 홈 제거, 광택·허브 링만 | 확정 |
| 5 | "내 목소리"보다 "분석" | 탭명 **분석** | 확정 |
| 6 | 랙 위쪽 잘림 | 랙 상단 패딩 6dp | 확정 |
| 7 | 디스크에 "재생 중" 표시 있으면 안 됨 | 추천 페이지에서 재생 표시 전부 제거 | 확정 |
| 8 | 중앙 CD 탭 → 곡 상세로 이동하며 재생 시작 | 플로우 확정: 추천(정지/천천히 회전) → 탭 → 곡 상세(재생) | 확정 |
| 9 | 음계는 도레미로 | 높낮이 말 + 도레미, **♯는 허용** | 확정 |
| 10 | CD 플레이어가 둥근 네모일 뿐 | 디스크맨 디테일 추가(측면 너클, 원형 창, LCD, 물리 버튼) | 확정 |
| 11 | 상단 ㅡ 일자 선이 드래그 핸들로 오해됨 | 힌지선 제거 → 측면 너클로 이동 | 확정 |
| 12 | 추천 7곡인데 5곡만 보이고 더 있는 느낌 없음 | 6번째 디스크 반쯤 노출 + 우측 페이드 + LCD `01 / 07` 카운터 | 확정 |
| 13 | 모든 페이지 디자인 통일 + 컴포넌트화 공부 | 아토믹 5단계, 신규 컴포넌트 10종, 팀 규칙 5개 | 문서화 완료 |
| 14 | 코드가 아니라 시안만 한 게 맞나 | 맞음. 코드 미수정, **시안 확정 후 코드** | 원칙 |
| 15 | 전 페이지 캔버스 + 팀 분배 | 21화면 구조 시안 + 분배표(줄기 A~D) | 완료 |
| 16 | 설명은 .md로 파트별 줄바꿈 | `FRONTEND_REDESIGN_GUIDE.md` | 완료 |
| 17 | 캔버스 용량 커서 HTML 내보내기 안 됨 | 파이썬 플래트너로 단일 HTML → 바탕화면 | 완료 |
| 18 | PC에서 글씨 깨짐 | UTF-8 BOM + `http-equiv charset` + 맑은 고딕 폴백 추가 | **확인 대기** |
| 19 | 1행 제목 3개가 겹침 | 제목 폭(maxW)에 맞춰 폰트 자동 축소(서버 계산 + JS 재측정) | 완료 |
| 20 | 퍼펙트스코어(부르는 중) 너무 딱딱함 | 방향 2안 제시: **"따라 부르기 게임"** vs **"조용한 코치"** | **사용자 선택 대기** |

---

## 6. 컴포넌트 인벤토리 (신규 10종 → 중복 대체)

패키지 제안: `ui/design/{tokens, atoms, molecules, organisms, catalog}`

| 컴포넌트 | 대체하는 중복 | 비고 |
|---|---|---|
| `MiconTopBar` (logo / back 2종) | 화면별 Row 헤더 | 액션 48dp |
| `PrimaryButton` / `TextLink` | 하드코딩 Button 12곳+ | 52–56dp, 비활성은 `#DCD6FF`+잉크 60% |
| `FilterChip` | 장르/키 칩 | 32dp, 선택 `#DCD6FF` |
| `SoftCard` | Surface+padding 조합 | radius 24, 보라 그림자 |
| `TextFieldSoft` | 로그인/가입 필드 | helper·error 슬롯 |
| `CompactDisc(size, art, title, artist, spinning)` | LP 이미지 | 라벨 D안 내장 |
| `SongRow` | 리스트 아이템 5종 | 40dp CD + 제목/아티스트/우측 슬롯 |
| `MiniKeyboard(range, mine)` | 음역 텍스트 | 도레미 라벨 |
| `StepHeader(step, total, label)` | 온보딩 진행 표시 | "과정의 분절" 완화 |
| `FloatingNavBar` | `NavigationBar` | 5탭, 활성 필 |
| `NoteNames.friendly(midi)` | 노트명 문자열 | 유틸, GUIDE §7-5 |

팀 규칙 5개: ① 색·크기 리터럴 금지(토큰만) ② 화면 파일에 `Modifier.padding(숫자)` 반복 3회↑면 컴포넌트화 ③ 새 컴포넌트는 `catalog` 화면에 등록 ④ 상태(enabled/selected/error) 슬롯 필수 ⑤ 컴포넌트는 데이터를 모름(문자열/콜백만 받음).

---

## 7. 캔버스 구조 (40 보드, 행 단위)

| y | 행 제목 | 보드 |
|---|---|---|
| 0 | M3 점검 (현재 vs 가이드) | Main, Report-Guide, Practice-Current/Guide, Login-Current/Guide *(구 팔레트, 삭제 후보)* |
| 1200 | 시스템 레벨 점검 | S10-Spec, Checklist, Contrast |
| 2900 | 리디자인 1차 · CD 플레이어 | **Recommend-S10(인터랙티브)**, Recommend-S23U, CD-Spec, Tokens, UX-Principles |
| 4200 | 공통 | Nav-Spec, Screen-Inventory, Note-Names |
| 5760 | 통일의 방법 | Components, Info-Diet, SongDetail-Current/Guide |
| 7540 | 구조 시안 ① 온보딩 7 | Splash, Login, Signup, Survey, Measure-Intro/Run/Done |
| 8600 | 구조 시안 ② 분석 3 · 연습 4 | Analysis-Home/Empty, SingAnalysis, Practice-Pick/Ready/Running/Result |
| 9660 | 구조 시안 ③ 기록·찜·설정 + 분배 | History, Favorites, Setlist, Settings, **Assignment** |

fidelity: 추천 2장·곡 상세 1장은 hi-fi, 나머지 21장은 **구조·문구 확정용**(회색 박스 없이 실제 토큰 적용, 세부 폴리시 전).

---

## 8. 작업 분배 (줄기 A~D) — 요약

- **A 공통 컴포넌트 (1주 내, 선행)**: 토큰 → `Type.kt` 스케일 → 컴포넌트 10종 → catalog → 라이트 고정/inset.
- **B 온보딩 + 분석 (8화면)**: 스플래시·로그인·가입·설문·측정 3단계·분석 홈/빈 상태.
- **C 추천 + 연습 (6화면, 가장 무거움)**: CD 플레이어·곡 상세·곡 선택·시작 전·부르는 중·결과.
- **D 기록 + 찜 + 설정 (5화면, 가장 가벼움)**.
상세 체크리스트는 GUIDE §12, 캔버스 Assignment 보드.

---

## 9. 도구·기술 메모 (재생성·수정 시)

### 9-1. 캔버스(Claude Design 아티팩트) 편집 규칙
- 인덱스 `project/canvas.json`: `boards{x,y,w,h,title,is_interactive}`, `order[]`, `notes{}`(kind `title1`, `maxW`).
- 보드 파일 `*.dc.html`: `<x-dc>` 루트, `<helmet>` 메타, 인라인 스타일. 인터랙티브 보드만 `{{holes}}`, `<sc-for>`, `<sc-if>`, `data-props`, `class Component extends DCLogic`.
- 게시(publish)할 때: **직전에 반드시 `read`(path 없이 전체)** → 그 다음 `publish(url=…KAZeY82fNHW42SA1difvYZ, root, file_path, files)`. `/code/artifact/…` URL 아님.

### 9-2. 스크립트 (zip 안)
- `gen_screens.py`: 헬퍼(`shell, topbar_*, nav, primary, chip, tag, card, field, cd, song_row, keyboard, step_header, note`)로 21개 구조 보드 생성. 토큰 상수 상단에 정의.
- `export_html.py`: 40보드를 단일 HTML로 플래트닝. 인터랙티브 보드는 `bake_recommend_s10`으로 첫 곡 정적 상태로 굽기. 제목은 `maxW`에 맞춰 폰트 축소. `utf-8-sig`로 저장.
- 재생성: `python3 gen_screens.py && python3 export_html.py`.

### 9-3. 로컬 기기 경로 (Claude 세션에서 접근할 때)
- 프로젝트 `$HOME/mnt/S15P21B110`, 바탕화면 `$HOME/mnt/Desktop`.
- 한글 정규식 grep이 실패함 → 파이썬으로 처리. 넓은 grep은 120s 타임아웃 → 경로 좁히기.
- 파일 커밋은 `SendUserFile` → `fileUuid` → `device_commit_files`(force). `stagedPath` 방식은 404.

---

## 10. 미결 사항 · 다음 단계

1. **퍼펙트스코어(부르는 중) 톤 결정** — "따라 부르기 게임"(목표 음 띠 + 내 점 하나, 가사 색 판정, 구간 완료 카드) vs "조용한 코치"(채점 요소 숨김, 끝나고 한 줄 피드백). 결정되면 부르는 중 · 구간 완료 · 결과 3장 hi-fi.
2. HTML 내보내기 **글씨 깨짐 최종 확인**(브라우저·깨짐 형태 필요).
3. 구조 시안 21장 → 담당자별 **hi-fi 폴리시**.
4. 캔버스 0행 구 팔레트 점검 보드 6개 삭제 여부.
5. 코드 구현은 **줄기 A(토큰·타입·컴포넌트)부터**, 확정 후 시작. 첫 파일: `ui/theme/Theme.kt`, `Type.kt`, `ui/design/tokens/*`.
6. 앨범아트 로딩(Coil) + 라벨 D안 폴백 컴포넌트, `lp_ring.png`/`tonearm.png` 제거.

---

## 11. 코드 진입점 (수정 대상 후보)

```
frontend/android/app/src/main/java/com/micon/app/
├─ MainActivity.kt                 enableEdgeToEdge → 라이트 고정 + inset
├─ ui/theme/Theme.kt, Type.kt      토큰·타입 스케일
├─ ui/common/Components.kt         → ui/design/* 로 이관
├─ ui/navigation/MiconNavHost.kt, Routes.kt   탭명·플로팅 내비
├─ ui/recommend/RecommendScreens.kt           CD 플레이어
├─ ui/songdetail/*                             곡 상세(재생 시작)
├─ ui/practice/PracticeScreen.kt, Runway.kt    연습·부르는 중
├─ scoring/ScoringUi.kt                        PitchStatus 색 → 토큰
├─ ui/report/*  → "분석"    ui/result/* → "기록"
└─ res/drawable-nodpi/lp_ring.png, tonearm.png  삭제 예정
```
