package com.micon.app.ui.result

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.core.auth.SessionSnapshot
import com.micon.app.core.auth.TokenManager
import com.micon.app.core.auth.matchesRecordingOwner
import com.micon.app.data.practice.PracticeRecordingStore
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.PlaybackLifecycleEffect
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.delay
import java.io.File

/** Only a verified report shows this control; the local store also selects the current account's file. */
@Composable
internal fun PracticeRecordingSection(practiceId: Long, recordings: PracticeRecordingStore) {
    val session = TokenManager.snapshot()
    val file = remember(practiceId, session.userId, session.generation) {
        if (session.isLoggedIn && session.userId > 0) recordings.find(session.userId, practiceId) else null
    }
    if (file == null) {
        Text("이 기기에 저장된 녹음이 없어요.", color = Grey, fontSize = 12.sp)
        return
    }

    val context = LocalContext.current
    val playback = remember(file, session.userId, session.generation) {
        PracticeRecordingPlayback(context, file, session, TokenManager::snapshot)
    }
    var expanded by remember(file) { mutableStateOf(false) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    PlaybackLifecycleEffect(playback, onExit = { playback.release(); expanded = false })
    LaunchedEffect(playback, expanded) {
        while (expanded) {
            delay(250)
            if (!playback.refresh()) { expanded = false; break }
        }
    }

    if (expanded) {
        RecordingPlayerCard(
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            playing = playback.playing,
            error = playback.error,
            onToggle = { if (playback.playing) playback.pause() else playback.play() },
            onSeek = playback::seekTo,
            onSkipBack = { playback.skip(-10_000) },
            onSkipForward = { playback.skip(10_000) },
            onClose = { playback.release(); expanded = false },
        )
    } else {
        RecordingCollapsedCard { expanded = true; playback.play() }
    }
}

/** The debug route renders these same controls without user data or a backend request. */
@Composable
internal fun PreviewPracticeRecordingSection() {
    var expanded by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(37_000) }
    if (expanded) RecordingPlayerCard(
        positionMs = positionMs, durationMs = 134_000, playing = playing, error = null,
        onToggle = { playing = !playing }, onSeek = { positionMs = it },
        onSkipBack = { positionMs = (positionMs - 10_000).coerceAtLeast(0) },
        onSkipForward = { positionMs = (positionMs + 10_000).coerceAtMost(134_000) },
        onClose = { expanded = false },
    ) else RecordingCollapsedCard { expanded = true }
}

@Composable
private fun RecordingCollapsedCard(onClick: () -> Unit) {
    MiconCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("내 녹음", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("마이크로 녹음한 목소리", color = Grey, fontSize = 12.sp)
            }
            MiconButton("듣기", onClick = onClick, modifier = Modifier.semantics { contentDescription = "내 녹음 듣기" },
                kind = MiconButtonKind.Outline)
        }
    }
}

@Composable
private fun RecordingPlayerCard(
    positionMs: Int,
    durationMs: Int,
    playing: Boolean,
    error: String?,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onClose: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    MiconCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("내 녹음", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("마이크로 녹음한 목소리", color = Grey, fontSize = 12.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "녹음 플레이어 닫기") }
        }
        Slider(
            value = dragging ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = it },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            onValueChangeFinished = { dragging?.let { onSeek(it.toInt()) }; dragging = null },
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(positionMs), color = Grey, fontSize = 12.sp)
            Text(clock(durationMs), color = Grey, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSkipBack, modifier = Modifier.size(52.dp).semantics { contentDescription = "10초 전" }) {
                Text("−10", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(20.dp))
            Surface(onClick = onToggle, modifier = Modifier.size(56.dp).clip(CircleShape), color = Purple) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "일시정지" else "재생",
                    tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(20.dp))
            IconButton(onClick = onSkipForward, modifier = Modifier.size(52.dp).semantics { contentDescription = "10초 후" }) {
                Text("+10", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (error != null) Text(error, color = Color(0xFFB43F4B), fontSize = 12.sp)
    }
}

private fun clock(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private class PracticeRecordingPlayback(
    context: Context,
    private val file: File,
    private val owner: SessionSnapshot,
    private val currentSession: () -> SessionSnapshot,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { if (it <= 0) pause() }
    private val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build()
    else null
    private var player: MediaPlayer? = null
    var positionMs by mutableIntStateOf(0); private set
    var durationMs by mutableIntStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private fun currentOwner(): Boolean = currentSession().matchesRecordingOwner(owner)

    fun refresh(): Boolean {
        if (!currentOwner()) { release(); return false }
        if (playing) positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        return true
    }

    fun play() {
        if (!currentOwner()) { release(); error = "로그인 계정이 바뀌어 재생을 멈췄어요."; return }
        if (!file.isFile) { release(); error = "이 기기의 녹음 파일을 찾을 수 없어요."; return }
        try {
            if (player == null) {
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        playing = false
                        positionMs = durationMs
                        abandonFocus()
                    }
                    prepare()
                }
                durationMs = player?.duration?.coerceAtLeast(0) ?: 0
            }
            if (!requestFocus()) { error = "다른 소리가 재생 중이라 녹음을 시작할 수 없어요."; return }
            if (durationMs > 0 && positionMs >= durationMs) seekTo(0)
            player?.start()
            playing = true
            error = null
        } catch (_: Exception) {
            release()
            error = "녹음을 재생하지 못했어요."
        }
    }

    fun pause() {
        if (playing) runCatching { player?.pause() }
        playing = false
        positionMs = runCatching { player?.currentPosition ?: positionMs }.getOrDefault(positionMs)
        abandonFocus()
    }

    fun seekTo(position: Int) {
        if (!currentOwner()) { release(); return }
        val target = position.coerceIn(0, durationMs)
        runCatching { player?.seekTo(target); positionMs = target }
    }

    fun skip(deltaMs: Int) = seekTo(positionMs + deltaMs)

    fun release() {
        player?.runCatching { release() }
        player = null
        playing = false
        positionMs = 0
        durationMs = 0
        abandonFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    else audioManager.requestAudioFocus(
        focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(focusRequest!!)
        else audioManager.abandonAudioFocus(focusListener)
    }
}
