package com.micon.app.ui.songdetail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.*
import com.micon.app.data.backend.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.PlaybackLifecycleEffect
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Plays a generated local tone through the real Media3 engine; no real account/song/network. */
class SongDetailPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val store = ViewModelStore()
    private val players = mutableListOf<TrackedPlayer>()
    private lateinit var vm: SongDetailViewModel

    @Before fun setUp() {
        server.start()
        repeat(6) { server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(toneWav()))) }
        // MockWebServer.url resolves its host; keep that DNS work off Android's main thread.
        val playbackUrl = server.url("/tone.wav").toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songs = object : SongRepository {
            override suspend fun detail(songId: Long) = SongDetail(SongSummary(7, "재생 테스트", "합성 음원", null, 15_000), emptyList(), null, false, true)
            override suspend fun search(query: String, page: Int, size: Int) = SongPage(emptyList(), page, size, 0, 0)
            override suspend fun practiceAssets(songId: Long, selectedKey: Int) = PracticeAssets(7, "재생 테스트", selectedKey,
                InstrumentalMedia(null, null, "audio/wav", null, null, playbackUrl),
                null, null, listOf(KaraokeNote(0.0, 15.0, 69)), "test", emptyList())
        }
        val recommendations = object : RecommendRepository {
            override suspend fun fit(songId: Long): SongFit? = null
            override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList())
        }
        main {
            vm = SongDetailViewModel(songs, recommendations, 7, {
                TrackedPlayer(Media3SongPreview(context, OkHttpClient())).also { players += it }
            }, MutableStateFlow<UserStore.VoiceProfile?>(null))
            store.put("test-detail", vm)
        }
    }

    @After fun tearDown() { main { store.clear() }; server.shutdown() }

    @Test fun navigationStopsRealPlaybackAndBackDoesNotAutoplay() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "detail") {
                composable("detail") {
                    PlaybackLifecycleEffect(vm, vm::enterScreen, vm::leaveScreen)
                    Column {
                        Button(onClick = vm::toggle) { Text("재생 테스트") }
                        // Do not explicitly stop here: tests NavBackStackEntry ON_PAUSE itself.
                        Button(onClick = { nav.navigate("other") }) { Text("다른 페이지") }
                    }
                }
                composable("other") { Button(onClick = { nav.popBackStack() }) { Text("상세로 복귀") } }
            }
        }
        startAndVerifyPlayback()
        val old = main { players.last() }
        compose.onNodeWithText("다른 페이지").performClick()
        compose.waitUntil(5_000) { main { old.released && !vm.state.value.playing } }
        assertTrue(main { old.pausedBeforeRelease })
        compose.onNodeWithText("상세로 복귀").performClick()
        compose.waitUntil(10_000) { main { players.last() !== old && players.last().isReady } }
        assertFalse(main { players.last().playRequested })
        assertFalse(main { players.last().isPlaying })
    }

    @Test fun backgroundAndCompositionDisposalStopRealPlayback() {
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) {
                PlaybackLifecycleEffect(vm, vm::enterScreen, vm::leaveScreen)
                Button(onClick = vm::toggle) { Text("재생 테스트") }
            }
        }
        startAndVerifyPlayback()
        val old = main { players.last() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(main { old.released && old.pausedBeforeRelease })
        assertFalse(main { vm.state.value.playing })
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { main { players.last() !== old && players.last().isReady } }
        assertFalse(main { players.last().playRequested })
        startAndVerifyPlayback()
        val current = main { players.last() }
        main { visible.value = false }
        compose.waitUntil(5_000) { main { current.released } }
        assertTrue(main { current.pausedBeforeRelease })
    }

    private fun startAndVerifyPlayback() {
        compose.waitForIdle()
        try {
            compose.waitUntil(10_000) { main { players.lastOrNull()?.isReady == true } }
        } catch (e: Throwable) {
            val snapshot = main { "preview=${vm.state.value}, players=${players.size}, requests=${server.requestCount}, lifecycle=${compose.activity.lifecycle.currentState}" }
            android.util.Log.e("PlaybackTest", snapshot, e)
            throw AssertionError(snapshot, e)
        }
        compose.onNodeWithText("재생 테스트").performClick()
        compose.waitUntil(10_000) { main { players.last().isPlaying && players.last().positionSec > .2 } }
        assertTrue(main { vm.state.value.playing })
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private class TrackedPlayer(private val media: SongPreviewPlayer) : SongPreviewPlayer by media {
        var released = false
        var pausedBeforeRelease = false
        override fun release() {
            pausedBeforeRelease = !media.playRequested
            media.release()
            released = true
        }
    }

    private fun toneWav(): ByteArray {
        val rate = 16_000
        val samples = rate * 15
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((sin(2 * PI * 440 * it / rate) * 1_000).toInt().toShort()) }
        }.array()
    }
}
