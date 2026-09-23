package com.micon.app.data.recommend

import com.micon.app.data.RangeFit
import com.micon.app.data.RecommendRepository
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
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.ScoringConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 추천·적합도의 백엔드 구현. 앱은 추천 서버(8788)를 직접 부르지 않고 백엔드 `RecommendationController`·`SongFitController` 만 본다.
 *
 * - [shelves]: `GET /recommendations/latest` 가 COMPLETED 면 그 섹션을 곡 카탈로그로 채워 돌려준다. 추천이 없으면 `POST /recommendations` 로
 *   생성을 요청한다. 생성 중에는 빈 목록을 돌려 화면이 폴링하게 하고, 실패·미준비일 때만 앱 안 계산([local])을 사용한다.
 *   비회원이거나 목소리 분석이 없으면 빈 목록 + 이유(로그인·분석 필요) — 기본 추천은 없다.
 * - [fit]: 저장된 목소리 프로필(`GET /users/me/voice-profile`)이 있으면 `GET /songs/{id}/fit?analysisId=<profileId>` 를 쓴다 —
 *   BE 는 이 파라미터를 user_voice_profile.profile_id 로 조회한다. UNAVAILABLE·오류·비회원이면 [local] 로 내려간다.
 * - 서버 점수는 0~100 도 0~1 도 올 수 있어 [unit] 으로 0~1 에 맞춘다. 화면은 재정렬하지 않는다(REC-001).
 */
