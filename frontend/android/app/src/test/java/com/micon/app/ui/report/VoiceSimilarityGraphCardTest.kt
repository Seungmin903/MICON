package com.micon.app.ui.report

import com.micon.app.data.onboarding.ComponentStatus
import com.micon.app.data.onboarding.SimilarVoiceArtist
import com.micon.app.data.onboarding.VoiceAnalysisProgress
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSimilarityGraphCardTest {
    private fun progress(
        status: ComponentStatus = ComponentStatus.COMPLETED,
        artists: List<SimilarVoiceArtist>,
    ) = VoiceAnalysisProgress("7", 2, artistSearch = status, artists = artists)

    private fun artist(id: Long, similarity: Double, name: String = "가수$id") =
        SimilarVoiceArtist(id, name, null, similarity)

    @Test fun highSimilarityWindowIsExpandedAndLowerScoresClampToFarthestRadius() {
        assertEquals(0.0, cosineDistanceFraction(1.0), 0.0)
        assertEquals(1.0 / 3.0, cosineDistanceFraction(.9), .0001)
        assertEquals(2.0 / 3.0, cosineDistanceFraction(.8), .0001)
        assertEquals(1.0, cosineDistanceFraction(.7), 0.0)
        assertEquals(1.0, cosineDistanceFraction(-1.0), 0.0)
        assertEquals(0.0, cosineDistanceFraction(2.0), 0.0)
        assertEquals(1.0, cosineDistanceFraction(-2.0), 0.0)
    }

    @Test fun completedArtistsAreSortedLimitedAndPlacedClockwiseFromTop() {
        val nodes = voiceSimilarityNodes(progress(artists = listOf(
            artist(3, .6), artist(1, .9), artist(2, .8), artist(4, .5), artist(5, .4), artist(6, .3),
        )))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), nodes.map { it.artist.artistId })
        assertEquals(-PI / 2.0, nodes.first().angleRadians, 0.0001)
        assertEquals(2.0 * PI / 5.0, nodes[1].angleRadians - nodes[0].angleRadians, 0.0001)
        assertTrue(nodes.zipWithNext().all { (nearer, farther) ->
            nearer.distanceFraction <= farther.distanceFraction
        })
    }

    @Test fun equalSimilaritiesUseTheSameRadiusButDifferentAngles() {
        val nodes = voiceSimilarityNodes(progress(artists = listOf(artist(1, .8), artist(2, .8))))
        assertEquals(nodes[0].distanceFraction, nodes[1].distanceFraction, 0.0)
        assertEquals(PI, nodes[1].angleRadians - nodes[0].angleRadians, 0.0001)
    }

    @Test fun graphRejectsInvalidDuplicateAndUnavailableArtistResults() {
        val artists = listOf(
            artist(1, .9, "첫째"),
            artist(1, .8, "중복"),
            artist(2, Double.NaN),
            artist(3, .7, ""),
            artist(4, 1.2),
        )
        assertEquals(listOf(1L), voiceSimilarityNodes(progress(artists = artists)).map { it.artist.artistId })
        assertTrue(voiceSimilarityNodes(progress(ComponentStatus.RUNNING, artists)).isEmpty())
        assertTrue(voiceSimilarityNodes(progress(ComponentStatus.FAILED, artists)).isEmpty())
        assertTrue(voiceSimilarityNodes(progress(artists = emptyList())).isEmpty())
    }
}
