package com.micon.app.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * 엔진 `GET /jobs/{id}/files/instrumental?semitones=N` (Opus/ogg) 스트리밍 재생. Media3 ExoPlayer.
 * 메인 스레드에서 만들고 호출할 것. 화면을 떠날 때 [release].
 */
class InstrumentalPlayer(context: Context, okHttp: OkHttpClient) {
    @OptIn(UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttp)))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    var onError: ((String) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onError?.invoke(error.message ?: "반주를 재생하지 못했습니다.")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
    }

    fun load(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    val positionSec: Double get() = player.currentPosition / 1000.0
    val durationSec: Double? get() = player.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 }
    val isPlaying: Boolean get() = player.isPlaying
    val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    val isReady: Boolean get() = player.playbackState == Player.STATE_READY

    fun seekTo(seconds: Double) = player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
    fun play() = player.play()
    fun pause() = player.pause()
    fun release() = player.release()
}
