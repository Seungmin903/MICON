package com.micon.app.data.preferences

import com.micon.app.data.backend.dto.ApiResponse
import retrofit2.Response

interface PreferenceRepository {
    suspend fun getGenres(): List<PreferenceGenre>
    suspend fun getArtists(query: String, page: Int): PreferenceArtistPage
    suspend fun getPreferences(): UserPreferences
    suspend fun savePreferences(request: SavePreferencesRequest): UserPreferences
}

class PreferenceSessionExpiredException : Exception("로그인이 만료됐어요. 다시 로그인해주세요.")
class PreferenceRequestException(val status: Int?, message: String) : Exception(message)

class NetworkPreferenceRepository(private val api: PreferenceApi) : PreferenceRepository {
    override suspend fun getGenres() = api.genres().dataOrThrow()
    override suspend fun getArtists(query: String, page: Int) = api.artists(query, page).dataOrThrow()
    override suspend fun getPreferences() = api.preferences().dataOrThrow()
    override suspend fun savePreferences(request: SavePreferencesRequest) = api.save(request).dataOrThrow()

    private fun <T> Response<ApiResponse<T>>.dataOrThrow(): T {
        if (code() == 401 || code() == 403) throw PreferenceSessionExpiredException()
        if (!isSuccessful) {
            val message = when (code()) {
                400, 404, 409, 422 -> "선택 정보를 확인해주세요. 목록을 다시 불러온 뒤 저장해주세요."
                else -> "서버에 연결하지 못했어요. 잠시 후 다시 시도해주세요."
            }
            throw PreferenceRequestException(code(), message)
        }
        return body()?.data ?: throw PreferenceRequestException(code(), "응답을 확인할 수 없어요. 다시 시도해주세요.")
    }
}
