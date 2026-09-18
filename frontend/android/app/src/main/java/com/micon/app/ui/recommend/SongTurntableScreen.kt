package com.micon.app.ui.recommend

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.R
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.RecommendBackground
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// 턴테이블 화면 — 추천에서 LP 를 누르면 여기로. 아래로 스크롤하면 검은 시트가 올라와 곡 분석을 덮는다.
// 수치는 모두 시안(추천곡 (3).png, 412×917)에서 픽셀로 잰 값. 단위는 화면 폭(W) 또는 높이(H) 배수.
// ---------------------------------------------------------------------------

private const val LP_DIAMETER = 0.706f      // W — 가운데 LP 지름
private const val LP_CENTER_Y = 0.444f      // H — 가운데 LP 중심
private const val PAGE_WIDTH = 0.71f        // W — 이웃 LP 와의 중심 간격
private const val NEIGHBOUR_DROP = 0.165f   // W — 이웃 LP 가 내려앉는 정도(바퀴처럼 굴러가는 느낌)
private const val ARM_PIVOT_X = 0.79f       // W
private const val ARM_PIVOT_Y = 0.234f      // H — 톤암 회전축(피벗 블록)
private const val ARM_LENGTH = 0.62f        // W
private const val ARM_ASPECT = 0.282f       // 에셋 가로/세로
private const val ARM_ANGLE = 62f           // 세로 에셋을 이만큼 시계방향으로 → 시안 s4 의 기울기(수평에서 28°)
private const val SHEET_ARC_R = 0.585f      // W — 시트 윗 원호 반지름
private const val SHEET_TOP = 0.611f        // H — 원호 꼭대기 위치
private val SheetColor = Color(0xFF10161F)  // LP 링과 같은 검정

/**
 * 추천 화면이 "지금 보고 있는 섹션"을 턴테이블 화면에 건네주는 임시 통로.
 * 곡 목록을 네비게이션 인자로 넘기면 URL 이 길어져서, 앱 안 메모리로만 전달한다(백엔드 붙어도 그대로 쓸 수 있다).
 */
object SongCarousel {
    var ids: List<Long> = emptyList()
    var title: String = ""

    fun set(songIds: List<Long>, shelfTitle: String) {
        ids = songIds
        title = shelfTitle
    }

    /** 딥링크 등으로 목록 없이 들어오면 그 곡 하나만 */
    fun idsFor(songId: Long): List<Long> = ids.takeIf { songId in it } ?: listOf(songId)

    fun imageOf(songId: Long): String? = SongCarouselArt.urls[songId]
}

