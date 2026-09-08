package com.micon.app.data.local

import com.micon.app.data.RangeFit
import com.micon.app.data.RecommendMode
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendResult
import com.micon.app.data.RecommendedSong
import com.micon.app.data.SongFit
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.StrainSections
import com.micon.app.scoring.PitchProfile
import com.micon.app.scoring.RangeFitCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * 앱 안에서 계산하는 추천·적합도 — **오프라인·개발 경로**. 음역만 계산하고 음색은 null(평가 불가).
 *
 * 곡 쪽은 [SongRepository](sqlite) 의 analysis·karaokeNotes, 사용자 쪽은 [UserStore.profile] 의 안정 음역.
 * 서버 추천(RemoteRecommendRepository)이 붙어도 추천 목록 밖의 곡(검색·연습·찜에서 진입) 적합도에는 계속 쓸 수 있다.
 * 결과는 프로필이 바뀔 때까지 곡별로 캐시한다(목록·상세가 같은 값을 보게 — REC-005).
 */
class LocalRecommendRepository(private val songs: SongRepository) : RecommendRepository {
    private val mutex = Mutex()
    private var cachedFor: UserStore.VoiceProfile? = null
    private val cache = HashMap<String, SongFit?>()

    override suspend fun fit(songId: String): SongFit? {
        val profile = UserStore.profile.value ?: return null      // 프로필 없음 → 평가 불가
        mutex.withLock {
            if (cachedFor != profile) { cache.clear(); cachedFor = profile }
            if (cache.containsKey(songId)) return cache[songId]
        }
        val computed = compute(songId, profile)
        mutex.withLock { if (cachedFor == profile) cache[songId] = computed }
        return computed
    }

    private suspend fun compute(songId: String, profile: UserStore.VoiceProfile): SongFit? {
        val detail = songs.detail(songId) ?: return null
        val a = detail.analysis ?: return null                       // ERR-004: 곡 음역 정보 없음 → 평가 불가
        val coreLow = a.coreLowMidi ?: a.lowestMidi ?: return null
        val coreHigh = a.coreHighMidi ?: a.highestMidi ?: return null
        val notes = runCatching { songs.karaokeNotes(songId) }.getOrNull().orEmpty()
        val reliable = a.taskSupport?.get("pitch_range") !in setOf("fail", "not_analyzed")
        val song = PitchProfile.fromNotes(notes, reliable) ?: PitchProfile.ofStableRange(coreLow, coreHigh)
        val user = PitchProfile.ofStableRange(profile.comfortLow, profile.comfortHigh)
        val best = RangeFitCalculator.best(user, song)
        val strain = RangeFitCalculator.strainSectionsBoth(notes, best.keyShift, profile.comfortHigh)
        return SongFit(
            songId = songId,
            timbre = null,
            rangeFit = best.fit,
            recommendedKey = best.keyShift,
            strainSections = strain,
            overallFit = best.fit.score,   // TODO(§15 미확정): 종합 공식 확정 시 교체
            reasons = reasons(best.fit, best.keyShift, strain),
            scoringVersion = RangeFitCalculator.SCORING_VERSION,
            compositionVersion = null,
        )
    }

    override suspend fun recommend(mode: RecommendMode, limit: Int): RecommendResult {
        val profile = UserStore.profile.value
        val pool = songs.search("", analyzedOnly = true, limit = 300)
        if (mode == RecommendMode.RANGE && profile != null) {
            val ranked = pool.mapNotNull { s -> fit(s.id)?.let { s to it } }
                .sortedWith(compareByDescending<Pair<SongSummary, SongFit>> { it.second.overallFit }.thenBy { it.first.id })
                .take(limit)
            return RecommendResult(mode, personalized = true, items = ranked.mapIndexed { i, (s, f) -> RecommendedSong(i + 1, s, f, f.reasons) })
        }
        // 기본 추천 (REC-004): 프로필이 없거나, 로컬에 데이터가 없는 모드(음색). 개인화 문구를 쓰지 않는다.
        val preferred = UserStore.preferredGenres
        fun preferredGenre(s: SongSummary) = s.genre?.let(preferred::contains) == true
        val sorted = when (mode) {
            RecommendMode.POPULAR -> pool.sortedByDescending { it.releaseDate ?: "" }   // 인기 데이터 없음 → 최근 발매 순
            else -> pool.sortedWith(compareByDescending<SongSummary> { preferredGenre(it) }.thenByDescending { it.releaseDate ?: "" })
        }
        val items = sorted.take(limit).mapIndexed { i, s ->
            val reason = when {
                mode == RecommendMode.TIMBRE -> "음색 추천은 목소리 분석 후 제공돼요"
                preferredGenre(s) -> "선호 장르 ${s.genre}"
                s.releaseDate != null -> "${s.releaseDate.take(4)}년 발매"
                else -> "많이 연습하는 곡"
            }
            RecommendedSong(i + 1, s, fit = null, reasons = listOf(reason))
        }
        return RecommendResult(mode, personalized = false, items = items)
    }

    companion object {
        /** 음역 사유 문장 규칙 (REC-002). 음색 라벨은 서버 응답이 붙인다. */
        fun reasons(fit: RangeFit, key: Int, strain: StrainSections): List<String> = buildList {
            if (key == 0 && fit.coverage >= 0.9f) add("원키로 안정 음역 안에서 부를 수 있어요")
            if (key != 0) {
                val k = if (key > 0) "+$key" else "$key"
                val before = strain.original.size
                val after = strain.atKey.size
                add(
                    when {
                        before > 0 && after == 0 -> "키 $k 조정으로 고음 부담이 사라져요"
                        after < before -> "키 $k 조정으로 부담 구간 ${before}곳 → ${after}곳"
                        after == 0 -> "키 $k 로 곡의 중심음을 내 음역 중심에 맞춰요"
                        else -> "키 $k 로 중심음을 맞추되 부담 구간 ${after}곳은 남아요"   // 중심음 우선 조정: 부담이 줄지 않을 수 있음
                    },
                )
            }
            if (fit.centerScore >= 0.8f) add("곡의 중심음이 내 음역 중심과 가까워요")
            if (isEmpty()) add("음표의 ${(fit.coverage * 100).roundToInt()}%가 내 안정 음역 안에 있어요")
        }
    }
}
