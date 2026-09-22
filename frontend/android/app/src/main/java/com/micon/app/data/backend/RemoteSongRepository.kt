package com.micon.app.data.backend

import com.micon.app.data.InstrumentalMedia
import com.micon.app.data.PracticeAssets
import com.micon.app.data.PracticeAssetsNotReadyException
import com.micon.app.data.PracticeSection
import com.micon.app.data.SongAnalysis
import com.micon.app.data.SongDetail
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.UnsupportedPracticeKeyException
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.PracticeAssetsDto
import com.micon.app.data.backend.dto.SongDetailDto
import com.micon.app.data.backend.dto.SongSummaryDto
import com.micon.app.core.auth.TokenManager
import retrofit2.Response
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

/** 백엔드가 4xx/5xx 로 거절한 요청. [code] 는 `ApiResponse.code`(예 SONG_NOT_FOUND), 본문이 없으면 null. */
class BackendRequestException(val status: Int, val code: String?, message: String) : Exception(message)

/** 곡 카탈로그의 백엔드 구현. 경로는 `/api/v1/` 기준 상대 경로([BackendApi]). */
class RemoteSongRepository(private val api: BackendApi) : SongRepository {
    private data class SearchKey(val userId: Long, val query: String, val genreId: Long?, val size: Int)
    private val firstPages = ConcurrentHashMap<SearchKey, SongPage>()
    @Volatile private var cachedUserId: Long? = null

    override suspend fun search(query: String, page: Int, size: Int): SongPage =
        search(query, page, size, null)

    override suspend fun search(query: String, page: Int, size: Int, genreId: Long?): SongPage {
        val normalizedQuery = query.trim()
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedSize = size.coerceIn(1, 100)
        val userId = TokenManager.getUserId()
        if (cachedUserId != userId) {
            firstPages.clear()
            cachedUserId = userId
        }
        val key = SearchKey(userId, normalizedQuery.lowercase(), genreId, normalizedSize)
        if (normalizedPage == 0) firstPages[key]?.let { return it }
        val body = api.songs(
            query = normalizedQuery,
            genreId = genreId,
            page = normalizedPage,
            size = normalizedSize,
        ).unwrapData()
        val result = SongPage(
            items = body.content.map { it.toSummary() },
            page = body.page, size = body.size, totalElements = body.totalElements, totalPages = body.totalPages,
        )
        if (normalizedPage == 0) firstPages[key] = result
        return result
    }

    override fun invalidateSearchCache(query: String, size: Int, genreId: Long?) {
        firstPages.remove(SearchKey(TokenManager.getUserId(), query.trim().lowercase(), genreId, size.coerceIn(1, 100)))
    }

    override suspend fun detail(songId: String): SongDetail? {
        val response = api.song(songId)
        if (response.code() == 404) return null
        return response.unwrapData().toDetail()
    }

    override suspend fun practiceAssets(songId: String, selectedKey: Int): PracticeAssets {
        val response = api.practiceAssets(songId, selectedKey)
        if (!response.isSuccessful) {
            val error = response.errorEnvelope()
            when (response.code()) {
                409 -> throw PracticeAssetsNotReadyException(error?.message ?: "연습 데이터가 아직 준비되지 않았습니다.")
                422 -> throw UnsupportedPracticeKeyException(error?.message ?: "지원하지 않는 연습 키입니다.")
            }
        }
        return response.unwrapData().toAssets()
    }

    override suspend fun instrumental(songId: String, selectedKey: Int): InstrumentalMedia {
        val response = api.instrumental(songId, selectedKey)
        if (!response.isSuccessful) {
            val error = response.errorEnvelope()
            when (response.code()) {
                409 -> throw PracticeAssetsNotReadyException(error?.message ?: "연습 데이터가 아직 준비되지 않았습니다.")
                422 -> throw UnsupportedPracticeKeyException(error?.message ?: "지원하지 않는 연습 키입니다.")
            }
        }
        val body = response.unwrapData()
        check(body.songId == songId && body.selectedKey == selectedKey) { "요청한 곡과 반주 정보가 일치하지 않습니다." }
        return body.instrumental.toMedia()
    }

    // ------------------------------------------------------------------ 매핑

    private fun SongDetailDto.toDetail(): SongDetail {
        val analysis = if (originalKey != null || bpm != null || lowestMidi != null || highestMidi != null) {
            SongAnalysis(
                keyName = originalKey,
                tempoBpm = bpm?.roundToInt(),
                timeSignature = null,
                lowestMidi = lowestMidi?.roundToInt(),
                highestMidi = highestMidi?.roundToInt(),
            )
        } else null
        return SongDetail(
            summary = SongSummary(
                id = songId, title = title, artist = artists.joinToString(", "), imageUrl = imageUrl, durationMs = durationMs,
                album = albumName, genre = genres.firstOrNull(), releaseDate = releaseDate,
            ),
            genres = genres,
            analysis = analysis,
            favorite = favorite,
            practiceReady = practiceReady,
        )
    }

    private fun com.micon.app.data.backend.dto.MediaDto?.toMedia() = InstrumentalMedia(
        mediaId = this?.mediaId, filePath = this?.filePath, mimeType = this?.mimeType,
        fileBytes = this?.fileBytes, sha256 = this?.sha256, playbackUrl = this?.playbackUrl,
        playbackUrlExpiresAt = this?.playbackUrlExpiresAt,
    )

    private fun PracticeAssetsDto.toAssets() = PracticeAssets(
        songId = songId,
        title = title,
        selectedKey = selectedKey,
        instrumental = instrumental.toMedia(),
        lyricsPlain = lyricsPlain,
        lyricsSynced = (lyricsSynced as? String)?.takeIf { it.isNotBlank() },
        targetNotes = targetNotes.orEmpty(),
        dataVersion = dataVersion,
        sections = sections.map {
            PracticeSection(
                sectionId = it.sectionId, sectionType = it.sectionType, sectionName = it.sectionName,
                startMs = it.startMs, endMs = it.endMs, sequenceNo = it.sequenceNo,
                difficultyType = it.difficultyType, difficultyScore = it.difficultyScore, description = it.description,
            )
        }.sortedBy { it.sequenceNo },
        keyName = keyName,
        tempoBpm = tempoBpm?.roundToInt(),
        timeSignature = timeSignature,
        beatGrid = beatGrid,
    )

}
