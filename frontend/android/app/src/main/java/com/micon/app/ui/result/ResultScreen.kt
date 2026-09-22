package com.micon.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.BuildConfig
import com.micon.app.data.SongRepository
import com.micon.app.data.practice.PracticeRecord
import com.micon.app.data.practice.PracticeReport
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.SectionEvaluation
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.GreenBar
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal const val RESULT_PREVIEW_PRACTICE_ID = 9_999_999L

/**
 * 연습 기록 상세. 데이터: 백엔드 `GET /practices/{id}`(세션) + `GET /practices/{id}/report`(연습 화면이 저장한 라이브 채점).
 * 로컬 엔진 job 은 더 쓰지 않는다. 결과가 없는 세션(중단·저장 실패)은 상태만 보여 준다.
 */
class ResultViewModel(private val practices: PracticeRepository, private val songs: SongRepository?, private val practiceId: Long) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        /**
         * [report] 가 null 이면 세션은 있지만 저장된 점수가 없다.
         * [detail] 은 절충안 확장(노트 판정·음정 곡선)을 practice-assets 와 결합한 것 — 서버가 아직 확장 필드를 돌려주지 않거나
         * 곡 자산 조회에 실패하면 null 이고 화면은 해당 카드를 숨긴다.
         */
        data class Ready(
            val practice: PracticeRecord,
            val report: PracticeReport?,
            val detail: ReportDetail? = null,
            val artist: String? = null,
            val previousScore: Double? = null,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = State.Loading
            if (BuildConfig.DEBUG && practiceId == RESULT_PREVIEW_PRACTICE_ID) {
                val report = previewPracticeReport()
                _state.value = State.Ready(
                    practice = report.toRecord(),
                    report = report,
                    artist = "백아",
                    previousScore = 78.0,
                )
                return@launch
            }
            _state.value = try {
                // 세션 상세(GET /practices/{practiceId})는 BE 명세에서 제거됨 — 리포트 상세가 곡·키·범위·시간을 함께 준다.
                val report = practices.report(practiceId) ?: throw NoSuchElementException("저장된 연습 결과가 없어요.")
                val practice = report.toRecord()
                val artist = runCatching { songs?.detail(report.songId)?.summary?.artist }.getOrNull()
                val previousScore = runCatching { findPreviousScore(report.songId, report.practiceId) }.getOrNull()
                State.Ready(
                    practice = practice,
                    report = report,
                    detail = buildDetail(practice, report),
                    artist = artist,
                    previousScore = previousScore,
                )
            } catch (e: Exception) {
                State.Error(describeBackendError(e, "연습 기록을 불러오지 못했어요."))
            }
        }
    }

    /** 기록 목록은 최신순이므로 현재 결과를 제외한 같은 곡의 첫 점수가 바로 직전 연습이다. */
    private suspend fun findPreviousScore(songId: String, currentPracticeId: Long): Double? {
        var page = 0
        do {
            val history = practices.history(page = page, size = 100)
            history.items.firstOrNull {
                it.id != currentPracticeId && it.songId == songId && it.overallScore != null
            }?.overallScore?.let { return it }
            page++
        } while (page < history.totalPages)
        return null
    }

    /** 곡 자산 조회 실패(409·네트워크)는 상세만 포기하고 점수 화면은 그대로 보여 준다. */
    private suspend fun buildDetail(practice: PracticeRecord, report: PracticeReport): ReportDetail? {
        val repo = songs ?: return null
        if (!report.hasDetail) return null
        return runCatching { ReportDetailBuilder.build(repo.practiceAssets(practice.songId, practice.selectedKey), report) }.getOrNull()
    }
}

