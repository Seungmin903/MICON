package com.micon.app.ui.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test

class PlaybackLifecycleEffectTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun `stops at pause before stop and does not resume at start`() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var active = false
        var resumes = 0
        val dispose = bindPlaybackLifecycle(owner.lifecycle, { active = true; resumes++ }, { active = false })
        assertTrue(active)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertFalse(active)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertFalse(active)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(active)
        dispose()
        assertFalse(active)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertFalse(active)
        assertEquals(2, resumes)
    }

    @Test fun `non resumed screen stops immediately on binding`() {
        val owner = Owner()
        var stopped = false
        val dispose = bindPlaybackLifecycle(owner.lifecycle, { fail("must not activate") }, { stopped = true })
        assertTrue(stopped)
        dispose()
    }
}
