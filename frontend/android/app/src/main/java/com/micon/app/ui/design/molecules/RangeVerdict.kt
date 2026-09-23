package com.micon.app.ui.design.molecules

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.tokens.MiconSpace
import kotlin.math.max
import kotlin.math.min

/**
 * "이 곡 부를 수 있나" 한 장. `SongFitCard` · `RangeMatchingCard` · `MatchBody` ·
 * `RangeReportCards` 네 벌이 같은 판정을 서로 다른 말로 하던 것을 하나로 모은다.
 *
 * 화면에는 숫자를 내보내지 않는다 — 판정 한 마디, 비교 한 문장, 미니 키보드가 전부다.
 */
enum class RangeFit(val label: String, val tone: MiconChipTone) {
    Easy("편해요", MiconChipTone.Success),
    Slight("살짝 도전", MiconChipTone.Neutral),
    Hard("고음 도전", MiconChipTone.Challenge);

    companion object {
        /**
         * 서버 적합도(0~100) → 판정.
         *
         * **적합도는 백엔드가 계산한다** (`SongFitDto.recommendationScores.range`). 앱이 음역만
         * 보고 따로 판정하면 서버가 "잘 맞아요" 라고 한 곡을 화면은 "고음 도전" 이라 부르는 일이
         * 생긴다. 서버 값이 있으면 언제나 이걸 써서 [RangeVerdict] 의 `fitOverride` 로 넘긴다.
         *
         * 경계 90 / 75 는 기존 `RecommendScreens` 의 찰떡궁합 · 잘 맞아요 · 도전해 보세요 와
         * 같은 값이다 — 말만 바뀌고 기준은 그대로다.
         */
        fun fromScore(percent: Double?): RangeFit? = when {
            percent == null -> null
            percent >= 90 -> Easy
            percent >= 75 -> Slight
            else -> Hard
        }
    }
}

/**
 * @param keyShift 지금 적용된 키 조절. 곡 음역을 이만큼 옮겨 판정한다.
 * @param fitOverride 서버가 계산한 판정([RangeFit.fromScore]). 주면 앱은 다시 판정하지 않는다.
 * @param recommendedKeyShift 서버가 권하는 키 조절(`SongFitDto.recommendedKeyOffset`).
 *   주면 조언 문장이 이 값을 쓴다 — 앱이 따로 센 키와 서버가 권한 키가 어긋나지 않게.
 */