private fun previewPracticeReport() = PracticeReport(
    practiceId = RESULT_PREVIEW_PRACTICE_ID,
    songId = "preview-song",
    songTitle = "첫사랑",
    selectedKey = -1,
    practiceScope = "FULL_SONG",
    sectionId = null,
    durationMs = 214_000,
    status = "COMPLETED",
    overallScore = 84.0,
    pitchScore = 88.0,
    sustainScore = 85.0,
    rhythmScore = 79.0,
    summaryText = "음정이 안정적이고 후렴의 표현력이 좋아요.\n정확 42 · 낮게 5 · 높게 3 · 다른 음 2 · 안 부름 1",
    sections = listOf(
        SectionEvaluation(1, 91.0, true, "도입부가 안정적이에요"),
        SectionEvaluation(2, 84.0, true, "후렴의 감정 표현이 좋아요"),
        SectionEvaluation(3, 72.0, true, "고음 구간을 조금 더 연습해 보세요"),
    ),
    weakSection = null,
    analyzedAt = "2026-09-22T13:30:00",
    initialKey = 0,
    keyChanged = true,
)

/** 서버 값 → 화면 라벨. */
internal object PracticeLabels {
    fun status(status: String): String = when (status) {
        "READY" -> "준비"
        "RECORDING", "IN_PROGRESS" -> "진행 중"
        "PAUSED" -> "일시정지"
        "COMPLETED" -> "완료"
        "ABORTED" -> "중단"
        "FAILED" -> "실패"
        else -> status
    }

    fun scope(scope: String?): String = when (scope) {
        "SECTION" -> "구간 연습"
        "CUSTOM" -> "사용자 구간"
        else -> "전곡"
    }

    fun key(shift: Int): String = if (shift == 0) "원키" else "키 " + (if (shift > 0) "+$shift" else "$shift")

    /** "2026-09-13T21:00:00" → "2026.09.13" */
    fun date(iso: String?): String? = iso?.takeIf { it.length >= 10 }?.substring(0, 10)?.replace('-', '.')

    /** "2026-09-13T21:00:00" → "2026.09.13 21:00" */
    fun dateTime(iso: String?): String? = iso?.takeIf { it.length >= 16 }?.let { date(it) + " " + it.substring(11, 16) }

    fun duration(ms: Int?): String? = ms?.takeIf { it > 0 }?.let { val s = it / 1000; "%d:%02d".format(s / 60, s % 60) }
}

/**
 * 전체 화면 버전(딥링크·연습 종료 후). 한줄 평가 → 곡 행 → 카드들 → "처음으로" 버튼.
 * [onHome] 은 연습 목록(곡 선택)으로 돌아간다.
 */
