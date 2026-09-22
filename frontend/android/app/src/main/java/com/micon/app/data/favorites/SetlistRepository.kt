package com.micon.app.data.favorites

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.SetlistDetailDto
import com.micon.app.data.backend.dto.SetlistNameRequest
import com.micon.app.data.backend.dto.SetlistOrderRequest
import com.micon.app.data.backend.dto.SetlistSongItemDto
import com.micon.app.data.backend.dto.SetlistSummaryDto
import com.micon.app.data.backend.unwrapData
import com.micon.app.data.backend.unwrapNullable

/** 세트리스트 요약(`SetlistResponse.Summary`). */
data class Setlist(val id: Long, val name: String, val songCount: Int, val createdAt: String?, val updatedAt: String?)

/** 세트리스트 곡(`SetlistResponse.SongItem`). [position] 은 서버 순서(0부터). */
data class SetlistSong(val songId: String, val title: String, val artist: String, val imageUrl: String?, val durationMs: Long?, val position: Int)

data class SetlistDetail(val id: Long, val name: String, val songs: List<SetlistSong>, val createdAt: String?, val updatedAt: String?)

/**
 * 세트리스트. 백엔드 `SetlistController`(`/setlists`)만 본다.
 *
 * 서버 규칙: 이름 1~100자(공백 제거), 곡 추가는 전체 곡 카탈로그에서 가능하며 남의 리스트는 404 SETLIST_NOT_FOUND.
 * 순서 변경은 정렬된 전체 곡 ID와 상세의 updatedAt 을 함께 보내 동시 수정 충돌을 검증한다.
 */
interface SetlistRepository {
    suspend fun list(): List<Setlist>
    suspend fun detail(setlistId: Long): SetlistDetail
    suspend fun create(name: String): Setlist
    suspend fun rename(setlistId: Long, name: String): Setlist
    suspend fun delete(setlistId: Long)
    suspend fun addSong(setlistId: Long, songId: String): SetlistDetail
    suspend fun removeSong(setlistId: Long, songId: String): SetlistDetail
    suspend fun reorderSongs(setlistId: Long, songIds: List<String>, updatedAt: String?): SetlistDetail

    companion object { const val NAME_MAX = 100 }
}

class RemoteSetlistRepository(private val api: BackendApi) : SetlistRepository {
    override suspend fun list(): List<Setlist> = api.setlists().unwrapData().map { it.toModel() }

    override suspend fun detail(setlistId: Long): SetlistDetail = api.setlist(setlistId).unwrapData().toModel()

    override suspend fun create(name: String): Setlist =
        api.createSetlist(SetlistNameRequest(name.trim().take(SetlistRepository.NAME_MAX))).unwrapData().toModel()

    override suspend fun rename(setlistId: Long, name: String): Setlist =
        api.renameSetlist(setlistId, SetlistNameRequest(name.trim().take(SetlistRepository.NAME_MAX))).unwrapData().toModel()

    override suspend fun delete(setlistId: Long) { api.deleteSetlist(setlistId).unwrapNullable() }

    override suspend fun addSong(setlistId: Long, songId: String): SetlistDetail = api.addSetlistSong(setlistId, songId).unwrapData().toModel()

    override suspend fun removeSong(setlistId: Long, songId: String): SetlistDetail = api.removeSetlistSong(setlistId, songId).unwrapData().toModel()

    override suspend fun reorderSongs(setlistId: Long, songIds: List<String>, updatedAt: String?): SetlistDetail =
        api.reorderSetlistSongs(setlistId, SetlistOrderRequest(songIds, updatedAt)).unwrapData().toModel()

    private companion object {
        fun SetlistSummaryDto.toModel() = Setlist(setlistId, name, songCount, createdAt, updatedAt)
        fun SetlistSongItemDto.toModel() = SetlistSong(songId, title, artists.joinToString(", "), imageUrl, durationMs, position)
        fun SetlistDetailDto.toModel() = SetlistDetail(setlistId, name, songs.sortedBy { it.position }.map { it.toModel() }, createdAt, updatedAt)
    }
}
