package com.micon.app.data.recommend

import com.micon.app.data.AxisScores
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
import com.micon.app.data.StrainSections
import com.micon.app.data.TimbreAxis
import com.micon.app.data.TimbreMatch
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import com.micon.app.data.recommend.dto.RecommendResultDto
import com.micon.app.data.recommend.dto.RecommendationDto
import com.micon.app.scoring.RangeFitCalculator
import java.io.File

/**
 * 추천 서버 응답을 [SongFit] 으로 매핑하는 저장소. 순서·비율·점수는 서버 값 그대로(재정렬·재계산 없음).
 *
 * - 서버가 다루지 않는 모드(장르·인기)와 녹음이 없을 때는 [local] 로 내려간다.
 * - 부담 구간은 서버가 내려주지 않으므로(인덱스에 음표 없음) 로컬 규칙으로 곡 음표에서 계산해 붙인다.
 * - [fit] 은 마지막 응답의 곡이면 그 값을, 아니면 로컬 계산을 준다 — 추천 목록 밖의 곡도 상세에서 값이 나오게.
 */
class RemoteRecommendRepository(
    private val gateway: RecommendGateway,
    private val songs: SongRepository,
    private val local: LocalRecommendRepository,
    /** 서버에 올릴 사용자 보컬 녹음. 없으면 서버 추천 불가 → 로컬 */
    private val recording: () -> File?,
) : RecommendRepository {
    private val lastFits = HashMap<String, SongFit>()

    override suspend fun fit(songId: String): SongFit? = lastFits[songId] ?: local.fit(songId)

    override suspend fun recommend(mode: RecommendMode, limit: Int): RecommendResult {
        val serverMode = when (mode) { RecommendMode.RANGE -> "range"; RecommendMode.TIMBRE -> "timbre"; else -> return local.recommend(mode, limit) }
        val audio = recording() ?: return local.recommend(mode, limit)
        val result = fetch(audio, limit, serverMode)
        val items = result.recommendations.mapNotNull { toItem(it) }
        remember(items)
        return RecommendResult(mode, personalized = true, items = items, languageGateAllowed = result.algorithm?.languageGate?.allowed)
    }

    /**
     * 서버 shelves 그대로(순서·곡 유지). challenge 는 뺀다. 녹음이 없으면 로컬.
     * TODO: 서버가 seed 파라미터를 받으면 [reshuffle] 을 넘겨 "새로운 발견"만 다시 뽑는다 (현재는 전체 재요청).
     */
    override suspend fun shelves(limit: Int, reshuffle: Int): RecommendShelves {
        val audio = recording() ?: return local.shelves(limit, reshuffle)
        val result = fetch(audio, limit, mode = "range")           // 서버가 프로파일 신뢰도를 보고 음역 조건을 켜고 끈다
        val shelves = result.shelves
            .filter { it.key != ShelfKeys.CHALLENGE }
            .map { s -> RecommendShelf(s.key, s.title, s.metric, s.items.mapNotNull { toItem(it) }) }
        remember(shelves.flatMap { it.items })
        return RecommendShelves(personalized = true, shelves = shelves)
    }

    private suspend fun fetch(audio: File, limit: Int, mode: String): RecommendResultDto {
        val job = gateway.awaitJob(
            gateway.recommend(audio, topK = limit, mode = mode, genres = UserStore.preferredGenres.toList(), preferredArtists = UserStore.preferredArtists.toList()).id,
        )
        val result = job.result ?: throw RecommendException("추천 결과가 비어 있습니다.")
        applyMeasuredProfile(result)      // 항목 매핑(부담 구간) 전에 — 실제 안정 음역으로 계산되게
        return result
    }

    /**
     * 서버가 녹음에서 관찰한 음역 4개(전체·주요)를 [UserStore.profile] 에 반영한다. 곡 분석 화면의 "내 음역 범위·안정 음역대"가 실제 값이 되는 경로.
     * 음역 외 항목(안정성·특성 등)은 건드리지 않는다 — 리포트 화면 소관. 신뢰도가 낮으면(reliable=false) 덮어쓰지 않는다.
     */
    private fun applyMeasuredProfile(result: RecommendResultDto) {
        val p = result.query?.pitchProfile ?: return
        if (!p.reliable) return
        val base = UserStore.profile.value ?: UserStore.VoiceProfile()
        UserStore.setProfile(
            base.copy(
                lowMidi = p.lowestMidi, highMidi = p.highestMidi,
                comfortLow = p.coreLowMidi, comfortHigh = p.coreHighMidi,        // 안정 음역 = 주요 음역(5~95 %) 근사 (용어집 §5)
                recommendLow = p.coreLowMidi, recommendHigh = p.coreHighMidi,
                measuredAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 서버 항목 → 앱 항목. 앱이 열 수 없는 곡(song_id 없음)은 null. 부담 구간은 로컬 음표가 있을 때만 붙인다. */
    private suspend fun toItem(dto: RecommendationDto): RecommendedSong? {
        val songId = dto.songId ?: return null
        val profile = UserStore.profile.value
        val detail = runCatching { songs.detail(songId) }.getOrNull()
        val summary = detail?.summary ?: RecommendMapper.summaryOf(songId, dto)
        val strain = if (profile != null && dto.recommendedKey != null && detail?.analysis != null) {
            val notes = runCatching { songs.karaokeNotes(songId) }.getOrNull().orEmpty()
            if (notes.isEmpty()) null else RangeFitCalculator.strainSectionsBoth(notes, dto.recommendedKey, profile.comfortHigh)
        } else null
        val fit = RecommendMapper.toFit(songId, dto, strain)
        return RecommendedSong(rank = dto.position, song = summary, fit = fit, reasons = fit.reasons)
    }

    /** 마지막 응답의 적합도를 상세 화면이 같은 값으로 보게(REC-005) */
    private fun remember(items: List<RecommendedSong>) {
        lastFits.clear()
        items.forEach { item -> item.fit?.let { lastFits[it.songId] = it } }
    }
}

/** 서버 DTO → 앱 모델. 순수 함수라 단위 테스트가 쉽다. */
object RecommendMapper {
    fun toFit(songId: String, dto: RecommendationDto, strain: StrainSections?): SongFit {
        val range = dto.rangeFit?.let { RangeFit(it.score, it.coverage, it.centerScore, it.spanFit, it.softFit, it.songCoreLowMidi, it.songCoreHighMidi) }
        val rangeReasons = if (range != null && dto.recommendedKey != null && strain != null) LocalRecommendRepository.reasons(range, dto.recommendedKey, strain) else emptyList()
        // 음색은 voice 섹션에서만 (axis·label 있음). 다른 섹션은 평가 불가(null)
        val timbre = if (dto.axis != null && dto.label != null) {
            TimbreMatch(
                axis = runCatching { TimbreAxis.valueOf(dto.axis) }.getOrDefault(TimbreAxis.CONSENSUS),
                label = dto.label,
                axisScores = dto.axisScores?.let { AxisScores(it.ecapa, it.singerByol, it.consensus) },
            )
        } else null
        val shelfReason = when (dto.shelf) {
            ShelfKeys.ORIGINAL_KEY -> "원키 그대로 부를 수 있어요"
            ShelfKeys.FAVORITE_ARTISTS -> "선호 가수와 닮은 목소리의 곡"
            ShelfKeys.CHALLENGE -> "고음 한 단계 도전"
            ShelfKeys.DISCOVERY -> "안 듣던 장르에서 부를 수 있는 곡"
            else -> "추천 곡"
        }
        return SongFit(
            songId = songId,
            timbre = timbre,
            rangeFit = range,
            recommendedKey = dto.recommendedKey,
            strainSections = strain,
            overallFit = dto.finalScore,                       // 서버 final_score 그대로. 클라이언트 계산 금지
            reasons = (listOfNotNull(dto.label) + rangeReasons).ifEmpty { listOf(shelfReason) },   // 음색 라벨이 첫 사유. 최소 1개(REC-002)
            scoringVersion = dto.scoringVersion,
            compositionVersion = dto.compositionVersion,
        )
    }

    /** 로컬 카탈로그에 없는 곡의 최소 요약 (서버 metadata 로) */
    fun summaryOf(songId: String, dto: RecommendationDto) = SongSummary(
        id = songId, tjNumber = dto.metadata?.tjId, title = dto.title, artist = dto.artist, album = dto.metadata?.albumName,
        durationMs = null, imageUrl = dto.metadata?.imageUrl, genre = dto.metadata?.genres?.firstOrNull(),
        language = dto.metadata?.language, releaseDate = dto.metadata?.releaseDate, analyzed = false,
    )
}
