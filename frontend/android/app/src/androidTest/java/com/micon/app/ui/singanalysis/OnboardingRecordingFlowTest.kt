package com.micon.app.ui.singanalysis

import android.Manifest
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.*
import com.micon.app.data.onboarding.VoiceOnboardingInput
import com.micon.app.data.singanalysis.*
import com.micon.app.data.voice.*
import com.micon.app.range.*
import com.micon.app.ui.singanalysis.SingAnalysisViewModel.Phase
import com.micon.app.ui.theme.MiconTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real emulator microphone and Media3 playback, with generated MR and isolated local files. */
class OnboardingRecordingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private val songId = "d221afea-6dc1-5223-a6bc-68fa055b4820"
    private lateinit var vm: SingAnalysisViewModel
    private lateinit var recordings: SingAnalysisRecordingStore
    private lateinit var directory: File

    @Before fun setUp() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        directory = File(context.cacheDir, "song-guide-recording-${UUID.randomUUID()}")
        recordings = SingAnalysisRecordingStore(context, storageDirectory = directory)
        server.start()
        repeat(5) { server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(silentWav()))) }
        val url = server.url("/generated-mr.wav").toString()
        val songs = object : SongRepository {
            override suspend fun detail(songId: String) = SongDetail(SongSummary(songId, "테스트 곡", "합성 MR", null,
                55_000), emptyList(), null, false, true)
            override suspend fun search(query: String, page: Int, size: Int) = SongPage(emptyList(), page, size, 0, 0)
            override suspend fun practiceAssets(songId: String, selectedKey: Int) = PracticeAssets(songId, "테스트 곡", 0,
                InstrumentalMedia(null, null, "audio/wav", null, null, url), "테스트 가사",
                "[00:00.00] 테스트 가사\n[00:54.00] 마지막 가사", emptyList(),
                "test", emptyList(), tempoBpm = 120)
        }
        val submitter = object : SongAnalysisSubmitter {
            override suspend fun submit(recording: SavedRecording, onProgress: (String) -> Unit): SubmitResult =
                error("This test must not upload or analyze")
        }
        val voice = object : VoiceAnalysisRepository {
            override suspend fun upload(file: File, mimeType: String, attemptNo: Int, onProgress: (Float) -> Unit): Long = error("unused")
            override suspend fun create(recordingId: Long, songId: String?, method: String): VoiceAnalysis = error("unused")
            override suspend fun get(analysisId: Long): VoiceAnalysis = error("unused")
            override suspend fun latest(): VoiceAnalysis? = error("unused")
            override suspend fun report(analysisId: Long): VoiceAnalysisReport = error("unused")
        }
        main {
            vm = SingAnalysisViewModel(context, songId, songs, OkHttpClient(), recordings, submitter, voice, "test-draft")
            models.put("recording", vm)
        }
        try {
            compose.waitUntil(12_000) { main { vm.state.value.phase == Phase.SETUP && vm.player.isReady } }
        } catch (failure: Throwable) {
            throw AssertionError(main { "test MR not ready: state=${vm.state.value}, ready=${vm.player.isReady}, requests=${server.requestCount}" }, failure)
        }
        main { vm.choose(vm.state.value.plans.first { it.enabled }) }
        compose.waitUntil(12_000) { main { vm.state.value.phase == Phase.RUNNING } }
    }

    @After fun tearDown() { main { models.clear() }; server.shutdown(); directory.deleteRecursively() }

    @Test fun finishFromRunningPausesAndCancelResumesSameWavThenConfirmationSavesOnce() {
        main { vm.requestFinish() }
        assertFalse(main { vm.state.value.confirmEnd })
        assertEquals(Phase.RUNNING, main { vm.state.value.phase })
        // Refresh snapshots at interactions: the live 33ms progress flow otherwise prevents
        // Espresso from becoming idle long enough to assert the pre-30-second UI.
        val displayed = mutableStateOf(main { vm.state.value })
        compose.setContent { MiconTheme {
            val state = displayed.value
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (state.isSinging) SingingBody(state, vm)
                    }
                }
                if (state.confirmEnd) FinishDialog(state.sungSeconds, vm::confirmFinish, vm::cancelFinish,
                    onDiscard = { fail("no discard action") }, onboarding = true)
            }
        } }
        compose.onNodeWithTag("sing-finish").assertIsDisplayed().assertIsNotEnabled()
        compose.waitUntil(45_000) { main { vm.state.value.canFinish } }
        main { displayed.value = vm.state.value }
        assertEquals(Phase.RUNNING, main { vm.state.value.phase })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "onboarding-singing-large-font.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("여기까지 부르기").assertIsDisplayed().performClick()
        main { vm.requestFinish(); displayed.value = vm.state.value }
        assertTrue(main { vm.state.value.confirmEnd })
        assertEquals(Phase.PAUSED, main { vm.state.value.phase })
        assertFalse(main { vm.player.isPlaying })
        compose.onNodeWithText("정말 마치겠습니까?").assertIsDisplayed()
        compose.onNodeWithText("저장하지 않고 나가기").assertDoesNotExist()
        val seconds = main { vm.state.value.elapsedRecSec }
        val wav = directory.listFiles()!!.single { it.extension == "wav" }
        val length = wav.length()
        SystemClock.sleep(600)
        assertEquals(seconds, main { vm.state.value.elapsedRecSec }, .001)
        assertEquals(length, wav.length())
        compose.onNodeWithText("계속 부르기").assertIsDisplayed().performClick()
        main { vm.cancelFinish(); displayed.value = vm.state.value }
        compose.waitUntil(12_000) { main { vm.state.value.phase == Phase.RUNNING && vm.state.value.elapsedRecSec > seconds + .3 } }
        main { displayed.value = vm.state.value }
        assertEquals(wav, directory.listFiles()!!.single { it.extension == "wav" })
        compose.onNodeWithText("여기까지 부르기").performClick()
        main { displayed.value = vm.state.value }
        // BufferedOutputStream flushes the appended PCM when the confirmation pauses capture.
        assertTrue(wav.length() > length)
        compose.onNodeWithText("여기까지 마치기").assertIsDisplayed().performClick()
        main { vm.confirmFinish(); displayed.value = vm.state.value }
        compose.waitUntil(12_000) { main { vm.state.value.phase == Phase.DONE } }
        val saved = main { checkNotNull(vm.state.value.saved) }
        assertEquals(songId, saved.meta.songId)
        assertEquals(wav, saved.wav)
        assertEquals(1, recordings.list().size)
        val range = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
            "completed", "FOUND", "FOUND", "available", verification = "device_measured")
        assertNull(VoiceOnboardingInput.problem("test-draft", range, saved))
        main { vm.cancelFinish(); vm.requestFinish() }
        assertEquals(Phase.DONE, main { vm.state.value.phase })
        assertFalse(main { vm.state.value.confirmEnd })
    }

    @Test fun interruptedRecordingCannotOpenConfirmationOrSave() {
        main { vm.interruptRecording(); vm.requestFinish(); vm.confirmFinish(); vm.cancelFinish() }
        assertEquals(Phase.SETUP, main { vm.state.value.phase })
        assertFalse(main { vm.state.value.canFinish || vm.state.value.confirmEnd || vm.state.value.recordingValid })
        assertTrue(recordings.list().isEmpty())
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun silentWav(): ByteArray {
        val rate = 16_000
        val bytes = rate * 55 * 2
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
    }
}
