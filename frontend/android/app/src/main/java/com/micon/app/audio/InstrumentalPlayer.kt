package com.micon.app.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * 반주(Opus/ogg) 스트리밍 재생. Media3 ExoPlayer.
 * 메인 스레드에서 만들고 호출할 것. 화면을 떠날 때 [release].
 *
 * 키별로 미리 렌더된 반주 URL을 받아 재생한다. 키 전환은 ViewModel이 현재 위치를 저장한 뒤
 * 새 미디어를 준비하고 같은 위치로 이동하는 방식으로 처리한다.
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

    /**
     * 곡 상세의 짧은 미리듣기 전용 로컬 피치 설정. 연습 화면은 이 값을 사용하지 않고 키별 S3 파일을 교체한다.
     */
    var keyShift: Int = 0
        set(value) {
            field = value
            val ratio = Math.pow(2.0, value / 12.0).toFloat()
            player.playbackParameters = PlaybackParameters(1f, ratio)
        }

    val positionSec: Double get() = player.currentPosition / 1000.0
    val durationSec: Double? get() = player.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 }
    val isPlaying: Boolean get() = player.isPlaying
    val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    val isReady: Boolean get() = player.playbackState == Player.STATE_READY
    val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING
    val isIdle: Boolean get() = player.playbackState == Player.STATE_IDLE

    /**
     * 스트림이 재생 가능(STATE_READY)해질 때까지 기다린다. 오류·IDLE 이면 false, [timeoutMs] 초과도 false.
     * 카운트다운 전에 호출해 "소리 없이 타이머만 흐르는" 상황을 막는다.
     */
    suspend fun awaitReady(timeoutMs: Long = 8000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (player.playbackState) {
                Player.STATE_READY -> return true
                Player.STATE_IDLE -> return false      // prepare 전 또는 오류
                else -> kotlinx.coroutines.delay(80)
            }
        }
        return player.playbackState == Player.STATE_READY
    }

    fun seekTo(seconds: Double) = player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
    fun play() = player.play()
    fun pause() = player.pause()
    fun release() = player.release()
}
