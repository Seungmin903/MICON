package com.micon.app.data.backend

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.core.network.Json
import com.micon.app.data.backend.dto.PracticeCreateRequest
import com.micon.app.data.backend.dto.SongSummaryDto
import com.micon.app.data.backend.dto.VoiceAnalysisCreateRequest
import com.micon.app.ui.navigation.Routes
import com.micon.app.ui.navigation.loginReturnDestination
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SongIdContractTest {
    private val id = "e8873464-9cc0-5dbe-b3ac-f561fd55eaa5"

    @Test fun uuidSurvivesJsonNavigationAndRequestBodies() {
        val adapter = Json.moshi.adapter(SongSummaryDto::class.java)
        assertEquals(id, adapter.fromJson("""{"songId":"$id"}""")!!.songId)
        // A new app can still read the previous numeric server and saved metadata.
        assertEquals("42", adapter.fromJson("""{"songId":42}""")!!.songId)
        assertEquals("song/$id", Routes.song(id))
        assertEquals("practice/$id?selectedKey=-2", Routes.practice(id, -2))
        assertEquals("report/sing/$id", Routes.singAnalysis(id))
        assertEquals("match/$id", loginReturnDestination(Routes.SONG_MATCH, id))
        val practice = Json.moshi.adapter(PracticeCreateRequest::class.java).toJson(PracticeCreateRequest(id, 0))
        assertTrue(practice.contains("\"songId\":\"$id\""))
        val voice = Json.moshi.adapter(VoiceAnalysisCreateRequest::class.java).toJson(VoiceAnalysisCreateRequest(57, "SONG", id))
        assertTrue(voice.contains("\"songId\":\"$id\""))
        assertTrue(voice.contains("\"recordingId\":57"))
        assertNull(loginReturnDestination(Routes.SONG, "../outside"))
    }

    @Test fun uuidDetailRequestAndResponseRemainIdentical() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"code":"SUCCESS","data":{"songId":"$id","title":"천상연","artists":["이창섭"],"practiceReady":true}}"""))
            val api = BackendClient(server.url("/api/v1/").toString(), false, MemoryTokenStore()) {}.api
            assertEquals(id, RemoteSongRepository(api).detail(id)!!.summary.id)
            assertEquals("/api/v1/songs/$id", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
