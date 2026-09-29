package com.micon.app.ui.recommend

import androidx.lifecycle.ViewModelStore
import com.micon.app.data.*
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher); UserStore.clearVoiceData() }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private object Songs : SongRepository {
        override suspend fun search(query: String, page: Int, size: Int) = SongPage(emptyList(),page,size,0,0)
        override suspend fun detail(songId: String): SongDetail? = null
        override suspend fun practiceAssets(songId: String, selectedKey: Int): PracticeAssets = error("unused")
    }
    private class Recs : RecommendRepository {
        var calls = 0
        var requestedLimit = 0
        var fetch: suspend (Int) -> RecommendShelves = { result("new") }
        override suspend fun fit(songId: String): SongFit? = null
        override suspend fun shelves(limit: Int, reshuffle: Int): RecommendShelves {
            requestedLimit = limit
            return fetch(++calls)
        }
    }
    private fun vm(recs: Recs) = RecommendViewModel(Songs,recs).also { models.put("test",it) }
    @Test fun profileChangeDropsAnOldLateResponse() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val recs = Recs().apply { fetch = { n ->
            if (n == 1) { withContext(NonCancellable) { gate.await() }; result("old") }
            else result("new")
        } }
        val vm=vm(recs); runCurrent(); vm.onVisible(); runCurrent()
        UserStore.setProfile(UserStore.VoiceProfile(measuredAt=10)); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("new",vm.state.value.shelves.single().key)
    }
    @Test fun generationIsPolledUntilCompleteAndStopsWhenHidden() = runTest(dispatcher) {
        val recs=Recs().apply { fetch={ n -> if (n < 2) RecommendShelves(true, emptyList(), "추천 생성 중", generating = true) else result("n$n") } }
        val vm=vm(recs); runCurrent(); vm.onVisible(); runCurrent()
        assertEquals(1,recs.calls)
        assertEquals(15,recs.requestedLimit)
        assertTrue(vm.state.value.generating)
        assertTrue(vm.state.value.shelves.isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2,recs.calls)
        assertFalse(vm.state.value.generating)
        assertEquals("n2",vm.state.value.shelves.single().key)
        vm.onHidden(); advanceTimeBy(10_000); runCurrent(); assertEquals(2,recs.calls)
        vm.onVisible(); runCurrent(); assertEquals(3,recs.calls)
    }
    @Test fun deletionClearsRetainedTabStateEvenWhileHidden() = runTest(dispatcher) {
        val recs=Recs(); val vm=vm(recs); runCurrent(); vm.onVisible(); runCurrent()
        assertFalse(vm.state.value.shelves.isEmpty())
        vm.onHidden(); UserStore.clearVoiceData(); runCurrent()
        assertTrue(vm.state.value.shelves.isEmpty())
        assertFalse(vm.state.value.personalized)
        assertEquals(1,recs.calls)
    }
    companion object {
        private fun result(key:String)=RecommendShelves(true,listOf(RecommendShelf(key,key,null,emptyList())))
    }
}
