package com.micon.app.ui.onboarding

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceGenre
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in visual QA. Fixtures are local reviewed photos, never fetched in CI. */
class PreferenceSurveyCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureArtists() = capture(1, "taste-artists.png")
    @Test fun captureGenres() = capture(0, "taste-genres.png")

    private fun capture(step: Int, filename: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureTasteScreens") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val manifest = JSONArray(assets.open("portrait-fixtures/manifest.json").bufferedReader().use { it.readText() })
        val artists = (0 until manifest.length()).map { index ->
            val item = manifest.getJSONObject(index)
            val file = File(context.cacheDir, "taste-portrait-$index.jpg")
            assets.open("portrait-fixtures/${item.getString("file")}").use { input -> file.outputStream().use { input.copyTo(it) } }
            runBlocking {
                assertTrue(context.imageLoader.execute(ImageRequest.Builder(context).data(file).build()) is SuccessResult)
            }
            PreferenceArtist(index + 1L, item.getString("name"), file.toURI().toString(),
                item.getString("sourceUrl"), item.getString("attribution"), item.getString("license"))
        }
        val genres = listOf("발라드", "댄스", "R&B/소울", "록/메탈", "힙합", "포크/어쿠스틱", "OST", "영어곡", "일본곡", "트로트")
            .mapIndexed { index, name -> PreferenceGenre(index + 1L, name) }
        compose.setContent {
            MaterialTheme {
                PreferenceSurveyContent(PreferenceSurveyState(loading = false, step = step, artists = artists, genres = genres,
                    selectedArtists = artists.filterIndexed { index, _ -> index in listOf(3, 6, 8) }.associateBy { it.artistId },
                    selectedGenres = genres.filterIndexed { index, _ -> index in listOf(2, 6, 7) }.associateBy { it.genreId }), keyboardVisible = false)
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("preference-survey").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
