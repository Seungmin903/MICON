package com.micon.app.data.recommend

import com.micon.app.data.RangeFit
import com.micon.app.data.RecommendMode
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendResult
import com.micon.app.data.RecommendShelf
import com.micon.app.data.RecommendShelves
import com.micon.app.data.RecommendedSong
import com.micon.app.data.ShelfKeys
import com.micon.app.data.SongFit
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.StrainSection
import com.micon.app.data.StrainSections
import com.micon.app.data.TimbreAxis
import com.micon.app.data.TimbreMatch
import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.BackendRequestException
import com.micon.app.data.backend.dto.RecommendationDto
import com.micon.app.data.backend.dto.RecommendationSongDto
import com.micon.app.data.backend.dto.SongFitDto
import com.micon.app.data.backend.unwrapData
import com.micon.app.data.voice.VoiceAnalysis
import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceAnalysisStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.roundToInt

/**
 * 추천·적합도의 백엔드 구현. 앱은 추천 서버(8788)를 직접 부르지 않고 백엔드 `RecommendationController`·`SongFitController` 만 본다.
 *
 * - [shelves]: `GET /recommendations/latest` 가 COMPLETED 면 그 섹션을 곡 카탈로그로 채워 돌려준다. 추천이 없으면 `POST /recommendations` 로
 *   생성을 요청하고, 생성 중·실패·미준비(AI 연동 꺼짐)·비회원이면 앱 안 계산([local])을 [RecommendShelves.note] 와 함께 돌려준다.
 * - [fit]: 최근 COMPLETED 음성 분석이 있으면 `GET /songs/{id}/fit?analysisId` 를 쓰고, UNAVAILABLE·오류·비회원이면 [local] 로 내려간다.
 * - 서버 점수는 0~100 도 0~1 도 올 수 있어 [unit] 으로 0~1 에 맞춘다. 화면은 재정렬하지 않는다(REC-001).
 */
