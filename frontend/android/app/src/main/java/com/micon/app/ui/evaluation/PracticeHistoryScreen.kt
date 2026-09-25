package com.micon.app.ui.evaluation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.PracticeSummary
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.theme.Red
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 기록 탭 — 프로토타입 `history` (이전 이름 평가). 이번 주 요약 + 평평한 목록. 아코디언은 없앴다. */
enum class HistoryFilter(val label: String) { ALL("전체"), WEEK("이번 주"), TOP("높은 점수순") }

class PracticeHistoryViewModel(private val practices: PracticeRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val items: List<PracticeSummary> = emptyList(),
        val filter: HistoryFilter = HistoryFilter.ALL,
        val confirmDelete: PracticeSummary? = null,
        /** 삭제 실패 전용 알림. 성공은 목록에서 바로 사라지는 것으로 충분하다 */
        val notice: String? = null,
    ) {
        private val scored get() = items.filter { it.overallScore != null }
        /** 서버가 날짜를 하나도 주지 않으면 주간 요약·"이번 주" 탭은 감춘다 — 빈 탭을 고장으로 오해하지 않게 */
        val hasDates get() = items.any { it.occurredAt != null }
        val weekItems get() = scored.filter { it.occurredAt?.let(::isThisWeek) == true }
        val weekAverage: Int? get() = weekItems.map { it.overallScore!! }.average().takeIf { weekItems.isNotEmpty() }?.roundToInt()
        val visible get() = when (filter) {
            HistoryFilter.ALL -> items
            // 날짜가 없으면 "이번 주" 칩 자체를 감추므로, 고른 채로 남아 있어도 전체를 보여 준다
            HistoryFilter.WEEK -> if (!hasDates) items else items.filter { it.occurredAt?.let(::isThisWeek) == true }
            HistoryFilter.TOP -> items.sortedByDescending { it.overallScore ?: -1.0 }
        }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    /** 새로고침이 겹치면 앞선 요청은 버린다 — 느린 옛 응답이 최신 목록을 덮어쓰지 않는다 */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = practices.history(0, 50)
                ensureActive()
                _state.update { it.copy(loading = false, items = page.items) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = describeBackendError(e, "연습 기록을 불러오지 못했어요.")) } }
        }
    }

    fun setFilter(f: HistoryFilter) = _state.update { it.copy(filter = f) }
    fun askDelete(item: PracticeSummary) = _state.update { it.copy(confirmDelete = item) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * 확인을 누르면 목록에서 **바로** 지운다. 서버 호출은 뒤에서 돌고, 실패했을 때만 원래 자리에 되돌리고 알린다.
     * (예전에는 응답을 기다렸다 지워서 삭제가 느리게 느껴졌다.)
     */
    fun confirmDelete() {
        val target = _state.value.confirmDelete ?: return
        val index = _state.value.items.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        // 삭제 전 목록을 가져오던 응답이 나중에 도착해 삭제한 행을 되살리지 못하게 한다.
        loadJob?.cancel()
        _state.update { s -> s.copy(confirmDelete = null, items = s.items.filterNot { it.id == target.id }) }
        viewModelScope.launch {
            try {
                practices.delete(target.id)
                // 서버 상태를 기준으로 요약 점수와 필터 결과까지 다시 맞춘다.
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    val restored = s.items.toMutableList().apply { add(index.coerceIn(0, size), target) }
                    s.copy(items = restored, notice = describeBackendError(e, "기록을 삭제하지 못했어요."))
                }
            }
        }
    }
}

private fun parse(iso: String): LocalDate? = runCatching { LocalDateTime.parse(iso.take(19)).toLocalDate() }.getOrNull()
private fun weekStart(d: LocalDate) = d.minusDays((d.dayOfWeek.value - 1).toLong())
private fun isThisWeek(iso: String): Boolean { val d = parse(iso) ?: return false; val ws = weekStart(LocalDate.now()); return !d.isBefore(ws) && d.isBefore(ws.plusDays(7)) }

/** "오늘 · 원래 키" / "어제 · 한 키 ↓" / "9월 17일 · 두 키 ↓" */
internal fun historyMeta(item: PracticeSummary): String {
    val d = item.occurredAt?.let(::parse)
    val today = LocalDate.now()
    val day = when {
        d == null -> "날짜 기록 없음"
        d == today -> "오늘"
        d == today.minusDays(1) -> "어제"
        else -> d.format(DateTimeFormatter.ofPattern("M월 d일"))
    }
    val k = item.selectedKey
    val key = when { k == 0 -> "원래 키"; k == -1 -> "한 키 ↓"; k == 1 -> "한 키 ↑"; k < 0 -> "${-k}키 ↓"; else -> "${k}키 ↑" }
    return "$day · $key"
}

