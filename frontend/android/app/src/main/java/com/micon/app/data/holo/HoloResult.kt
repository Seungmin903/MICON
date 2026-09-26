package com.micon.app.data.holo

import android.content.Context
import androidx.core.util.AtomicFile
import com.micon.app.audio.PitchCapture
import com.micon.app.core.network.Json
import com.micon.app.range.RangeProtocol
import com.micon.app.range.VoiceQualityResult
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 첫 화면(홀로) 30초 녹음의 기기 분석 결과. 프로토타입 "분석 결과" 화면이 보여 주는 항목 그대로다.
 * 서버 음색 분석(닮은 목소리)은 [artists] 로 나중에 채워진다.
 */
data class HoloResult(
    /** 녹음 기준 곡. 간편 분석이면 null */
    val songId: String? = null,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val measuredAt: Long,
    /** 전체 음역(MIDI) */
    val lowMidi: Int,
    val highMidi: Int,
    /** 편안한(안정) 음역 */
    val comfortLow: Int,
    val comfortHigh: Int,
    /** 추천곡 음역 — 편안한 구간 안쪽 */
    val recommendLow: Int,
    val recommendHigh: Int,
    /** 그래프 점수 0~1. [voiceQuality]가 있으면 개인 기준 발성 유지 점수다. */
    val stability: List<Float>,
    val stabilityMidis: List<Int>,
    val voiceQuality: VoiceQualityResult? = null,
    /** 한 호흡(프레이즈) 평균 초 */
    val phraseSec: Double,
    /** 적정 템포 범위 */
    val bpmLow: Int,
    val bpmHigh: Int,
    /** 음별 측정 결과 — 서버 range-result 로 그대로 올라간다 */
    val notes: List<HoloNote>,
    /** 녹음 중 목소리가 잡힌 초 */
    val voicedSec: Double,
    /** 서버 세션(회원·게스트 공통). 게스트 토큰은 저장하지 않는다 */
    val sessionId: Long? = null,
    val guestToken: String? = null,
    val timbreSubmitted: Boolean = false,
    val artists: List<HoloArtist> = emptyList(),
    val timbreLabels: List<String> = emptyList(),
    val profileSaved: Boolean = false,
) {
    val octaves: Double get() = (highMidi - lowMidi) / 12.0

    /**
     * 1단계에서 제대로 잰 음역으로 갈아 끼운다. 2단계(노래) 녹음으로 계산한 음역은 곡이 쓰는
     * 범위에 갇혀 실제보다 좁다 — 음역과 발성 유지 점수는 1단계 측정값을,
     * 호흡·템포는 노래 녹음을 쓴다.
     */
    fun withMeasuredRange(low: Int, high: Int, quality: VoiceQualityResult? = null): HoloResult {
        val points = quality?.points.orEmpty()
        if (high - low < 5) return copy(
            stability = if (points.isNotEmpty()) points.map { (it.score / 100.0).toFloat() } else stability,
            stabilityMidis = if (points.isNotEmpty()) points.map { it.midi } else stabilityMidis,
            voiceQuality = quality,
        )
        val cl = comfortLow.coerceIn(low, high)
        val ch = comfortHigh.coerceIn(cl, high)
        val (fixLow, fixHigh) = if (ch - cl >= 4) cl to ch else {
            val c = (low + high) / 2
            (c - (high - low) / 4).coerceAtLeast(low) to (c + (high - low) / 4).coerceAtMost(high)
        }
        val span = fixHigh - fixLow
        return copy(
            lowMidi = low, highMidi = high, comfortLow = fixLow, comfortHigh = fixHigh,
            recommendLow = fixLow + (span * 0.15).roundToInt(), recommendHigh = fixHigh - (span * 0.15).roundToInt(),
            stability = if (points.isNotEmpty()) points.map { (it.score / 100.0).toFloat() } else stability,
            stabilityMidis = if (points.isNotEmpty()) points.map { it.midi } else stabilityMidis,
            voiceQuality = quality,
        )
    }
    val usable: Boolean get() = voicedSec >= 4.0 && highMidi - lowMidi >= 5
}