class BackendRecommendRepository(
    private val api: BackendApi,
    private val songs: SongRepository,
    private val voice: VoiceAnalysisRepository,
    private val local: RecommendRepository,
    private val isLoggedIn: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : RecommendRepository {
    private val mutex = Mutex()
    private var catalog: Map<Long, SongSummary> = emptyMap()
    private var latestAnalysis: VoiceAnalysis? = null
    private var latestAnalysisAt = 0L

    private class ServerState(val recommendation: RecommendationDto?, val note: String?)

    override suspend fun recommend(mode: RecommendMode, limit: Int): RecommendResult = local.recommend(mode, limit)

    override suspend fun shelves(limit: Int, reshuffle: Int): RecommendShelves {
        if (!isLoggedIn()) return local.shelves(limit, reshuffle)
        val server = try {
            fetchOrCreate()
        } catch (e: AuthSessionExpiredException) {
            return local.shelves(limit, reshuffle)
        } catch (e: IOException) {
            return local.shelves(limit, reshuffle).copy(note = "서버에 연결할 수 없어 이 기기에서 계산한 추천을 보여 드려요.")
        } catch (e: BackendRequestException) {
            return local.shelves(limit, reshuffle).copy(note = e.message)
        }
        val rec = server.recommendation
        if (rec != null && rec.status == "COMPLETED") {
            val mapped = mapShelves(rec, limit)
            if (mapped.isNotEmpty()) return RecommendShelves(personalized = true, shelves = mapped)
        }
        return local.shelves(limit, reshuffle).copy(note = server.note ?: noteFor(rec))
    }

    /** 최근 추천을 읽고, 없으면 생성을 요청한다. 생성 요청의 거절 사유는 안내 문구로 바꾼다. */
    private suspend fun fetchOrCreate(): ServerState {
        val latest = try {
            api.latestRecommendation().unwrapData()
        } catch (e: BackendRequestException) {
            if (e.status == 404) null else throw e
        }
        if (latest != null) return ServerState(latest, null)
        return try {
            api.createRecommendation().unwrapData()
            ServerState(null, NOTE_GENERATING)
        } catch (e: BackendRequestException) {
            ServerState(
                null,
                when (e.code) {
                    "RECOMMENDATION_INPUT_NOT_READY" -> "목소리 분석을 완료하면 서버가 음색까지 맞춘 추천을 만들어요. 지금은 이 기기의 음역 계산 추천이에요."
                    "RECOMMENDATION_ALREADY_GENERATING" -> NOTE_GENERATING
                    "RECOMMENDATION_DISPATCH_FAILED" -> "추천 서버가 아직 준비되지 않았어요. 지금은 이 기기의 음역 계산 추천이에요."
                    else -> e.message
                },
            )
        }
    }

    private fun noteFor(rec: RecommendationDto?): String? = when (rec?.status) {
        null -> null
        "GENERATING" -> NOTE_GENERATING
        "FAILED" -> "서버 추천 생성에 실패했어요" + (rec.failureReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". 지금은 이 기기의 음역 계산 추천이에요."
        "EMPTY" -> "서버 추천 결과가 비어 있어 이 기기의 음역 계산 추천을 보여 드려요."
        else -> "서버 추천을 아직 쓸 수 없어 이 기기의 음역 계산 추천을 보여 드려요."
    }

    // ------------------------------------------------------------------ 추천 섹션 매핑

    private suspend fun mapShelves(rec: RecommendationDto, limit: Int): List<RecommendShelf> {
        val ids = rec.shelves.flatMap { s -> s.items.map { it.songId } }.toSet()
        val songsById = catalogFor(ids)
        return rec.shelves
            .filter { it.key != ShelfKeys.CHALLENGE }
            .sortedBy { it.order }
            .map { shelf ->
                val items = shelf.items.sortedBy { it.position }.take(limit).mapNotNull { dto ->
                    val song = songsById[dto.songId] ?: return@mapNotNull null
                    val fit = dto.toSongFit(shelf.key, rec)
                    RecommendedSong(rank = if (dto.overallRank > 0) dto.overallRank else dto.position + 1, song = song, fit = fit, reasons = fit.reasons)
                }
                RecommendShelf(shelf.key, shelf.title, shelf.metric, items)
            }
            .filter { it.items.isNotEmpty() }
    }

    /** 곡 카탈로그(`GET /songs`)를 페이지로 읽어 id→요약 캐시를 만든다. 캐시에 없는 곡은 상세를 한 번 더 읽는다. */
    private suspend fun catalogFor(ids: Set<Long>): Map<Long, SongSummary> = mutex.withLock {
        if (ids.any { it !in catalog }) {
            val loaded = HashMap(catalog)
            var page = 0
            while (page < MAX_CATALOG_PAGES) {
                val result = songs.search("", page = page, size = CATALOG_PAGE)
                result.items.forEach { loaded[it.id] = it }
                if (page + 1 >= result.totalPages || result.items.isEmpty()) break
                page++
            }
            ids.filter { it !in loaded }.forEach { id -> runCatching { songs.detail(id) }.getOrNull()?.summary?.let { loaded[id] = it } }
            catalog = loaded
        }
        catalog
    }

    private fun RecommendationSongDto.toSongFit(shelfKey: String, rec: RecommendationDto): SongFit {
        val axis = axis?.let { runCatching { TimbreAxis.valueOf(it) }.getOrNull() }
        val reasons = listOfNotNull(reasonText?.takeIf { it.isNotBlank() }, label?.takeIf { it.isNotBlank() && it != reasonText })
            .ifEmpty { listOf(shelfReason(shelfKey)) }
        return SongFit(
            songId = songId,
            timbre = if (axis != null && label != null) TimbreMatch(axis, label, null) else null,
            timbreFit = unit(timbreScore),
            rangeFit = null,
            recommendedKey = recommendedKey,
            strainSections = null,
            overallFit = unit(finalScore) ?: 0f,
            reasons = reasons,
            scoringVersion = scoringVersion ?: rec.scoringVersion ?: "backend-recommendation",
            compositionVersion = compositionVersion ?: rec.compositionVersion,
        )
    }

    // ------------------------------------------------------------------ 곡 적합도

    override suspend fun fit(songId: Long): SongFit? {
        if (!isLoggedIn()) return local.fit(songId)
        val analysis = latestCompletedAnalysis() ?: return local.fit(songId)
        val dto = try {
            api.songFit(songId, analysis.id, 0).unwrapData()
        } catch (e: Exception) {
            return local.fit(songId)
        }
        if (dto.evaluationStatus != "AVAILABLE") return local.fit(songId)
        return dto.toSongFit(songId) ?: local.fit(songId)
    }

    /** 최근 COMPLETED 음성 분석(적합도 API 의 analysisId). 1분 캐시. 없으면 null → 로컬 계산. */
    private suspend fun latestCompletedAnalysis(): VoiceAnalysis? {
        val t = now()
        if (t - latestAnalysisAt > ANALYSIS_CACHE_MS) {
            latestAnalysis = runCatching { voice.latest() }.getOrNull()
            latestAnalysisAt = t
        }
        return latestAnalysis?.takeIf { it.status == VoiceAnalysisStatus.COMPLETED }
    }

    companion object {
        private const val CATALOG_PAGE = 100
        private const val MAX_CATALOG_PAGES = 5
        private const val ANALYSIS_CACHE_MS = 60_000L
        private const val NOTE_GENERATING = "서버가 내 목소리 분석으로 추천을 만들고 있어요. 잠시 뒤 새로고침하면 바뀌어요. 지금은 이 기기의 음역 계산 추천이에요."

        /** 서버 점수(0~100 또는 0~1) → 0~1 */
        fun unit(value: Double?): Float? = value?.let { (if (it > 1.0) it / 100.0 else it).coerceIn(0.0, 1.0).toFloat() }

        fun shelfReason(key: String): String = when (key) {
            ShelfKeys.VOICE -> "내 음색과 닮은 가수의 곡"
            ShelfKeys.ORIGINAL_KEY -> "원키 그대로 부를 수 있어요"
            ShelfKeys.FAVORITE_ARTISTS -> "선호 가수와 닮은 목소리의 곡"
            ShelfKeys.DISCOVERY -> "안 듣던 장르에서 부를 수 있는 곡"
            else -> "서버 추천"
        }

        /** `SongFitResponse`(AVAILABLE) → 앱 적합도. 음역 점수는 겹침 비율 하나뿐이라 coverage 에 넣고 세부 항목은 0. */
        fun SongFitDto.toSongFit(songId: Long): SongFit? {
            val range = selectedKeyFit ?: originalKeyFit ?: return null
            val score = unit(range.score) ?: return null
            val burden = burdenSections.filter { it.endMs > it.startMs }.map { StrainSection(it.startMs / 1000.0, it.endMs / 1000.0, 0) }
            val scores = recommendationScores
            return SongFit(
                songId = songId,
                timbre = null,
                timbreFit = unit(scores?.timbre),
                rhythmFit = unit(scores?.rhythm),
                rangeFit = RangeFit(
                    score = score, coverage = score, centerScore = 0f, spanFit = 0f, softFit = 0f,
                    songCoreLowMidi = range.songLowMidi?.roundToInt(), songCoreHighMidi = range.songHighMidi?.roundToInt(),
                ),
                recommendedKey = recommendedKeyOffset ?: 0,
                strainSections = StrainSections(original = burden, atKey = burden),
                overallFit = unit(scores?.overall) ?: score,
                reasons = listOfNotNull(reason?.takeIf { it.isNotBlank() }).ifEmpty { listOf("곡 음역의 ${(score * 100).roundToInt()}%가 내 안정 음역과 겹쳐요") },
                scoringVersion = scores?.algorithmVersion ?: "backend-song-fit",
                compositionVersion = null,
            )
        }
    }
}
