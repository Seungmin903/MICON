package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.local.UserStore
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.SharedPreferenceDraftStore
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.atoms.MiconSearchField
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer

/**
 * 취향 설문 — 프로토타입 `survey1`(장르 칩) · `survey2`(가수 3열 디스크). 두 단계, 상단 스텝바, 건너뛰기.
 * 설정에서 열면([isEditing]) 제목만 바뀌고 저장 시 돌아간다.
 */
@Composable
fun PreferenceSurveyScreen(
    onDone: () -> Unit,
    onSessionExpired: () -> Unit = {},
    onBack: () -> Unit = {},
    isEditing: Boolean = false,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current.applicationContext
    val userId = TokenManager.getUserId()
    val factory = remember(container, context, userId, isEditing) {
        viewModelFactory {
            initializer {
                PreferenceSurveyViewModel(
                    NetworkPreferenceRepository(container.backend.retrofit.create(PreferenceApi::class.java)),
                    SharedPreferenceDraftStore(context), userId, isEditing,
                    isCurrentUser = { TokenManager.getUserId() == userId && TokenManager.isLoggedIn() },
                    refreshRecommendations = { container.recommend.regenerate() },
                )
            }
        }
    }
    val vm: PreferenceSurveyViewModel = viewModel(key = "music-preferences-$userId-$isEditing", factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val back = { if (!vm.back()) onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            UserStore.preferredGenres = state.selectedGenres.values.map { it.name }.toSet()
            UserStore.preferredArtists = state.selectedArtists.values.map { it.name }.toSet()
            onDone()
        }
    }
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }
    PreferenceSurveyContent(state, isEditing, onBack = back, onSkip = onDone, onNext = vm::next, onSave = vm::finish,
        onGenre = vm::toggleGenre, onArtist = vm::toggleArtist, onQuery = vm::setQuery, onReload = vm::reload,
        onRetryArtists = vm::retryArtists, onMoreArtists = vm::loadMoreArtists, onRetryDraft = vm::retryDraft)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferenceSurveyContent(
    state: PreferenceSurveyState, isEditing: Boolean,
    onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit, onSave: () -> Unit,
    onGenre: (PreferenceGenre) -> Unit, onArtist: (PreferenceArtist) -> Unit, onQuery: (String) -> Unit,
    onReload: () -> Unit, onRetryArtists: () -> Unit, onMoreArtists: () -> Unit, onRetryDraft: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val genreStep = state.step == 0
    val enabled = !state.loading && state.loadError == null && !state.isSaving && !state.sessionExpired && !state.saved

    MiconScreen(
        modifier = Modifier.testTag("preference-survey"),
        appBar = {
            MiconAppBar(onBack = if (!genreStep || isEditing) onBack else null, showMark = false, actions = {
                if (!isEditing) MiconButton("건너뛰기", onSkip, Modifier.testTag("preferences-back"), kind = MiconButtonKind.Text, enabled = !state.isSaving)
            })
        },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.md),
        bottom = {
            if (genreStep) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("${state.selectedGenres.size}", fontFamily = MiconMonoFamily, fontWeight = FontWeight.Bold, color = accent, fontSize = 12.sp)
                    Text("개 선택", fontSize = 12.sp, color = muted)
                }
                MiconButton("다음", onNext, Modifier.fillMaxWidth(), enabled = enabled && state.canContinue)
            } else {
                state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                MiconButton(if (state.isSaving) "저장 중…" else if (isEditing) "저장" else "다음", onSave, Modifier.fillMaxWidth(), enabled = enabled && state.canFinish)
            }
        },
    ) {
        // 스텝바
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { i -> Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= state.step) MaterialTheme.colorScheme.primary else Color(0xFFDCD6FF))) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (genreStep) "1 / 2 · 좋아하는 장르" else "2 / 2 · 좋아하는 가수", style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp)
            Text(if (genreStep) "어떤 노래를 자주 들으세요?" else "자주 부르는 가수가 있나요?", style = MaterialTheme.typography.titleLarge)
            if (genreStep) Text("여러 개 골라도 돼요. 추천에 바로 반영됩니다.", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        when {
            state.sessionExpired -> Text("다시 로그인해 주세요.", color = muted, modifier = Modifier.padding(top = 24.dp))
            state.loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.loadError, color = muted, style = MaterialTheme.typography.bodyMedium)
                MiconButton("다시 불러오기", onReload, kind = MiconButtonKind.Text)
            }
            genreStep -> FlowRow(horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                state.genres.forEach { g -> MiconFilterChip(g.name, selected = g.genreId in state.selectedGenres, onClick = { onGenre(g) }, enabled = enabled) }
            }
            else -> {
                MiconSearchField(state.query, onQuery, "가수 이름 검색")
                if (state.artistsError != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.artistsError, color = muted, style = MaterialTheme.typography.bodyMedium)
                    MiconButton("다시 시도", onRetryArtists, kind = MiconButtonKind.Text)
                }
                // 선택한 가수는 검색 결과에 없어도 맨 앞에
                val shown = (state.selectedArtists.values + state.artists).distinctBy { it.artistId }
                ArtistGrid(shown, state.selectedArtists.keys, enabled, onArtist)
                if (state.artistsLoading) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                else if (state.hasMoreArtists) MiconButton("더 보기", onMoreArtists, Modifier.fillMaxWidth(), kind = MiconButtonKind.Text)
                if (shown.isEmpty() && !state.artistsLoading) Text("검색 결과가 없어요.", color = muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.draftError != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.draftError, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f))
            MiconButton("다시 저장", onRetryDraft, kind = MiconButtonKind.Text)
        }
    }
}

/** 3열 디스크 그리드. 선택된 디스크에는 보라 링 */
@Composable
private fun ArtistGrid(artists: List<PreferenceArtist>, selected: Set<Long>, enabled: Boolean, onArtist: (PreferenceArtist) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        artists.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { a ->
                    val on = a.artistId in selected
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onArtist(a) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(78.dp).then(if (on) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier.padding(3.dp))) {
                            CompactDisc(title = a.name, imageUrl = a.imageUrl, size = 72.dp)
                        }
                        Text(a.name, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
