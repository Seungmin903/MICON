package com.micon.app.ui.design.organisms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.micon.app.R
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 하단 물방울 탭바에 가리지 않도록 탭 화면이 확보해야 하는 바닥 여백.
 * 탭바가 있는 라우트에서만 0 이 아니다(루트가 넣어 준다). 시스템 내비 바 높이는 포함하지 않으므로
 * 스크롤 컨텐츠는 `navigationBarsPadding()` 을 따로 더한다 — [MiconScreen] 이 대신 처리한다.
 */
val LocalTabBarPadding = compositionLocalOf { 0.dp }

/**
 * 화면 뼈대. 배경(라벤더 틴트) · 상태바 여백 · 앱바 · 본문 · 하단 고정 영역.
 *
 * 기기별 화면 크기(S10 360×760 · S24 384×854 · S23U 412×883)와 3버튼/제스처 내비 바를
 * 여기서만 처리한다 — 화면 코드는 고정 dp 여백을 갖지 않는다.
 *
 * - [scrollable] 이면 본문이 세로 스크롤이고, 바닥에 탭바·시스템 바 여백이 자동으로 붙는다.
 * - [bottom] 은 스크롤 밖 하단 고정 영역(주 버튼). 시스템 내비 바 위에 놓인다.
 */
@Composable
fun MiconScreen(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    appBar: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    horizontalPadding: Dp = MiconSpace.screenHorizontal,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(MiconSpace.sm),
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tabPad = LocalTabBarPadding.current
    Column(modifier.fillMaxSize().background(background).statusBarsPadding()) {
        appBar?.invoke()
        val body = Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = horizontalPadding)
        Column(body, verticalArrangement = verticalArrangement) {
            content()
            if (bottom == null) {
                // 탭바(있으면) + 시스템 내비 바만큼 비운다. 탭바 없는 화면은 시스템 바만.
                Spacer(Modifier.navigationBarsPadding().heightIn(min = tabPad))
            }
        }
        if (bottom != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(top = MiconSpace.sm)
                    .navigationBarsPadding()
                    .padding(bottom = MiconSpace.md),
                verticalArrangement = Arrangement.spacedBy(MiconSpace.xs),
                content = bottom,
            )
        }
    }
}

/**
 * 앱바(h56, 좌우 8). 프로토타입 `.appbar`.
 * - 탭 화면: 마크 로고 + 탭 제목 17/900
 * - 하위 화면: ← 뒤로 + 제목 17/700 (제목 없이 ←만도 가능)
 */
@Composable
fun MiconAppBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    showMark: Boolean = onBack == null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MiconSpace.topBarMinHeight)
            .padding(horizontal = MiconSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onBack, Modifier.size(MiconSpace.touchTarget)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", modifier = Modifier.size(24.dp))
            }
        } else {
            Spacer(Modifier.width(MiconSpace.sm))
        }
        if (showMark) {
            // 연습 탭과 같은 MICON 로고로 모든 탭 헤더를 통일한다
            Image(
                painter = painterResource(R.drawable.micon_logo),
                contentDescription = "MICON",
                modifier = Modifier.height(22.dp).width(88.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(MiconSpace.xs))
        }
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = 17.sp,
                fontWeight = if (onBack == null) FontWeight.Black else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions?.invoke(this)
    }
}

/** 프로토타입 `.tabpad`/`.pad` 를 쓰지 않는 특수 화면(홀로 등)이 직접 쓸 여백 값 */
@Composable
fun tabBarContentPadding(): PaddingValues = PaddingValues(bottom = LocalTabBarPadding.current)
