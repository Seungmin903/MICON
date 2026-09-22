package com.micon.app.ui.songdetail

import androidx.lifecycle.ViewModelStore
import com.micon.app.data.*
import com.micon.app.data.backend.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val players = mutableListOf<FakePlayer>()
    private val songs = FakeSongs()
    private val recommend = object : RecommendRepository {
        override suspend fun fit(songId: String): SongFit? = null
        override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList())
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }
    private fun model(recommend: RecommendRepository = this.recommend) = SongDetailViewModel(songs, recommend, "7", {
        FakePlayer().also { players += it }
    }, MutableStateFlow<UserStore.VoiceProfile?>(null)).also { store.put("detail", it) }

    @Test fun `recommended key is applied once and never overwrites a manual choice`() = runTest {
        var recommended = -2
        val changingRecommend = object : RecommendRepository {
            override suspend fun fit(songId: String) = fit(recommended)
            override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList())
        }
        val vm = model(changingRecommend); vm.enterScreen(); runCurrent()
        assertEquals(-2, vm.state.value.selectedKey)
        assertEquals(-2, players.single().keyShift)

        vm.selectKey(1)
        recommended = -3
        vm.refreshFit(); runCurrent()

        assertEquals(1, vm.state.value.selectedKey)
        assertEquals(1, players.single().keyShift)
        vm.leaveScreen()
    }

    @Test fun `pause on exit cancels buffering intent and releases once`() = runTest {
        val vm = model(); vm.enterScreen(); runCurrent()
        val player = players.single()
        player.ready = false
        vm.toggle()
        assertTrue(player.playRequested)
        vm.leaveScreen(); vm.leaveScreen()
        assertFalse(player.playRequested)
        assertFalse(vm.state.value.playing)
        assertEquals(1, player.releases)
        assertNull(player.onError)
        vm.toggle(); vm.listenAt(2.0)
        assertEquals(1, players.size)
        assertFalse(player.playRequested)
    }

    @Test fun `late assets after exit never create a player`() = runTest {
        val pending = CompletableDeferred<PracticeAssets>()
        songs.assets = { pending.await() }
        val vm = model(); vm.enterScreen(); runCurrent()
        vm.leaveScreen()
        pending.complete(assets(0)); runCurrent()
        assertTrue(vm.state.value.playable)
        assertTrue(players.isEmpty())
    }

    @Test fun `return refreshes original key URL and restores position and key without autoplay`() = runTest {
        val vm = model(); vm.enterScreen(); runCurrent()
        vm.selectKey(-2); runCurrent()
        val old = players.last()
        vm.listenAt(12.0)
        vm.leaveScreen()
        assertEquals(1, old.releases)
        vm.enterScreen(); runCurrent()
        assertEquals(listOf(0, 0), songs.requestedKeys)                 // 키를 바꿔도 서버엔 늘 원키만
        assertEquals(-2, vm.state.value.selectedKey)
        assertEquals(-2, players.last().keyShift)                       // 새 재생기에도 고른 키가 다시 적용
        assertEquals(12.0, players.last().positionSec, 0.001)
        assertFalse(players.last().playRequested)
        assertFalse(vm.state.value.playing)
        vm.leaveScreen()
    }

    @Test fun `key change shifts the preview locally without refetch and never double shifts notes`() = runTest {
        val vm = model(); vm.enterScreen(); runCurrent()
        vm.toggle()
        assertTrue(players.single().playRequested)
        vm.selectKey(-2); runCurrent()
        assertFalse(players.single().playRequested)                    // 키를 바꾸면 미리듣기는 멈춘다
        vm.selectKey(1); runCurrent()
        assertEquals(1, players.size)                                   // 재생기를 다시 만들지 않는다
        assertEquals(1, players.single().keyShift)
        assertEquals(1, vm.state.value.mediaKey)
        assertTrue(vm.state.value.playable)
        assertEquals(listOf(0), songs.requestedKeys)
        assertEquals(60, vm.state.value.notes.single().midi)          // 음표는 원키 그대로(표시에서만 옮김)
        assertTrue(players.single().url!!.endsWith("/0.wav"))
        vm.leaveScreen()
    }

    @Test fun `key is clamped to the realtime shift range`() = runTest {
        val vm = model(); vm.enterScreen(); runCurrent()
        vm.selectKey(-6); runCurrent()
        assertEquals(com.micon.app.scoring.ScoringConstants.KEY_SHIFT_MIN, vm.state.value.selectedKey)
        vm.selectKey(5); runCurrent()
        assertEquals(com.micon.app.scoring.ScoringConstants.KEY_SHIFT_MAX, vm.state.value.selectedKey)
        assertTrue(vm.state.value.practiceEnabled)
        vm.leaveScreen()
    }

    @Test fun `assets metadata survives either request completion order`() = runTest {
        val delayedDetail = CompletableDeferred<SongDetail>()
        songs.details = { delayedDetail.await() }
        val vm = model(); runCurrent()
        delayedDetail.complete(detail()); runCurrent()
        assertEquals("C major", vm.state.value.detail?.analysis?.keyName)
        assertEquals(108, vm.state.value.detail?.analysis?.tempoBpm)
        assertEquals(1, vm.state.value.detail?.analysis?.noteCount)
        assertTrue(players.isEmpty())
    }

    @Test fun `rejects mismatched assets and malformed notes`() = runTest {
        songs.assets = { assets(it).copy(songId = "999") }
        val vm = model(); vm.enterScreen(); runCurrent()
        assertFalse(vm.state.value.playable)
        assertTrue(players.isEmpty())
        songs.assets = { assets(it).copy(targetNotes = listOf(KaraokeNote(sourceStart = 1.0, sourceEnd = 2.0, midi = 999))) }
        vm.retryAssets(); runCurrent()
        assertTrue(vm.state.value.notes.isEmpty())
        assertFalse(vm.state.value.practiceEnabled)
        vm.leaveScreen()
    }

    @Test fun `second tap while buffering cancels pending playback`() = runTest {
        val vm = model(); vm.enterScreen(); runCurrent()
        players.single().ready = false
        vm.toggle(); vm.toggle()
        assertFalse(players.single().playRequested)
        assertFalse(vm.state.value.playing)
        vm.leaveScreen()
    }

    private class FakeSongs : SongRepository {
        var assets: suspend (Int) -> PracticeAssets = { SongDetailViewModelTest.assets(it) }
        var details: suspend () -> SongDetail = { detail() }
        val requestedKeys = mutableListOf<Int>()
        override suspend fun detail(songId: String) = details()
        override suspend fun practiceAssets(songId: String, selectedKey: Int): PracticeAssets {
            requestedKeys += selectedKey
            return assets(selectedKey)
        }
        override suspend fun search(query: String, page: Int, size: Int) = SongPage(emptyList(), page, size, 0, 0)
    }

    private class FakePlayer : SongPreviewPlayer {
        override var onError: ((String) -> Unit)? = null
        override var onEnded: (() -> Unit)? = null
        override var positionSec = 0.0
        override val durationSec = 30.0
        var ready = true
        override val isReady get() = ready
        override val isPlaying get() = ready && playRequested
        override var playRequested = false
        override var keyShift = 0
        var releases = 0
        var url: String? = null
        override fun load(url: String) { this.url = url }
        override fun seekTo(seconds: Double) { positionSec = seconds }
        override fun play() { playRequested = true }
        override fun pause() { playRequested = false }
        override fun release() { releases++ }
    }

    companion object {
        private fun detail() = SongDetail(SongSummary("7", "테스트", "가수", null, 30_000), emptyList(), null, false, true)
        private fun assets(key: Int) = PracticeAssets("7", "테스트", key,
            InstrumentalMedia(null, null, "audio/wav", null, null, "http://localhost/$key.wav"),
            null, null, listOf(KaraokeNote(sourceStart = 1.0, sourceEnd = 2.0, midi = 60)), "test", emptyList(),
            keyName = "C major", tempoBpm = 108, timeSignature = "4/4")
        private fun fit(key: Int) = SongFit(
            songId = "7", timbre = null,
            rangeFit = RangeFit(0.9f, 0.9f, 0f, 0f, 0.9f, 60, 70),
            recommendedKey = key, strainSections = null, overallFit = 0.9f,
            reasons = listOf("테스트"), scoringVersion = "test", compositionVersion = null,
        )
    }
}
