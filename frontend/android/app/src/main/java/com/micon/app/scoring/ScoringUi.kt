package com.micon.app.scoring

import kotlin.math.roundToInt

/**
 * 판정 상태의 색·라벨 (scoring-ui.ts 포팅). 엔진 `comparison.notes[].pitchStatus` 문자열과 1:1.
 * 색은 ARGB Long — Compose `Color(value)` 에 바로 넣는다.
 */
enum class PitchStatus(val key: String, val label: String, val argb: Long) {
    GOOD("good", "정확", 0xFF3F9D5A),
    SHARP("sharp", "높게", 0xFFD98B2B),
    FLAT("flat", "낮게", 0xFF3F7FD9),
    WRONG("wrong", "다른 음", 0xFFC94A3D),
    MISSED("missed", "안 부름", 0xFF9AA19C),
    UNSUNG("unsung", "미포함", 0xFFD8D4C9);

    companion object {
        fun fromKey(key: String?): PitchStatus? = entries.firstOrNull { it.key == key }
    }
}

object ScoringUi {
    /** 러너웨이 배경·현재선 등 웹과 같은 팔레트 */
    const val COLOR_INK = 0xFF17221D
    const val COLOR_PAPER = 0xFFFBFAF5
    const val COLOR_STRIP = 0xFFF1EEE3
    const val COLOR_NOW = 0xFFD7FF43
    const val COLOR_MUTED = 0xFF9AA19C
    const val COLOR_MUTED_2 = 0xFFA9A294
    const val COLOR_UPCOMING = 0xFFD8D4C9

    private val STOPS = listOf(
        0.0 to intArrayOf(201, 74, 61),
        60.0 to intArrayOf(217, 139, 43),
        100.0 to intArrayOf(63, 157, 90),
    )

    /** 0–100 점수 → 빨강(0)·주황(60)·초록(100) 선형 램프. ARGB. */
    fun scoreColor(score: Double): Long {
        val clamped = score.coerceIn(0.0, 100.0)
        for (index in 0 until STOPS.size - 1) {
            val (from, fromColor) = STOPS[index]
            val (to, toColor) = STOPS[index + 1]
            if (clamped <= to) {
                val t = (clamped - from) / (to - from)
                val r = (fromColor[0] + (toColor[0] - fromColor[0]) * t).roundToInt()
                val g = (fromColor[1] + (toColor[1] - fromColor[1]) * t).roundToInt()
                val b = (fromColor[2] + (toColor[2] - fromColor[2]) * t).roundToInt()
                return argb(r, g, b)
            }
        }
        return argb(63, 157, 90)
    }

    private fun argb(r: Int, g: Int, b: Int): Long =
        0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}