data class HoloNote(val midi: Int, val status: String, val holdMs: Int, val stability: Double)
data class HoloArtist(val artistId: Long, val name: String, val imageUrl: String?, val similarity: Double)

/**
 * 30초 프레임 → [HoloResult]. 기기에서만 계산한다(서버 왕복 없이 결과 화면이 바로 뜬다).
 *
 * - 유성 프레임: clarity ≥ 0.75, rms ≥ 소음 바닥×2.5
 * - 전체 음역: 유성 시간 3%·97% 분위수 (튀는 프레임 제외)
 * - 음별 상태: 0.25초 이상 유지된 반음은 측정됨. 그 안에서 주파수 흔들림(반음 단위 표준편차)이
 *   0.35 이하면 STABLE, 아니면 UNSTABLE
 * - 편안한 음역: STABLE 음이 가장 길게 이어지는 구간(1초 이상 유지된 음 우선)
 * - 프레이즈: 유성 구간이 0.4초 넘게 끊기지 않고 이어진 길이의 평균
 */
object HoloRangeAnalyzer {
    private const val CLARITY = 0.75
    private const val MIN_NOTE_SEC = 0.25
    private val STABILITY_MIDIS = listOf(43, 52, 59, 64, 69, 74, 79) // G2 E3 B3 E4 A4 D5 G5

