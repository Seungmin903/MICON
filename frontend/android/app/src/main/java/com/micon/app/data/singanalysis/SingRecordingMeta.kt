package com.micon.app.data.singanalysis

import com.micon.app.core.network.Json
import com.squareup.moshi.JsonClass

/**
 * 불러서 분석하기 녹음 한 건의 메타데이터. WAV 옆에 같은 이름의 `.json` 으로 저장된다.
 *
 * 분석 담당자가 알아야 할 것:
 * - WAV 는 PCM16 모노, [sampleRate] Hz. 카운트인("탁탁탁")·일시정지 구간까지 **한 파일에 이어져** 있다.
 * - [takes] 가 "실제로 반주가 흐르며 부른 구간" 이다. `wavStartSec~wavEndSec`(WAV 시계) ↔ `songStartSec~songEndSec`(곡/반주 시계)
 *   가 1:1 대응하므로, 이 구간만 잘라 곡 시각에 맞추면 된다. 일시정지 1회면 take 가 2개.
 * - [lyricLines] 는 부른 범위와 겹치는 가사 줄(곡 시각). 가사 타이밍 신뢰도는 [lyricTiming](SYNCED > NOTES > APPROX > NONE).
 * - 채점·음정 분석은 이 앱이 하지 않는다. 마이크는 VOICE_RECOGNITION 소스(AGC·노이즈 억제 없음).
 */
@JsonClass(generateAdapter = false)
data class SingRecordingMeta(
    val id: String,
    val schemaVersion: Int = 1,
    val songId: String,
    val title: String,
    val artist: String,
    /** [SingScope] 이름 */
    val scope: String,
    /** [PlanSource] 이름 */
    val planSource: String,
    /** 반주 키(반음). 이 흐름은 원키(0) 고정 */
    val selectedKey: Int = 0,
    val plannedStartSec: Double,
    val plannedEndSec: Double,
    /** 실제로 부른 곡 시각 범위 */
    val sungStartSec: Double,
    val sungEndSec: Double,
    /** 사용자가 "여기까지 마치기" 로 끝냈는가 */
    val endedEarly: Boolean,
    /** epoch ms */
    val recordedAt: Long,
    val sampleRate: Int,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    /** WAV 전체 길이(초) */
    val durationSec: Double,
    val takes: List<SingTake> = emptyList(),
    /** [com.micon.app.scoring.LyricTiming] 이름 */
    val lyricTiming: String,
    val lyricLines: List<SingLyricLine> = emptyList(),
    val wavFileName: String,
    /** 백엔드 practice-assets 의 dataVersion (음표·가사 버전) */
    val dataVersion: String? = null,
    /** 서버 업로드가 끝난 녹음 id(`POST /recordings`). 있으면 재요청 때 다시 올리지 않는다. */
    val serverRecordingId: Long? = null,
    /** 마지막으로 접수된 서버 분석 id(`POST /voice-analyses`). */
    val serverAnalysisId: Long? = null,
    /** Local onboarding draft correlation; never a backend sessionId. */
    val onboardingDraftId: String? = null,
) {
    val scopeEnum: SingScope get() = runCatching { SingScope.valueOf(scope) }.getOrDefault(SingScope.FULL)
}

/** 반주가 흐르며 부른 구간 하나. 시각은 초. */
@JsonClass(generateAdapter = false)
data class SingTake(
    val wavStartSec: Double,
    val wavEndSec: Double,
    val songStartSec: Double,
    val songEndSec: Double,
)

/** 가사 줄(곡 시각). */
@JsonClass(generateAdapter = false)
data class SingLyricLine(val text: String, val start: Double, val end: Double)

/** 메타데이터 JSON 직렬화. Moshi(리플렉션) — 앱의 다른 저장소와 같은 인스턴스를 쓴다. */
object SingRecordingMetaCodec {
    private val adapter = Json.moshi.adapter(SingRecordingMeta::class.java).indent("  ")

    fun toJson(meta: SingRecordingMeta): String = adapter.toJson(meta)

    /** 깨진 파일은 null (호출자가 정리한다) */
    fun fromJson(json: String): SingRecordingMeta? = runCatching { adapter.fromJson(json) }.getOrNull()
}
