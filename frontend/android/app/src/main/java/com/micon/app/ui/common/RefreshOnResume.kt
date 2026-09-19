package com.micon.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Refresh server data on first display, return from another screen, and app foregrounding. */
@Composable
internal fun RefreshOnResume(onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
}
