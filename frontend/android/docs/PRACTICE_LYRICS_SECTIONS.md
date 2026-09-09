# 연습 화면 — 가사 시트·구간(절/간주/하이라이트)·점프 설계 노트 (2026-09-09)

`ui/practice/`, `scoring/LyricLines.kt`, `scoring/SongSections.kt` 가 무엇을 어떤 데이터로 그리는지 적는다.
데이터 계약은 `vocal-scope/docs/STEM_PITCH_IMPORT.md`(karaokeNotes·LRC 생성) 와 함께 읽는다.

## 1. 왜 바꿨나

- 예전 `LyricLines.build(notes, lyricsPlain)` 은 **음표에 붙은 음절만 이어 붙여** 줄을 만들었다. 파이프라인이 음표에 배정하지 못한 음절
  (한 음표에 음절 2개 이상 → 첫 것만 남김, 겹치는 음표 없음 → 버림)은 화면에서 사라져 줄마다 1–2글자가 잘렸다(123곡 중앙값 91 % 만 살아남음).
- LRC 가 없는 88곡(한 56·일 28·기타 4)은 가사 텍스트가 있어도 "가사 타이밍 정보가 없는 곡" 만 보였다.
- 간주 점프·절 점프는 없었다(`song_sections` 테이블 0행). 있는 건 전주 건너뛰기와 가사 줄 구간 반복만.

## 2. 가사 시트 (`LyricLines.build(notes, lyricsSynced, lyricsPlain): LyricSheet`)

| `LyricSheet.timing` | 조건 | 줄 텍스트 | 줄 시각 | 글자 채움 |
|---|---|---|---|---|
| `SYNCED` | `songs.lyrics_synced`(LRC) 줄 ≥ 2 | LRC 줄 그대로 | `start` = LRC 시각(음절 음표가 0.3 s 앞서면 그 시각), `until` = 다음 줄 시각, `end` = 마지막 음절 음표 끝 | 음절 음표 가사를 텍스트에서 순서대로 찾아(8글자 이내) 그 `lyricAt` 을 주고, 못 찾은 글자는 앞뒤 시각을 선형 보간. 공백은 다음 글자 시각 |
| `NOTES` | LRC 없음, 음절 음표 ≥ 4 (엔진 응답) | 음절 이어 붙임(예전 방식) | 음표 시각 | 음표 시각 |
| `APPROX` | 가사 텍스트만 | `lyrics_plain` 줄 | 음표를 4 s 이상 빈 곳에서 끊은 **노래 구간**에 글자 수 비례로 배치. 문단 수 = 구간 수면 문단별 배치 | 줄 안에서 선형 |
| `NONE` | 가사 없음 | — | — | — |

- `LyricChar(text, at, note)`: `note` 가 있으면 판정 색(정확/높게/낮게/다른 음/안 부름), 없으면 `now ≥ at` 로 보라(부른 부분)/검정.
- 빈 텍스트 LRC 줄(`[mm:ss.xx]` 만)은 `breaks` 로 따로 둔다(간주 표시). 현재 줄은 `sheet.currentIndex(now)` = `start ≤ now` 인 마지막 줄.
- 러너웨이 가사 스트립도 같은 `chars` 를 그린다(배경 → 글자 2패스).

## 3. 구간 (`SongSections.build(notes, lines): SectionMap`)

- **간주** `Interlude`: 음표 사이 공백 ≥ 5 s, 전주(첫 음표 앞 ≥ 5 s). 아웃트로는 만들지 않는다(마지막 음표 +2 s 에 자동 종료).
- **절** `Section`: 간주로 나뉜 보컬 묶음. 12 s 미만 묶음은 앞 묶음에 병합(간주 중 애드리브). 라벨은 순서대로 `1절, 2절, …`
  (6개 초과면 `구간 n`, 1개면 `전체`). 후렴 라벨은 데이터가 없어 만들지 않는다 — `song_sections` 가 채워지면 그 라벨로 바꾼다.
- **하이라이트** `Highlight`: 최고음 −5 반음 이상 음표의 (길이 × 높이) 합이 가장 큰 10 s 창. 시작은 그 창이 속한 가사 줄 시작으로 맞춘다.
- 211곡 기준 118곡에 간주 ≥ 6 s 가 있다. Piano Man 은 5절, Creep 은 1절(전체)+하이라이트만.

## 4. 점프·건너뛰기 (`PracticeViewModel`)

- `seekTo(target)` 하나로 통일: READY/FINISHED 는 시작 위치 지정, PAUSED/RUNNING 은 `LiveScorer.jumpTo(target, from)` —
  뒤로 가면 `rewindTo`(그 뒤 음표 미판정으로), 앞으로 가면 `skipTo`(건너뛴 음표는 UPCOMING 으로 남아 **총점에서 제외**, 놓침으로 세지 않음).
  재생 중이면 반주만 옮기고 마이크·판정은 이어 간다. 구간 반복 범위를 벗어나면 반복 해제.
- 구간 칩(헤더): 절 시작 1.5 s 앞으로. 하이라이트 칩도 같다. 카운트다운 중만 비활성.
- 간주 건너뛰기: `state.interlude`(tick 마다 `sections.interludeAt(now)`) 가 있고 남은 시간 > 2.5 s 면 하단 시트 위에 버튼. 간주 끝 1.5 s 앞으로.
- 캡처 시계 ↔ MR 시계: tick 이 "가장 최근 도착한 프레임의 캡처 시각 ↔ 지금 재생 위치" 를 2 s 마다(그리고 점프 직후 `sync = null`) 다시 맞춘다.
  동기 전 프레임은 채점에 넣지 않는다(예전엔 mr=0 인 임시 동기로 잘못된 시각의 프레임이 몇 개 섞였다).
- 헤드셋(유선·BT·USB) 출력이 잡히면 "이어폰" 을 자동으로 켠다. 스피커로 들으면 마이크가 반주를 함께 잡아 판정이 섞인다
  (S10 실측: Piano Man 스피커 재생만으로 정확 5·보정 −176 ms).

## 5. 곡 목록

- 헤더에 `연습 가능 N곡 · 가사 싱크 N곡 · 가사만 N곡` (`SongRepository.counts()`).
- 행마다 배지 `가사 싱크`(보라) / `가사만`(회색) / `반주만`(빨강) — `SongSummary.lyricsSynced / hasLyrics`.

## 6. 남은 일

- 일본 곡의 한국어 독음 줄을 보조 줄로 같이 보여 주기(`LyricLine.secondary`). 지금은 정렬·LRC 에서 제외만 한다.
- `song_sections` 가 채워지면 절 라벨(후렴 등)을 데이터에서 읽기.
- 채점 상수(`ScoringConstants`) 는 이어폰 실측 후 조정. 현재 값은 웹 라이브 채점과 동일.
