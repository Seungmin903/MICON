package com.micon.app.ui.songdetail

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.*
import com.micon.app.data.backend.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import com.micon.app.ui.theme.MiconTheme
import com.micon.app.ui.navigation.MiconTabBar
import com.micon.app.ui.navigation.Routes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic UI fixtures only. Does not log in, record audio, or alter real user data. */
class SongDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val profile = UserStore.VoiceProfile(lowMidi = 55, highMidi = 72, comfortLow = 58, comfortHigh = 69)
    private val notes = (0..78).map { i ->
        val start = 16.0 + i * 2.2
        val midi = when (i) { in 23..28 -> 73 + i % 2; in 49..55 -> 73; else -> 60 + (i * 3 % 12) }
        KaraokeNote(start, start + 1.6, midi)
    }
    private val fixture = SongDetailState(loading = false,
        detail = SongDetail(SongSummary(42, "밤편지", "아이유", null, 253000), listOf("발라드"), SongAnalysis("A♭", 79, "4/4", 60, 74), false, true),
        fit = SongFit(42, TimbreMatch(TimbreAxis.CONSENSUS, "목소리의 질감이 잘 어울려요", null), .87f,
            rangeFit = null, recommendedKey = -2, strainSections = null, overallFit = .89f, reasons = emptyList(), scoringVersion = "ui-fixture", compositionVersion = null),
        fitLoading = false, notes = notes, assetsLoading = false, mediaKey = 0, durationSec = 253.0,
        sections = listOf(PracticeSection(1, "CHORUS", "후렴 · 긴 호흡", 144000, 152000, 1, "LONG_BREATH", null, "프레이즈가 길게 이어져요. 시작 전에 숨을 준비해 보세요.")))

    private fun show(state: SongDetailState = fixture, measured: UserStore.VoiceProfile? = profile, large: Boolean = false,
        onPractice: (Int) -> Unit = {}, onListen: (Double) -> Unit = {}) {
        compose.setContent {
            var current by remember { mutableStateOf(state) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.4f else 1f)) {
                MiconTheme {
                    Column((if (large) Modifier.widthIn(max = 320.dp) else Modifier).fillMaxSize().background(SongDetailColors.background)) {
                        Box(Modifier.weight(1f)) {
                            SongDetailContent(current, measured,
                                onKey = { current = current.copy(selectedKey = it, mediaKey = it) },
                                onPractice = { onPractice(current.selectedKey) }, onListen = onListen)
                        }
                        MiconTabBar(Routes.SONG_MATCH)
                    }
                }
            }
        }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("song-detail-scroll").performScrollToNode(hasTestTag(tag))
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun unifiedCardsSelectKeyAndPassItToPractice() {
        var practiced: Int? = null
        show(onPractice = { practiced = it })
        compose.onNodeWithTag("song-turntable").assertIsDisplayed()
        compose.onNodeWithText("이 노래, 나한테 맞을까?").assertDoesNotExist()
        capture("song-detail-top.png")
        scrollTo("range-matching")
        compose.onNodeWithText("음역대 매칭 분석").assertIsDisplayed()
        capture("song-detail-range.png")
        scrollTo("song-settings")
        compose.onNodeWithTag("key-recommended").performClick().assertIsSelected()
        compose.onNodeWithText("키 -2로 연습 시작").assertIsDisplayed()
        compose.onNodeWithTag("song-detail-scroll").performScrollToIndex(3)
        capture("song-detail-settings.png")
        compose.onNodeWithTag("song-practice").performClick()
        assertEquals(-2, practiced)
    }

    @Test fun burdenSelectionExplainsRegionAndSeeksOnlyOnListen() {
        var listened: Double? = null
        show(onListen = { listened = it })
        scrollTo("song-pitch-card")
        compose.onNodeWithTag("pitch-region-0").performScrollTo().performClick()
        assertNull(listened)
        compose.onNodeWithText("높은 음 주의").assertExists()
        compose.onNodeWithTag("pitch-region-detail").performScrollTo()
        capture("song-detail-graph.png")
        compose.onNodeWithText("이 위치 듣기").performScrollTo().performClick()
        assertEquals(16.0 + 23 * 2.2, listened!!, .001)
    }

    @Test fun missingDataDoesNotInventKeyOrPersonalAnalysis() {
        show(fixture.copy(fit = null, notes = emptyList(), sections = emptyList(), mediaKey = null), measured = null)
        compose.onNodeWithTag("song-practice").assertIsNotEnabled()
        scrollTo("song-settings")
        compose.onNodeWithText("추천 키 정보가 아직 없어요").assertIsDisplayed()
        compose.onNodeWithTag("key-recommended").assertDoesNotExist()
        scrollTo("song-pitch-card")
        compose.onNodeWithText("음표가 준비되면 높낮이 흐름을 볼 수 있어요").assertIsDisplayed()
        capture("song-detail-empty.png")
    }

    @Test fun largerTextKeepsKeyAndRegionActionsReachable() {
        show(large = true)
        scrollTo("song-settings")
        compose.onNodeWithTag("key-recommended").performScrollTo().performClick()
        compose.onNodeWithTag("song-detail-scroll").performScrollToIndex(3)
        capture("song-detail-large-text.png")
        compose.onNodeWithTag("song-practice").assertIsEnabled()
        scrollTo("song-pitch-card")
        compose.onNodeWithTag("pitch-region-0").performScrollTo().assertIsDisplayed()
    }

    @Test fun unavailableShiftedAudioDisablesPracticeUntilReturningToOriginalKey() {
        show(fixture.copy(selectedKey = -2, mediaKey = null,
            assetsError = "-2 키 반주가 아직 준비되지 않았어요. 원키로 돌아가서 연습할 수 있어요."))
        compose.onNodeWithTag("song-practice").assertIsNotEnabled()
        scrollTo("song-settings")
        compose.onNodeWithTag("key-recommended").assertIsSelected()
        compose.onNodeWithTag("key-original").performClick()
        compose.onNodeWithTag("song-practice").assertIsEnabled()
        compose.onNodeWithText("원키로 연습 시작").assertIsDisplayed()
    }

    @Test fun turntableSwipeChangesSongAndKeepsPracticeTargetInSync() {
        var viewed = 42L
        var practiced = 0L
        compose.setContent {
            var current by remember { mutableStateOf(fixture) }
            MiconTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        SongDetailContent(current, profile, carouselIds = listOf(42L, 43L, 44L),
                            onSongSelected = { id ->
                                viewed = id
                                current = current.copy(selectedKey = 0, mediaKey = 0,
                                    detail = current.detail!!.copy(summary = current.detail!!.summary.copy(id = id, title = "테스트 곡 $id")))
                            }, onPractice = { practiced = current.detail!!.summary.id })
                    }
                    MiconTabBar(Routes.SONG_MATCH)
                }
            }
        }
        capture("song-detail-carousel.png")
        // A deliberately one-record drag: a full-width fling can validly travel two 71%-width pages.
        compose.onNodeWithTag("song-carousel").performTouchInput {
            swipeLeft(startX = center.x + width * .2f, endX = center.x - width * .2f, durationMillis = 500)
        }
        compose.waitUntil(5_000) { viewed == 43L }
        capture("song-detail-carousel-swiped.png")
        compose.onNodeWithText("테스트 곡 43").assertExists()
        compose.onNodeWithTag("song-practice").performClick()
        assertEquals(43L, practiced)
        scrollTo("song-settings")
        compose.onNodeWithTag("song-detail-scroll").performScrollToIndex(0)
        compose.onNodeWithText("2 / 3 · LP를 넘겨 다른 곡 보기").assertExists()
        compose.onNodeWithTag("song-carousel").performTouchInput {
            swipeRight(startX = center.x - width * .2f, endX = center.x + width * .2f, durationMillis = 500)
        }
        compose.waitUntil(5_000) { viewed == 42L }
    }

    @Test fun favoritesOriginStaysSelectedAndDetailThemeDoesNotLeakToOtherTabs() {
        var outsideBackground: Color? = null
        var insideBackground: Color? = null
        compose.setContent {
            MiconTheme {
                SongDetailTheme { insideBackground = MaterialTheme.colorScheme.background }
                outsideBackground = MaterialTheme.colorScheme.background
                MiconTabBar(Routes.SONG, Routes.FAVORITES)
            }
        }
        compose.onNodeWithText("찜").assertIsSelected()
        compose.onNodeWithText("연습").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(SongDetailColors.background, insideBackground)
            assertEquals(Color.White, outsideBackground)
        }
    }
}
