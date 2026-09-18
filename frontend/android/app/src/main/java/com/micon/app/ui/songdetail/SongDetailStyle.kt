package com.micon.app.ui.songdetail

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The existing turntable sheet palette, scoped to song detail rather than the whole app. */
internal object SongDetailColors {
    val background = Color(0xFF10161F)
    val card = Color(0xFF1A2230)
    val inset = Color(0xFF141B27)
    val title = Color.White
    val body = Color(0xFFD3D8E3)
    val muted = Color(0xFFABB3C3)
    val track = Color(0xFF303B4E)
    val accent = Color(0xFFB3A5FF)
    val accentSurface = Color(0xFF292541)
    val rangeBand = Color(0xFF302B49)
    val measured = Color(0xFF7E8EA8)
    val warning = Color(0xFFFF7DA3)
    val songWarning = Color(0xFFFFBE64)
    val scheme = darkColorScheme(
        primary = accent, onPrimary = background,
        primaryContainer = accentSurface, onPrimaryContainer = accent,
        secondary = accent, secondaryContainer = accentSurface, onSecondaryContainer = title,
        background = background, onBackground = title, surface = card, onSurface = title,
        surfaceVariant = inset, onSurfaceVariant = body, outline = track,
    )
}

@Composable
internal fun SongDetailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SongDetailColors.scheme, typography = MaterialTheme.typography, content = content)
}

/** MiconRoot draws dark system-inset backgrounds on detail routes. Restore icon style on exit. */
@Composable
internal fun SongDetailSystemBars() {
    val view = LocalView.current
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull() ?: return
    DisposableEffect(view, activity) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousStatus = controller.isAppearanceLightStatusBars
        val previousNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatus
            controller.isAppearanceLightNavigationBars = previousNavigation
        }
    }
}
