package com.micon.app.ui.evaluation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        val notice: String? = null,
    ) {
        private val scored get() = items.filter { it.overallScore != null }
        val weekItems get() = scored.filter { it.startedAt?.let(::isThisWeek) == true }
        val lastWeekItems get() = scored.filter { it.startedAt?.let(::isLastWeek) == true }
        val weekAverage: Int? get() = weekItems.map { it.overallScore!! }.average().takeIf { weekItems.isNotEmpty() }?.roundToInt()
        val weekDelta: Int? get() {
            val now = weekAverage ?: return null
            val prev = lastWeekItems.map { it.overallScore!! }.average().takeIf { lastWeekItems.isNotEmpty() } ?: return null
            return now - prev.roundToInt()
        }
        /** 스파크라인: 최근 7회(오래된 것 → 최신) */
        val spark get() = scored.take(7).reversed().map { it.overallScore!!.roundToInt() }
        val visible get() = when (filter) {
            HistoryFilter.ALL -> items
            HistoryFilter.WEEK -> items.filter { it.startedAt?.let(::isThisWeek) == true }
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
    fun confirmDelete() {
        val target = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        viewModelScope.launch {
            try { practices.delete(target.id); _state.update { s -> s.copy(items = s.items.filter { it.id != target.id }, notice = "연습 기록을 삭제했어요.") } }
            catch (e: Exception) { _state.update { it.copy(notice = describeBackendError(e, "기록을 삭제하지 못했어요.")) } }
        }
    }
}

private fun parse(iso: String): LocalDate? = runCatching { LocalDateTime.parse(iso.take(19)).toLocalDate() }.getOrNull()
private fun weekStart(d: LocalDate) = d.minusDays((d.dayOfWeek.value - 1).toLong())
private fun isThisWeek(iso: String): Boolean { val d = parse(iso) ?: return false; val ws = weekStart(LocalDate.now()); return !d.isBefore(ws) && d.isBefore(ws.plusDays(7)) }
private fun isLastWeek(iso: String): Boolean { val d = parse(iso) ?: return false; val ws = weekStart(LocalDate.now()).minusDays(7); return !d.isBefore(ws) && d.isBefore(ws.plusDays(7)) }

/** "오늘 · 원래 키" / "어제 · 한 키 ↓" / "9월 17일 · 두 키 ↓" */
internal fun historyMeta(item: PracticeSummary): String {
    val d = item.startedAt?.let(::parse)
    val today = LocalDate.now()
    val day = when {
        d == null -> "날짜 없음"
        d == today -> "오늘"
        d == today.minusDays(1) -> "어제"
        else -> d.format(DateTimeFormatter.ofPattern("M월 d일"))
    }
    val k = item.selectedKey
    val key = when { k == 0 -> "원래 키"; k == -1 -> "한 키 ↓"; k == 1 -> "한 키 ↑"; k < 0 -> "${-k}키 ↓"; else -> "${k}키 ↑" }
    return "$day · $key"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PracticeHistoryScreen(onOpenResult: (Long) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: PracticeHistoryViewModel = viewModel(factory = viewModelFactory { initializer { PracticeHistoryViewModel(container.practices) } })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume { vm.load() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer

    MiconScreen(
        appBar = { MiconAppBar(title = "기록", actions = { IconButton(onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "설정") } }) },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
    ) {
        val avg = state.weekAverage
        if (avg != null) MiconCard(kind = MiconCardKind.Elevated) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("이번 주 평균", style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                state.weekDelta?.let { d -> MiconBadge(if (d >= 0) "지난주 +${d}점" else "지난주 ${d}점", tone = if (d >= 0) MiconChipTone.Success else MiconChipTone.Challenge) }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$avg", fontFamily = MiconMonoFamily, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp)
                Text(" 점 · ${state.weekItems.size}회 연습", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            val spark = state.spark
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                val max = spark.maxOrNull() ?: 0
                spark.forEach { v ->
                    Box(Modifier.weight(1f).fillMaxHeight((v / 100f).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (v == max) MaterialTheme.colorScheme.primary else Color(0xFFDCD6FF)))
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
            HistoryFilter.entries.forEach { f -> MiconFilterChip(f.label, selected = state.filter == f, onClick = { vm.setFilter(f) }, emphasis = true) }
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
                state.visible.forEach { item ->
                    val score = item.overallScore?.roundToInt()
                    SongRow(
                        title = item.songTitle ?: "곡 ${item.songId}", artist = historyMeta(item), imageUrl = item.imageUrl,
                        modifier = Modifier.combinedClickable(onClick = { onOpenResult(item.id) }, onLongClick = { vm.askDelete(item) }),
                        trailing = {
                            if (score != null) Text("$score", fontFamily = MiconMonoFamily, fontSize = 18.sp, fontWeight = FontWeight.Black,
                                color = if (score >= 80) accent else muted)
                            else Text(if (item.status == "COMPLETED") "분석 중" else "미완료", style = MaterialTheme.typography.labelMedium, color = muted)
                        },
                    )
                }
                Text("길게 누르면 기록을 지울 수 있어요", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    state.confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("기록을 삭제할까요?") },
            text = { Text("${target.songTitle ?: "이 곡"} 연습 기록이 지워져요. 되돌릴 수 없어요.") },
            confirmButton = { TextButton(vm::confirmDelete) { Text("삭제") } },
            dismissButton = { TextButton(vm::dismissDelete) { Text("취소") } },
        )
    }
    state.notice?.let { AlertDialog(onDismissRequest = vm::dismissNotice, confirmButton = { TextButton(vm::dismissNotice) { Text("확인") } }, text = { Text(it) }) }
}
