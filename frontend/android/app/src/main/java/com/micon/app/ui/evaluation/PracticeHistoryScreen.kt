package com.micon.app.ui.evaluation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.result.PracticeLabels
import com.micon.app.ui.result.ResultDetailCards
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Figma "노래 연습 분석" (평가 탭). 날짜 배지 아래 연습 곡 아코디언.
 * 데이터: 백엔드 `GET /practices`(내 연습 세션, 최신순 최대 50건). 로컬 엔진 job 은 더 쓰지 않는다. 비회원은 NavHost 가 로그인 안내를 보여 준다.
 * 항목: 오른쪽에 종합 점수(결과 있음) 또는 상태(진행 중·중단). 미확인 항목은 빨간 점. 펼치면 "노래 연습 분석 상세" 카드.
 * 삭제: 펼친 항목의 쓰레기통 → 확인 대화상자 → `DELETE /practices/{id}`(BE 2026-09-15 구현). 세션·점수만 지워지고 녹음·음성 분석은 남는다.
 */
class PracticeHistoryViewModel(private val practices: PracticeRepository) : ViewModel() {
    data class Item(val practice: PracticeSummary, val date: String, val seen: Boolean)
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        /** 날짜(yyyy.MM.dd) → 항목, 최신 날짜 먼저 */
        val groups: Map<String, List<Item>> = emptyMap(),
        val expanded: Long? = null,
        /** 삭제 확인 대화상자를 띄운 항목 */
        val confirmDelete: PracticeSummary? = null,
        /** 삭제 요청 중인 항목 */
        val deleting: Long? = null,
        /** 삭제 실패 등 한 줄 안내(스낵바 대용) */
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val seen = HashSet<Long>()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val page = practices.history(page = 0, size = PAGE_SIZE)
                val items = page.items.map { Item(it, PracticeLabels.date(it.startedAt) ?: "날짜 없음", it.id in seen) }
                _state.update { it.copy(loading = false, groups = items.groupBy { i -> i.date }.toSortedMap(compareByDescending { d -> d })) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describeBackendError(e, "연습 기록을 불러오지 못했어요.")) }
            }
        }
    }

    fun toggle(practiceId: Long) {
        seen += practiceId
        _state.update { s ->
            s.copy(
                expanded = if (s.expanded == practiceId) null else practiceId,
                groups = s.groups.mapValues { (_, list) -> list.map { if (it.practice.id == practiceId) it.copy(seen = true) else it } },
            )
        }
    }

    fun askDelete(item: PracticeSummary) = _state.update { it.copy(confirmDelete = item) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** 확인된 삭제. 성공하면 목록에서 바로 빼고(빈 날짜 그룹도 제거), 실패하면 안내만 띄운다. */
    fun confirmDelete() {
        val target = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null, deleting = target.id) }
        viewModelScope.launch {
            try {
                practices.delete(target.id)
                _state.update { s ->
                    s.copy(
                        deleting = null,
                        expanded = if (s.expanded == target.id) null else s.expanded,
                        groups = s.groups.mapValues { (_, list) -> list.filter { it.practice.id != target.id } }.filterValues { it.isNotEmpty() },
                        notice = "연습 기록을 삭제했어요.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(deleting = null, notice = describeBackendError(e, "기록을 삭제하지 못했어요.")) }
            }
        }
    }

    private companion object { const val PAGE_SIZE = 50 }
}

@Composable
fun PracticeHistoryScreen(onOpenResult: (Long) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: PracticeHistoryViewModel = viewModel(factory = viewModelFactory { initializer { PracticeHistoryViewModel(container.practices) } })
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null -> RetryBlock(state.error!!, vm::load)
            state.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 연습 기록이 없어요.\n연습 탭에서 노래를 불러 보세요.", color = Grey, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.groups.forEach { (date, items) ->
                    item(key = "date-$date") { DatePill(date) }
                    items.forEach { item ->
                        item(key = item.practice.id) {
                            HistoryRow(item, expanded = state.expanded == item.practice.id, onToggle = { vm.toggle(item.practice.id) })
                            if (state.expanded == item.practice.id) {
                                Column(Modifier.fillMaxWidth().background(GreyLight.copy(alpha = 0.35f)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                                    ResultDetailCards(item.practice.id, onOpenFull = { onOpenResult(item.practice.id) })
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                        val busy = state.deleting == item.practice.id
                                        TextButton(onClick = { vm.askDelete(item.practice) }, enabled = !busy) {
                                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = if (busy) Grey else Pink)
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (busy) "삭제 중…" else "이 기록 삭제", color = if (busy) Grey else Pink, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    state.confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("연습 기록을 삭제할까요?") },
            text = { Text("${target.songTitle ?: "곡 #${target.songId}"} 연습의 점수와 구간 평가가 지워져요. 되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = vm::confirmDelete) { Text("삭제", color = Pink, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("취소", color = Grey) } },
        )
    }
    state.notice?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("확인", color = Purple) } },
        )
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

@Composable
private fun HistoryRow(item: PracticeHistoryViewModel.Item, expanded: Boolean, onToggle: () -> Unit) {
    val p = item.practice
    val title = p.songTitle ?: "곡 #${p.songId}"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VinylArt(p.imageUrl, 80.dp, fallbackText = title)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(PracticeLabels.key(p.selectedKey), PracticeLabels.scope(p.practiceScope), PracticeLabels.duration(p.durationMs)).joinToString(" · "),
                fontSize = 14.sp, color = Grey,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val score = p.overallScore
            if (score != null) Text("${score.roundToInt()}점", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Purple)
            else Text(PracticeLabels.status(p.status), fontSize = 13.sp, color = Grey)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Ink)
            if (!item.seen) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(Pink, CircleShape))
        }
    }
}
