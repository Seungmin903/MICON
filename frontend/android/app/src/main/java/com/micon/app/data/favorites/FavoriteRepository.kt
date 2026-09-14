package com.micon.app.data.favorites

import com.micon.app.data.SongSummary
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.toSummary
import com.micon.app.data.backend.unwrapData
import com.micon.app.data.backend.unwrapNullable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 찜. 백엔드 `FavoriteController`(`/users/me/favorites`)만 본다 — 앱 안 임시 저장(UserStore.liked)은 삭제했다.
 *
 * [likedIds] 는 화면 하트가 구독하는 캐시다. [refresh] 가 서버 목록으로 채우고, [toggle] 은 먼저 바꾼 뒤(낙관적) 서버가 거절하면 되돌린다.
 * 모두 로그인 필요(비회원 401 → AuthSessionExpiredException). 비회원 화면은 이 저장소를 부르지 않는다.
 */
interface FavoriteRepository {
    val likedIds: StateFlow<Set<Long>>

    /** 서버 찜 목록으로 캐시를 새로 채우고 곡 요약을 돌려준다. */
    suspend fun refresh(): List<SongSummary>

    /** 찜 ↔ 해제. 낙관적으로 캐시를 먼저 바꾸고 실패하면 되돌린 뒤 예외를 던진다. @return 바뀐 뒤 찜 여부 */
    suspend fun toggle(songId: Long): Boolean

    /** 로그아웃·계정 전환 때 캐시를 비운다. */
    fun clear()
}

class RemoteFavoriteRepository(private val api: BackendApi) : FavoriteRepository {
    private val _liked = MutableStateFlow<Set<Long>>(emptySet())
    override val likedIds: StateFlow<Set<Long>> = _liked.asStateFlow()

    override suspend fun refresh(): List<SongSummary> {
        val songs = api.favorites().unwrapData().songs.map { it.toSummary() }
        _liked.value = songs.map { it.id }.toSet()
        return songs
    }

    override suspend fun toggle(songId: Long): Boolean {
        val liking = songId !in _liked.value
        _liked.update { if (liking) it + songId else it - songId }
        try {
            if (liking) api.addFavorite(songId).unwrapNullable() else api.removeFavorite(songId).unwrapNullable()
        } catch (e: Exception) {
            _liked.update { if (liking) it - songId else it + songId }
            throw e
        }
        return liking
    }

    override fun clear() { _liked.value = emptySet() }
}
