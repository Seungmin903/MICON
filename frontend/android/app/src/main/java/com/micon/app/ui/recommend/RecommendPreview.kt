package com.micon.app.ui.recommend

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.data.SongRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 추천 탭 미리듣기. 데크 위 CD 가운데를 누르면 그 곡의 원곡 앞 60초를 튼다.
 * 연습용 MR과 분리된 preview(...)의 짧은 만료 URL만 사용한다.
 *
 * 화면(컴포지션)에 묶여 있어 탭을 떠나면 멈추고 풀린다. 앱이 백그라운드로 가면 일시정지.
 * 상태는 Compose state 라 [playing] · [progress] 를 그대로 데크에 넘긴다.
 */
class RecommendPreview(
    context: Context,
    http: OkHttpClient,
    private val songs: SongRepository,
    private val scope: CoroutineScope,
) {
    private val player = InstrumentalPlayer(context.applicationContext, http)

    /** 지금 데크에서 틀 준비가 된(또는 트는) 곡 */
    var songId by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    /** 0..1 */
    var progress by mutableFloatStateOf(0f); private set
    var positionSec by mutableStateOf(0.0); private set
    var durationSec by mutableStateOf<Double?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    private var loadJob: Job? = null
    private var tick: Job? = null

    init {
        player.onEnded = { playing = false; progress = 0f; positionSec = 0.0; player.seekTo(0.0) }
        player.onError = { playing = false; loading = false; error = "미리듣기를 재생하지 못했어요" }
    }

    /** CD 가운데 탭. 같은 곡이면 재생/일시정지, 다른 곡이면 새로 받아 튼다 */
    fun toggle(id: String) {
        if (songId == id && !loading) {
            if (playing) pause() else play()
            return
        }
        start(id)
    }

    private fun start(id: String) {
        loadJob?.cancel()
        tick?.cancel()
        player.pause()
        songId = id
        playing = false
        progress = 0f
        positionSec = 0.0
        durationSec = null
        error = null
        loading = true
        loadJob = scope.launch {
            val url = try {
                songs.preview(id).playbackUrl.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (songId != id) return@launch
            loading = false
            if (url == null) {
                error = "이 곡은 아직 미리듣기가 준비되지 않았어요"
                return@launch
            }
            try {
                player.load(url)
                play()
            } catch (e: Exception) {
                error = "미리듣기를 재생하지 못했어요"
            }
        }
    }

    fun play() {
        if (songId == null) return
        player.play()
        playing = true
        tick?.cancel()
        tick = scope.launch {
            while (isActive && playing) {
                val d = player.durationSec
                durationSec = d
                positionSec = player.positionSec
                progress = if (d != null && d > 0) (positionSec / d).toFloat().coerceIn(0f, 1f) else 0f
                delay(200)
            }
        }
    }

    fun pause() {
        player.pause()
        playing = false
    }

    /** 데크에서 CD 를 빼거나 다른 곡으로 바꿀 때 */
    fun stop() {
        loadJob?.cancel()
        tick?.cancel()
        player.pause()
        songId = null
        playing = false
        loading = false
        progress = 0f
        positionSec = 0.0
        durationSec = null
        error = null
    }

    fun release() {
        stop()
        player.release()
    }
}

@Composable
fun rememberRecommendPreview(songs: SongRepository, http: OkHttpClient): RecommendPreview {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { RecommendPreview(context, http, songs, scope) }
    DisposableEffect(preview) { onDispose { preview.release() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, preview) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) preview.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return preview
}
