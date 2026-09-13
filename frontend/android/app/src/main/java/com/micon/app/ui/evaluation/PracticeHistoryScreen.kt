package com.micon.app.ui.evaluation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.SongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.Job
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.result.ResultDetailCards
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Figma "노래 연습 분석" (평가 탭). 날짜 배지 아래 연습 곡 아코디언.
 * 데이터: 엔진 GET /jobs?kind=performance (practice_sessions 백엔드가 생기면 교체).
 * 항목 상태: 분석 중(스피너) / 완료·미확인(빨간 점) / 완료·확인. 펼치면 "노래 연습 분석 상세" 카드 3개.
 * 삭제: 펼친 항목의 "이 기록 삭제" 또는 행 길게 누르기 → 확인 다이얼로그 → DELETE /jobs/{id} (엔진 workspace 의 녹음·결과 제거).
 */
class PracticeHistoryViewModel(
    private val engine: EngineClient,
    private val repo: SongRepository,
    private val allowedJobIds: (() -> Set<String>)? = null,
) : ViewModel() {
    data class Item(val job: Job, val title: String, val artist: String, val imageUrl: String?, val date: String, val seen: Boolean)
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val groups: Map<String, List<Item>> = emptyMap(),
        val expanded: String? = null,
        /** 삭제 요청이 진행 중인 job id (행 스피너, 버튼 비활성) */
        val deleting: Set<String> = emptySet(),
        /** 일회성 안내(토스트). 화면이 보여 준 뒤 [consumeToast]. */
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val seen = HashSet<String>()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val jobs = engine.api.jobs(kind = "performance", limit = 50).jobs
                    .let { all -> allowedJobIds?.invoke()?.let { allowed -> all.filter { it.id in allowed } } ?: all }
                val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
                val items = jobs.map { job ->
                    val song = job.referenceJobId?.toLongOrNull()?.let { runCatching { repo.detail(it) }.getOrNull() }?.summary   // 엔진 job 은 백엔드 song_id 를 모른다 → 곡 정보 없이 표시. 백엔드 /practices 로 옮기면 해결
                    Item(
                        job = job,
                        title = song?.title ?: job.referenceFilename ?: job.referenceJobId ?: job.id,
                        artist = song?.artist ?: "",
                        imageUrl = song?.imageUrl,
                        date = job.createdAt?.let { fmt.format(Date((it * 1000).toLong())) } ?: "날짜 없음",
                        seen = job.id in seen,
                    )
                }
                _state.update { it.copy(loading = false, groups = items.groupBy { i -> i.date }.toSortedMap(compareByDescending { d -> d })) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = engine.describe(e)) }
            }
        }
    }

    /** 부른 노래 기록 삭제. 성공하면 재조회 없이 목록에서 바로 빼고, 실패하면 이유를 토스트로 알린다. */
    fun delete(jobId: String) {
        if (jobId in _state.value.deleting) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = it.deleting + jobId) }
            try {
                val res = engine.api.deleteJob(jobId)
                if (!res.isSuccessful) throw retrofit2.HttpException(res)
                seen -= jobId
                _state.update { s ->
                    val groups = s.groups.mapValues { (_, list) -> list.filterNot { it.job.id == jobId } }.filterValues { it.isNotEmpty() }
                    s.copy(
                        groups = groups.toSortedMap(compareByDescending { d -> d }),
                        expanded = if (s.expanded == jobId) null else s.expanded,
                        deleting = s.deleting - jobId,
                        toast = "기록을 삭제했어요.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(deleting = it.deleting - jobId, toast = "삭제하지 못했어요: ${engine.describe(e)}") }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    fun toggle(jobId: String) {
        seen += jobId
        _state.update { s ->
            s.copy(
                expanded = if (s.expanded == jobId) null else jobId,
                groups = s.groups.mapValues { (_, list) -> list.map { if (it.job.id == jobId) it.copy(seen = true) else it } },
            )
        }
    }
}

@Composable
fun PracticeHistoryScreen(
    onOpenResult: (String) -> Unit,
    onSettings: () -> Unit,
    onLogin: (() -> Unit)? = null,
    guestJobIds: (() -> Set<String>)? = null,
) {
    val container = rememberAppContainer()
    val vm: PracticeHistoryViewModel = viewModel(factory = viewModelFactory {
        initializer { PracticeHistoryViewModel(container.engine, container.songs, guestJobIds) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<PracticeHistoryViewModel.Item?>(null) }

    LaunchedEffect(state.toast) {
        state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() }
    }
    confirmDelete?.let { target ->
        DeleteConfirmDialog(
            title = target.title,
            onDismiss = { confirmDelete = null },
            onConfirm = { confirmDelete = null; vm.delete(target.job.id) },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings, onLogin = onLogin)
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null -> RetryBlock(state.error!!, vm::load)
            state.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 연습 기록이 없어요.\n연습 탭에서 노래를 불러 보세요.", color = Grey, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.groups.forEach { (date, items) ->
                    item(key = "date-$date") { DatePill(date) }
                    items.forEach { item ->
                        item(key = item.job.id) {
                            val deleting = item.job.id in state.deleting
                            HistoryRow(
                                item,
                                expanded = state.expanded == item.job.id,
                                deleting = deleting,
                                onToggle = { vm.toggle(item.job.id) },
                                onLongPress = { if (item.job.isDone && !deleting) confirmDelete = item },
                            )
                            if (state.expanded == item.job.id) {
                                Column(Modifier.fillMaxWidth().background(GreyLight.copy(alpha = 0.35f)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                                    ResultDetailCards(item.job.id, onOpenFull = { onOpenResult(item.job.id) })
                                    Spacer(Modifier.height(8.dp))
                                    DeleteRecordButton(enabled = !deleting, onClick = { confirmDelete = item })
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun DatePill(date: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 76.dp, vertical = 14.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp).background(Purple, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Text(date, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: PracticeHistoryViewModel.Item, expanded: Boolean, deleting: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VinylArt(item.imageUrl, 80.dp, fallbackText = item.title)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.artist, fontSize = 14.sp, color = Grey)
                if (item.job.compareOptions?.let { true } == true && item.job.sourceType == "mix") {
                    Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.Mic, "반주 포함 녹음", Modifier.size(16.dp), tint = Ink)
                }
            }
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                deleting -> CircularProgressIndicator(Modifier.size(24.dp), color = Red, strokeWidth = 2.dp)
                !item.job.isDone -> CircularProgressIndicator(Modifier.size(24.dp), color = GreyLight, strokeWidth = 2.dp)
                else -> {
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Ink)
                    if (!item.seen) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(Pink, CircleShape))
                }
            }
        }
    }
}

/** 펼친 기록 하단의 "이 기록 삭제" (Figma 에 없는 추가 동작). 행 길게 누르기와 같은 확인 다이얼로그로 이어진다. */
@Composable
private fun DeleteRecordButton(enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = if (enabled) Red else Grey)
            Spacer(Modifier.width(6.dp))
            Text("이 기록 삭제", color = if (enabled) Red else Grey, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeleteConfirmDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("기록을 삭제할까요?", color = Ink, fontWeight = FontWeight.Bold) },
        text = { Text("'$title' 연습 기록과 녹음이 지워지고 되돌릴 수 없어요.", color = InkSoft) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제", color = Red, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Grey) } },
    )
}
