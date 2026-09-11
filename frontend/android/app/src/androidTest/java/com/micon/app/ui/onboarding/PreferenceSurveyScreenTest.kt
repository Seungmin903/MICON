package com.micon.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceGenre
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PreferenceSurveyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsSwitchWithoutSubmittingAndPhotoCreditsAreAvailable() {
        var step = -1
        val artist = PreferenceArtist(22, "아이유", imageSourceUrl = "https://commons.wikimedia.org/wiki/File:Example.jpg",
            imageAttribution = "사진 작가", imageLicense = "CC BY 4.0")
        compose.setContent {
            MaterialTheme {
                PreferenceSurveyContent(PreferenceSurveyState(loading = false, artists = listOf(artist)), onStep = { step = it })
            }
        }
        compose.onNodeWithTag("preferences-next").assertIsNotEnabled()
        compose.onNodeWithTag("preferences-tab-genres").performClick()
        compose.runOnIdle { assertEquals(0, step) }
        compose.onNodeWithTag("photo-credits").performClick()
        compose.onNodeWithText("아티스트 사진 출처").assertIsDisplayed()
        compose.onNodeWithText("사진 작가").assertIsDisplayed()
    }

    @Test fun genreStageRequiresBothSelectionsAndExposesCheckboxState() {
        val genre = PreferenceGenre(1, "발라드")
        var clicked: PreferenceGenre? = null
        compose.setContent {
            MaterialTheme {
                PreferenceSurveyContent(
                    PreferenceSurveyState(loading = false, step = 0, genres = listOf(genre)), onGenre = { clicked = it },
                )
            }
        }
        compose.onNodeWithTag("preferences-complete").assertIsNotEnabled()
        compose.onNodeWithTag("genre-1").performClick()
        compose.runOnIdle { assertEquals(genre, clicked) }
        compose.onNodeWithText("장르 0개 선택").assertIsDisplayed()
    }

    @Test fun selectedArtistWithMissingImageStaysAccessibleAndCanBeRemovedOutsideSearchResults() {
        val artist = PreferenceArtist(22, "아이유", imageUrl = null)
        var removed: PreferenceArtist? = null
        compose.setContent {
            MaterialTheme {
                PreferenceSurveyContent(
                    PreferenceSurveyState(
                        loading = false, step = 1, genres = listOf(PreferenceGenre(1, "발라드")),
                        selectedGenres = mapOf(1L to PreferenceGenre(1, "발라드")),
                        artists = listOf(artist), selectedArtists = mapOf(22L to artist),
                    ), onArtist = { removed = it },
                )
            }
        }
        compose.onNodeWithTag("artist-22").assertIsOn()
        compose.onNodeWithTag("preferences-next").assertIsEnabled()
        compose.onNodeWithText("내 취향으로 시작하기").assertDoesNotExist()
        compose.onNodeWithText("다음").assertIsDisplayed()
        compose.onNodeWithTag("selected-artist-22").performClick()
        compose.runOnIdle { assertEquals(artist, removed) }
    }

    @Test fun saveFailureKeepsRetryActionAndCurrentSelectionVisible() {
        compose.setContent {
            MaterialTheme {
                PreferenceSurveyContent(
                    PreferenceSurveyState(
                        loading = false, step = 0,
                        selectedGenres = mapOf(1L to PreferenceGenre(1, "발라드")),
                        selectedArtists = mapOf(22L to PreferenceArtist(22, "아이유")),
                        saveError = "연결이 원활하지 않아요. 다시 시도해주세요.",
                    ),
                )
            }
        }
        compose.onNodeWithTag("preferences-save-error").assertIsDisplayed()
        compose.onNodeWithText("다시 저장").assertIsDisplayed()
        compose.onNodeWithText("내 취향으로 시작하기").assertDoesNotExist()
        compose.onNodeWithTag("preferences-complete").assertIsEnabled()
        compose.onNodeWithTag("preferences-previous").assertIsDisplayed()
    }

    @Test fun keyboardSearchShowsClickableArtistNamesWithinThreeHundredDpHeight() {
        val artist = PreferenceArtist(22, "아이유")
        var clicked: PreferenceArtist? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredHeight(300.dp)) {
                    PreferenceSurveyContent(
                        PreferenceSurveyState(loading = false, step = 1, query = "아이유", artists = listOf(artist)),
                        keyboardVisible = true, onArtist = { clicked = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("preferences-complete").assertDoesNotExist()
        compose.onNodeWithTag("preferences-close-search").assertIsDisplayed()
        compose.onNodeWithTag("artist-22").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(artist, clicked) }
    }

    @Test fun normalArtistGridShowsFirstRowNamesWithSelectedChipsWithinSixHundredEightyDp() {
        val catalogue = listOf(PreferenceArtist(1, "AKMU"), PreferenceArtist(2, "BTS"), PreferenceArtist(3, "BTOB"))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredHeight(680.dp)) {
                    PreferenceSurveyContent(
                        PreferenceSurveyState(
                            loading = false, step = 1, artists = catalogue,
                            selectedGenres = mapOf(1L to PreferenceGenre(1, "발라드")),
                            selectedArtists = mapOf(22L to PreferenceArtist(22, "아이유"), 23L to PreferenceArtist(23, "DAY6")),
                        ),
                        keyboardVisible = false,
                    )
                }
            }
        }
        compose.onNodeWithTag("selected-artist-22").assertIsDisplayed()
        compose.onNodeWithTag("selected-artist-23").assertIsDisplayed()
        catalogue.forEach { compose.onNodeWithText(it.name, useUnmergedTree = true).assertIsDisplayed() }
        compose.onNodeWithTag("preferences-next").assertIsDisplayed()
    }
}