@Composable
fun ResultScreen(practiceId: Long, onBack: () -> Unit, onHome: (() -> Unit)? = null) {
    com.micon.app.ui.design.organisms.MiconScreen(
        appBar = { com.micon.app.ui.design.organisms.MiconAppBar(title = "연습 분석 상세", onBack = onBack) },
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ResultDetailCards(practiceId, onOpenFull = null, showSong = true, onHome = onHome, onBack = onBack)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 연습 상세 리포트 본문(MR !207 리디자인): 총점 → 지난 연습 비교 → 곡 정보 → 상세 점수·판정 분포 →
 * 한 줄 평가 → 구간 평가 → (서버가 주면) 음절 판정·음정 곡선.
 * onOpenFull 이 있으면 마지막에 "전체 리포트 보기" 링크를 둔다.
 */
@Composable
fun ResultDetailCards(practiceId: Long, onOpenFull: (() -> Unit)?, showSong: Boolean = false, onHome: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val container = rememberAppContainer()
    val vm: ResultViewModel = viewModel(key = "result-$practiceId", factory = viewModelFactory { initializer { ResultViewModel(container.practices, container.songs, practiceId) } })
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        ResultViewModel.State.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
        is ResultViewModel.State.Error -> RetryBlock(s.message, vm::load)
        is ResultViewModel.State.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val practice = s.practice
            val report = s.report
            val detail = s.detail
            val summaryLines = report?.summaryText?.lines()?.filter { it.isNotBlank() } ?: emptyList()

            // 1. 총점 — HTML 프로토타입처럼 화면 상단 중앙에 가장 크게 표시한다.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        report?.overallScore?.roundToInt()?.toString() ?: "-",
                        fontSize = 72.sp,
                        lineHeight = 76.sp,
                        color = Color(0xFF5546C9),
                        fontWeight = FontWeight.Black,
                    )
                    Text("점", fontSize = 16.sp, color = Grey, modifier = Modifier.padding(bottom = 12.dp))
                }

                // 2. 이전 기록 비교
                PracticeComparison(report?.overallScore, s.previousScore)
            }

            // 3. 곡 제목 · 가수 · 날짜
            if (showSong) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(practice.songTitle ?: "곡 #${practice.songId}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                    Text(s.artist ?: "가수 정보 없음", fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center)
                    Text(PracticeLabels.date(practice.startedAt ?: practice.createdAt) ?: "-", fontSize = 12.sp, color = Grey, textAlign = TextAlign.Center)
                }
            }

            if (report != null) {
                // 4. 상세 점수 + 판정 분포
                MiconCard {
                    CardTitle("상세 점수")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        // "유지"(sustain)는 라이브 채점이 내지 않는 값이라 늘 "-" 였다 — 뺀다.
                        // 서버가 채우기 시작하면 PracticeScoreMapper 와 함께 되살린다.
                        BigStat("음정", report.pitchScore)
                        BigStat("박자", report.rhythmScore)
                    }
                    Spacer(Modifier.height(18.dp))
                    val metricText = summaryLines.drop(1).joinToString(" ")
                    JudgementTooltipTitle()
                    Spacer(Modifier.height(10.dp))
                    MetricBars(
                        "Perfect" to metricCount(metricText, "정확"),
                        "Low" to metricCount(metricText, "낮게"),
                        "High" to metricCount(metricText, "높게"),
                        "Bad" to metricCount(metricText, "다른 음"),
                        "Miss" to metricCount(metricText, "안 부름"),
                    )
                }

                // 5. 한 줄 평가
                Column(Modifier.fillMaxWidth().background(Lavender, RoundedCornerShape(16.dp)).padding(18.dp)) {
                    Text("한 줄 평가", fontSize = 12.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        summaryLines.firstOrNull() ?: "점수가 기록됐어요.",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 24.sp,
                    )
                }

                // 6. 구간 평가 — 전부 나열하지 않고 잘한 구간 하나·아쉬운 구간 하나만.
                //    점수가 매겨진 구간이 없으면(구간 자산이 없는 곡) 카드를 아예 그리지 않는다.
                val measured = report.sections.filter { it.measured && it.score != null }
                if (measured.isNotEmpty()) {
                    val best = measured.maxBy { it.score!! }
                    val worst = measured.minBy { it.score!! }
                    MiconCard {
                        CardTitle("구간 평가")
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                            if (best.sectionId == worst.sectionId) {
                                SectionRow(best, label = "부른 구간")
                            } else {
                                SectionRow(best, label = "잘한 구간")
                                SectionRow(worst, label = "아쉬운 구간")
                            }
                        }
                    }
                }

                // 절충안 확장: 서버가 노트 판정·음정 곡선을 돌려줄 때만(hasDetail) 두 카드를 그린다.
                if (detail != null) {
                    SyllableJudgementCard(detail)
                    PitchCurveCard(detail)
                }
            } else {
                Column(Modifier.fillMaxWidth().background(Lavender, RoundedCornerShape(16.dp)).padding(18.dp)) {
                    Text(
                        "저장된 점수가 없어요. 연습을 끝까지 마치면 라이브 채점 점수가 기록돼요.",
                        fontSize = 15.sp, color = InkSoft, lineHeight = 22.sp,
                    )
                }
            }

            onOpenFull?.let {
                Text(
                    "전체 리포트 보기", fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = it).padding(8.dp), textAlign = TextAlign.Center,
                )
            }
            if (onHome != null) {
                Spacer(Modifier.height(4.dp))
                MiconButton("목록으로", onHome, Modifier.fillMaxWidth())
                if (onBack != null) {
                    Spacer(Modifier.height(2.dp))
                    MiconButton("이 곡 다시 연습", onBack, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

/** 지난 연습과의 비교 한 줄. 같은 곡의 직전 점수가 없으면 "처음 연습한 노래예요". */
@Composable
private fun PracticeComparison(currentScore: Double?, previousScore: Double?) {
    if (previousScore == null) {
        Text("처음 연습한 노래예요", fontSize = 14.sp, color = InkSoft, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        return
    }
    if (currentScore == null) return
    val difference = currentScore.roundToInt() - previousScore.roundToInt()
    val text = buildAnnotatedString {
        if (difference == 0) {
            append("지난 연습과 동일해요")
        } else {
            append("지난 연습보다 ")
            withStyle(
                SpanStyle(
                    color = if (difference > 0) Color(0xFF2E9D68) else Red,
                    fontWeight = FontWeight.Bold,
                ),
            ) { append("${kotlin.math.abs(difference)}점") }
            append(if (difference > 0) " 좋아졌어요" else " 부족해요")
        }
    }
    Text(text, fontSize = 14.sp, color = InkSoft, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
}

@Composable
private fun BigStat(label: String, value: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Grey)
        Text(value?.roundToInt()?.toString() ?: "-", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

/** "정확 12" 처럼 summaryText 둘째 줄부터 들어 있는 판정 개수를 뽑는다. 없으면 null → "-" */
private fun metricCount(text: String, sourceLabel: String): Int? =
    Regex("${Regex.escape(sourceLabel)}\\s+(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

/** "판정 분포" 제목 + ⓘ 판정 기준 설명 다이얼로그 */
@Composable
private fun JudgementTooltipTitle() {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("판정 분포", fontSize = 12.sp, color = Grey, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "판정 기준 설명", tint = Grey, modifier = Modifier.size(18.dp))
        }
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }) {
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(start = 22.dp, end = 14.dp, top = 12.dp, bottom = 22.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("판정 기준", fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { expanded = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Grey)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(
                        "Perfect" to "완벽했어요",
                        "Low" to "원음보다 낮게 불렀어요",
                        "High" to "원음보다 높게 불렀어요",
                        "Bad" to "완전 다른 음으로 불렀어요",
                        "Miss" to "감지되지 않았어요",
                    ).forEach { (label, description) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 14.sp, color = Purple, fontWeight = FontWeight.Bold, modifier = Modifier.width(66.dp))
                            Text(description, fontSize = 14.sp, color = InkSoft)
                        }
                    }
                }
            }
        }
    }
}

/** 판정별 개수를 가로 막대로. 가장 많은 판정이 꽉 찬 길이가 된다 */
@Composable
private fun MetricBars(vararg metrics: Pair<String, Int?>) {
    val maxValue = metrics.mapNotNull { it.second }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, color = InkSoft, fontWeight = FontWeight.Medium, modifier = Modifier.width(54.dp))
                Box(Modifier.weight(1f).height(8.dp).background(Color(0xFFEAE6FA), RoundedCornerShape(50))) {
                    val fraction = ((value ?: 0).toFloat() / maxValue).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(Purple, RoundedCornerShape(50)))
                    }
                }
                Text(
                    value?.toString() ?: "-",
                    fontSize = 12.sp,
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(30.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionRow(section: SectionEvaluation, label: String? = null) {
    val score = section.score
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, fontSize = 11.sp, color = Purple, fontWeight = FontWeight.SemiBold)
            Text(section.summary ?: "구간 #${section.sectionId}", fontSize = 13.sp, color = if (section.measured) Ink else Grey, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (section.measured && score != null) "${score.roundToInt()}" else "-",
            fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = when {
                !section.measured || score == null -> Grey
                score >= 80 -> GreenBar
                score >= 60 -> Purple
                else -> Red
            },
        )
    }
}
