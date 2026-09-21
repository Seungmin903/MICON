package com.micon.app.ui.report

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.local.UserStore
import com.micon.app.data.onboarding.*
import com.micon.app.range.*
import com.micon.app.ui.theme.MiconTheme
import com.micon.app.ui.theme.Purple
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Display fixtures only: no account, saved profile, recording, or network is modified. */
class SavedVoiceReportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val range = RangeReport(RangeProtocol.VERSION, listOf(
        RangeNote(55, "passed", RangeMetrics(5.0, 8.0, 1.0, 1.0, 2.0, stabilityScore = 80.0)),
        RangeNote(67, "passed", RangeMetrics(8.0, 9.0, 1.0, 1.0, 1.8, stabilityScore = 40.0))),
        55, 67, emptyList(), "completed", "FOUND", "FOUND", "available", backendSessionId = 7)
    private val progress = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
        byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.COMPLETED,
        artists = listOf(SimilarVoiceArtist(1, "첫 번째 가수", null, .9),
            SimilarVoiceArtist(2, "두 번째 가수", null, .8)),
        rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.SAVED)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { content() }
        } }
    }

    private fun removedCardsAreAbsent() {
        listOf("음색 분석 결과", "음색 분석", "내 목소리에 맞는 음악", "곡 적합도", "종합 보컬 특성",
            "인정 유지", "완충", "시작 높이 대비 흔들림", "엄격한 연속 유지", "실제 발성", "목표음 유지율").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun savedReportMovesPianoIntoMapAndShowsActualArtistResults() {
        show { SavedVoiceReportContent(range, progress) }
        compose.onNodeWithText("나와 닮은 가수").assertIsDisplayed()
        compose.onNodeWithTag("voice-similarity-graph-card").assertExists()
        compose.onNodeWithContentDescription("나를 중심으로 닮은 가수를 연결한 목소리 유사도 그래프").assertExists()
        compose.onNodeWithText("첫 번째 가수").assertIsDisplayed()
        compose.onNodeWithText("두 번째 가수").assertIsDisplayed()
        compose.onNodeWithText("3G(솔) ~ 4G(솔)").performScrollTo().assertIsDisplayed()
        val map = hasTestTag("range-map-card")
        compose.onNode(hasTestTag("guided-range-keyboard") and hasAnyAncestor(map))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("음역").assertDoesNotExist()
        compose.onNodeWithText("✓ 초록 건반은 통과한 음이에요.").assertDoesNotExist()
        listOf("전체 음역", "안정 음역", "추천 음역").forEach { text ->
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(layouts))
                assertEquals(0f, layouts.single().getLineLeft(0), .5f)
            }
        }
        capture("saved-report-map.png")
        removedCardsAreAbsent()
        capture("saved-report-artists.png")
    }

    @Test fun similarityGraphDrawsUserArtistsAndEdgesWithoutPcaCopy() {
        show { SavedVoiceReportContent(range, progress) }
        val plot = compose.onNodeWithTag("voice-similarity-graph").performScrollTo().assertIsDisplayed()
        val bitmap = plot.captureToImage().asAndroidBitmap()
        val purple = Purple.toArgb()
        var purplePixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == purple) purplePixels++
        }
        assertTrue("The user and artist nodes must use the theme purple", purplePixels > 20)
        val user = compose.onNodeWithTag("voice-similarity-user").assertIsDisplayed().fetchSemanticsNode().size
        val artist = compose.onNodeWithTag("voice-similarity-artist-1").assertIsDisplayed().fetchSemanticsNode().size
        assertEquals(user.width, user.height)
        assertEquals(artist.width, artist.height)
        assertTrue(user.width > artist.width)
        compose.onNodeWithTag("voice-similarity-artist-2").assertIsDisplayed()
        listOf("PCA1", "PCA2", "ECAPA", "임베딩", "projection-v1", "catalog-v1", "유사도").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        capture("saved-report-voice-similarity.png")
    }

    @Test fun pendingLookupKeepsArtistCardWithoutShowingInternalStatusOrStaleResults() {
        var current by mutableStateOf(progress.copy(artistSearch = ComponentStatus.PENDING, message = "접수 준비 중"))
        show { SavedVoiceReportContent(range, current) }
        for (status in listOf(ComponentStatus.PENDING, ComponentStatus.QUEUED, ComponentStatus.RUNNING)) {
            compose.runOnIdle { current = current.copy(artistSearch = status) }
            compose.onNodeWithText("나와 닮은 가수").assertExists()
            listOf("접수 준비 중", "순서 대기 중", "분석 중", "첫 번째 가수").forEach {
                compose.onNodeWithText(it).assertDoesNotExist()
            }
        }
        compose.runOnIdle { current = progress.copy(sessionId = "8") }
        compose.onNodeWithText("첫 번째 가수").assertDoesNotExist()
        compose.runOnIdle { current = progress.copy(artists = emptyList()) }
        compose.onNodeWithText("지금은 닮은 가수를 찾지 못했어요.").performScrollTo().assertIsDisplayed()
    }

    @Test fun stabilityConnectsMeasuredPointsAcrossUnmeasuredNotesAndHasNoDetails() {
        show { RangeStabilityCard(range, overview = true) }
        val chart = compose.onNodeWithTag("range-stability-chart").assertIsDisplayed()
        chart.assert(hasContentDescription("측정한 음 사이를 선으로 연결합니다.", substring = true))
        val bitmap = chart.captureToImage().asAndroidBitmap()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val x = ((44 * density + bitmap.width - 16 * density) / 2).toInt()
        val bottom = bitmap.height - 30 * density
        val y = (bottom - (bottom - 14 * density) * .6f).toInt()
        val purple = android.graphics.Color.rgb((Purple.red * 255).toInt(), (Purple.green * 255).toInt(), (Purple.blue * 255).toInt())
        assertTrue("A connecting line must be visible halfway between measured notes", (-3..3).any { dx ->
            (-3..3).any { dy -> bitmap.getPixel(x + dx, y + dy) == purple }
        })
        chart.performTouchInput { click(center) }
        removedCardsAreAbsent()
        capture("saved-report-stability.png")
    }

    @Test fun previousAnalysisUsesTheSameReducedCardsAndPianoPlacement() {
        show { PreviousAnalysisReportCards(UserStore.VoiceProfile(lowMidi = 55, highMidi = 67)) }
        compose.onNodeWithText("나와 닮은 가수").assertIsDisplayed()
        compose.onNodeWithTag("voice-similarity-graph-card").assertDoesNotExist()
        compose.onNodeWithText("3G(솔) ~ 4G(솔)").performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag("report-piano") and hasAnyAncestor(hasTestTag("range-map-card")))
            .performScrollTo().assertIsDisplayed()
        removedCardsAreAbsent()
    }

    @Test fun compactReportFitsSmallScreenAtLargeFont() {
        compose.setContent { MiconTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    SavedVoiceReportContent(range, progress)
                }
            }
        } }
        compose.onNodeWithText("3G(솔) ~ 4G(솔)").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(layouts))
                assertFalse(layouts.single().hasVisualOverflow)
            }
        compose.onNodeWithTag("voice-similarity-graph").performScrollTo().assertIsDisplayed().also {
            val size = it.fetchSemanticsNode().size
            assertEquals(size.width, size.height)
        }
        capture("saved-report-large-font.png")
        listOf("전체 음역", "안정 음역", "추천 음역").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(action(layouts))
                    val layout = layouts.single()
                    assertFalse("Label height must fit: ${layout.multiParagraph.height} <= ${layout.size.height}",
                        layout.didOverflowHeight)
                    assertEquals(1, layout.lineCount)
                    assertTrue("Label ink must fit its width: ${layout.getLineRight(0)} <= ${layout.size.width}",
                        layout.getLineRight(0) <= layout.size.width + 1)
                    assertTrue(layout.getLineBottom(0) <= layout.size.height + 1)
                }
        }
        capture("saved-report-large-font.png")
    }
}
