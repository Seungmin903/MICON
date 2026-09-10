package com.micon.app.data.engine.dto

import com.squareup.moshi.JsonClass

/*
 * 엔진 job 객체 (GET /jobs/{id}). 필드 정의는 docs/DATA_INVENTORY.md §2.
 * 앱이 읽는 것만 선언한다 — Moshi 는 선언 안 한 키를 무시한다. 모두 nullable + 기본값으로 두어
 * 버전이 달라져도 파싱이 깨지지 않게 한다.
 */

@JsonClass(generateAdapter = false)
data class Job(
    val id: String,
    val kind: String = "reference",           // reference | performance
    val filename: String? = null,
    val createdAt: Double? = null,
    val sourceType: String? = null,           // mix | vocal
    val separator: String? = null,
    val hasLyrics: Boolean? = null,
    val status: String = "queued",            // queued | processing | complete | failed
    val progress: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val result: JobResult? = null,
    // performance job 전용
    val referenceJobId: String? = null,
    val referenceFilename: String? = null,
    val compareOptions: CompareOptions? = null,
) {
    val isComplete: Boolean get() = status == "complete"
    val isFailed: Boolean get() = status == "failed"
    val isDone: Boolean get() = isComplete || isFailed
}

@JsonClass(generateAdapter = false)
data class CompareOptions(
    val allowTranspose: Boolean = true,
    val octaveTolerant: Boolean = true,
)

@JsonClass(generateAdapter = false)
data class JobsResponse(val jobs: List<Job> = emptyList())

@JsonClass(generateAdapter = false)
data class HealthResponse(
    val ok: Boolean = false,
    val device: String? = null,
    val gpu: String? = null,
    val models: List<String>? = null,
    val separators: List<String>? = null,
)

@JsonClass(generateAdapter = false)
data class SemitonesRequest(val semitones: Int)

@JsonClass(generateAdapter = false)
data class InstrumentalKeyResponse(
    val semitones: Int = 0,
    val file: String? = null,
    val url: String? = null,
    val seconds: Double? = null,
    val range: List<Int>? = null,
)

/** FastAPI 오류 본문 `{detail: "..."}` */
@JsonClass(generateAdapter = false)
data class ErrorDetail(val detail: String? = null)

// ---------------------------------------------------------------- result

@JsonClass(generateAdapter = false)
data class JobResult(
    // 음역
    val lowest: String? = null,
    val highest: String? = null,
    val coreRange: String? = null,
    val lowestMidi: Int? = null,
    val highestMidi: Int? = null,
    val ratios: RangeRatios? = null,
    val confidence: Int? = null,
    val noteCount: Int? = null,
    // 템포·조성
    val tempoBpm: Int? = null,
    val tempoRawBpm: Double? = null,
    val timeSignature: String? = null,
    val meterConfidence: Double? = null,
    val vocalStart: Double? = null,
    val vocalEnd: Double? = null,
    val voicedRatio: Double? = null,
    val voicedSeconds: Double? = null,
    val tuningCents: Int? = null,
    val key: KeyInfo? = null,
    val beatGrid: BeatGrid? = null,
    // 성구
    val register: Register? = null,
    // 가사·음표
    val lyrics: LyricsInfo? = null,
    val notes: List<SourceNote>? = null,
    val karaokeNotes: List<KaraokeNote>? = null,
    val karaokeVersion: Int? = null,
    val artifacts: Map<String, String>? = null,
    // performance job 전용
    val comparison: Comparison? = null,
) {
    val hasInstrumental: Boolean get() = artifacts?.containsKey("instrumental") == true
}

@JsonClass(generateAdapter = false)
data class RangeRatios(val low: Double = 0.0, val middle: Double = 0.0, val high: Double = 0.0)

@JsonClass(generateAdapter = false)
data class KeyInfo(
    val tonic: Int? = null,
    val mode: String? = null,
    val sharps: Int? = null,
    val name: String? = null,
    val confidence: Double? = null,
)

@JsonClass(generateAdapter = false)
data class BeatGrid(val beatTimes: List<Double> = emptyList(), val offsetBeats: Double = 0.0)

