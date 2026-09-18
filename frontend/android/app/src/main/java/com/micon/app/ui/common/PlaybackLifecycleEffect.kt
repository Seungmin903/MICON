package com.micon.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Navigation entries leave RESUMED before their transition finishes or their ViewModel is cleared. */
internal fun bindPlaybackLifecycle(lifecycle: Lifecycle, onResume: () -> Unit, onExit: () -> Unit): () -> Unit {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> onResume()
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> onExit()
            else -> Unit
        }
    }
    lifecycle.addObserver(observer)
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onExit()
    // addObserver synchronously catches up through ON_RESUME when already resumed.
    return { lifecycle.removeObserver(observer); onExit() }
}

@Composable
internal fun PlaybackLifecycleEffect(ownerKey: Any, onResume: () -> Unit = {}, onExit: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resume = rememberUpdatedState(onResume)
    val exit = rememberUpdatedState(onExit)
    DisposableEffect(lifecycle, ownerKey) {
        val dispose = bindPlaybackLifecycle(lifecycle, { resume.value() }, { exit.value() })
        onDispose { dispose() }
    }
}
