package com.micon.app.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.onboarding.ComponentStatus
import com.micon.app.data.onboarding.EcapaEmbeddingMap
import com.micon.app.data.onboarding.EcapaPcaPoint
import com.micon.app.data.onboarding.VoiceAnalysisProgress
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Purple
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class EcapaPlot(
    val allArtists: List<EcapaPcaPoint>,
    val similarArtists: List<EcapaPcaPoint>,
    val user: EcapaPcaPoint?,
    val extent: Double,
)

internal fun ecapaPlot(map: EcapaEmbeddingMap?, progress: VoiceAnalysisProgress?): EcapaPlot {
    val validMap = when {
        map == null -> null
        progress == null -> map.usableFor(map.sessionId)
        else -> progress.sessionId.toLongOrNull()?.let(map::usableFor)
    }
    val artists = validMap?.artists.orEmpty()
    val similarIds = if (progress?.artistSearch == ComponentStatus.COMPLETED) {
        progress.visibleArtists.mapTo(hashSetOf()) { it.artistId }
    } else emptySet()
    val allPoints = artists.map { it.point }
    val similar = artists.filter { it.artistId in similarIds }.map { it.point }
    val user = validMap?.user
    val maximum = (allPoints + listOfNotNull(user)).fold(0.0) { value, point ->
        max(value, max(abs(point.pca1), abs(point.pca2)))
    }
    return EcapaPlot(allPoints, similar, user, if (maximum > 0.0) maximum * 1.1 else 1.0)
}

@Composable
internal fun EcapaEmbeddingCard(
    progress: VoiceAnalysisProgress?,
    map: EcapaEmbeddingMap? = null,
) {
    val plot = ecapaPlot(map, progress)
    val measurer = rememberTextMeasurer()
    MiconCard(Modifier.testTag("ecapa-embedding-card")) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("ecapa-embedding-plot")
            .semantics { contentDescription = "PCA1, PCA2" }) {
            val labelStyle = TextStyle(fontSize = 12.sp, color = InkSoft)
            val pca1 = measurer.measure("PCA1", labelStyle)
            val pca2 = measurer.measure("PCA2", labelStyle)
            val left = max(28.dp.toPx(), pca2.size.width.toFloat() + 8.dp.toPx())
            val top = max(8.dp.toPx(), pca2.size.height.toFloat() + 4.dp.toPx())
            val right = 8.dp.toPx()
            val bottom = max(28.dp.toPx(), pca1.size.height.toFloat() + 8.dp.toPx())
            val side = min(size.width - left - right, size.height - top - bottom).coerceAtLeast(1f)
            val origin = Offset(left + side / 2f, top + side / 2f)
            drawLine(GreyLight, Offset(left, origin.y), Offset(left + side, origin.y), 1.dp.toPx())
            drawLine(GreyLight, Offset(origin.x, top), Offset(origin.x, top + side), 1.dp.toPx())
            drawText(pca1, topLeft = Offset(left + side - pca1.size.width, top + side + 6.dp.toPx()))
            drawText(pca2, topLeft = Offset(0f, 0f))

            fun position(point: EcapaPcaPoint) = Offset(
                origin.x + (point.pca1 / plot.extent * side / 2.0).toFloat(),
                origin.y - (point.pca2 / plot.extent * side / 2.0).toFloat(),
            )
            plot.allArtists.forEach { drawCircle(Grey, 3.dp.toPx(), position(it)) }
            plot.similarArtists.forEach { drawCircle(Purple, 3.dp.toPx(), position(it)) }
            plot.user?.let { drawCircle(Purple, 6.dp.toPx(), position(it)) }
        }
    }
}
