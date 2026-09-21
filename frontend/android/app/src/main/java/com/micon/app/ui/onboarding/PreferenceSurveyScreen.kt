package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
    PreferenceSurveyContent(
        state = state, isEditing = isEditing, onBack = back, onNext = vm::next, onSave = vm::finish,
        onGenre = vm::toggleGenre, onArtist = vm::toggleArtist, onQuery = vm::setQuery,
        onReload = vm::reload, onRetryArtists = vm::retryArtists, onMoreArtists = vm::loadMoreArtists,
        onRetryDraft = vm::retryDraft, onStep = vm::selectStep,
    )
}

/** Search, selection and save states share the same content in onboarding and settings. */
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
    onStep: (Int) -> Unit = {},
    keyboardVisible: Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
) {
    val artistStep = state.step == 1
    val compactHeader = artistStep && keyboardVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showCredits by remember { mutableStateOf(false) }
    val photoCredits = (state.artists + state.selectedArtists.values).filter { !it.imageSourceUrl.isNullOrBlank() }
        .distinctBy { it.imageSourceUrl }
    val enabled = !state.loading && state.loadError == null && !state.isSaving && !state.sessionExpired && !state.saved
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC)).imePadding().testTag("preference-survey")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artistStep || isEditing) IconButton(onClick = onBack, enabled = !state.isSaving, modifier = Modifier.testTag("preferences-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전으로", tint = Ink)
            } else Spacer(Modifier.size(48.dp))
            if (compactHeader) Text("가수 찾기", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Ink, modifier = Modifier.weight(1f).semantics { heading() })
            else {
                Spacer(Modifier.weight(1f))
                if (photoCredits.isNotEmpty()) TextButton(onClick = { showCredits = true }, modifier = Modifier.testTag("photo-credits")) {
                    Text("사진 출처", color = Grey, fontSize = 11.sp)
                }
            }
        }
        if (!compactHeader) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.Start) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Purple))
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (artistStep) Purple else Color(0xFFDCD6FF)))
            }
            Spacer(Modifier.height(8.dp))
            Text(if (artistStep) "2 / 2 · 좋아하는 가수" else "1 / 2 · 좋아하는 장르",
                color = Color(0xFF5546C9), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (artistStep) "어떤 가수의 노래를 자주 부르나요?" else "어떤 노래를 자주 들으세요?",
                fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, color = Ink,
                modifier = Modifier.semantics { heading() })
            if (!artistStep) {
                Spacer(Modifier.height(8.dp))
                Text("한 개 이상 골라 주세요. 추천에 반영됩니다.", color = Grey, fontSize = 13.5.sp)
            } else{
                Spacer(Modifier.height(8.dp))
                Text("한 명 이상 골라 주세요. 추천에 반영됩니다.", color = Grey, fontSize = 13.5.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
        when {
            state.loading -> CenteredStatus("음악 취향을 불러오고 있어요", Modifier.weight(1f), loading = true)
            state.loadError != null -> CenteredStatus(state.loadError, Modifier.weight(1f), onRetry = onReload)
            state.sessionExpired -> CenteredStatus("다시 로그인해주세요", Modifier.weight(1f))
            artistStep -> ArtistPicker(state, onArtist, onQuery, onRetryArtists, onMoreArtists, Modifier.weight(1f), compactHeader)
            else -> GenrePicker(state, onGenre, onReload, Modifier.weight(1f))
        }
        if (compactHeader) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("가수 ${state.selectedArtists.size}명 선택", fontSize = 13.sp, color = Purple,
                    modifier = Modifier.weight(1f).testTag("preferences-selection-count"))
                TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus() }, modifier = Modifier.testTag("preferences-close-search")) {
                    Text("선택 확인", color = Purple)
                }
            }
            if (state.message != null) Text(state.message, color = Red, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp).testTag("preferences-save-error"))
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(if (artistStep) "${state.selectedArtists.size}명 선택" else "${state.selectedGenres.size}개 선택",
                color = Color(0xFF5546C9), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    .testTag("preferences-selection-count"))
            if (state.message != null || state.saveError != null) Text(state.saveError ?: state.message.orEmpty(),
                color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("preferences-save-error"))
            if (state.draftError != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("이 기기에 임시 저장됨", fontSize = 11.sp, color = Grey, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetryDraft, enabled = !state.isSaving) { Text("동기화 재시도", color = Purple, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = if (artistStep) onSave else onNext,
                enabled = if (artistStep) state.canFinish else state.canContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag(if (artistStep) "preferences-complete" else "preferences-next"),
                shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(when { state.isSaving -> "저장 중"; !artistStep -> "다음"; state.saveError != null -> "다시 저장"; isEditing -> "저장"; else -> "완료" },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (showCredits) PhotoCredits(photoCredits) { showCredits = false }
}

@Composable
private fun SurveyTab(label: String, active: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = Modifier.width(116.dp).heightIn(min = 44.dp).testTag(tag).semantics { stateDescription = if (active) "현재 단계" else "다른 단계" },
        colors = ButtonDefaults.buttonColors(containerColor = if (active) Purple else Color.White, contentColor = if (active) Color.White else InkSoft),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)) {
        Text(label, fontSize = 15.sp)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun GenrePicker(state: PreferenceSurveyState, onGenre: (PreferenceGenre) -> Unit, onReload: () -> Unit, modifier: Modifier) {
    if (state.genres.isEmpty()) { CenteredStatus("아직 선택할 수 있는 장르가 없어요", modifier, onRetry = onReload); return }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("genre-grid")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.genres.forEach { genre ->
            val selected = genre.genreId in state.selectedGenres
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                .background(if (selected) Lavender else Color.White)
                .border(1.dp, if (selected) Purple else Color(0xFFE4E2EE), RoundedCornerShape(18.dp))
                .toggleable(selected, enabled = !state.isSaving, role = Role.Checkbox, onValueChange = { onGenre(genre) })
                .testTag("genre-${genre.genreId}").padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                Text(genre.name, color = if (selected) Color(0xFF5546C9) else Ink, fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PhotoCredits(artists: List<PreferenceArtist>, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("가수 사진 출처") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("사진을 원형으로 잘라 표시합니다.", fontSize = 12.sp) }
                items(artists, key = { it.imageSourceUrl.orEmpty() }) { artist ->
                    Column {
                        Text(artist.name, fontWeight = FontWeight.Bold)
                        Text(artist.imageAttribution.orEmpty(), fontSize = 12.sp)
                        TextButton(onClick = { artist.imageSourceUrl?.takeIf { it.startsWith("https://") }?.let { runCatching { uri.openUri(it) } } }) {
                            Text("원본 · ${artist.imageLicense.orEmpty()}", fontSize = 12.sp)
                        }
                        artist.imageLicenseUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { license ->
                            TextButton(onClick = { runCatching { uri.openUri(license) } }) { Text("이용 조건", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
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
            placeholder = { Text("가수 이름으로 검색", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, "가수 검색", tint = Grey) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp).testTag("preferences-search"),
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
                state.artistsLoading -> CenteredStatus("가수를 찾고 있어요", Modifier.weight(1f), loading = true)
                state.artistsError != null -> CenteredStatus(state.artistsError, Modifier.weight(1f), onRetry = onRetry)
                else -> CenteredStatus(if (state.query.isBlank()) "등록된 가수가 없어요" else "검색 결과가 없어요\n다른 이름으로 찾아보세요", Modifier.weight(1f), onRetry = if (state.query.isBlank()) onRetry else null)
            }
        } else {
            LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(1) else GridCells.Fixed(3),
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp).testTag("artist-grid"),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 14.dp),
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
                        state.hasMoreArtists -> TextButton(onClick = onMore, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().testTag("preferences-more-artists")) { Text("가수 더 보기", color = Purple) }
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
    Column(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox,
        onValueChange = { onClick() }).testTag("artist-${artist.artistId}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFF0EBFC)), contentAlignment = Alignment.Center) {
            Text(artist.name.take(1), color = Purple, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (!artist.imageUrl.isNullOrBlank()) AsyncImage(model = artist.imageUrl, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (selected) {
                Box(Modifier.fillMaxSize().background(Purple.copy(alpha = 0.60f)))
                Icon(Icons.Filled.Favorite, null, Modifier.size(32.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(artist.name, fontSize = 12.5.sp, color = Ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
