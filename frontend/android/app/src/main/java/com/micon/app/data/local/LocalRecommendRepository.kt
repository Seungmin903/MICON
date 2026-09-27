package com.micon.app.data.local

import com.micon.app.data.RangeFit
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelf
import com.micon.app.data.RecommendShelves
import com.micon.app.data.RecommendedSong
import com.micon.app.data.ShelfKeys
import com.micon.app.data.SongFit
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.StrainSections
import com.micon.app.scoring.PitchProfile
import com.micon.app.scoring.RangeFitCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 앱 안에서 계산하는 추천·적합도 — **오프라인·개발 경로**. 음역만 계산하고 음색은 null(평가 불가).
 *
 * 곡 쪽은 [SongRepository](백엔드) 의 analysis·karaokeNotes, 사용자 쪽은 [UserStore.profile] 의 안정 음역.
 * 서버 추천(BackendRecommendRepository)이 붙어도 추천 목록 밖의 곡(검색·연습·찜에서 진입) 적합도에는 계속 쓸 수 있다.
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
        if (profile.voiceQuality != null &&
            com.micon.app.range.VoiceQualityComfortRange.find(profile.voiceQuality, profile.lowMidi, profile.highMidi) == null) return null
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

    /**
     * 서버 shelves-v1 의 오프라인 근사. 음색이 없어 voice 는 음역 적합도 순, favorite_artists 는 선호 가수 본인 곡만(가수 유사도 없음).
     * 한 곡은 한 섹션에만(우선순위 voice → original_key → favorite_artists → discovery).
     * 프로필(측정된 음역)이 없으면 추천할 근거가 없으므로 빈 목록 — 목소리 분석을 마쳐야 추천이 생긴다.
     */
    override suspend fun shelves(limit: Int, reshuffle: Int): RecommendShelves {
        val profile = UserStore.profile.value ?: return RecommendShelves.empty(RecommendShelves.NOTE_ANALYSIS_REQUIRED)
        val pool = songs.search("", size = POOL_SIZE).items
        val fits = pool.mapNotNull { s -> fit(s.id)?.let { s to it } }
        val used = HashSet<String>()
        // 섹션끼리 곡을 나눠 갖는다. 개발용 카탈로그는 분석된 곡이 50곡뿐이라, 첫 섹션이 limit 만큼 다 가져가면 뒷 섹션이 빈다.
        val quota = minOf(limit, (fits.size / SHELF_COUNT).coerceAtLeast(MIN_PER_SHELF))
        fun shelf(key: String, title: String, metric: String?, ranked: List<Pair<SongSummary, SongFit>>): RecommendShelf {
            val picked = ranked.filter { it.first.id !in used }.take(quota)
            used += picked.map { it.first.id }
            return RecommendShelf(key, title, metric, picked.mapIndexed { i, (s, f) -> RecommendedSong(i + 1, s, f, f.reasons) })
        }
        fun coverage(f: SongFit) = f.rangeFit?.coverage ?: 0f
        // 새로운 발견: 하루 단위로 섞이고, 다시 뽑을 때마다 또 달라진다.
        // reshuffle 을 큰 소수로 곱해 연속된 값이 비슷한 순서를 내지 않게 한다.
        val daySeed = System.currentTimeMillis() / 86_400_000L * 1_000_003L + reshuffle * 2_654_435_761L
        return RecommendShelves(
            personalized = true,
            // 빈 섹션은 빼서 페이지가 헛돌지 않게 (선호 가수를 안 골랐으면 그 섹션이 통째로 없다)
            shelves = listOfNotNull(
                shelf(ShelfKeys.VOICE, "내 음역에 맞는 곡", "음색 추천은 서버 연결 후. 지금은 음역 적합도 순", fits.sortedByDescending { it.second.overallFit }),
                shelf(
                    ShelfKeys.ORIGINAL_KEY, "키 안 바꾸고 바로 부를 곡", "최적 키 0 · 원키 커버리지 ≥ 0.95",
                    fits.filter { it.second.recommendedKey == 0 && coverage(it.second) >= 0.95f }.sortedByDescending { it.second.rangeFit?.centerScore ?: 0f },
                ),
                shelf(ShelfKeys.FAVORITE_ARTISTS, "선호 가수 좋아하면", "선호 가수 본인 곡", fits.filter { it.first.artist in UserStore.preferredArtists }),
                shelf(
                    ShelfKeys.DISCOVERY, "새로운 발견", "선택 안 한 장르 중 부를 수 있는 곡",
                    fits.filter { (s, f) -> s.genre != null && s.genre !in UserStore.preferredGenres && coverage(f) >= 0.9f }.shuffled(Random(daySeed)),
                ),
            ).filter { it.items.isNotEmpty() },
        )
    }

    companion object {
        private const val SHELF_COUNT = 4
        private const val MIN_PER_SHELF = 8
        /** 오프라인 근사에 쓰는 곡 수. 곡마다 상세를 한 번씩 읽으므로 서버 페이지 최대(100)로 묶는다. */
        private const val POOL_SIZE = 100

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
