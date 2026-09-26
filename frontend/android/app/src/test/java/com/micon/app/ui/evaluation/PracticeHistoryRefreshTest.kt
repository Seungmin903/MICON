package com.micon.app.ui.evaluation

import androidx.lifecycle.viewModelScope
import com.micon.app.core.auth.SessionSnapshot
import com.micon.app.data.practice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeHistoryRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<PracticeHistoryViewModel>()
    @get:Rule val folder = TemporaryFolder()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun summary(id: Long) = PracticeSummary(id, "song-$id", "Song $id", null, 0,
        "FULL_SONG", null, "COMPLETED", "2026-09-18T10:00:00", null, 1000, "COMPLETED", 90.0)
    private fun page(id: Long) = PracticePage(listOf(summary(id)), 0, 50, 1, 1)
    private fun model(repo: FakePractices) = PracticeHistoryViewModel(repo).also(models::add)

    @Test fun reloadReplacesOldHistoryWithLatestServerRows() = runTest(dispatcher) {
        val repo = FakePractices()
        val vm = model(repo)
        vm.load(); advanceUntilIdle()
        repo.fetch = { page(2) }
        vm.load(); advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loading)
    }

    @Test fun previousSlowRequestCannotOverwriteTheReloadedHistory() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakePractices().apply {
            fetch = { withContext(NonCancellable) { gate.await() }; page(1) }
        }
        val vm = model(repo)
        vm.load(); runCurrent()
        repo.fetch = { page(2) }
        vm.load(); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun deletingServerRecordAlsoDeletesOnlyItsOwnersLocalRecording() = runTest(dispatcher) {
        val repo = FakePractices()
        val store = PracticeRecordingStore(folder.newFolder("recordings"))
        val source = folder.newFile("take.wav").apply { writeBytes(validWav()) }
        store.save(11, 1, source)
        store.save(12, 1, source)
        val vm = PracticeHistoryViewModel(repo, store,
            { SessionSnapshot("access", "refresh", 11, 1) }, dispatcher).also(models::add)
        vm.load(); advanceUntilIdle()

        vm.askDelete(summary(1)); vm.confirmDelete(); advanceUntilIdle()

        assertNull(store.find(11, 1))
        assertNotNull(store.find(12, 1))
    }

    @Test fun failedServerDeletePreservesTheLocalRecording() = runTest(dispatcher) {
        val repo = FakePractices().apply { remove = { error("server unavailable") } }
        val store = PracticeRecordingStore(folder.newFolder("recordings"))
        val source = folder.newFile("take.wav").apply { writeBytes(validWav()) }
        store.save(11, 1, source)
        val vm = PracticeHistoryViewModel(repo, store,
            { SessionSnapshot("access", "refresh", 11, 1) }, dispatcher).also(models::add)
        vm.load(); advanceUntilIdle()

        vm.askDelete(summary(1)); vm.confirmDelete(); advanceUntilIdle()

        assertNotNull(store.find(11, 1))
        assertEquals(listOf(1L), vm.state.value.items.map { it.id })
    }

    private fun validWav() = ByteArray(48).also {
        "RIFF".toByteArray().copyInto(it, 0)
        "WAVE".toByteArray().copyInto(it, 8)
    }

    private inner class FakePractices : PracticeRepository {
        var fetch: suspend () -> PracticePage = { page(1) }
        var remove: suspend (Long) -> Unit = {}
        override suspend fun history(page: Int, size: Int, date: String?) = fetch()
        override suspend fun scoreTrend(songId: String, limit: Int) = PracticeScoreTrend(
            songId, 0, null, null, null, null, emptyList(),
        )
        override suspend fun create(songId: String, selectedKey: Int, practiceScope: String): PracticeRecord = error("unused")
        override suspend fun complete(
            practiceId: Long,
            durationMs: Int,
            finalSelectedKey: Int?,
            keyChangeCount: Int?,
        ): PracticeRecord = error("unused")
        override suspend fun cancel(practiceId: Long): PracticeRecord = error("unused")
        override suspend fun savePerformance(practiceId: Long, result: PracticeResultInput): PracticeReport = error("unused")
        override suspend fun report(practiceId: Long): PracticeReport? = null
        override suspend fun delete(practiceId: Long) = remove(practiceId)
    }
}
