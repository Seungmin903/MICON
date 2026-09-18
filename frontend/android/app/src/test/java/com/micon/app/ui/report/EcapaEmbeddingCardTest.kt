package com.micon.app.ui.report

import com.micon.app.data.onboarding.ComponentStatus
import com.micon.app.data.onboarding.EcapaArtistPoint
import com.micon.app.data.onboarding.EcapaEmbeddingMap
import com.micon.app.data.onboarding.EcapaPcaPoint
import com.micon.app.data.onboarding.SimilarVoiceArtist
import com.micon.app.data.onboarding.VoiceAnalysisProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcapaEmbeddingCardTest {
    private fun map(
        sessionId: Long = 7,
        user: EcapaPcaPoint? = EcapaPcaPoint(-4.0, 1.0),
        artists: List<EcapaArtistPoint> = listOf(
            EcapaArtistPoint(1, EcapaPcaPoint(2.0, -3.0)),
            EcapaArtistPoint(2, EcapaPcaPoint(-1.0, 1.0)),
        ),
    ) = EcapaEmbeddingMap(sessionId, "pca-v1", "catalog-v1", user, artists)

    @Test fun mapRejectsAnotherSessionAndDropsNonFiniteCoordinates() {
        assertNull(map().usableFor(8))
        val valid = map(user = EcapaPcaPoint(Double.NaN, 0.0), artists = listOf(
            EcapaArtistPoint(1, EcapaPcaPoint(Double.POSITIVE_INFINITY, 0.0)),
            EcapaArtistPoint(2, EcapaPcaPoint(-1.0, 1.0)),
        )).usableFor(7)!!
        assertNull(valid.user)
        assertEquals(listOf(2L), valid.artists.map { it.artistId })
    }

    @Test fun plotUsesSymmetricPaddedBoundsAndDefaultsEmptyAxesToOne() {
        assertEquals(4.4, ecapaPlot(map(), null).extent, 0.0001)
        assertEquals(1.0, ecapaPlot(null, null).extent, 0.0)
        assertEquals(1.0, ecapaPlot(map(user = EcapaPcaPoint(0.0, 0.0), artists = emptyList()), null).extent, 0.0)
    }

    @Test fun onlyCompletedVisibleMatchesAreHighlightedAndMissingCoordinatesAreIgnored() {
        val progress = VoiceAnalysisProgress("7", 2, artistSearch = ComponentStatus.COMPLETED,
            artists = listOf(SimilarVoiceArtist(2, "둘", null, .9), SimilarVoiceArtist(99, "없음", null, .8)))
        val plot = ecapaPlot(map(), progress)
        assertEquals(listOf(EcapaPcaPoint(-1.0, 1.0)), plot.similarArtists)
        assertEquals(2, plot.allArtists.size)
        assertEquals(emptyList<EcapaPcaPoint>(), ecapaPlot(map(), progress.copy(artistSearch = ComponentStatus.RUNNING)).similarArtists)
    }

    @Test fun staleSessionCoordinatesProduceAnEmptyPlot() {
        val plot = ecapaPlot(map(sessionId = 8), VoiceAnalysisProgress("7", 2))
        assertEquals(emptyList<EcapaPcaPoint>(), plot.allArtists)
        assertNull(plot.user)
        assertEquals(1.0, plot.extent, 0.0)
        assertEquals(emptyList<EcapaPcaPoint>(),
            ecapaPlot(map(), VoiceAnalysisProgress("invalid-session", 2)).allArtists)
    }
}
