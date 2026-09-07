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

    // --- 라이브 채점 전용 (live-scoring.tsx L26–33) ---
    const val CLARITY_MIN = 0.6             // YIN 명료도 게이트
    const val RMS_MIN = 0.004               // ≈ −48 dBFS
    const val ONSET_NEAR_SEMITONES = 0.6
    const val ONSET_SEARCH_SECONDS = 0.3    // 음표 시작 ± 탐색
    const val CALIBRATION_LIMIT_SECONDS = 0.4  // 자동 지연 보정 한계 (TIMING_ZERO 와 우연히 같은 값, 별개 상수)
    const val RUNWAY_SECONDS_AHEAD = 5.0
    const val RUNWAY_SECONDS_BEHIND = 3.0
    const val FRAME_HOP_SECONDS = 0.02      // 프레임 간격(20 ms) — expected 프레임 수 계산에 사용

    // --- 반주 키 변경 범위 (엔진 /instrumental-key: −6…+5) ---
    const val KEY_SHIFT_MIN = -6
    const val KEY_SHIFT_MAX = 5
}