@JsonClass(generateAdapter = false)
data class LyricsInfo(
    val source: String? = null,
    val text: String? = null,
    val syllables: Int? = null,
    val alignmentScore: Double? = null,
    val unknownTokens: Int? = null,
    val error: String? = null,
)

/** result.register (DATA_INVENTORY §2.4). ratios 는 0–1, lightnessMean 0–100. */
@JsonClass(generateAdapter = false)
data class Register(
    val bimodal: Boolean? = null,
    val twoRegisters: Boolean? = null,
    val singleRegisterLabel: String? = null,
    val seconds: Map<String, Double>? = null,
    val ratios: Map<String, Double>? = null,     // modal, falsetto, mixed, unclear
    val breathyRatio: Double? = null,
    val lightnessMean: Double? = null,
    val lightnessHistogram: List<Double>? = null,
    val modalRange: String? = null,
    val falsettoRange: String? = null,
    val modalRangeMidi: List<Int>? = null,
    val falsettoRangeMidi: List<Int>? = null,
    val labels: Map<String, String>? = null,
    val caveats: List<String>? = null,
    val error: String? = null,
)

/** result.notes[] — 원본 음표 (start/end 또는 sourceStart/sourceEnd 초). */
@JsonClass(generateAdapter = false)
data class SourceNote(
    val start: Double? = null,
    val end: Double? = null,
    val sourceStart: Double? = null,
    val sourceEnd: Double? = null,
    val duration: Double? = null,
    val midi: Int,
    val actualMidi: Double? = null,
    val cents: Int? = null,
    val name: String? = null,
    val register: String? = null,
    val lightness: Double? = null,
    val scoreStartBeat: Double? = null,
    val scoreEndBeat: Double? = null,
)

/** result.karaokeNotes[] — 가사 음절이 붙은 노래방 음표 (DATA_INVENTORY §2.6). */
@JsonClass(generateAdapter = false)
data class KaraokeNote(
    val sourceStart: Double,
    val sourceEnd: Double,
    val midi: Int,
    val actualMidi: Double? = null,
    val name: String? = null,
    val cents: Int? = null,
    val confidence: Double? = null,
    val duration: Double? = null,
    val register: String? = null,            // modal | falsetto | mixed | unclear
    val registerConfidence: Double? = null,
    val lightness: Double? = null,
    val breathy: Boolean? = null,
    val lyric: String? = null,               // 음절. 멜리스마 연장은 null
    val lyricExtend: Boolean? = null,
    val lyricStart: Double? = null,
    val lyricEnd: Double? = null,
    val lyricStatus: String? = null,         // aligned | low-confidence | unknown-language
    val scoreStartBeat: Double? = null,
    val scoreEndBeat: Double? = null,
    val scoreStart: Double? = null,
    val scoreEnd: Double? = null,
)

// ---------------------------------------------------------------- comparison (채점 결과)

/** result.comparison (DATA_INVENTORY §2.9) — performance job 에만 있다. */
@JsonClass(generateAdapter = false)
data class Comparison(
    val referenceJobId: String? = null,
    val performanceJobId: String? = null,
    val options: CompareOptions? = null,
    val transposeSemitones: Int? = null,
    val transposition: Transposition? = null,
    val referenceKey: String? = null,
    val performanceKey: String? = null,
    val startOffsetSeconds: Double? = null,
    val tempoRatio: Double? = null,
    val coverage: Coverage? = null,
    val scores: Scores,
    val pitchStats: PitchStats? = null,
    val timingStats: TimingStats? = null,
    val notes: List<ComparisonNote> = emptyList(),
    val measures: List<ComparisonMeasure> = emptyList(),
    val issues: List<ComparisonIssue> = emptyList(),
    val curves: Curves? = null,
    val alignment: Alignment? = null,
    val thresholds: Thresholds? = null,
)

@JsonClass(generateAdapter = false)
data class Scores(
    val pitch: Double = 0.0,
    val timing: Double = 0.0,
    val overall: Double = 0.0,
    val grade: String = "F",
    val stabilityCents: Double? = null,
)

