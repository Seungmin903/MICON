package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.local.UserStore
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.SharedPreferenceDraftStore
import com.micon.app.ui.common.MiconLogo
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red

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
    PreferenceSurveyContent(
        state = state, isEditing = isEditing, onBack = back, onNext = vm::next, onSave = vm::finish,
        onGenre = vm::toggleGenre, onArtist = vm::toggleArtist, onQuery = vm::setQuery,
        onReload = vm::reload, onRetryArtists = vm::retryArtists, onMoreArtists = vm::loadMoreArtists,
        onRetryDraft = vm::retryDraft,
    )
}

/** Kept separate from Android dependencies so previews and UI tests can cover every visible state. */
@Composable
internal fun PreferenceSurveyContent(
    state: PreferenceSurveyState,
    isEditing: Boolean = false,
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onSave: () -> Unit = {},
    onGenre: (PreferenceGenre) -> Unit = {},
    onArtist: (PreferenceArtist) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onReload: () -> Unit = {},
    onRetryArtists: () -> Unit = {},
    onMoreArtists: () -> Unit = {},
    onRetryDraft: () -> Unit = {},
    keyboardVisible: Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
) {
    val compactHeader = state.step == 1 && keyboardVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize().background(Color.White).imePadding().testTag("preference-survey")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.isSaving, modifier = Modifier.testTag("preferences-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전으로", tint = Ink)
            }
            if (compactHeader) {
                Text("아티스트 찾기", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() })
            } else {
                Spacer(Modifier.weight(1f))
                MiconLogo(barHeight = 18.dp, fontSize = 18.sp)
                if (state.step == 1) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onSave,
                        enabled = state.canFinish,
                        modifier = Modifier.testTag("preferences-complete"),
                    ) {
                        Text(
                            when {
                                state.isSaving -> "저장 중"
                                state.saveError != null -> "다시 저장"
                                isEditing -> "저장"
                                else -> "완료"
                            },
                            color = if (state.canFinish) Purple else Grey,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        if (!compactHeader) Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            if (state.step == 0) {
                Text(if (isEditing) "나의 음악 취향" else "나만의 음악을 만나는 첫걸음", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (state.step == 0) "좋아하는 장르를\n골라주세요" else "좋아하는 아티스트를\n알려주세요",
                fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.step == 0) "자주 듣는 장르를 1개 이상 선택해주세요." else "내 취향에 가까운 아티스트를 1명 이상 선택해주세요.",
                fontSize = 13.sp, lineHeight = 19.sp, color = InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SurveyStep(1, "장르", active = state.step == 0, complete = state.step == 1, Modifier.weight(1f))
                SurveyStep(2, "아티스트", active = state.step == 1, complete = false, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            state.loading -> CenteredStatus("음악 취향을 불러오고 있어요", Modifier.weight(1f), loading = true)
            state.loadError != null -> CenteredStatus(state.loadError, Modifier.weight(1f), onRetry = onReload)
            state.sessionExpired -> CenteredStatus("다시 로그인해주세요", Modifier.weight(1f))
            state.step == 0 -> GenrePicker(state, onGenre, onReload, Modifier.weight(1f))
            else -> ArtistPicker(state, onArtist, onQuery, onRetryArtists, onMoreArtists, Modifier.weight(1f), compact = compactHeader)
        }
        HorizontalDivider(color = GreyLight)
        if (compactHeader) {
            // Keep the search results usable above the IME. Final save stays on the full survey.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("아티스트 ${state.selectedArtists.size}명 선택", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Purple, modifier = Modifier.weight(1f).testTag("preferences-selection-count"))
                TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus() }, modifier = Modifier.testTag("preferences-close-search")) {
                    Text("선택 확인", color = Purple, fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.message != null) Text(state.message, color = Red, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("preferences-save-error"))
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
            val count = if (state.step == 0) "장르 ${state.selectedGenres.size}개 선택" else "아티스트 ${state.selectedArtists.size}명 선택"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(count, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Purple, modifier = Modifier.testTag("preferences-selection-count"))
                Text(if (state.step == 0) "최대 20개" else "최대 50명", fontSize = 12.sp, color = Grey)
            }
            if (state.message != null || state.saveError != null) {
                Text(state.saveError ?: state.message.orEmpty(), color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("preferences-save-error"))
            }
            if (state.draftError != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이 기기에 임시 저장됨", color = InkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetryDraft, enabled = !state.isSaving) { Text("동기화 재시도", fontSize = 12.sp, color = Purple) }
                }
            } else {
                Text(
                    when {
                        state.draftSaving -> "선택한 취향을 임시 저장하고 있어요"
                        isEditing && state.draftPending -> "변경한 취향은 완료 버튼으로 저장해주세요"
                        state.draftPending -> "선택한 취향이 이 기기에 임시 저장됐어요"
                        else -> "음악 취향은 설정에서 언제든 바꿀 수 있어요"
                    },
                    fontSize = 11.sp, color = Grey, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
            }
            if (state.step == 0) {
                Button(
                    onClick = onNext,
                    enabled = state.canContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("preferences-next"),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple),
                ) {
                    Text("다음 · 아티스트 선택", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SurveyStep(number: Int, label: String, active: Boolean, complete: Boolean, modifier: Modifier) {
    Column(modifier.semantics { stateDescription = if (active) "현재 단계" else if (complete) "완료한 단계" else "다음 단계" }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(22.dp).background(if (active || complete) Purple else GreyLight, CircleShape), contentAlignment = Alignment.Center) {
                if (complete) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Color.White)
                else Text(number.toString(), fontSize = 11.sp, color = if (active) Color.White else Grey, fontWeight = FontWeight.Bold)
            }
            Text(label, fontSize = 13.sp, color = if (active || complete) Purple else Grey, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (active || complete) Purple else GreyLight, CircleShape))
    }
}

@Composable
private fun GenrePicker(state: PreferenceSurveyState, onGenre: (PreferenceGenre) -> Unit, onReload: () -> Unit, modifier: Modifier) {
    if (state.genres.isEmpty()) {
        CenteredStatus("아직 선택할 수 있는 장르가 없어요", modifier, onRetry = onReload)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp), modifier = modifier.padding(horizontal = 24.dp).testTag("genre-grid"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(state.genres, key = { it.genreId }) { genre ->
            val selected = genre.genreId in state.selectedGenres
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape)
                    .background(if (selected) Purple else Lavender.copy(alpha = 0.55f))
                    .border(1.dp, if (selected) Purple else Color(0xFFEAE6F5), shape)
                    .toggleable(selected, enabled = !state.isSaving, role = Role.Checkbox, onValueChange = { onGenre(genre) })
                    .testTag("genre-${genre.genreId}").padding(16.dp),
            ) {
                Icon(Icons.Filled.MusicNote, null, Modifier.size(48.dp).align(Alignment.BottomEnd), tint = (if (selected) Color.White else Purple).copy(alpha = 0.14f))
                Column {
                    Icon(if (selected) Icons.Filled.Check else Icons.Filled.MusicNote, null, Modifier.size(20.dp), tint = if (selected) Color.White else Purple)
                    Spacer(Modifier.height(12.dp))
                    Text(genre.name, fontSize = 16.sp, color = if (selected) Color.White else Ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ArtistPicker(
    state: PreferenceSurveyState, onArtist: (PreferenceArtist) -> Unit, onQuery: (String) -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit, modifier: Modifier, compact: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        OutlinedTextField(
            value = state.query, onValueChange = onQuery, enabled = !state.isSaving, singleLine = true,
            placeholder = { Text("아티스트 이름으로 검색", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, "아티스트 검색", tint = Grey) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("preferences-search"),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = GreyLight),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        if (state.selectedArtists.isNotEmpty() && !compact) {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("selected-artists")) {
                items(state.selectedArtists.values.toList(), key = { it.artistId }) { artist ->
                    AssistChip(
                        onClick = { onArtist(artist) }, enabled = !state.isSaving,
                        label = { Text(artist.name, fontSize = 12.sp, color = Purple) },
                        trailingIcon = { Icon(Icons.Filled.Close, "${artist.name} 선택 해제", Modifier.size(14.dp), tint = Purple) },
                        modifier = Modifier.testTag("selected-artist-${artist.artistId}"),
                    )
                }
            }
        } else Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        if (state.artists.isEmpty()) {
            when {
                state.artistsLoading -> CenteredStatus("아티스트를 찾고 있어요", Modifier.weight(1f), loading = true)
                state.artistsError != null -> CenteredStatus(state.artistsError, Modifier.weight(1f), onRetry = onRetry)
                else -> CenteredStatus(if (state.query.isBlank()) "등록된 아티스트가 없어요" else "검색 결과가 없어요\n다른 이름으로 찾아보세요", Modifier.weight(1f), onRetry = if (state.query.isBlank()) onRetry else null)
            }
        } else {
            LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(88.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp).testTag("artist-grid"),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 18.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
            ) {
                items(state.artists, key = { it.artistId }) { artist ->
                    if (compact) ArtistSearchRow(artist, selected = artist.artistId in state.selectedArtists, enabled = !state.isSaving) { onArtist(artist) }
                    else ArtistTile(artist, selected = artist.artistId in state.selectedArtists, enabled = !state.isSaving) { onArtist(artist) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        state.artistsLoading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Purple, strokeWidth = 2.dp) }
                        state.artistsError != null -> CenteredStatus(state.artistsError, onRetry = onRetry)
                        state.hasMoreArtists -> TextButton(onClick = onMore, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().testTag("preferences-more-artists")) { Text("아티스트 더 보기", color = Purple) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSearchRow(artist: PreferenceArtist, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Lavender.copy(alpha = 0.45f) else Color.White)
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            .testTag("artist-${artist.artistId}").padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
            Text(artist.name.take(1), color = Purple, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (!artist.imageUrl.isNullOrBlank()) AsyncImage(
                model = artist.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        }
        Text(artist.name, color = Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(22.dp).background(if (selected) Purple else Color.White, CircleShape)
            .border(1.dp, if (selected) Purple else GreyLight, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = Color.White)
        }
    }
}

@Composable
private fun ArtistTile(artist: PreferenceArtist, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }).testTag("artist-${artist.artistId}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).border(if (selected) 3.dp else 0.dp, if (selected) Purple else Color.Transparent, CircleShape).padding(5.dp)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFECE6FF), Color(0xFFDAD0F5)))),
                contentAlignment = Alignment.Center,
            ) {
                // A real image overlays the initial only when loading succeeds; failures retain the initial.
                Text(artist.name.take(1), color = Purple, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (!artist.imageUrl.isNullOrBlank()) AsyncImage(model = artist.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (selected) Box(Modifier.size(25.dp).align(Alignment.BottomEnd).background(Purple, CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(artist.name, fontSize = 13.sp, color = Ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenteredStatus(message: String, modifier: Modifier = Modifier, loading: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Purple, strokeWidth = 2.dp)
            Spacer(Modifier.height(14.dp))
        }
        Text(message, color = InkSoft, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.testTag("preferences-retry")) { Text("다시 시도", color = Purple) }
    }
}
