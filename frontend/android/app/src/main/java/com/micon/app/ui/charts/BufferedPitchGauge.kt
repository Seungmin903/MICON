package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.sharpNoteText
import com.micon.app.ui.theme.*
import kotlin.math.*

/** Only the needle uses display smoothing. Zone color/state comes from the raw evaluator. */
@Composable
fun BufferedPitchGauge(target: Int, currentMidi: Double?, referenceMidi: Double?, zone: String?, hold: Float) {
    val center = referenceMidi ?: target.toDouble()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PitchHoldRing(ScoringMath.midiName(target), hold, holdLabel = "인정 유지")
        Spacer(Modifier.height(8.dp))
        val centerLabel = if (referenceMidi == null) "시작 높이를 잡고 있어요" else
            "고정 중심 ${ScoringMath.midiName(referenceMidi)} · 목표 ${ScoringMath.midiName(target)}"
        Text(sharpNoteText(centerLabel, 12.sp), color = Grey, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics {
            contentDescription = "음정 영역 ${zone ?: "준비"} · 정상 45 cents 이내 · 완충 100 cents 이내"
        }) {
            val cx = size.width/2; val cy = size.height-8.dp.toPx()
            val r = min(cx-20.dp.toPx(), cy-8.dp.toPx())
            val origin = Offset(cx-r,cy-r); val bounds = Size(2*r,2*r)
            drawArc(Red.copy(alpha=.18f),180f,180f,true,origin,bounds)
            drawArc(Orange.copy(alpha=.35f),210f,120f,true,origin,bounds)
            drawArc(Teal.copy(alpha=.32f),243f,54f,true,origin,bounds)
            fun point(cents: Double, length: Float): Offset {
                val angle = PI*(1-(cents.coerceIn(-150.0,150.0)+150)/300)
                return Offset(cx+(length*cos(angle)).toFloat(),cy-(length*sin(angle)).toFloat())
            }
            drawLine(Grey,Offset(cx,cy),point(0.0,r),1.dp.toPx())
            drawLine(Purple,Offset(cx,cy),point((target-center)*100,r),2.dp.toPx(),
                pathEffect=PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),4.dp.toPx())))
            currentMidi?.let {
                val color = when(zone) { "normal"->Teal; "buffer"->Orange; "outside"->Red; else->Grey }
                drawLine(color,Offset(cx,cy),point((it-center)*100,r*.94f),4.dp.toPx(),StrokeCap.Round)
            }
            drawCircle(Purple,3.dp.toPx(),Offset(cx,cy))
        }
        Text("정상 ±45  ·  완충 ±100 cents  ·  점선은 목표음", color = Grey, fontSize = 11.sp)
    }
}
