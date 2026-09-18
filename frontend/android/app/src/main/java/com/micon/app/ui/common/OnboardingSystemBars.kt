package com.micon.app.ui.common

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal class OnboardingChromeState {
    var dark by mutableStateOf(false)
}

internal val LocalOnboardingChrome = staticCompositionLocalOf<OnboardingChromeState?> { null }

/** The root Scaffold also paints its system-inset background with the overlay's color. */
@Composable
internal fun OnboardingDarkBackground(active: Boolean) {
    val chrome = LocalOnboardingChrome.current
    DisposableEffect(chrome, active) {
        chrome?.dark = active
        onDispose { chrome?.dark = false }
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun OnboardingSystemBars() {
    val view = LocalView.current
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull() ?: return
    DisposableEffect(view, activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        val previousStatus = controller.isAppearanceLightStatusBars
        val previousNavigation = controller.isAppearanceLightNavigationBars
        val previousColor = window.navigationBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else null
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatus
            controller.isAppearanceLightNavigationBars = previousNavigation
            window.navigationBarColor = previousColor
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = checkNotNull(previousContrast)
        }
    }
}
