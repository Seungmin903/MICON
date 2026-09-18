package com.micon.app.ui.songdetail

import android.content.Context
import com.micon.app.audio.InstrumentalPlayer
import okhttp3.OkHttpClient

/** Main-thread interface: supports deterministic cancellation tests without an Android audio device. */
internal interface SongPreviewPlayer {
    var onError: ((String) -> Unit)?
    var onEnded: (() -> Unit)?
    val positionSec: Double
    val durationSec: Double?
    val isPlaying: Boolean
    val isReady: Boolean
    val playRequested: Boolean
    fun load(url: String)
    fun seekTo(seconds: Double)
    fun play()
    fun pause()
    fun release()
}

internal class Media3SongPreview(context: Context, http: OkHttpClient) : SongPreviewPlayer {
    private val delegate = InstrumentalPlayer(context, http)
    override var onError: ((String) -> Unit)?
        get() = delegate.onError
        set(value) { delegate.onError = value }
    override var onEnded: (() -> Unit)?
        get() = delegate.onEnded
        set(value) { delegate.onEnded = value }
    override val positionSec get() = delegate.positionSec
    override val durationSec get() = delegate.durationSec
    override val isPlaying get() = delegate.isPlaying
    override val isReady get() = delegate.isReady
    override val playRequested get() = delegate.player.playWhenReady
    override fun load(url: String) { delegate.pause(); delegate.load(url) }
    override fun seekTo(seconds: Double) { delegate.seekTo(seconds) }
    override fun play() { delegate.play() }
    override fun pause() { delegate.pause() }
    override fun release() { delegate.release() }
}
