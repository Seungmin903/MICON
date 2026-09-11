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
import com.micon.app.data.engine.EngineClient
import retrofit2.Response
import kotlin.math.roundToInt

/** 백엔드가 4xx/5xx 로 거절한 요청. [code] 는 `ApiResponse.code`(예 SONG_NOT_FOUND), 본문이 없으면 null. */
class BackendRequestException(val status: Int, val code: String?, message: String) : Exception(message)

/** 곡 카탈로그의 백엔드 구현. 경로는 `/api/v1/` 기준 상대 경로([BackendApi]). */
class RemoteSongRepository(private val api: BackendApi) : SongRepository {

    override suspend fun search(query: String, page: Int, size: Int): SongPage {
        val body = api.songs(query.trim(), page.coerceAtLeast(0), size.coerceIn(1, 100)).unwrap()
        return SongPage(
            items = body.content.map { it.toSummary() },
            page = body.page, size = body.size, totalElements = body.totalElements, totalPages = body.totalPages,
        )
    }

    override suspend fun detail(songId: Long): SongDetail? {
        val response = api.song(songId)
        if (response.code() == 404) return null
        return response.unwrap().toDetail()
    }

    override suspend fun practiceAssets(songId: Long, selectedKey: Int): PracticeAssets {
        val response = api.practiceAssets(songId, selectedKey)
        if (!response.isSuccessful) {
            val error = response.errorEnvelope()
            when (response.code()) {
                409 -> throw PracticeAssetsNotReadyException(error?.message ?: "연습 데이터가 아직 준비되지 않았습니다.")
                422 -> throw UnsupportedPracticeKeyException(error?.message ?: "지원하지 않는 연습 키입니다.")
            }
        }
        return response.unwrap().toAssets()
    }

    // ------------------------------------------------------------------ 매핑

    private fun SongSummaryDto.toSummary() = SongSummary(
        id = songId, title = title, artist = artists.joinToString(", "), imageUrl = imageUrl, durationMs = durationMs,
    )

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

    private fun PracticeAssetsDto.toAssets() = PracticeAssets(
        songId = songId,
        title = title,
        selectedKey = selectedKey,
        instrumental = InstrumentalMedia(
            mediaId = instrumental?.mediaId, filePath = instrumental?.filePath, mimeType = instrumental?.mimeType,
            fileBytes = instrumental?.fileBytes, sha256 = instrumental?.sha256, playbackUrl = instrumental?.playbackUrl,
        ),
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

    // ------------------------------------------------------------------ 응답 봉투

    private fun <T> Response<ApiResponse<T>>.unwrap(): T {
        if (code() == 401 || code() == 403) throw AuthSessionExpiredException()
        if (!isSuccessful) {
            val error = errorEnvelope()
            throw BackendRequestException(code(), error?.code, error?.message ?: "서버 요청에 실패했습니다 (HTTP ${code()}).")
        }
        return body()?.data ?: throw BackendRequestException(code(), body()?.code, "서버 응답에 데이터가 없습니다.")
    }

    private fun Response<*>.errorEnvelope(): ApiResponse<Any>? {
        val raw = errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { errorAdapter.fromJson(raw) }.getOrNull()
    }

    private companion object {
        val errorAdapter = EngineClient.moshi.adapter<ApiResponse<Any>>(
            com.squareup.moshi.Types.newParameterizedType(ApiResponse::class.java, Any::class.java),
        )
    }
}
