package com.micon.app.data.recommend

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SavedProfileSongFitTest {
    @Test fun requestsOnlyTheDisplayedProfileAndMapsItsScore() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = BackendClient(server.url("/api/v1/").toString(), false,
                MemoryTokenStore().apply { saveSession(42, "access", "refresh") }).api
            server.enqueue(response(9, "AVAILABLE"))
            val result = SavedProfileSongFit(api, "9").fit(17)
            assertEquals(.85f, checkNotNull(result).overallFit, .001f)
            val request = server.takeRequest()
            assertEquals("/api/v1/songs/17/fit?analysisId=9&keyOffset=0", request.path)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun unavailableOrMismatchedResultsNeverBecomeAnotherProfilesScore() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = BackendClient(server.url("/api/v1/").toString(), false,
                MemoryTokenStore().apply { saveSession(42, "access", "refresh") }).api
            val reader = SavedProfileSongFit(api, "9")
            server.enqueue(response(9, "UNAVAILABLE"))
            server.enqueue(response(10, "AVAILABLE"))
            assertNull(reader.fit(17))
            assertNull(reader.fit(17))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun response(profileId: Long, status: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":"SUCCESS","data":{"songId":17,"analysisId":$profileId,"evaluationStatus":"$status","originalKeyFit":{"score":85}}}""")
}
