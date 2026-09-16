package com.micon.app.data.recommend

import com.micon.app.data.SongFit
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.unwrapData
import com.micon.app.data.recommend.BackendRecommendRepository.Companion.toSongFit

/** The existing fit API's analysisId parameter identifies a saved profile. Never use another profile as fallback. */
class SavedProfileSongFit(private val api: BackendApi, profileId: String) {
    private val id = profileId.toLongOrNull()?.takeIf { it > 0 }

    suspend fun fit(songId: Long): SongFit? {
        val profile = id ?: return null
        val result = api.songFit(songId, profile, 0).unwrapData()
        if (result.evaluationStatus != "AVAILABLE" || result.analysisId != profile || result.songId != songId) return null
        return result.toSongFit(songId)
    }
}