@Composable
fun RangeVerdict(
    myLowMidi: Int,
    myHighMidi: Int,
    songLowMidi: Int,
    songHighMidi: Int,
    modifier: Modifier = Modifier,
    keyShift: Int = 0,
    fitOverride: RangeFit? = null,
    recommendedKeyShift: Int? = null,
) {
    val songLow = songLowMidi + keyShift
    val songHigh = songHighMidi + keyShift
    val over = songHigh - myHighMidi
    val under = myLowMidi - songLow
    // 서버 판정이 있으면 그것이 기준이다. 없을 때만(오프라인·평가 불가) 음역으로 가늠한다.
    val fit = fitOverride ?: when {
        over <= 0 && under <= 0 -> RangeFit.Easy
        over <= 2 && under <= 2 -> RangeFit.Slight
        else -> RangeFit.Hard
    }

    // 사람이 읽을 한 문장. 높은 쪽이 문제일 때가 대부분이라 그쪽을 먼저 말한다.
    val sentence = when {
        fit == RangeFit.Easy -> "내 편한 음역 안에 들어와요."
        over > 0 -> "가장 높은 음이 내 음역보다 ${NoteNames.distanceText(myHighMidi, songHigh)}"
        else -> "가장 낮은 음이 내 음역보다 ${NoteNames.distanceText(myLowMidi, songLow)}"
    }
    // 다음 행동 한 개. 서버가 권한 키가 있으면 그걸, 없으면 몇 키 내리면 들어오는지 세서 알려 준다.
    val shift = recommendedKeyShift ?: when {
        over > 0 -> -over
        under > 0 -> under
        else -> 0
    }
    val advice = if (fit == RangeFit.Easy || shift == 0) {
        null
    } else {
        "${NoteNames.keyShiftText(shift)}를 추천해요."
    }

    MiconCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiconBadge(fit.label, tone = fit.tone)
            Spacer(Modifier.width(MiconSpace.xs))
            Text(
                NoteNames.rangeText(songLow, songHigh),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(MiconSpace.sm))
        Text(
            buildString {
                append(sentence)
                if (advice != null) {
                    append(" ")
                    append(advice)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(MiconSpace.md))
        MiniKeyboard(
            myLowMidi = myLowMidi,
            myHighMidi = myHighMidi,
            songLowMidi = songLow,
            songHighMidi = songHigh,
        )
    }
}

/**
 * 미니 건반. 내 범위는 라벤더로 **채우고**, 곡 범위는 분홍 **테두리**로 두른다 —
 * 두 정보가 겹쳐도 서로를 가리지 않는다. 가온 도에는 항상 라벨을 붙여
 * 건반 어디쯤을 보고 있는지 알 수 있게 한다.
 *
 * `GuidedRangeKeyboard` 를 줄여 쓴 것이고, 축은 두 범위를 모두 담는 옥타브 경계로 맞춘다.
 */
@Composable
fun MiniKeyboard(
    myLowMidi: Int,
    myHighMidi: Int,
    songLowMidi: Int,
    songHighMidi: Int,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val mineFill = scheme.secondaryContainer
    val mineFillDark = scheme.primary
    val songStroke = scheme.tertiary
    val keyLine = scheme.outlineVariant
    val labelColor = scheme.onSurfaceVariant

    // 축: 두 범위를 모두 담되 도(C) 에서 시작해 시(B) 로 끝나게 옥타브 단위로 맞춘다
    val spanLow = (min(myLowMidi, songLowMidi) / 12) * 12
    val spanHigh = (max(myHighMidi, songHighMidi) / 12) * 12 + 11

    val whiteClasses = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whiteKeys = (spanLow..spanHigh).filter { ((it % 12) + 12) % 12 in whiteClasses }
    if (whiteKeys.isEmpty()) return

    val description = "내 음역 ${NoteNames.rangeText(myLowMidi, myHighMidi)}, " +
        "곡 음역 ${NoteNames.rangeText(songLowMidi, songHighMidi)}"

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val keyWidth = size.width / whiteKeys.size
        val whiteHeight = size.height
        val blackHeight = size.height * 0.62f
        val blackWidth = keyWidth * 0.62f
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        fun xOfWhite(midi: Int): Float = whiteKeys.indexOf(midi) * keyWidth

        // --- 흰 건반 ---
        whiteKeys.forEachIndexed { index, midi ->
            val inMine = midi in myLowMidi..myHighMidi
            drawRoundRect(
                color = if (inMine) mineFill else Color.White,
                topLeft = Offset(index * keyWidth, 0f),
                size = Size(keyWidth, whiteHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = keyLine,
                topLeft = Offset(index * keyWidth, 0f),
                size = Size(keyWidth, whiteHeight),
                cornerRadius = radius,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // --- 검은 건반 (흰 건반 경계 위에 얹는다) ---
        (spanLow..spanHigh).forEach { midi ->
            val pitchClass = ((midi % 12) + 12) % 12
            if (pitchClass in whiteClasses) return@forEach
            val leftWhite = midi - 1
            val leftIndex = whiteKeys.indexOf(leftWhite)
            if (leftIndex < 0) return@forEach
            val inMine = midi in myLowMidi..myHighMidi
            drawRoundRect(
                color = if (inMine) mineFillDark else Color(0xFF2A2740),
                topLeft = Offset((leftIndex + 1) * keyWidth - blackWidth / 2f, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = radius,
            )
        }

        // --- 곡 범위 테두리 ---
        val songLeftWhite = whiteKeys.firstOrNull { it >= songLowMidi } ?: whiteKeys.first()
        val songRightWhite = whiteKeys.lastOrNull { it <= songHighMidi } ?: whiteKeys.last()
        val left = xOfWhite(songLeftWhite)
        val right = xOfWhite(songRightWhite) + keyWidth
        drawRoundRect(
            color = songStroke,
            topLeft = Offset(left + 1f, 1f),
            size = Size((right - left - 2f).coerceAtLeast(2f), whiteHeight - 2f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )

        // --- 가온 도 라벨 ---
        val middleC = 60
        if (middleC in whiteKeys) {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = labelColor.toArgb()
                    // dp 가 아니라 sp — 글자 크게 설정을 따르고, 최소 12sp 규칙 안에 든다
                    textSize = 12.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(
                    "도",
                    xOfWhite(middleC) + keyWidth / 2f,
                    whiteHeight - 4.dp.toPx(),
                    paint,
                )
            }
        }
    }
}
