package com.micon.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.R
import coil.compose.AsyncImage
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Paper
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.organisms.MiconAppBar

// ---------------------------------------------------------------------------
// 로고 · 헤더
// ---------------------------------------------------------------------------

/** MICON 브랜드 로고 이미지. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun MiconLogo(modifier: Modifier = Modifier, barHeight: Dp = 24.dp, fontSize: TextUnit = 22.sp) {
    Image(
        painter = painterResource(R.drawable.micon_logo),
        contentDescription = "MICON",
        modifier = modifier.height(barHeight * 1.15f).width(barHeight * 4.5f),
        contentScale = ContentScale.Fit,
    )
}

/** Figma 공통 헤더. 비회원에게는 계정 아이콘 대신 로그인 버튼만 노출한다. */
@Composable
fun MiconHeader(
    onSettings: () -> Unit = {},
    onLogin: (() -> Unit)? = null,
) {
    MiconAppBar(actions = {
        if (onLogin != null) {
            MiconButton("로그인", onLogin, kind = MiconButtonKind.Text)
        } else {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings, "설정", Modifier.size(24.dp), tint = Ink)
            }
        }
    })
}

/** 하위 화면 헤더: ← 제목 (오른쪽 액션 슬롯). 아래 얇은 그림자. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = Paper, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Ink) }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

// ---------------------------------------------------------------------------
// 검색창 · 앨범 아트 · 곡 행
// ---------------------------------------------------------------------------

@Composable
fun MiconSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "노래를 검색해보세요",
    prototypeStyle: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(if (prototypeStyle) 52.dp else 54.dp),
        shape = RoundedCornerShape(if (prototypeStyle) 16.dp else 27.dp),
        color = if (prototypeStyle) Color.White else GreyBg,
        border = if (prototypeStyle) BorderStroke(1.dp, Color(0xFFE4E2EE)) else null,
        shadowElevation = if (prototypeStyle) 0.dp else 3.dp,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (prototypeStyle) {
                Icon(Icons.Outlined.Search, "검색", Modifier.size(22.dp), tint = Grey)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = Grey, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!prototypeStyle) Icon(Icons.Outlined.Search, "검색", Modifier.size(28.dp), tint = Grey)
        }
    }
}

/**
 * LP 판 모양 앨범 아트 = 디자인 에셋 링(`lp_ring.png`, 가운데가 뚫려 있고 흰 구멍 포함) 아래에 앨범아트를 깐다.
 * 에셋의 뚫린 지름은 0.828·size — 아트는 0.85·size 로 조금 더 크게 깔아 링이 가장자리를 덮는다. imageUrl 이 없으면 제목 첫 글자.
 */
@Composable
fun VinylArt(imageUrl: String?, size: Dp, modifier: Modifier = Modifier, fallbackText: String = "") {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.85f).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF9F8DF7), Color(0xFF6C5CE7)))), contentAlignment = Alignment.Center) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(fallbackText.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.22f).sp)
            }
        }
        Image(painterResource(R.drawable.lp_ring), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 곡 선택 화면용 CD 아트. 앨범 커버가 있으면 원형으로 표시하고, 없으면 시안의 은은한
 * 보라색 CD 그라데이션을 사용한다. CD 안에는 곡명이나 대체 문자를 표시하지 않는다.
 */
@Composable
fun CdArt(imageUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(
            Brush.sweepGradient(
                listOf(
                    Color(0xFFEFEAFF), Color(0xFFDCD6F8), Color(0xFFF6F3FF),
                    Color(0xFFD5CDF4), Color(0xFFEFEAFF),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val diameter = this.size.minDimension
            val ring = diameter * 0.37f
            drawCircle(Color.White.copy(alpha = 0.28f), radius = ring, style = Stroke(width = 1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.20f), radius = diameter * 0.44f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(Color.White, radius = diameter * 0.115f)
            drawCircle(Color(0x1A1E1B33), radius = diameter * 0.115f, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
fun KeyBadge(text: String) {
    Box(Modifier.background(LavenderDeep, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 추천 키 정수 → "+1 key" / "0 key" */
fun keyBadgeText(key: Int): String = (if (key > 0) "+$key" else "$key") + " key"

@Composable
fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) "찜 해제" else "찜",
            tint = if (liked) Purple else Grey,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** Figma 곡 목록 행: LP 아트 · 제목/가수 · key 배지 · 하트(또는 trailing 슬롯) */
@Composable
fun SongRow(
    title: String,
    artist: String,
    imageUrl: String?,
    onClick: () -> Unit,
    keyBadge: Int? = null,
    liked: Boolean? = null,
    onLike: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    /** 가수명 옆 작은 칩(예: "가사 싱크", "가사만"). null 이면 없음 */
    subBadge: String? = null,
    subBadgeColor: Color = Purple,
    compactCd: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compactCd) CdArt(imageUrl, 56.dp) else VinylArt(imageUrl, 76.dp, fallbackText = title)
        Spacer(Modifier.width(if (compactCd) 12.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artist, fontSize = 14.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (subBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        subBadge, fontSize = 10.sp, color = subBadgeColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(subBadgeColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        keyBadge?.let { KeyBadge(keyBadgeText(it)); Spacer(Modifier.width(6.dp)) }
        when {
            trailing != null -> trailing()
            liked != null -> HeartButton(liked, onLike)
        }
    }
}

// ---------------------------------------------------------------------------
// 버튼 · 입력 · 카드 · 칩
// ---------------------------------------------------------------------------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 56.dp) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Paper, disabledContainerColor = Purple.copy(alpha = 0.45f), disabledContentColor = Paper),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun OutlinePurpleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) Purple else GreyLight),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Purple, disabledContentColor = Grey),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** 단계 진행선 + "n / m" (Figma 음성 간편 분석 상단). 가로 패딩 32dp 를 포함한다. */
@Composable
fun StepBar(step: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawLine(Lavender, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
            drawLine(Purple, Offset(0f, size.height / 2), Offset(size.width * step / total, size.height / 2), size.height, StrokeCap.Round)
        }
        Spacer(Modifier.height(8.dp))
        Text("$step / $total", fontSize = 14.sp, color = Grey)
    }
}

@Composable
fun GreyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GreyBg, contentColor = Ink),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it() } }
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun MiconTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
    helperColor: Color = Grey,
) {
    Column(modifier) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = Grey, fontSize = 16.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = GreyLight),
        )
        helper?.let { Text(it, fontSize = 12.sp, color = helperColor, modifier = Modifier.padding(start = 16.dp, top = 6.dp)) }
    }
}

/** 흰 카드 (둥근 16dp, 얇은 그림자). */
@Composable
fun MiconCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    color: Color = Paper,                       // 어두운 배경(턴테이블 시트) 위에서는 어두운 카드로
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color, shadowElevation = if (color == Paper) 3.dp else 0.dp) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 카드 제목 행: 왼쪽 굵은 제목, 오른쪽 슬롯(보라 라벨 등). */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier, color: Color = Ink, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 알약 칩. selected → 보라 배경 흰 글자, 아니면 흰 배경 + 테두리 + 그림자. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    fontSize: TextUnit = 15.sp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) Purple else Paper,
        shadowElevation = 3.dp,
        border = if (selected) null else BorderStroke(1.dp, GreyLight),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(6.dp)) }
            Text(text, color = if (selected) Paper else InkSoft, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
            if (chevron) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = if (selected) Paper else InkSoft)
        }
    }
}

