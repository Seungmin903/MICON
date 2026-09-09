package com.micon.app.data.preferences

import com.micon.app.data.backend.dto.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query

data class PreferenceGenre(val genreId: Long = 0, val name: String = "")
data class PreferenceArtist(val artistId: Long = 0, val name: String = "", val imageUrl: String? = null)
data class PreferenceArtistPage(
    val content: List<PreferenceArtist> = emptyList(),
    val page: Int = 0,
    val size: Int = 30,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)
data class UserPreferences(
    val genreIds: List<Long> = emptyList(),
    val artistIds: List<Long> = emptyList(),
    val genres: List<PreferenceGenre> = emptyList(),
    val artists: List<PreferenceArtist> = emptyList(),
    val onboardingCompleted: Boolean = false,
)
data class SavePreferencesRequest(
    val genreIds: List<Long>,
    val artistIds: List<Long>,
    val onboardingCompleted: Boolean,
)

/** All paths share BackendClient's authenticated Retrofit and token refresh. */
interface PreferenceApi {
    @GET("genres")
    suspend fun genres(): Response<ApiResponse<List<PreferenceGenre>>>

    @GET("artists")
    suspend fun artists(
        @Query("query") query: String = "",
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 30,
    ): Response<ApiResponse<PreferenceArtistPage>>

    @GET("users/me/preferences")
    suspend fun preferences(): Response<ApiResponse<UserPreferences>>

    @PUT("users/me/preferences")
    suspend fun save(@Body request: SavePreferencesRequest): Response<ApiResponse<UserPreferences>>
}
