package com.micon.app.scoring

/**
 * 채점 상수. 엔진(compare_engine.py L58–73)·웹(live-scoring.tsx L14–33)과 동일한 값이어야 한다.
 * 값을 바꾸면 세 곳이 함께 바뀌어야 하므로 여기 외에는 숫자를 직접 쓰지 않는다.
 */
object ScoringConstants {
    // --- 음정 판정 (¢) ---
    const val PITCH_GOOD_CENTS = 45.0
    const val PITCH_OFF_CENTS = 75.0        // 엔진 전용 sub-tier(sharp/flat 경계). 라이브는 사용 안 함
    const val PITCH_WRONG_CENTS = 150.0
    const val MISSED_VOICED_RATIO = 0.35    // 유성 프레임 비율 < 35 % → missed

    // --- 박자 판정 (초) ---
    const val TIMING_GOOD_SECONDS = 0.12
    const val TIMING_OFF_SECONDS = 0.45     // 엔진 전용

    // --- 점수 곡선 ---
    const val PITCH_FULL_CENTS = 30.0       // 30¢ → 100 %
    const val PITCH_ZERO_CENTS = 150.0      // 150¢ → 0 %
    const val TIMING_FULL_SECONDS = 0.06    // 60 ms → 100 %
    const val TIMING_ZERO_SECONDS = 0.40    // 400 ms → 0 %
    const val WEIGHT_PITCH = 0.65
    const val WEIGHT_TIMING = 0.35

    /** 등급 경계 (내림차순). 미달은 F. */
    val GRADES: List<Pair<Double, String>> = listOf(95.0 to "S", 88.0 to "A", 78.0 to "B", 65.0 to "C", 50.0 to "D")

    // --- 라이브 판정 완화 (2026-09-09, 퍼펙트스코어식) ---
    // 위 값은 엔진 정밀 리포트(/compare)와 같은 "정밀" 기준이다. 연습 중 실시간 판정은 원곡 가수도 맞추기 어려울 만큼
    // 촘촘하다는 피드백으로 아래 LIVE_* 값을 따로 둔다. LiveScorer 만 이 값을 쓴다.
    const val LIVE_PITCH_GOOD_CENTS = 70.0      // ±70¢ 까지 "정확"
    const val LIVE_PITCH_WRONG_CENTS = 200.0    // 2반음 넘게 벗어나면 "다른 음"
    const val LIVE_PITCH_FULL_CENTS = 50.0      // 50¢ 까지 만점
    const val LIVE_PITCH_ZERO_CENTS = 250.0     // 250¢ 에서 0점
    const val LIVE_MISSED_VOICED_RATIO = 0.25   // 유성 프레임 비율 < 25 % → 놓침
    const val LIVE_TIMING_GOOD_SECONDS = 0.20
    const val LIVE_TIMING_FULL_SECONDS = 0.12
    const val LIVE_TIMING_ZERO_SECONDS = 0.60
    const val LIVE_ONSET_NEAR_SEMITONES = 1.0
    const val LIVE_ONSET_SEARCH_SECONDS = 0.30   // 더 넓히면 같은 음의 앞 음표 꼬리를 온셋으로 잡아 보정이 틀어진다
    /** 이보다 짧은 음표(장식음·경과음)는 판정하지 않고 총점에서 뺀다. 원곡 음표의 40 % 가 0.18 s 미만이다. */
    const val LIVE_MIN_JUDGE_SECONDS = 0.18
    /** 음정 오차 계산에 쓰는 프레임 비율: 목표에 가까운 순으로 이 비율만 남긴다(비브라토·스쿱·꺾기 관용) */
    const val LIVE_CORE_FRAME_RATIO = 0.6
    /** 판정 창을 음표 앞뒤로 이만큼 넓혀 살짝 이르거나 늦은 발성도 잡는다 */
    const val LIVE_WINDOW_PAD_SECONDS = 0.08

    // --- 라이브 채점 전용 (live-scoring.tsx L26–33) ---
    const val CLARITY_MIN = 0.6             // YIN 명료도 게이트
    const val RMS_MIN = 0.004               // ≈ −48 dBFS
    const val ONSET_NEAR_SEMITONES = 0.6
    const val ONSET_SEARCH_SECONDS = 0.3    // 음표 시작 ± 탐색
    const val CALIBRATION_LIMIT_SECONDS = 0.4  // 자동 지연 보정 한계 (TIMING_ZERO 와 우연히 같은 값, 별개 상수)
    const val RUNWAY_SECONDS_AHEAD = 5.0
    const val RUNWAY_SECONDS_BEHIND = 3.0
    const val FRAME_HOP_SECONDS = 0.02      // 프레임 간격(20 ms) — expected 프레임 수 계산에 사용

    // --- 반주 키 변경 범위 (연습 화면 전용) ---
    // 반음마다 반주 파일을 만들면 S3·DB 부담이 커서, 원키 반주 하나만 받아 재생기가 실시간으로
    // 음높이를 옮긴다(InstrumentalPlayer.keyShift). 폭이 커질수록 시프트 알고리즘이 배경을
    // 뭉개므로 ±3 으로 제한한다.
    const val KEY_SHIFT_MIN = -5
    const val KEY_SHIFT_MAX = 5
}