@Composable
fun PracticeHistoryScreen(onOpenResult: (Long) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: PracticeHistoryViewModel = viewModel(factory = viewModelFactory { initializer { PracticeHistoryViewModel(container.practices) } })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume { vm.load() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    // 아이폰처럼 한 번에 한 행만 열린다
    var openId by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        MiconScreen(
            appBar = { MiconHeader(onSettings = onSettings) },
            verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
        ) {
            val avg = state.weekAverage
            if (avg != null) MiconCard(kind = MiconCardKind.Elevated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이번 주 평균", style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$avg", fontFamily = MiconMonoFamily, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp)
                    Text(" 점 · ${state.weekItems.size}회 연습", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
                val filters = if (state.hasDates) HistoryFilter.entries else HistoryFilter.entries.filter { it != HistoryFilter.WEEK }
                filters.forEach { f -> MiconFilterChip(f.label, selected = state.filter == f, onClick = { vm.setFilter(f) }, emphasis = true) }
            }
            when {
                state.error != null -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error!!, style = MaterialTheme.typography.bodyMedium, color = muted)
                    MiconButton("다시 불러오기", vm::load, kind = MiconButtonKind.Text)
                }
                state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.visible.isEmpty() -> Text(if (state.items.isEmpty()) "아직 연습 기록이 없어요. 연습 탭에서 한 곡 불러 보세요." else "이번 주 기록이 없어요.",
                    style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 24.dp))
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "기록을 왼쪽으로 밀면 삭제할 수 있어요",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                    )
                    state.visible.forEach { item ->
                        val score = item.overallScore?.roundToInt()
                        key(item.id) {
                            SwipeToDeleteRow(
                                opened = openId == item.id,
                                onOpenChange = { open -> openId = if (open) item.id else null },
                                onDelete = { vm.askDelete(item) },
                            ) {
                                SongRow(
                                    title = item.songTitle ?: "곡 ${item.songId}", artist = historyMeta(item), imageUrl = item.imageUrl,
                                    // 열려 있으면 탭은 먼저 닫는 데 쓴다 — 실수로 상세가 열리지 않는다
                                    onClick = { if (openId == item.id) openId = null else onOpenResult(item.id) },
                                    trailing = {
                                        if (score != null) Text("$score", fontFamily = MiconMonoFamily, fontSize = 18.sp, fontWeight = FontWeight.Black,
                                            color = if (score >= 80) accent else muted, modifier = Modifier.padding(end = 12.dp))
                                        else Text(if (item.status == "COMPLETED") "분석 중" else "미완료", style = MaterialTheme.typography.labelMedium,
                                            color = muted, modifier = Modifier.padding(end = 12.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        state.notice?.let { message ->
            LaunchedEffect(message) { delay(3500); vm.dismissNotice() }
            Snackbar(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(MiconSpace.sm),
                action = { TextButton(onClick = vm::dismissNotice) { Text("확인") } },
            ) { Text(message) }
        }
    }

    state.confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { openId = null; vm.dismissDelete() },
            title = { Text("기록을 삭제할까요?") },
            text = { Text("${target.songTitle ?: "이 곡"} 연습 기록이 지워져요. 되돌릴 수 없어요.") },
            confirmButton = { TextButton({ openId = null; vm.confirmDelete() }) { Text("삭제") } },
            dismissButton = { TextButton({ openId = null; vm.dismissDelete() }) { Text("취소") } },
        )
    }
}

/** 드러나는 빨간 영역의 폭. 터치 타깃 48dp 에 "삭제" 글자가 숨 쉴 자리를 더했다. */
private val DeleteActionWidth = 88.dp

/**
 * 아이폰의 목록 삭제. 행을 왼쪽으로 밀면 그 자리에 멈춰 서고, 드러난 빨간 "삭제" 를 눌러야 지워진다.
 *
 * [SwipeToDismissBox] 를 쓰지 않는다 — 임계점을 넘는 순간 값 변경을 거부하며 튕겨 돌아가서
 * 빨간 영역을 볼 틈이 없었다. 열린 상태는 화면이 [opened] 로 들고 있어 한 번에 한 행만 열린다.
 */
@Composable
private fun SwipeToDeleteRow(
    opened: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val delete by rememberUpdatedState(onDelete)
    val openChange by rememberUpdatedState(onOpenChange)
    val maxOffset = with(LocalDensity.current) { DeleteActionWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 바깥에서 열림이 바뀌면(다른 행을 열었다·다이얼로그를 닫았다) 따라 움직인다
    LaunchedEffect(opened, maxOffset) {
        offset.animateTo(if (opened) -maxOffset else 0f, tween(220))
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(MiconSpace.sm))) {
        // 드러나는 폭만 빨강이다 — 살짝 밀었다고 행 전체가 빨개지지 않는다
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(DeleteActionWidth)
                    .background(Red)
                    .clickable { delete() },
                contentAlignment = Alignment.Center,
            ) {
                Text("삭제", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // 뒤의 빨간 영역이 비치지 않도록 행은 불투명하게 깐다
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-maxOffset, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        // 반쯤 넘겼거나 왼쪽으로 튕겼으면 열고, 아니면 닫는다
                        val open = offset.value < -maxOffset / 2 || velocity < -600f
                        openChange(open)
                        offset.animateTo(if (open) -maxOffset else 0f, tween(220))
                    },
                )
                .semantics {
                    customActions = listOf(CustomAccessibilityAction("삭제") { delete(); true })
                },
        ) {
            content()
        }
    }
}