class BackendRecommendRepository(
    private val api: BackendApi,
    private val songs: SongRepository,
    private val local: RecommendRepository,
    private val isLoggedIn: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : RecommendRepository {
    private val mutex = Mutex()
    private var catalog: Map<String, SongSummary> = emptyMap()
    private var profileId: Long? = null
    private var profileAt = 0L
    private var profileSeen: Long? = null   // UserStore.profile.measuredAt. 재측정으로 바뀌면 캐시를 버린다

    private class ServerState(val recommendation: RecommendationDto?, val note: String?,
        val generating: Boolean = false, val profileMissing: Boolean = false)

    override suspend fun shelves(limit: Int, reshuffle: Int): RecommendShelves {
        if (!isLoggedIn()) return RecommendShelves.empty(RecommendShelves.NOTE_LOGIN_REQUIRED)
        val server = try {
            fetchOrCreate()
        } catch (e: AuthSessionExpiredException) {
            return RecommendShelves.empty(RecommendShelves.NOTE_LOGIN_REQUIRED)
        } catch (e: IOException) {
            return fallback(limit, reshuffle, "서버에 연결할 수 없어요.")
        } catch (e: BackendRequestException) {
            return fallback(limit, reshuffle, e.message)
        }
        val rec = server.recommendation
        if (server.profileMissing) return RecommendShelves.empty(RecommendShelves.NOTE_ANALYSIS_REQUIRED)
        if (rec != null && rec.status == "COMPLETED") {
            val mapped = mapShelves(rec, limit, reshuffle)
            if (mapped.isNotEmpty()) return RecommendShelves(
                personalized = true, shelves = mapped, note = server.note, generating = server.generating)
        }
        if (server.generating || rec?.status == "GENERATING") return RecommendShelves(
            personalized = true, shelves = emptyList(), note = server.note ?: noteFor(rec), generating = true)
        return fallback(limit, reshuffle, server.note ?: noteFor(rec))
    }

    /**
     * 서버 추천이 없을 때 앱 안 음역 계산으로 내려간다. 기기에 측정된 음역이 있으면 그 추천에 [reason] 을 붙여 보여 주고,
     * 없으면(분석 전) 빈 목록 — 이때는 서버가 준 이유가 더 정확하므로 그것을 우선 보인다.
     */
    private suspend fun fallback(limit: Int, reshuffle: Int, reason: String?): RecommendShelves {
        val shelves = local.shelves(limit, reshuffle)
        return when {
            reason == null -> shelves
            shelves.shelves.isEmpty() -> shelves.copy(note = reason)
            else -> shelves.copy(note = "$reason $NOTE_LOCAL_TAIL")
        }
    }

    override suspend fun regenerate(): Boolean {
        if (!isLoggedIn()) return false
        return try {
            api.createRecommendation().unwrapData()
            true
        } catch (e: BackendRequestException) {
            e.code == "RECOMMENDATION_ALREADY_GENERATING"
        }
    }

    /** 최근 추천을 읽고, 없으면 생성을 요청한다. 생성 요청의 거절 사유는 안내 문구로 바꾼다. */
    private suspend fun fetchOrCreate(): ServerState {
        val profile = try {
            api.latestVoiceProfile().unwrapData()
        } catch (e: BackendRequestException) {
            if (e.status == 404) return ServerState(null, RecommendShelves.NOTE_ANALYSIS_REQUIRED, profileMissing = true)
            throw e
        }
        val latest = try {
            api.latestRecommendation().unwrapData()
        } catch (e: BackendRequestException) {
            if (e.status == 404) null else throw e
        }
        val sameProfile = latest != null && latest.profileId == profile.profileId &&
            latest.sourceSessionId == profile.sessionId && latest.profileVersion == profile.profileVersion
        // Results created before preference-versioning do not carry either field. They are
        // still valid when the voice-profile identity matches; only a version mismatch (or
        // a partially populated version pair) requires regeneration.
        val preferenceFresh = latest != null && when {
            latest.preferenceVersion == null && latest.currentPreferenceVersion == null -> true
            latest.preferenceVersion != null && latest.preferenceVersion == latest.currentPreferenceVersion -> true
            else -> false
        }
        val fresh = sameProfile && preferenceFresh
        if (fresh) {
            return ServerState(latest, null)
        }
        return try {
            api.createRecommendation().unwrapData()
            // A preference-only change can still show the same voice-profile result while it refreshes.
            ServerState(
                if (sameProfile) latest else null,
                if (sameProfile) NOTE_PREFERENCE_REFRESHING else NOTE_GENERATING,
                generating = true,
            )
        } catch (e: BackendRequestException) {
            ServerState(
                if (sameProfile) latest else null,
                when (e.code) {
                    "RECOMMENDATION_INPUT_NOT_READY" -> RecommendShelves.NOTE_ANALYSIS_REQUIRED
                    "RECOMMENDATION_ALREADY_GENERATING" -> if (sameProfile) NOTE_PREFERENCE_REFRESHING else NOTE_GENERATING
                    "RECOMMENDATION_DISPATCH_FAILED" -> "추천 서버가 아직 준비되지 않았어요."
                    else -> e.message
                },
                generating = e.code == "RECOMMENDATION_ALREADY_GENERATING",
            )
        }
    }

    private fun noteFor(rec: RecommendationDto?): String? = when (rec?.status) {
        null -> null
        "GENERATING" -> NOTE_GENERATING
        "FAILED" -> "서버 추천 생성에 실패했어요" + (rec.failureReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + "."
        "EMPTY" -> RecommendShelves.NOTE_EMPTY
        else -> "서버 추천을 아직 쓸 수 없어요."
    }

    // ------------------------------------------------------------------ 추천 섹션 매핑

    /** 서버 순서 그대로(REC-001). 단 새로운 발견은 [reshuffle] 이 바뀔 때마다 서버가 준 후보 안에서 다시 섞어 [limit] 만큼 보인다 — 서버를 다시 부르지 않는다. */
    private suspend fun mapShelves(rec: RecommendationDto, limit: Int, reshuffle: Int): List<RecommendShelf> {
        val ids = rec.shelves.flatMap { s -> s.items.map { it.songId } }.toSet()
        val songsById = catalogFor(ids)
        return rec.shelves
            .filter { it.key != ShelfKeys.CHALLENGE }
            .sortedBy { it.order }
            .map { shelf ->
                val ordered = shelf.items.sortedBy { it.position }
                val visible = if (shelf.key == ShelfKeys.DISCOVERY && reshuffle > 0) ordered.shuffled(Random(reshuffle)) else ordered
                val items = visible.take(limit).mapNotNull { dto ->
                    val song = songsById[dto.songId] ?: return@mapNotNull null
                    val fit = dto.toSongFit(shelf.key, rec)
                    RecommendedSong(rank = if (dto.overallRank > 0) dto.overallRank else dto.position + 1, song = song, fit = fit, reasons = fit.reasons)
                }
                RecommendShelf(shelf.key, shelf.title, shelf.metric, items)
            }
            .filter { it.items.isNotEmpty() }
    }

    /** 곡 카탈로그(`GET /songs`)를 페이지로 읽어 id→요약 캐시를 만든다. 캐시에 없는 곡은 상세를 한 번 더 읽는다. */
    private suspend fun catalogFor(ids: Set<String>): Map<String, SongSummary> = mutex.withLock {
        if (ids.any { it !in catalog }) {
            val loaded = HashMap(catalog)
            var page = 0
            var totalPages = 1
            while (page < totalPages && page < MAX_CATALOG_PAGES && ids.any { it !in loaded }) {
                val result = songs.search("", page = page, size = CATALOG_PAGE)
                result.items.forEach { loaded[it.id] = it }
                totalPages = result.totalPages.coerceAtLeast(page + 1)
                if (result.items.isEmpty()) break
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
            recommendedKey = recommendedKey?.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX),
            strainSections = null,
            overallFit = unit(finalScore) ?: 0f,
            reasons = reasons,
            scoringVersion = scoringVersion ?: rec.scoringVersion ?: "backend-recommendation",
            compositionVersion = compositionVersion ?: rec.compositionVersion,
        )
    }

    // ------------------------------------------------------------------ 곡 적합도

    override suspend fun fit(songId: String): SongFit? {
        if (!isLoggedIn()) return local.fit(songId)
        val profile = savedProfileId() ?: return local.fit(songId)
        val dto = try {
            api.songFit(songId, profile, 0).unwrapData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return local.fit(songId)
        }
        if (dto.songId != songId || dto.analysisId != profile || dto.selectedKeyOffset != 0) return null
        if (dto.evaluationStatus != "AVAILABLE") return local.fit(songId)
        return dto.toSongFit(songId) ?: local.fit(songId)
    }

    /** 저장된 목소리 프로필 id. 1분 캐시, 재측정(프로필 measuredAt 변화)이면 즉시 다시 읽는다. 없으면(404) null → 로컬 계산. */
    private suspend fun savedProfileId(): Long? {
        val t = now()
        val seen = UserStore.profile.value?.measuredAt
        if (t - profileAt > PROFILE_CACHE_MS || seen != profileSeen) {
            profileId = try {
                api.latestVoiceProfile().unwrapData().profileId.takeIf { it > 0 }
            } catch (e: BackendRequestException) {
                if (e.status == 404) null else throw e
            }
            profileAt = t
            profileSeen = seen
        }
        return profileId
    }

    companion object {
        private const val CATALOG_PAGE = 100
        // Corrupt pagination metadata must not create an unbounded loop. This is a safety ceiling,
        // not a catalog slice: the current catalog can be read past its former five-page limit.
        private const val MAX_CATALOG_PAGES = 100
        private const val PROFILE_CACHE_MS = 60_000L
        private const val NOTE_GENERATING = "서버가 내 목소리 분석으로 추천을 만들고 있어요. 잠시만 기다려 주세요."
        private const val NOTE_PREFERENCE_REFRESHING = "변경한 선호를 반영해 추천을 갱신하고 있어요. 잠시만 기다려 주세요."
        /** 기기 계산 추천을 대신 보여 줄 때 이유 뒤에 붙이는 꼬리 */
        private const val NOTE_LOCAL_TAIL = "지금은 이 기기의 음역 계산 추천이에요."

        /** 서버 점수(0~100 또는 0~1) → 0~1 */
        fun unit(value: Double?): Float? = value?.takeIf { it.isFinite() }?.let { (if (it > 1.0) it / 100.0 else it).coerceIn(0.0, 1.0).toFloat() }

        fun shelfReason(key: String): String = when (key) {
            ShelfKeys.VOICE -> "내 음색과 닮은 가수의 곡"
            ShelfKeys.ORIGINAL_KEY -> "원키 그대로 부를 수 있어요"
            ShelfKeys.FAVORITE_ARTISTS -> "선호 가수와 닮은 목소리의 곡"
            ShelfKeys.DISCOVERY -> "안 듣던 장르에서 부를 수 있는 곡"
            else -> "서버 추천"
        }

        /** `SongFitResponse`(AVAILABLE) → 앱 적합도. 음역 점수는 겹침 비율 하나뿐이라 coverage 에 넣고 세부 항목은 0. */
        fun SongFitDto.toSongFit(songId: String): SongFit? {
            val range = selectedKeyFit ?: originalKeyFit ?: return null
            val score = unit(range.score) ?: return null
            val burden = burdenSections.filter { it.endMs > it.startMs }.map { StrainSection(it.startMs / 1000.0, it.endMs / 1000.0, 0) }
            val scores = recommendationScores
            val axis = scores?.timbreAxis?.let { runCatching { TimbreAxis.valueOf(it) }.getOrNull() }
            val label = scores?.timbreLabel?.takeIf { it.isNotBlank() }
            return SongFit(
                songId = songId,
                timbre = if (axis != null && label != null) TimbreMatch(axis, label, null) else null,
                timbreFit = unit(scores?.timbre),
                rhythmFit = unit(scores?.rhythm),
                rangeFit = RangeFit(
                    score = score, coverage = score, centerScore = 0f, spanFit = 0f, softFit = 0f,
                    songCoreLowMidi = range.songLowMidi?.roundToInt(), songCoreHighMidi = range.songHighMidi?.roundToInt(),
                ),
                recommendedKey = recommendedKeyOffset?.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX),
                // null = 계산되지 않음. 0 으로 바꾸면 원키 추천과 구분이 안 된다.
                // 구버전 서버에 남은 ±4 이상 값은 현재 재생 가능한 ±3으로 방어적으로 제한한다.
                strainSections = StrainSections(original = burden, atKey = burden),
                overallFit = unit(scores?.overall) ?: score,
                reasons = listOfNotNull(reason?.takeIf { it.isNotBlank() }).ifEmpty { listOf("곡 음역의 ${(score * 100).roundToInt()}%가 내 안정 음역과 겹쳐요") },
                scoringVersion = scores?.algorithmVersion ?: "backend-song-fit",
                compositionVersion = null,
            )
        }
    }
}