/** 위쪽이 커다란 원호로 둥근 시트. 원호 반지름 = 폭 × [SHEET_ARC_R] 이라 화면 가운데가 가장 높다. */
internal val TurntableSheetShape = GenericShape { size, _ ->
    val r = size.width * SHEET_ARC_R
    val a = size.width / 2f
    val s = sqrt((r * r - a * a).coerceAtLeast(0f))       // 원호가 좌우 끝에서 꼭대기보다 내려온 높이
    val beta = Math.toDegrees(atan2(s.toDouble(), a.toDouble())).toFloat()
    moveTo(0f, r - s)
    arcTo(Rect(a - r, 0f, a + r, 2 * r), -180f + beta, 180f - 2 * beta, false)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/**
 * 곡 턴테이블 + 분석. 위쪽은 LP 를 좌우로 넘기는 턴테이블, 아래로 스크롤하면 검은 시트가 올라와 화면을 덮으며 분석이 나온다.
 * 분석 내용은 기존 [MatchBody] 를 어두운 팔레트로 그대로 쓴다 — 흰 화면 상세와 내용이 갈라지지 않게.
 */
@Composable
fun SongTurntableScreen(songId: Long, onBack: () -> Unit, onPractice: (Long) -> Unit) {
    val ids = remember(songId) { SongCarousel.idsFor(songId) }
    val shelfTitle = remember(songId) { SongCarousel.title }
    val pagerState = rememberPagerState(initialPage = ids.indexOf(songId).coerceAtLeast(0)) { ids.size }
    val currentId = ids.getOrElse(pagerState.currentPage) { songId }

    val container = rememberAppContainer()
    val vm: SongMatchViewModel = viewModel(
        key = "turntable-$currentId",
        factory = viewModelFactory { initializer { SongMatchViewModel(container.songs, container.recommend, currentId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(RecommendBackground)) {
        val w = maxWidth
        val h = maxHeight
        val heroHeight = h * SHEET_TOP
        // 0 = 안 내림, 1 = 시트가 히어로를 다 덮음. 제목·톤암을 사라지게 하는 데 쓴다.
        val collapsed = with(density) { (scroll.value / (heroHeight.toPx() * 0.6f)).coerceIn(0f, 1f) }

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // ---- 히어로: 스크롤의 절반만 따라 올라가 위쪽에 남는다(시안의 스크롤 후 상태) ----
            Box(
                Modifier.fillMaxWidth().height(heroHeight)
                    .graphicsLayer { translationY = scroll.value * 0.5f },
            ) {
                Text(
                    shelfTitle.ifBlank { "추천곡" },
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 36.sp,
                    modifier = Modifier.padding(start = 24.dp, top = 76.dp, end = 96.dp).graphicsLayer { alpha = 1f - collapsed },
                )
                // ---- LP 캐러셀 ---- (톤암보다 먼저 그려야 바늘이 판 위에 얹힌다)
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fixed(w * PAGE_WIDTH),
                    contentPadding = PaddingValues(horizontal = (w - w * PAGE_WIDTH) / 2),
                    pageSpacing = 0.dp,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .offset(y = h * LP_CENTER_Y - w * LP_DIAMETER / 2),
                ) { page ->
                    val away = ((page - pagerState.currentPage) - pagerState.currentPageOffsetFraction).absoluteValue.coerceAtMost(1.4f)
                    Box(Modifier.fillMaxWidth().height(w * LP_DIAMETER), contentAlignment = Alignment.Center) {
                        Box(Modifier.offset(y = w * NEIGHBOUR_DROP * away)) {
                            VinylArt(SongCarouselArt.of(page, ids), w * LP_DIAMETER, fallbackText = "")
                        }
                    }
                }
                // 피벗 뒤 연보라 원 (시안)
                Box(
                    Modifier.offset(x = w * ARM_PIVOT_X - w * 0.098f, y = h * ARM_PIVOT_Y - w * 0.06f)
                        .size(w * 0.196f).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .graphicsLayer { alpha = 1f - collapsed },
                )
                // 톤암 — 피벗을 중심으로 회전. LP 위에 그려진다. 스크롤하면 LP 와 함께 사라진다.
                Box(
                    Modifier.offset(x = w * ARM_PIVOT_X - w * ARM_LENGTH * ARM_ASPECT / 2, y = h * ARM_PIVOT_Y)
                        .size(width = w * ARM_LENGTH * ARM_ASPECT, height = w * ARM_LENGTH)
                        .graphicsLayer {
                            rotationZ = ARM_ANGLE
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.06f)   // 피벗은 에셋 위쪽
                            alpha = 1f - collapsed
                        },
                ) {
                    Image(painterResource(R.drawable.tonearm), contentDescription = null, Modifier.fillMaxSize())
                }
            }

            // ---- 검은 시트: 곡명·가수 + 분석 ----
            Column(
                Modifier.fillMaxWidth().heightIn(min = h).background(SheetColor, TurntableSheetShape).clip(TurntableSheetShape),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(h * 0.075f))
                when (val s = state) {
                    SongMatchViewModel.State.Loading ->
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                    is SongMatchViewModel.State.Error -> RetryBlock(s.message, vm::load)
                    is SongMatchViewModel.State.Ready -> {
                        Text(s.detail.summary.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(s.detail.summary.artist, fontSize = 16.sp, color = DarkMatch.muted)
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "아래로 스크롤 해보세요",
                            fontSize = 14.sp, color = DarkMatch.muted,
                            modifier = Modifier.graphicsLayer { alpha = 1f - collapsed },
                        )
                        Spacer(Modifier.height(8.dp))
                        MatchBody(
                            detail = s.detail, fit = s.fit, profile = profile, onPractice = { onPractice(currentId) },
                            palette = DarkMatch, showHeader = false, ownScroll = false,
                        )
                    }
                }
            }
        }

        // ---- 상단 바: 뒤로 + 스크롤하면 한 줄 제목 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Ink) }
            Text(
                shelfTitle,
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = collapsed },
            )
        }
    }
}

/** 페이지 index → 앨범 이미지. 이미지는 추천에서 이미 받아 둔 것을 쓴다(추가 조회 없음). */
private object SongCarouselArt {
    var urls: Map<Long, String?> = emptyMap()
    fun of(page: Int, ids: List<Long>): String? = urls[ids.getOrNull(page)]
}

/** 추천 화면이 캐러셀에 쓸 곡 목록·제목·앨범 이미지를 넘긴다. */
fun setSongCarousel(ids: List<Long>, title: String, images: Map<Long, String?>) {
    SongCarousel.set(ids, title)
    SongCarouselArt.urls = images
}
