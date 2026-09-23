package com.micon.app.ui.report

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.range.FreeRangeCheckpoint
import com.micon.app.range.FreeRangeStage
import com.micon.app.range.RangeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Onboarding must never resume an interrupted free-range recording. */
class FreeRangeOnboardingRestartTest {
    private fun isolatedContext(): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "onboarding-range-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        return context to directory
    }

    private fun savedCheckpoint(repository: RangeRepository, id: String = UUID.randomUUID().toString()) {
        // A complete first stage would previously reopen on the second stage.
        repository.audio(id).writeBytes(ByteArray(44))
        repository.saveFreeCheckpoint(FreeRangeCheckpoint(id,
            listOf(FreeRangeStage("comfort", 0.0, 2.0, 60.0, 60.0, 0.5, 2.0)),
            noiseRms = .001, audioSeconds = 2.0))
        assertNotNull(repository.freeCheckpoint())
    }

    @Test fun onboardingReentryDiscardsInterruptedCheckpointAndStartsAtFirstStep() {
        val (context, directory) = isolatedContext()
        try {
            val repository = RangeRepository(context)
            val oldId = UUID.randomUUID().toString()
            savedCheckpoint(repository, oldId)

            val viewModel = FreeRangeViewModel(context, repository, onboarding = true)

            assertEquals("noise", viewModel.state.value.view.phase)
            assertEquals(0, viewModel.state.value.view.completed)
            assertNull(viewModel.state.value.savedId)
            assertNull(repository.freeCheckpoint())
            assertFalse(repository.audio(oldId).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun resultRetryRestartsRetainedOnboardingModelExactlyOncePerVersion() {
        val (context, directory) = isolatedContext()
        try {
            val repository = RangeRepository(context)
            val viewModel = FreeRangeViewModel(context, repository, onboarding = true)
            val interruptedId = UUID.randomUUID().toString()
            savedCheckpoint(repository, interruptedId)

            viewModel.restartMeasurement(1)
            assertEquals("noise", viewModel.state.value.view.phase)
            assertNull(repository.freeCheckpoint())
            assertFalse(repository.audio(interruptedId).exists())

            val laterId = UUID.randomUUID().toString()
            savedCheckpoint(repository, laterId)
            viewModel.restartMeasurement(1)
            assertNotNull(repository.freeCheckpoint()) // Duplicate effects must not erase a new run.
            assertTrue(repository.audio(laterId).exists())

            viewModel.restartMeasurement(2)
            assertNull(repository.freeCheckpoint())
            assertFalse(repository.audio(laterId).exists())
            assertEquals("noise", viewModel.state.value.view.phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun standaloneMeasurementStillRestoresItsCheckpoint() {
        val (context, directory) = isolatedContext()
        try {
            val repository = RangeRepository(context)
            val oldId = UUID.randomUUID().toString()
            savedCheckpoint(repository, oldId)

            val viewModel = FreeRangeViewModel(context, repository, onboarding = false)

            assertEquals("transition", viewModel.state.value.view.phase)
            assertEquals(1, viewModel.state.value.view.completed)
            assertNotNull(repository.freeCheckpoint())
            assertTrue(repository.audio(oldId).exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
