package com.micon.app.range

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.audio.PitchCapture
import com.micon.app.ui.report.RangeProfileChoiceActions
import com.micon.app.ui.report.RangeProfileChoiceMessage
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RangeProfileChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choiceButtonsWaitForSavedRecordingAndRemainDistinct() {
        val paused = mutableStateOf(false)
        var finishes = 0; var continues = 0
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                RangeProfileChoiceMessage(3, paused.value)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RangeProfileChoiceActions(paused.value, { finishes++ }, { continues++ })
                }
            }
        } }
        compose.onNodeWithText("보이스 프로필 생성 가능").assertIsDisplayed()
        compose.onNodeWithText("프로필 생성하기").assertIsNotEnabled()
        compose.onNodeWithText("추가로 분석하기").performClick()
        compose.runOnIdle { assertEquals(0, continues); paused.value = true }
        compose.onNodeWithText("추가로 분석하기").performClick()
        compose.onNodeWithText("프로필 생성하기").performClick()
        compose.runOnIdle { assertEquals(1, continues); assertEquals(1, finishes) }
    }

    @Test fun emptyChoiceDoesNotClaimAUsableProfile() {
        compose.setContent { MiconTheme { RangeProfileChoiceMessage(0, true) } }
        compose.onNodeWithText("측정 자료가 부족해요").assertIsDisplayed()
        compose.onNodeWithText("보이스 프로필 생성 가능").assertDoesNotExist()
    }

    @Test fun microphonePauseFreezesFileAndResumePreservesAudioClock() {
        compose.setContent { MiconTheme { RangeProfileChoiceMessage(1, true) } }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("range-pause-test-", ".wav", context.cacheDir)
        val capture = PitchCapture(fmin = 60.0, fmax = 1150.0)
        val errors = CopyOnWriteArrayList<Throwable>()
        try {
            val first = CountDownLatch(8)
            capture.start(file, onError = { errors.add(it) }) { first.countDown() }
            assertTrue(first.await(5, TimeUnit.SECONDS)); capture.stop()
            val duration = capture.capturedSeconds
            val size = file.length()
            assertTrue(duration > .1)
            Thread.sleep(300)
            assertEquals(size, file.length()); assertEquals(duration, capture.capturedSeconds, 0.0)
            val prefix = file.readBytes().copyOfRange(44, size.toInt())
            val resumed = CountDownLatch(8)
            val times = CopyOnWriteArrayList<Double>()
            capture.resume(file, onError = { errors.add(it) }) { times.add(it.t); resumed.countDown() }
            assertTrue(resumed.await(5, TimeUnit.SECONDS)); capture.stop()
            assertTrue(times.first() > duration)
            assertTrue(capture.capturedSeconds > duration)
            val bytes = file.readBytes()
            assertArrayEquals(prefix, bytes.copyOfRange(44, size.toInt()))
            assertEquals(bytes.size - 44, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            assertEquals((bytes.size - 44).toDouble() / (capture.recordingRate * 2), capture.capturedSeconds, .0001)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { capture.stop(); file.delete() }
    }
}
