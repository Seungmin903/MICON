package com.micon.app.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.holo.HoloResult
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import kotlin.math.roundToInt

private val Ser1 = Color(0xFF6C5CE7)
private val Ser2 = Color(0xFF0F9B6C)
private val Ser3 = Color(0xFFC2410C)

/**
 * 분석 결과 — 프로토타입 `voiceResult`. 항목: 나의 음역대 · 음역대 지도 · 음역별 안정성 · 내 목소리에 맞는 음악 ·
 * 나와 닮은 목소리(서버 음색 분석이 끝나면) · 요약 태그. 카드는 60ms 간격으로 떠오른다.
 */
@Composable
fun VoiceResultScreen(
    result: HoloResult,
    saving: Boolean,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    saveError: String? = null,
    /** 분석 탭에서 다시 볼 때는 뒤로가기만 */
    onBack: (() -> Unit)? = null,
) {
    MiconScreen(
        background = Color.White,
        appBar = if (onBack != null) ({ com.micon.app.ui.design.organisms.MiconAppBar(title = "분석 결과", onBack = onBack) }) else null,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        bottom = if (onBack == null) ({
            val song = result.songId != null
            (saveError ?: if (!song) "추천은 노래를 부른 분석에서만 받을 수 있어요." else null)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = if (saveError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (song) MiconButton(if (saving) "음색 분석을 기다리는 중…" else "저장하고 추천받기", onSave, Modifier.fillMaxWidth(), enabled = !saving)
            else MiconButton("노래로 분석하고 추천받기", onRetry, Modifier.fillMaxWidth())
            MiconButton("다시 측정하기", onRetry, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline, enabled = !saving)
        }) else null,
    ) {
        if (onBack == null) Spacer(Modifier.height(12.dp))
        FadeIn(0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiconBadge("측정 완료", tone = MiconChipTone.Success)
                Text("편한 음역대를 찾았어요", style = MaterialTheme.typography.titleLarge)
            }
        }
        FadeIn(1) { HeroRange(result) }
        FadeIn(2) { RangeMapCard(result) }
        FadeIn(3) { StabilityCard(result) }
        FadeIn(4) { MatchingMusicCard(result) }
        if (result.artists.isNotEmpty()) FadeIn(5) { SimilarVoicesCard(result) }
        else if (result.timbreSubmitted) FadeIn(5) {
            MiconCard(kind = MiconCardKind.Outlined) {
                Text("나와 닮은 목소리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("음색을 분석하는 중이에요. 잠시 뒤 분석 탭에서 확인할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FadeIn(6) { SummaryTint(result) }
    }
}

@Composable
private fun FadeIn(order: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(360, delayMillis = 60 * order, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}

@Composable
private fun Eyebrow(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)

@Composable
private fun HeroRange(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined, contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나의 음역대", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(NoteNames.scientificRange(r.lowMidi, r.highMidi), fontFamily = MiconMonoFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                val oct = r.octaves
                val octText = if (oct % 1.0 < 0.25) "${oct.toInt()}옥타브" else "${oct.toInt()}옥타브 반"
                Text("${NoteNames.friendly(r.lowMidi)} ~ ${NoteNames.friendly(r.highMidi)} · $octText", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 전체 / 편안한 / 추천곡 음역 막대 3줄, 같은 자(전체 음역 = 0~100%) 위에 */
@Composable
private fun RangeMapCard(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("음역대 지도")
        Spacer(Modifier.height(10.dp))
        val span = (r.highMidi - r.lowMidi).coerceAtLeast(1).toFloat()
        fun f(m: Int) = ((m - r.lowMidi) / span).coerceIn(0f, 1f)
        // 눈금: 양 끝 + 중간 3개 (막대와 같은 자)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(80.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
                    Text(NoteNames.scientific((r.lowMidi + p * span).roundToInt()), fontSize = 12.sp, fontFamily = MiconMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        listOf(
            Triple("전체 음역", Ser1, 0f to 1f),
            Triple("편안한 음역", Ser2, f(r.comfortLow) to f(r.comfortHigh)),
            Triple("추천곡 음역", Ser3, f(r.recommendLow) to f(r.recommendHigh)),
        ).forEachIndexed { i, (label, color, seg) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                GrowBar(color, seg.first, seg.second, delayMs = 100 * i)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "전체는 ${NoteNames.scientific(r.lowMidi)}–${NoteNames.scientific(r.highMidi)}지만, 힘 안 들이고 부를 수 있는 구간은 " +
                "${NoteNames.scientific(r.comfortLow)}–${NoteNames.scientific(r.comfortHigh)}예요.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GrowBar(color: Color, from: Float, to: Float, delayMs: Int) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, delayMillis = delayMs, easing = FastOutSlowInEasing)) }
    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFF0EDF9))) {
        Canvas(Modifier.matchParentSize()) {
            listOf(0.25f, 0.5f, 0.75f).forEach { x -> drawLine(Color(0xFFE9E5F6), Offset(size.width * x, 0f), Offset(size.width * x, size.height), 1.dp.toPx()) }
            val x0 = size.width * from
            val w = size.width * (to - from) * grow.value
            drawRoundRect(color, Offset(x0, size.height * 0.2f), Size(w, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
}

/** 음역별 안정성 꺾은선. 축: 안정 / 보통 / 부담 */
@Composable
private fun StabilityCard(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("음역별 안정성")
        Spacer(Modifier.height(8.dp))
        val labels = r.stabilityMidis.map { NoteNames.scientific(it) }
        val best = r.stability.indices.maxByOrNull { r.stability[it] } ?: 0
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth().height(130.dp)) {
            Column(Modifier.height(100.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("안정", "보통", "부담").forEach { Text(it, fontSize = 12.sp, color = muted) }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                    val n = r.stability.size
                    fun x(i: Int) = size.width * (i + 0.5f) / n
                    fun y(v: Float) = size.height * (0.08f + (1f - v) * 0.84f)
                    listOf(0f, 0.5f, 1f).forEach { v ->
                        drawLine(Color(0xFFE9E5F6), Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    val path = Path()
                    r.stability.forEachIndexed { i, v ->
                        if (i == 0) path.moveTo(x(0), y(v)) else {
                            val px = x(i - 1); val py = y(r.stability[i - 1]); val cx = x(i); val cy = y(v)
                            path.cubicTo(px + (cx - px) / 2, py, px + (cx - px) / 2, cy, cx, cy)
                        }
                    }
                    drawPath(path, Ser1, style = Stroke(2.dp.toPx()))
                    r.stability.forEachIndexed { i, v ->
                        drawCircle(Color.White, 5.dp.toPx(), Offset(x(i), y(v)))
                        drawCircle(Ser1, 5.dp.toPx(), Offset(x(i), y(v)), style = Stroke(2.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, l ->
                        Text(l, Modifier.weight(1f), fontSize = 12.sp, fontFamily = MiconMonoFamily, textAlign = TextAlign.Center,
                            color = if (i == best) MaterialTheme.colorScheme.onPrimaryContainer else muted,
                            fontWeight = if (i == best) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        val bestLabel = labels[best]
        val nextLabel = labels.getOrNull(best + 1) ?: bestLabel
        val thinFrom = labels.getOrNull((best + 2).coerceAtMost(labels.lastIndex)) ?: nextLabel
        Text("${bestLabel}–${nextLabel}에서 음정이 가장 흔들리지 않았어요. ${thinFrom} 위로는 소리가 얇아집니다.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** 편안한 음역 건반 · 적정 템포 게이지 · 프레이즈 길이 */
@Composable
private fun MatchingMusicCard(r: HoloResult) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("내 목소리에 맞는 음악")
        Spacer(Modifier.height(10.dp))
        KeyValue("음역", NoteNames.scientificRange(r.comfortLow, r.comfortHigh))
        Spacer(Modifier.height(8.dp))
        Piano(r)
        Text("보라색 구간 안에서 멜로디가 도는 곡이 가장 편합니다.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
        Divider()
        KeyValue("적정 템포 · 가사 밀도", "")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Gauge(((r.bpmLow + r.bpmHigh) / 2f - 60f) / 80f)
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${r.bpmLow}–${r.bpmHigh}", fontFamily = MiconMonoFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text("BPM", fontSize = 12.sp, color = muted, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(if (r.bpmHigh <= 100) "한 마디에 글자가 많지 않은 곡 · 중간 템포 발라드" else "가사가 촘촘해도 따라갈 수 있어요 · 미디엄 템포", fontSize = 12.sp, color = muted)
            }
        }
        Divider()
        val ph = r.phraseSec
        KeyValue("프레이즈 길이", "${(ph * 0.7).roundToInt()} – ${(ph * 1.3).roundToInt()}초")
        Spacer(Modifier.height(8.dp))
        val pos = ((ph - 2.0) / 10.0).toFloat().coerceIn(0.05f, 0.95f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(0xFFF0EDF9))) {
            Box(Modifier.fillMaxWidth((pos + 0.15f).coerceAtMost(1f)).height(8.dp).padding(start = 0.dp)) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(0f to Color.Transparent, (pos - 0.15f).coerceAtLeast(0f) / (pos + 0.15f) to Ser1.copy(alpha = 0.35f), 1f to Ser1)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("짧게 끊김", Modifier.weight(1f), fontSize = 12.sp, color = muted)
            Text("내 호흡 ${ph.roundToInt()}초", Modifier.weight(1f), fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("긴 호흡", Modifier.weight(1f), fontSize = 12.sp, color = muted, textAlign = TextAlign.End)
        }
        Text("한 호흡에 한 줄이 끝나는 곡을 고르면 후렴에서 안 밀려요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (v.isNotEmpty()) Text(v, fontFamily = MiconMonoFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

/** 흰건반 15개 위에 편안한 구간을 보라색으로. 시작 건반은 편안한 음역 아래 5반음에서 */
@Composable
private fun Piano(r: HoloResult) {
    val first = (r.comfortLow - 5).let { m -> m - (if (listOf(1, 3, 6, 8, 10).contains(((m % 12) + 12) % 12)) 1 else 0) }
    val whites = generateSequence(first) { it + 1 }.filter { ((it % 12) + 12) % 12 !in setOf(1, 3, 6, 8, 10) }.take(15).toList()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.fillMaxWidth().height(74.dp)) {
        val ww = size.width / 15f
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(Color(0xFFE7E3F4), Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        whites.forEachIndexed { i, m ->
            val on = m in r.comfortLow..r.comfortHigh
            drawRoundRect(if (on) Color(0xFFEDE9FF) else Color.White, Offset(i * ww + 1.dp.toPx(), 4.dp.toPx()), Size(ww - 2.dp.toPx(), size.height - 8.dp.toPx()), corner)
        }
        whites.forEachIndexed { i, m ->
            val sharp = m + 1
            if (((sharp % 12) + 12) % 12 in setOf(1, 3, 6, 8, 10) && i < 14) {
                val on = sharp in r.comfortLow..r.comfortHigh
                drawRoundRect(if (on) accent else Color(0xFF3A3552), Offset((i + 1) * ww - 4.25.dp.toPx(), 4.dp.toPx()), Size(8.5.dp.toPx(), 44.dp.toPx()), corner)
            }
        }
        // 편안한 구간 틴트
        val li = whites.indexOfFirst { it >= r.comfortLow }.coerceAtLeast(0)
        val hi = whites.indexOfLast { it <= r.comfortHigh }.coerceAtLeast(li)
        drawRect(Ser1.copy(alpha = 0.13f), Offset(li * ww, 0f), Size((hi - li + 1) * ww, size.height))
    }
    Row(Modifier.fillMaxWidth()) {
        val li = whites.indexOfFirst { it >= r.comfortLow }.coerceAtLeast(0)
        val hi = whites.indexOfLast { it <= r.comfortHigh }.coerceAtLeast(li)
        Spacer(Modifier.weight((li + 0.5f).coerceAtLeast(0.01f)))
        Text(NoteNames.scientific(r.comfortLow), fontSize = 12.sp, fontFamily = MiconMonoFamily, color = accent)
        Spacer(Modifier.weight((hi - li).coerceAtLeast(1).toFloat()))
        Text(NoteNames.scientific(r.comfortHigh), fontSize = 12.sp, fontFamily = MiconMonoFamily, color = accent)
        Spacer(Modifier.weight((14.5f - hi).coerceAtLeast(0.01f)))
    }
}

/** 3/4 호 게이지, 0..1 */
@Composable
private fun Gauge(value: Float) {
    val v = value.coerceIn(0f, 1f)
    Canvas(Modifier.size(56.dp)) {
        val stroke = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val rect = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx())
        val tl = Offset(3.dp.toPx(), 3.dp.toPx())
        drawArc(Color(0xFFEAE6F7), 135f, 270f, false, tl, rect, style = stroke)
        drawArc(Ser1, 135f, 270f * v, false, tl, rect, style = stroke)
    }
}

@Composable
private fun SimilarVoicesCard(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("나와 닮은 목소리")
        Spacer(Modifier.height(6.dp))
        r.artists.take(3).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactDisc(title = a.name, imageUrl = a.imageUrl, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${(a.similarity * 100).roundToInt()}%", fontFamily = MiconMonoFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun SummaryTint(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Tinted) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val stable = r.stability.maxOrNull() ?: 0f
            val lowHeavy = r.stabilityMidis.zip(r.stability).filter { it.first <= 59 }.maxOfOrNull { it.second } ?: 0f
            MiconBadge(if (r.octaves >= 2) "넓은 음역" else "또렷한")
            MiconBadge(if (r.phraseSec >= 5) "긴 호흡" else "부드러운")
            MiconBadge(if (lowHeavy >= 0.6f) "중저음 안정" else if (stable >= 0.7f) "음정 안정" else "표현력", tone = MiconChipTone.Success)
        }
        Spacer(Modifier.height(8.dp))
        Text("이 조건에 맞는 곡을 추천 탭에서 골라 드려요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
