package com.micon.app.ui.songdetail

import com.micon.app.data.PracticeSection
import com.micon.app.data.backend.dto.KaraokeNote
import kotlin.math.max

/** Display-only comparison with measured range. Does not change recommendation/scoring rules. */
data class MeasuredPitchRange(val low: Int, val high: Int) {
    val valid: Boolean get() = low in 0..127 && high in low..127
}

enum class PitchRegionKind { HIGH, LOW, SONG }

data class PitchRegion(val id: String, val start: Double, val end: Double, val title: String,
    val description: String, val kind: PitchRegionKind)

data class SongPitchPresentation(
    val notes: List<KaraokeNote>, val duration: Double, val regions: List<PitchRegion>,
    val range: MeasuredPitchRange?, val selectedKey: Int, val axisLow: Int, val axisHigh: Int,
) {
    val personalRegions get() = regions.filter { it.kind != PitchRegionKind.SONG }

    companion object {
        fun build(notes: List<KaraokeNote>, duration: Double, range: MeasuredPitchRange?, selectedKey: Int,
            recommendedKey: Int?, sections: List<PracticeSection> = emptyList()): SongPitchPresentation {
            val validNotes = notes.filter {
                it.sourceStart.isFinite() && it.sourceEnd.isFinite() && it.sourceStart >= 0 &&
                    it.sourceEnd > it.sourceStart && it.midi in 0..127
            }.sortedBy { it.sourceStart }
            val measured = range?.takeIf { it.valid }
            val end = max(duration.takeIf { it.isFinite() && it > 0 } ?: 0.0, validNotes.maxOfOrNull { it.sourceEnd } ?: 0.0)
            val regions = mutableListOf<PitchRegion>()
            if (measured != null) {
                for (kind in listOf(PitchRegionKind.HIGH, PitchRegionKind.LOW)) {
                    val outside = validNotes.filter {
                        if (kind == PitchRegionKind.HIGH) it.midi + selectedKey > measured.high else it.midi + selectedKey < measured.low
                    }
                    var start = -1.0
                    var finish = 0.0
                    var excess = 0
                    fun flush() {
                        if (start < 0) return
                        regions += PitchRegion("${kind.name}:$start", start, finish,
                            if (kind == PitchRegionKind.HIGH) "높은 음 주의" else "낮은 음 주의",
                            "내 측정 음역보다 최대 ${excess}반음 ${if (kind == PitchRegionKind.HIGH) "높아요" else "낮아요"}.", kind)
                    }
                    outside.forEach { note ->
                        if (start < 0 || note.sourceStart - finish > 1.0) {
                            flush(); start = note.sourceStart; finish = note.sourceEnd; excess = 0
                        }
                        finish = max(finish, note.sourceEnd)
                        excess = max(excess, if (kind == PitchRegionKind.HIGH) note.midi + selectedKey - measured.high else measured.low - note.midi - selectedKey)
                    }
                    flush()
                }
            }
            sections.filter { it.difficultyType != "NONE" && it.startMs >= 0 && it.endMs > it.startMs && it.startMs / 1000.0 < end }
                .forEach { s ->
                    regions += PitchRegion("section:${s.sectionId}", s.startMs / 1000.0, (s.endMs / 1000.0).coerceAtMost(end),
                        s.sectionName.ifBlank { "곡 주의 구간" }, s.description?.takeIf { it.isNotBlank() } ?: when (s.difficultyType) {
                            "HIGH_NOTE" -> "원곡에 높은 음이 집중된 구간이에요."
                            "LONG_BREATH" -> "긴 호흡이 필요한 구간이에요."
                            "FAST_LYRICS" -> "가사가 빠르게 이어지는 구간이에요."
                            "PITCH_JUMP" -> "음 높이가 크게 바뀌는 구간이에요."
                            else -> "곡 분석에서 주의가 필요한 구간으로 표시됐어요."
                        }, PitchRegionKind.SONG)
                }
            val keys = listOf(0, selectedKey, recommendedKey ?: 0)
            val pitches = validNotes.flatMap { n -> keys.map { n.midi + it } } + listOfNotNull(measured?.low, measured?.high)
            return SongPitchPresentation(validNotes, end, regions.sortedWith(compareBy<PitchRegion> { it.start }.thenBy { it.kind }), measured, selectedKey,
                (pitches.minOrNull() ?: 55) - 3, (pitches.maxOrNull() ?: 79) + 3)
        }
    }
}

fun songKeyLabel(key: Int): String = if (key == 0) "원키" else "${if (key > 0) "+" else ""}$key"

fun songTime(seconds: Double): String {
    val value = seconds.takeIf { it.isFinite() }?.toInt()?.coerceAtLeast(0) ?: 0
    return "%d:%02d".format(value / 60, value % 60)
}