    fun analyze(frames: List<PitchCapture.PitchFrame>, songId: String?, title: String?, artist: String?, now: Long = System.currentTimeMillis()): HoloResult? {
        if (frames.isEmpty()) return null
        val rmsSorted = frames.map { it.rms }.sorted()
        val noise = rmsSorted[(rmsSorted.size * 0.15).toInt().coerceIn(0, rmsSorted.lastIndex)]
        val voiced = frames.filter { it.clarity >= CLARITY && it.f0 > 0 && it.rms >= noise * 2.5 && it.rms > 0.004 }
        val midis = voiced.map { RangeProtocol.midi(it.f0) }.filter { it in 30.0..96.0 }
        val voicedSec = voiced.sumOf { it.duration }
        if (midis.size < 20) return null

        val sorted = midis.sorted()
        val low = sorted[(sorted.size * 0.03).toInt()].roundToInt()
        val high = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.lastIndex)].roundToInt()

        // 음별 유지 시간·흔들림
        val byNote = HashMap<Int, MutableList<Double>>()
        val holdByNote = HashMap<Int, Double>()
        voiced.forEach { f ->
            val m = RangeProtocol.midi(f.f0)
            if (m !in 30.0..96.0) return@forEach
            val n = m.roundToInt()
            byNote.getOrPut(n) { ArrayList() }.add(m)
            holdByNote[n] = (holdByNote[n] ?: 0.0) + f.duration
        }
        val notes = (low..high).mapNotNull { n ->
            val hold = holdByNote[n] ?: 0.0
            if (hold < MIN_NOTE_SEC) return@mapNotNull null
            val xs = byNote[n] ?: return@mapNotNull null
            val mean = xs.average()
            val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
            val stability = (1.0 - (sd / 0.6)).coerceIn(0.0, 1.0)
            HoloNote(n, if (sd <= 0.35) "STABLE" else "UNSTABLE", (hold * 1000).roundToInt(), stability)
        }

        // 편안한 구간: STABLE 인 음이 이어지는 가장 긴 run (1개 반음 구멍은 허용)
        val stableSet = notes.filter { it.status == "STABLE" && it.holdMs >= 400 }.map { it.midi }.toSet()
        var bestRun = low..low
        var runStart = -1; var last = -1
        for (n in low..high) {
            val ok = n in stableSet || (n - 1 in stableSet && n + 1 in stableSet)
            if (ok) { if (runStart < 0) runStart = n; last = n }
            if (!ok || n == high) {
                if (runStart >= 0 && last - runStart > bestRun.last - bestRun.first) bestRun = runStart..last
                runStart = -1
            }
        }
        var comfortLow = bestRun.first
        var comfortHigh = bestRun.last
        if (comfortHigh - comfortLow < 4) { // 너무 좁으면 가운데 절반으로
            val c = (low + high) / 2
            comfortLow = (c - (high - low) / 4).coerceAtLeast(low)
            comfortHigh = (c + (high - low) / 4).coerceAtMost(high)
        }
        val span = comfortHigh - comfortLow
        val recLow = comfortLow + (span * 0.15).roundToInt()
        val recHigh = comfortHigh - (span * 0.15).roundToInt()

        // 안정성 곡선: 각 기준 음에서 ±3반음 안 음들의 안정도 가중 평균. 측정 밖은 거리에 따라 감쇠
        val stabilityCurve = STABILITY_MIDIS.map { m ->
            val near = notes.filter { abs(it.midi - m) <= 3 }
            if (near.isNotEmpty()) near.sumOf { it.stability * it.holdMs }.toFloat() / near.sumOf { it.holdMs }.toFloat()
            else {
                val d = when { m < low -> low - m; m > high -> m - high; else -> 0 }
                (0.35f - d * 0.06f).coerceAtLeast(0f)
            }
        }

        // 프레이즈 길이: 유성 프레임이 0.4초 넘게 끊기지 않은 구간
        val runs = ArrayList<Double>()
        var start = -1.0; var prevEnd = -1.0
        voiced.sortedBy { it.t }.forEach { f ->
            if (start < 0 || f.t - prevEnd > 0.4) {
                if (start >= 0 && prevEnd - start >= 0.8) runs.add(prevEnd - start)
                start = f.t
            }
            prevEnd = f.t + f.duration
        }
        if (start >= 0 && prevEnd - start >= 0.8) runs.add(prevEnd - start)
        val phrase = runs.takeIf { it.isNotEmpty() }?.average()?.coerceIn(1.5, 12.0) ?: 4.0
        // 한 호흡이 길수록 느린 템포가 편하다: 6초 → 72–96, 3초 → 96–120
        val bpmCenter = (120 - (phrase - 3.0) * 8).roundToInt().coerceIn(60, 132)
        return HoloResult(
            songId = songId, songTitle = title, songArtist = artist, measuredAt = now,
            lowMidi = low, highMidi = high, comfortLow = comfortLow, comfortHigh = comfortHigh,
            recommendLow = recLow, recommendHigh = recHigh,
            stability = stabilityCurve, stabilityMidis = STABILITY_MIDIS,
            phraseSec = phrase, bpmLow = bpmCenter - 12, bpmHigh = bpmCenter + 12,
            notes = notes, voicedSec = voicedSec,
        )
    }
}

/** 최근 결과 1건을 소유자(회원 id · 게스트)별 파일로 보관한다. 분석 탭이 읽는다. */
class HoloResultStore(context: Context) {
    private val dir = File(context.filesDir, "holo")
    private val adapter = Json.moshi.adapter(HoloResult::class.java)

    private fun file(ownerId: Long?) = File(dir, if (ownerId != null) "member-$ownerId.json" else "guest.json")

    fun read(ownerId: Long?): HoloResult? = runCatching {
        val f = file(ownerId)
        if (!f.isFile) null else adapter.fromJson(f.readText())
    }.getOrNull()

    fun write(ownerId: Long?, result: HoloResult) {
        dir.mkdirs()
        val atomic = AtomicFile(file(ownerId))
        val out = atomic.startWrite()
        try {
            out.write(adapter.toJson(result).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out); throw e
        }
    }

    fun clear(ownerId: Long?) { AtomicFile(file(ownerId)).delete() }
}