@JsonClass(generateAdapter = false)
data class Coverage(
    val refStart: Double? = null,
    val refEnd: Double? = null,
    val perfStart: Double? = null,
    val perfEnd: Double? = null,
    val notesTotal: Int = 0,
    val notesCovered: Int = 0,
    val notesSung: Int = 0,
    val notesMissed: Int = 0,
    val sungRatio: Double? = null,
    val coveredRatio: Double? = null,
)

@JsonClass(generateAdapter = false)
data class PitchStats(
    val good: Int = 0, val sharp: Int = 0, val flat: Int = 0, val wrong: Int = 0, val missed: Int = 0,
    val meanCents: Double? = null, val medianCents: Double? = null,
    val octaveShiftedNotes: Int? = null, val shortNotes: Int? = null,
)

@JsonClass(generateAdapter = false)
data class TimingStats(
    val good: Int = 0, val early: Int = 0, val late: Int = 0, val off: Int = 0, val missed: Int = 0,
    val meanDeviationMs: Double? = null, val absDeviationMs: Double? = null,
)

@JsonClass(generateAdapter = false)
data class Transposition(
    val semitones: Int = 0,
    val pitchClassShift: Int? = null,
    val medianGap: Double? = null,
    val confidence: Double? = null,
)

/** 음표별 판정. pitchStatus 는 PitchStatus.key 와 같은 문자열. */
@JsonClass(generateAdapter = false)
data class ComparisonNote(
    val index: Int,
    val refStart: Double,
    val refEnd: Double,
    val midi: Int,
    val targetMidi: Double? = null,
    val name: String? = null,
    val lyric: String? = null,
    val beat: Double? = null,
    val covered: Boolean = false,
    val perfStart: Double? = null,
    val perfEnd: Double? = null,
    val sungMidi: Double? = null,
    val sungInKey: Double? = null,
    val sungName: String? = null,
    val centsError: Int? = null,
    val octaveShift: Int? = null,
    val stabilityCents: Double? = null,
    val heldRatio: Double? = null,
    val onsetDeviation: Double? = null,
    val short: Boolean? = null,
    val pitchStatus: String = "unsung",      // good | sharp | flat | wrong | missed | unsung
    val timingStatus: String? = null,        // good | early | late | off | missed
    val pitchScore: Double? = null,
    val timingScore: Double? = null,
    val weight: Double? = null,
)

@JsonClass(generateAdapter = false)
data class ComparisonMeasure(
    val measure: Int,
    val start: Double? = null,
    val end: Double? = null,
    val notes: Int = 0,
    val pitch: Double = 0.0,
    val timing: Double = 0.0,
    val score: Double = 0.0,
    val missed: Int = 0, val sharp: Int = 0, val flat: Int = 0, val wrong: Int = 0,
    val early: Int = 0, val late: Int = 0,
    val lyric: String? = null,
)

@JsonClass(generateAdapter = false)
data class ComparisonIssue(
    val start: Double,
    val end: Double,
    val perfStart: Double? = null,
    val noteIndices: List<Int> = emptyList(),
    val lyric: String? = null,
    val notes: Int = 0,
    val summary: String = "",
    val severity: Double = 0.0,
)

/** 음정 곡선. ref/user 는 hop 간격 MIDI(무성 null). */
@JsonClass(generateAdapter = false)
data class Curves(
    val hop: Double = 0.05,
    val start: Double = 0.0,
    val ref: List<Double?> = emptyList(),
    val user: List<Double?> = emptyList(),
    val perfTime: List<Double?>? = null,
)

@JsonClass(generateAdapter = false)
data class Alignment(
    val method: String? = null,
    val reliable: Boolean? = null,
    val matchedRatio: Double? = null,
    val noteSource: String? = null,
    val referenceNoteCount: Int? = null,
)

@JsonClass(generateAdapter = false)
data class Thresholds(
    val pitchGoodCents: Double? = null,
    val pitchWrongCents: Double? = null,
    val timingGoodMs: Double? = null,
    val timingOffMs: Double? = null,
    val weights: Map<String, Double>? = null,
)

/** 엔진 카탈로그 곡 job 에 붙는 `catalog{}` (배치 적재기가 넣음). */
@JsonClass(generateAdapter = false)
data class CatalogInfo(
    val trackId: String? = null,
    val tjNumber: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val genre: String? = null,
    val primaryLanguage: String? = null,
)
