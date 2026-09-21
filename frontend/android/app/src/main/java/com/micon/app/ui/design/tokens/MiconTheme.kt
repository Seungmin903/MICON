package com.micon.app.ui.design.tokens

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 리디자인 테마. 기존 `com.micon.app.ui.theme.MiconTheme` 과 나란히 두고,
 * 화면을 하나씩 옮기면서 이쪽으로 갈아 끼운다. 다 옮기면 옛 테마를 지운다.
 *
 * 다크 모드는 제공하지 않는다(라이트 고정). 다만 **시스템이 다크 모드일 때 상태바
 * 아이콘이 흰색으로 남아 흰 배경에서 안 보이는 문제**가 있어, 여기서 아이콘을
 * 항상 어둡게 고정한다.
 */
@Composable
fun MiconDesignTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    CompositionLocalProvider(MiconColors provides MiconExtendedColors()) {
        MaterialTheme(
            colorScheme = MiconLightColorScheme,
            typography = MiconTypography,
            shapes = MiconShapes,
            content = content,
        )
    }
}