/** 연보라 배경의 작은 라벨 칩 (추천 장르 등) */
@Composable
fun SoftChip(text: String, bg: Color = LavenderDeep.copy(alpha = 0.55f), fg: Color = Purple) {
    Box(Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// 재생 진행 · 플레이어 카드
// ---------------------------------------------------------------------------

fun formatSec(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** 얇은 진행선 + 보라 점 썸 */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, thumb: Boolean = true) {
    Canvas(modifier.height(14.dp)) {
        val y = size.height / 2f
        val p = progress.coerceIn(0f, 1f)
        drawLine(GreyLight, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
        drawLine(Purple, Offset(0f, y), Offset(size.width * p, y), strokeWidth = 3f)
        if (thumb) drawCircle(Purple, radius = 6.dp.toPx(), center = Offset(size.width * p, y))
    }
}

@Composable
fun TimeProgress(positionSec: Double, durationSec: Double, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatSec(positionSec), fontSize = 14.sp, color = Ink)
        Spacer(Modifier.width(10.dp))
        ProgressLine(if (durationSec > 0) (positionSec / durationSec).toFloat() else 0f, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(formatSec(durationSec), fontSize = 14.sp, color = Ink)
    }
}

/** Figma Frame 18: LP 아트 + ▶, 곡명(보라)·가수, 진행 바 */
@Composable
fun PlayerCard(
    title: String,
    artist: String,
    imageUrl: String?,
    positionSec: Double,
    durationSec: Double,
    playing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** null 이면 읽기 전용 진행 바, 아니면 드래그해 탐색할 수 있는 슬라이더 */
    onSeek: ((Double) -> Unit)? = null,
    caption: String? = null,
) {
    MiconCard(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                VinylArt(imageUrl, 150.dp, fallbackText = title)
                Box(Modifier.size(150.dp * 0.66f).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)))
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(58.dp), tint = Purple)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Purple, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Text(artist, fontSize = 18.sp, color = Grey, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (onSeek == null) TimeProgress(positionSec, durationSec) else SeekableProgress(positionSec, durationSec, onSeek)
        if (caption != null) Text(caption, fontSize = 11.sp, color = Grey, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 시간 표시 + 드래그 탐색 슬라이더. 드래그 중에는 손가락 위치를 보여 주고 놓을 때 한 번 onSeek. */
@Composable
fun SeekableProgress(positionSec: Double, durationSec: Double, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationSec.coerceAtLeast(1.0)
    val shown = dragging ?: (positionSec / duration).toFloat().coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatSec(shown * duration), fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp))
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * duration).toDouble()) }; dragging = null },
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = GreyLight),
        )
        Text(formatSec(durationSec), fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

/** 점선 테두리 상자 (리포트 없음 상태) */
@Composable
fun DashedBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                GreyLight,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                cornerRadius = CornerRadius(14.dp.toPx()),
            )
        }
        content()
    }
}

/** 화면 하단에 붙는 흰 시트 (연습 컨트롤) */
@Composable
fun BottomSheetPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Paper, shadowElevation = 12.dp) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) { content() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, color = Grey, modifier = modifier)
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun RowScope.HSpace(width: Dp) = Spacer(Modifier.width(width))

/** 가운데 정렬된 안내/에러 문구 + 재시도 */
@Composable
fun RetryBlock(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = com.micon.app.ui.theme.Red, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        OutlinePurpleButton("다시 시도", onRetry, height = 44.dp)
    }
}
