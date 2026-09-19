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
 * 반주는 **항상 원키 파일 하나**만 받고, 키 조절은 [keyShift] 로 재생기 안에서 처리한다.
 * 반음마다 파일을 만들어 S3 에 올리면 저장 부담이 커지기 때문이다.
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
     * 반주 키(반음). 원키 파일을 그대로 두고 재생하면서 음높이만 옮긴다.
     *
     * 속도를 1.0 으로 고정하므로 템포와 길이는 그대로고 음높이만 움직인다. 한 옥타브가 12반음이니
     * 비율은 2^(반음/12) 이다. 새 미디어를 불러도 값이 유지되도록 setter 에서 다시 적용한다.
     *
     * **연습을 시작하기 전에만 바꾼다.** 재생 중에 바꾸면 오디오 버퍼가 비워지면서 소리가 튀고,
     * 시프트 처리로 생기는 출력 지연이 달라져 채점 타이밍 기준이 흔들린다. 시작 전에 고정하면
     * 그 지연은 상수라 자동 지연 보정이 흡수한다.
     */
    var keyShift: Int = 0
        set(value) {
            field = value
            val ratio = Math.pow(2.0, value / 12.0).toFloat()
            player.playbackParameters = PlaybackParameters(/* speed = */ 1f, /* pitch = */ ratio)
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
