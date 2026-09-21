package com.micon.app.ui.result

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.micon.app.data.SongRepository
import com.micon.app.data.practice.PracticeRecord
import com.micon.app.data.practice.PracticeReport
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.SectionEvaluation
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
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
        data class Ready(val practice: PracticeRecord, val report: PracticeReport?, val detail: ReportDetail? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = State.Loading
            _state.value = try {
                // 세션 상세(GET /practices/{practiceId})는 BE 명세에서 제거됨 — 리포트 상세가 곡·키·범위·시간을 함께 준다.
                val report = practices.report(practiceId) ?: throw NoSuchElementException("저장된 연습 결과가 없어요.")
                val practice = report.toRecord()
                State.Ready(practice, report, detail = buildDetail(practice, report))
            } catch (e: Exception) {
                State.Error(describeBackendError(e, "연습 기록을 불러오지 못했어요."))
            }
        }
    }

    /** 곡 자산 조회 실패(409·네트워크)는 상세만 포기하고 점수 화면은 그대로 보여 준다. */
    private suspend fun buildDetail(practice: PracticeRecord, report: PracticeReport): ReportDetail? {
        val repo = songs ?: return null
        if (!report.hasDetail) return null
        return runCatching { ReportDetailBuilder.build(repo.practiceAssets(practice.songId, practice.selectedKey), report) }.getOrNull()
    }
}

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

/** 카드 제목 아래 한 줄 설명 — 그래프를 처음 보는 사용자용 */
@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = Grey, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
}

/**
 * 평가 탭 아코디언 안에서도 쓰는 카드 묶음: 한줄 평가 · 점수 요약 · 구간 평가 · 연습 정보.
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

            // 한줄 평가: 숫자 대신 말로 먼저(연습 화면이 저장한 summaryText 첫 줄), 둘째 줄부터는 판정 개수
            Column(Modifier.fillMaxWidth().background(Lavender, RoundedCornerShape(16.dp)).padding(18.dp)) {
                Text("한줄 평가", fontSize = 12.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    summaryLines.firstOrNull() ?: if (report == null) "저장된 점수가 없어요. 연습을 끝까지 마치면 라이브 채점 점수가 기록돼요." else "점수가 기록됐어요.",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 24.sp,
                )
                summaryLines.drop(1).forEach { line ->
                    Spacer(Modifier.height(8.dp))
                    Text(line, fontSize = 13.sp, color = InkSoft, lineHeight = 19.sp)
                }
            }

            if (showSong) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(practice.songTitle ?: "곡 #${practice.songId}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            listOfNotNull(PracticeLabels.key(practice.selectedKey), PracticeLabels.scope(practice.practiceScope), PracticeLabels.date(practice.startedAt ?: practice.createdAt)).joinToString(" · "),
                            fontSize = 14.sp, color = Grey,
                        )
                    }
                    report?.overallScore?.let { overall ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${overall.roundToInt()}점", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Purple)
                            Text("등급 ${ScoringMath.gradeOf(overall)}", fontSize = 12.sp, color = Grey)
                        }
                    }
                }
            }

            if (report != null) {
                MiconCard {
                    CardTitle("점수 요약")
                    Caption("100점 만점. 연습 화면의 라이브 채점(음정 65% + 박자 35%)을 그대로 저장한 값이에요.")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BigStat("종합", report.overallScore, report.overallScore?.let(ScoringMath::gradeOf))
                        BigStat("음정", report.pitchScore, null)
                        BigStat("박자", report.rhythmScore, null)
                        BigStat("유지", report.sustainScore, if (report.sustainScore == null) "미측정" else null)
                    }
                    if (report.sustainScore == null) {
                        Caption("음 유지(길게 끄는 음의 안정도)와 호흡은 실시간 채점에서 측정하지 않아 미측정으로 표시돼요.")
                    }
                }

                // 절충안 확장: 서버가 노트 판정·음정 곡선을 돌려줄 때만(hasDetail) 두 카드를 그린다.
                if (detail != null) {
                    SyllableJudgementCard(detail)
                    PitchCurveCard(detail)
                }

                MiconCard {
                    CardTitle("구간 평가")
                    Caption("곡 구간(절·후렴)별 점수예요. 부르지 않은 구간은 회색. 연습 화면의 구간 칩으로 그 구간만 다시 연습할 수 있어요.")
                    Spacer(Modifier.height(10.dp))
                    if (report.sections.isEmpty()) {
                        Text("이 곡은 구간 정보가 없어 전체 점수만 기록됐어요.", fontSize = 14.sp, color = Grey)
                    } else {
                        Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                            report.sections.forEach { SectionRow(it) }
                        }
                    }
                }
            }

            MiconCard {
                CardTitle("연습 정보")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("연습 시각", PracticeLabels.dateTime(practice.startedAt ?: practice.createdAt) ?: "-")
                    InfoRow("부른 길이", PracticeLabels.duration(practice.durationMs) ?: "-")
                    InfoRow("상태", PracticeLabels.status(practice.status) + if (report == null) " · 저장된 점수 없음" else "")
                }
            }

            onOpenFull?.let {
                Text(
                    "전체 리포트 보기", fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = it).padding(8.dp), textAlign = TextAlign.Center,
                )
            }
            if (onHome != null) {
                Spacer(Modifier.height(6.dp))
                PrimaryButton("처음으로 (연습 곡 선택)", onHome, Modifier.fillMaxWidth())
                if (onBack != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinePurpleButton("이 곡 다시 연습", onBack, Modifier.fillMaxWidth(), height = 48.dp)
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun BigStat(label: String, value: Double?, grade: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Grey)
        Text(value?.roundToInt()?.toString() ?: "-", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (grade != null) Text("등급 $grade", fontSize = 11.sp, color = Grey)
    }
}

@Composable
private fun SectionRow(section: SectionEvaluation) {
    val score = section.score
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(section.summary ?: "구간 #${section.sectionId}", fontSize = 13.sp, color = if (section.measured) Ink else Grey, modifier = Modifier.weight(1f), lineHeight = 18.sp)
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = Grey, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
