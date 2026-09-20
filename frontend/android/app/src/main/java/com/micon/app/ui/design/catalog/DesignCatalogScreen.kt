package com.micon.app.ui.design.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.CompactDiscSize
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.molecules.ErrorCard
import com.micon.app.ui.design.molecules.EmptyState
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.molecules.RangeVerdict
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.molecules.StepHeader
import com.micon.app.ui.design.organisms.CdPlayer
import com.micon.app.ui.design.organisms.FloatingNavBar
import com.micon.app.ui.design.organisms.MiconTab
import com.micon.app.ui.design.organisms.MiconTopBar
import com.micon.app.ui.design.organisms.ScoreSummary
import com.micon.app.ui.design.tokens.MiconDesignTheme
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 디자인 카탈로그. **debug 빌드에서만 라우트에 등록한다.**
 *
 * 쓰임새 두 가지
 *  1. 새 화면을 만들기 전에 여기서 부품을 고른다. 없으면 캔버스 규격 보드에 먼저 추가하고 만든다.
 *  2. 토큰을 바꿨을 때 앱 전체에 어떻게 퍼지는지 한 화면에서 본다.
 *
 * 아래 Preview 두 개(360×760 · 384×824)는 팀 규칙이다 — 새 컴포넌트를 넣으면 여기에도 넣는다.
 */
@Composable
fun DesignCatalogScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    var selectedTab by remember { mutableStateOf(MiconTab.Recommend) }
    var filterOn by remember { mutableStateOf(true) }

    MiconDesignTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    MiconTopBar(title = "디자인 카탈로그", onBack = onBack)

                    Column(
                        Modifier.padding(
                            start = MiconSpace.screenHorizontal,
                            end = MiconSpace.screenHorizontal,
                            bottom = MiconSpace.navBarContentPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MiconSpace.md),
                    ) {
                        Section("타입 스케일")
                        Text("연습 점수 48", style = MaterialTheme.typography.displayLarge)
                        Text("리포트 수치 28", style = MaterialTheme.typography.headlineMedium)
                        Text("화면 제목 22", style = MaterialTheme.typography.titleLarge)
                        Text("카드 제목 18", style = MaterialTheme.typography.titleMedium)
                        Text("입력 · 가사 16", style = MaterialTheme.typography.bodyLarge)
                        Text("가수명 · 안내 14", style = MaterialTheme.typography.bodyMedium)
                        Text("캡션 12 — 최소 크기", style = MaterialTheme.typography.labelMedium)

                        Section("버튼")
                        MiconButton("연습 시작하기", onClick = {}, modifier = Modifier.fillMaxWidth())
                        MiconButton("찜하기", onClick = {}, kind = MiconButtonKind.Outline)
                        MiconButton("건너뛰기", onClick = {}, kind = MiconButtonKind.Tonal)
                        MiconButton("나중에 할게요", onClick = {}, kind = MiconButtonKind.Text)
                        MiconButton("비활성", onClick = {}, enabled = false)

                        Section("칩 · 배지")
                        Row(horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
                            MiconFilterChip("내 목소리", selected = filterOn, onClick = { filterOn = true })
                            MiconFilterChip("인기", selected = !filterOn, onClick = { filterOn = false })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
                            MiconBadge("한 키 내려")
                            MiconBadge("편해요", tone = MiconChipTone.Success)
                            MiconBadge("고음 도전", tone = MiconChipTone.Challenge)
                        }

                        Section("CD")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MiconSpace.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactDisc(title = "밤편지", artist = "아이유", size = CompactDiscSize.header)
                            CompactDisc(title = "좋은 날", artist = "아이유", size = CompactDiscSize.row)
                            CompactDisc(title = "가을 아침", artist = "아이유", size = CompactDiscSize.rack)
                        }

                        Section("카드")
                        MiconCard { Text("Elevated — 보라 틴트 그림자") }
                        MiconCard(kind = MiconCardKind.Outlined) { Text("Outlined") }
                        MiconCard(kind = MiconCardKind.Well) { Text("Well") }
                        MiconCard(kind = MiconCardKind.Tinted) { Text("Tinted") }

                        Section("곡 한 줄")
                        SongRow(title = "밤편지", artist = "아이유", keyLabel = "한 키 내려", onClick = {})
                        SongRow(title = "좋은 날", artist = "아이유", meta = "어제 · 82점", onClick = {})

                        Section("음역 판정")
                        // 내 음역 낮은 라(57) ~ 중간 솔(67), 곡 음역 중간 도(60) ~ 높은 도(72)
                        RangeVerdict(myLowMidi = 57, myHighMidi = 67, songLowMidi = 60, songHighMidi = 72)
                        Text(
                            "말로 옮기면 — ${NoteNames.rangeText(57, 67)} / ${NoteNames.distanceText(67, 72)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Section("단계 머리")
                        StepHeader(title = "어떤 노래를 좋아하세요?", step = 1, total = 2, subtitle = "3개까지 고를 수 있어요")

                        Section("오류 · 빈 상태")
                        ErrorCard(
                            what = "로그인이 풀렸어요",
                            why = "오래 안 쓰면 안전을 위해 자동으로 풀려요.",
                            actionText = "다시 로그인",
                            onAction = {},
                        )
                        EmptyState(
                            title = "아직 분석한 목소리가 없어요",
                            description = "30초만 불러 주시면 맞는 노래를 찾아드려요.",
                            icon = Icons.Outlined.Album,
                            actionText = "목소리 분석하기",
                            onAction = {},
                        )

                        Section("점수")
                        ScoreSummary(
                            score = 82,
                            comment = "후렴이 안정적이었어요.",
                            bestSection = "후렴",
                            weakSection = "2절 도입",
                        )

                        Section("CD 플레이어")
                        CdPlayer(
                            title = "밤편지",
                            artist = "아이유",
                            trackIndex = 1,
                            trackTotal = 7,
                            onDiscClick = {},
                            onPrev = {},
                            onNext = {},
                            discSize = 180.dp,
                        )
                    }
                }

                FloatingNavBar(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(MiconSpace.xs))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** Galaxy S10 — 좁은 쪽. 고정값은 여기서 먼저 터진다 */
@Preview(name = "S10 360x760", widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun DesignCatalogPreviewS10() = DesignCatalogScreen()

/** Galaxy S23 Ultra */
@Preview(name = "S23U 384x824", widthDp = 384, heightDp = 824, showBackground = true)
@Composable
private fun DesignCatalogPreviewS23Ultra() = DesignCatalogScreen()

/** 글자 크게 설정 — heightIn(min=) 이 제대로 걸렸는지 본다 */
@Preview(name = "S10 fontScale 1.3", widthDp = 360, heightDp = 760, fontScale = 1.3f, showBackground = true)
@Composable
private fun DesignCatalogPreviewLargeText() = DesignCatalogScreen()
