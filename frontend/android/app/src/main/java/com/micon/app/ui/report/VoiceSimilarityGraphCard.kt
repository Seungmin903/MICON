package com.micon.app.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.onboarding.ComponentStatus
import com.micon.app.data.onboarding.SimilarVoiceArtist
import com.micon.app.data.onboarding.VoiceAnalysisProgress
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val expandedSimilarityFloor = 0.7

internal data class VoiceSimilarityNode(
    val artist: SimilarVoiceArtist,
    val angleRadians: Double,
    /** 0 is the nearest display radius and 1 is the farthest. */
    val distanceFraction: Double,
)

internal fun cosineDistanceFraction(similarity: Double): Double =
    ((1.0 - similarity.coerceIn(expandedSimilarityFloor, 1.0)) / (1.0 - expandedSimilarityFloor))
        .coerceIn(0.0, 1.0)

internal fun voiceSimilarityNodes(progress: VoiceAnalysisProgress?): List<VoiceSimilarityNode> {
    if (progress?.artistSearch != ComponentStatus.COMPLETED) return emptyList()
    val artists = progress.visibleArtists
    if (artists.isEmpty()) return emptyList()
    val angleStep = 2.0 * PI / artists.size
    return artists.mapIndexed { index, artist ->
        VoiceSimilarityNode(
            artist = artist,
            angleRadians = -PI / 2.0 + angleStep * index,
            distanceFraction = cosineDistanceFraction(artist.similarity),
        )
    }
}

@Composable
internal fun VoiceSimilarityGraphCard(progress: VoiceAnalysisProgress?) {
    val nodes = voiceSimilarityNodes(progress)
    if (nodes.isEmpty()) return

    MiconCard(Modifier.testTag("voice-similarity-graph-card")) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag("voice-similarity-graph")
                .semantics {
                    contentDescription = "나를 중심으로 닮은 가수를 연결한 목소리 유사도 그래프"
                },
        ) {
            val graphSide = maxWidth
            val artistSize = 56.dp
            val userSize = 64.dp
            val edgePadding = 4.dp
            val center = graphSide / 2
            val farRadius = ((graphSide - artistSize) / 2 - edgePadding).coerceAtLeast(0.dp)
            val nearRadius = (userSize / 2 + artistSize / 2 + 8.dp).coerceAtMost(farRadius)

            fun radius(node: VoiceSimilarityNode) =
                nearRadius + (farRadius - nearRadius) * node.distanceFraction.toFloat()

            Canvas(Modifier.fillMaxSize()) {
                val origin = Offset(size.width / 2f, size.height / 2f)
                nodes.forEach { node ->
                    val distance = radius(node).toPx()
                    val target = Offset(
                        x = origin.x + cos(node.angleRadians).toFloat() * distance,
                        y = origin.y + sin(node.angleRadians).toFloat() * distance,
                    )
                    drawLine(GreyLight, origin, target, strokeWidth = 2.dp.toPx())
                }
            }

            nodes.forEach { node ->
                val distance = radius(node)
                val centerX = center + distance * cos(node.angleRadians).toFloat()
                val centerY = center + distance * sin(node.angleRadians).toFloat()
                Box(
                    Modifier
                        .offset(centerX - artistSize / 2, centerY - artistSize / 2)
                        .size(artistSize)
                        .clip(CircleShape)
                        .background(Lavender)
                        .border(1.dp, Purple, CircleShape)
                        .testTag("voice-similarity-artist-${node.artist.artistId}")
                        .semantics { contentDescription = node.artist.name },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = node.artist.name,
                        modifier = Modifier.padding(horizontal = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Purple,
                    )
                }
            }

            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(userSize)
                    .clip(CircleShape)
                    .background(Purple)
                    .testTag("voice-similarity-user"),
                contentAlignment = Alignment.Center,
            ) {
                Text("나", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
