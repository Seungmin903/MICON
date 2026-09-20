package com.micon.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.micon.app.ui.design.organisms.FloatingNavBar
import com.micon.app.ui.design.organisms.LocalTabBarPadding
import com.micon.app.ui.design.tokens.MiconSpace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.micon.app.appContainer
import com.micon.app.data.onboarding.VoiceProfileSync
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.micon.app.core.auth.TokenManager
import com.micon.app.core.network.SessionEvents
import com.micon.app.BuildConfig
import com.micon.app.ui.design.catalog.DesignCatalogScreen
import com.micon.app.ui.evaluation.PracticeHistoryScreen
import com.micon.app.ui.holo.HoloScreen
import com.micon.app.ui.analysis.AnalysisScreen
import com.micon.app.ui.holo.VoiceResultScreen
import com.micon.app.ui.holo.holoViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.ui.favorites.FavoritesScreen
import com.micon.app.ui.favorites.SetlistScreen
import com.micon.app.ui.onboarding.LoginScreen
import com.micon.app.ui.onboarding.PasswordResetScreen
import com.micon.app.ui.onboarding.PreferenceSurveyScreen
import com.micon.app.ui.onboarding.SignupScreen
import com.micon.app.ui.onboarding.SplashScreen
import com.micon.app.ui.practice.PracticeScreen
import com.micon.app.ui.recommend.RecommendDeckScreen
import com.micon.app.ui.recommend.SongCarousel
import com.micon.app.ui.report.QuickAnalysisScreen
import com.micon.app.ui.report.VoiceReportScreen
import com.micon.app.ui.singanalysis.SingAnalysisScreen
import com.micon.app.ui.singanalysis.SingAnalysisSongPickerScreen
import com.micon.app.ui.result.ResultScreen
import com.micon.app.ui.settings.SettingsScreen
import com.micon.app.ui.songdetail.SongDetailScreen
import com.micon.app.ui.songlist.SongListScreen
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.LocalOnboardingChrome
import com.micon.app.ui.common.OnboardingChromeState
import com.micon.app.ui.common.OnboardingSystemBars

/** 앱 루트: 하단 탭 Scaffold + NavHost. Figma 에서 하단 바가 있는 화면(Routes.withBottomBar)에서만 보인다. */
@Composable
fun MiconRoot() {
    if (com.micon.app.BuildConfig.GUIDED_RANGE_STANDALONE) {
        com.micon.app.ui.report.RangeTestFlow()
        return
    }
    val nav = rememberNavController()
    val session: NavigationSessionViewModel = viewModel()
    val sessionVerified = session.verified && TokenManager.isLoggedIn()
    val accessGranted = sessionVerified || session.guest
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onboardingChrome = remember { OnboardingChromeState() }
    val darkOnboarding = currentRoute == Routes.VOICE_ONBOARDING && onboardingChrome.dark
    if (darkOnboarding) OnboardingSystemBars()
    val showBottomBar = accessGranted && currentRoute in Routes.withBottomBar
    // 로그인 완료·앱 재실행 모두 여기서 세션이 확인된다. 저장된 목소리 프로필을 이때 채워 연습·추천 상세가 리포트와 같은 음역을 본다.
    val context = LocalContext.current
    LaunchedEffect(sessionVerified) {
        if (sessionVerified) TokenManager.getUserId().takeIf { it > 0 }?.let { VoiceProfileSync.restore(context, it, context.appContainer.voiceOnboarding) }
    }

    LaunchedEffect(nav) {
        SessionEvents.events.collect {
            // Ignore an already-handled expiration if a new login has since succeeded.
            if (!TokenManager.isLoggedIn() && !session.guest) {
                session.rememberDestination(nav)
                session.verified = false
                nav.replaceAll(Routes.LOGIN)
            }
        }
    }
    LaunchedEffect(currentRoute, accessGranted) {
        val publicRoutes = setOf(Routes.HOLO, Routes.VOICE_RESULT, Routes.SPLASH, Routes.LOGIN, Routes.SIGNUP, Routes.PASSWORD_RESET, Routes.SURVEY)
        if (currentRoute != null && currentRoute !in publicRoutes && !accessGranted) {
            // Also guards restored back stacks and incoming protected deep links.
            nav.replaceAll(Routes.REPORT)
        }
    }

    CompositionLocalProvider(
        LocalOnboardingChrome provides onboardingChrome,
        LocalTabBarPadding provides if (showBottomBar) MiconSpace.navBarContentPadding else 0.dp,
    ) {
        // 새 화면은 MiconScreen 이 상태바·내비 바 여백을 스스로 처리한다(전면 배경·홀로 화면 가능).
        // 아직 옛 헤더를 쓰는 화면만 여기서 시스템 바를 피해 준다 — 옮길 때마다 migratedRoutes 에 넣는다.
        val legacy = currentRoute !in migratedRoutes
        Box(
            Modifier.fillMaxSize().background(
                when {
                    darkOnboarding -> Ink
                    legacy -> Color.White
                    else -> MaterialTheme.colorScheme.background
                },
            ),
        ) {
            MiconNavHost(
                nav = nav,
                modifier = if (legacy) Modifier.systemBarsPadding() else Modifier,
                initialRoute = session.initialRoute,
                accessGranted = accessGranted,
                isGuest = session.guest,
                session = session,
                onVerified = {
                    session.verified = it
                    if (it) session.guest = false
                },
                onGuest = {
                    session.guest = it
                    if (it) session.verified = false
                },
            )
            if (showBottomBar) {
                MiconTabBar(currentRoute, onNavigate = nav::openBottomTab, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** MiconScreen 으로 옮겨져 시스템 바 여백을 스스로 처리하는 라우트 */
private val migratedRoutes: Set<String?> = setOf(Routes.HOLO, Routes.VOICE_RESULT, Routes.VOICE_RESULT_VIEW, Routes.REPORT, Routes.RECOMMEND, Routes.SONG, Routes.SONG_MATCH, Routes.PRACTICE_RUN, Routes.PRACTICE, Routes.EVALUATION, Routes.FAVORITES,
    Routes.LOGIN, Routes.SIGNUP, Routes.SURVEY, Routes.EDIT_PREFERENCES, Routes.SETTINGS)

/** Tabs always open their root; saved child screens must not override the tapped destination. */
internal fun NavHostController.openBottomTab(route: String) {
    require(route in BottomTab.routes)
    if (currentDestination?.route == route) {
        // Reselecting the visible tab reloads it, including a previously empty/error state.
        navigate(route) {
            popUpTo(route) { inclusive = true }
            launchSingleTop = true
        }
        return
    }
    navigate(route) {
        // Login/session replacement removes the original start destination (splash).
        // The graph remains a valid anchor for both member and guest tab stacks.
        popUpTo(graph.id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    popBackStack(route, inclusive = false)
}

/** 물방울 탭바. 탭 루트에서만 보이므로 하위 화면 → 탭 매핑은 없다. */
@Composable
internal fun MiconTabBar(currentRoute: String?, onNavigate: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val selected = BottomTab.entries.firstOrNull { it.route == currentRoute } ?: BottomTab.REPORT
    FloatingNavBar(selected = selected.tab, onSelect = { onNavigate(BottomTab.of(it).route) }, modifier = modifier)
}

@Composable
private fun MiconNavHost(
    nav: NavHostController,
    modifier: Modifier,
    initialRoute: String,
    accessGranted: Boolean,
    isGuest: Boolean,
    session: NavigationSessionViewModel,
    onVerified: (Boolean) -> Unit,
    onGuest: (Boolean) -> Unit,
) {
    fun goMain() { onGuest(false); onVerified(true); nav.replaceAll(session.consumeDestination()) }
    fun openLogin() {
        session.rememberDestination(nav)
        nav.navigate(Routes.LOGIN) { launchSingleTop = true }
    }
    fun goLogin() {
        // A failed parallel request and SessionEvents may report the same expiration.
        if (nav.currentDestination?.route == Routes.LOGIN) return
        TokenManager.clear()
        onGuest(false)
        onVerified(false)
        nav.replaceAll(Routes.LOGIN)
    }
    fun goGuest() {
        session.clearDestination()
        TokenManager.clear()
        onVerified(false)
        onGuest(true)
        nav.replaceAll(Routes.REPORT)
    }
    fun checkSession() { onGuest(false); onVerified(false); nav.replaceAll(Routes.SPLASH) }

    val holo = holoViewModel()
    /** 홀로에서 탭으로 나갈 때: 로그인돼 있으면 세션 확인 뒤 메인, 아니면 게스트로 */
    fun leaveHolo(destination: String) {
        if (TokenManager.isLoggedIn()) { session.rememberDestinationRoute(destination); checkSession() }
        else { onVerified(false); onGuest(true); nav.replaceAll(destination) }
    }

    NavHost(navController = nav, startDestination = initialRoute, modifier = modifier) {

        // ---- 첫 화면: 목소리 분석 → 결과 ----
        composable(Routes.HOLO) {
            HoloScreen(vm = holo, onResult = { nav.navigate(Routes.VOICE_RESULT) { launchSingleTop = true } },
                onSkip = { leaveHolo(Routes.REPORT) })
        }
        composable(Routes.VOICE_RESULT) {
            val holoState by holo.state.collectAsStateWithLifecycle()
            val result = holoState.result ?: holo.previousResult()
            if (result == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
            VoiceResultScreen(result, saving = holoState.saving,
                onRecommend = { leaveHolo(Routes.RECOMMEND) },
                onSave = {
                    if (TokenManager.isLoggedIn()) holo.save { leaveHolo(Routes.REPORT) }
                    else { session.rememberDestinationRoute(Routes.REPORT); nav.navigate(Routes.LOGIN) { launchSingleTop = true } }
                })
        }

        // ---- 온보딩 ----
        composable(Routes.SPLASH) {
            SplashScreen(onLoggedIn = { goMain() }, onNeedLogin = { goLogin() },
                onNeedSurvey = { nav.replaceAll(Routes.SURVEY) })
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = { checkSession() },
                onSignup = { nav.navigate(Routes.SIGNUP) },
                onFindPassword = { nav.navigate(Routes.PASSWORD_RESET) },
                onGuest = { goGuest() },
            )
        }
        composable(Routes.PASSWORD_RESET) {
            PasswordResetScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack(Routes.LOGIN, inclusive = false) })
        }
        composable(Routes.SIGNUP) { SignupScreen(onBack = { nav.popBackStack() }, onDone = { checkSession() }) }
        composable(Routes.SURVEY) {
            PreferenceSurveyScreen(onDone = { nav.completeInitialPreferenceSurvey(session) },
                onSessionExpired = { goLogin() }, onBack = { goLogin() })
        }
        composable(Routes.EDIT_PREFERENCES) {
            if (isGuest || !accessGranted) return@composable
            PreferenceSurveyScreen(onDone = { nav.popBackStack() }, onSessionExpired = { goLogin() },
                onBack = { nav.popBackStack() }, isEditing = true)
        }

        // ---- 탭: 추천 ----
        composable(Routes.RECOMMEND) {
            if (!accessGranted) return@composable
            if (isGuest) GuestMemberTab("추천", openLogin = { openLogin() })
            else RecommendDeckScreen(onSongClick = { nav.navigate(Routes.songMatch(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) },
                onAnalyze = { nav.navigate(Routes.VOICE_ONBOARDING) })
        }
        composable(Routes.SONG_MATCH, arguments = listOf(navArgument("songId") { type = NavType.StringType })) { entry ->
            if (isGuest || !accessGranted) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            // Preserve the old match route, but render exactly the same detail as practice/favorites.
            val ids = rememberSaveable(songId) { SongCarousel.idsFor(songId).toTypedArray() }
            var currentId by rememberSaveable(songId) { mutableStateOf(songId) }
            LaunchedEffect(currentId) { entry.savedStateHandle["detailSongId"] = currentId }
            val artwork = remember(ids) { ids.associateWith { SongCarousel.imageOf(it) } }
            SongDetailScreen(songId = currentId, onBack = { nav.popBackStack() },
                onPractice = { key -> nav.navigate(Routes.practice(currentId, key)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                carouselIds = ids.toList(), artwork = artwork, onSongSelected = { currentId = it })
        }

        // ---- 탭: 분석 (이전 리포트) ----
        composable(Routes.REPORT) {
            if (!accessGranted) return@composable
            AnalysisScreen(
                onAnalyze = { nav.navigate(Routes.HOLO) { launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onDetail = { nav.navigate(Routes.VOICE_RESULT_VIEW) },
                onLogin = if (isGuest) ({ openLogin() }) else null,
            )
        }
        composable(Routes.VOICE_RESULT_VIEW) {
            val holoState by holo.state.collectAsStateWithLifecycle()
            val result = holoState.result ?: holo.previousResult()
            if (result == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return@composable }
            VoiceResultScreen(result, saving = false, onRecommend = {}, onSave = {}, onBack = { nav.popBackStack() })
        }
        composable(Routes.QUICK_ANALYSIS) {
            if (!accessGranted) return@composable
            LaunchedEffect(Unit) { nav.navigate(Routes.VOICE_ONBOARDING) { popUpTo(Routes.QUICK_ANALYSIS) { inclusive = true } } }
        }
        composable(Routes.VOICE_ONBOARDING) {
            if (!accessGranted) return@composable
            com.micon.app.ui.onboarding.VoiceOnboardingFlow(isGuest,
                onExit = { if (!nav.popBackStack(Routes.REPORT, inclusive = false)) nav.replaceAll(Routes.REPORT) },
                onLogin = {
                    if (!nav.popBackStack(Routes.REPORT, inclusive = false)) nav.replaceAll(Routes.REPORT)
                    openLogin()
                })
        }
        // 불러서 분석하기: 곡 선택 → 범위 선택(전곡·2절·하이라이트) → 카운트인 → 가사만 보며 부르기(WAV 녹음) → 저장 완료
        composable(Routes.SING_ANALYSIS_PICK) {
            if (!accessGranted) return@composable
            LaunchedEffect(Unit) { nav.navigate(Routes.VOICE_ONBOARDING) { popUpTo(Routes.SING_ANALYSIS_PICK) { inclusive = true } } }
        }
        composable(
            Routes.SING_ANALYSIS_RUN,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.SING_ANALYSIS_RUN}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            LaunchedEffect(Unit) { nav.navigate(Routes.VOICE_ONBOARDING) { popUpTo(Routes.SING_ANALYSIS_RUN) { inclusive = true } } }
        }

        // ---- 탭: 연습 ----
        composable(Routes.PRACTICE) {
            if (!accessGranted) return@composable
            SongListScreen(
                onSongClick = { nav.navigate(Routes.song(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLogin = if (isGuest) ({ openLogin() }) else null,
            )
        }
        composable(
            Routes.SONG,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }, navArgument("source") { type = NavType.StringType; defaultValue = Routes.PRACTICE }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.SONG}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            SongDetailScreen(
                songId = songId,
                onBack = { nav.popBackStack() },
                onPractice = { key -> nav.navigate(Routes.practice(songId, key)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLogin = if (isGuest) ({ openLogin() }) else null,
            )
        }
        composable(
            Routes.PRACTICE_RUN,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }, navArgument("selectedKey") { type = NavType.IntType; defaultValue = 0 }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.PRACTICE_RUN}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            PracticeScreen(songId = songId, onBack = { nav.popBackStack() }, onOpenResult = { practiceId -> nav.navigate(Routes.result(practiceId)) },
                initialKey = entry.arguments?.getInt("selectedKey") ?: 0)
        }

        // ---- 탭: 평가 ----
        composable(Routes.EVALUATION) {
            if (!accessGranted) return@composable
            // 연습 기록은 백엔드 /practices(로그인 필요). 비회원은 라이브 점수만 보고 저장되지 않는다.
            if (isGuest) GuestMemberTab("평가", openLogin = { openLogin() })
            else PracticeHistoryScreen(
                onOpenResult = { practiceId -> nav.navigate(Routes.result(practiceId)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.RESULT,
            arguments = listOf(navArgument("practiceId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.RESULT}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            val practiceId = entry.arguments?.getLong("practiceId") ?: return@composable
            ResultScreen(
                practiceId = practiceId,
                onBack = { nav.popBackStack() },
                onHome = { nav.navigate(Routes.PRACTICE) { popUpTo(Routes.PRACTICE) { inclusive = false }; launchSingleTop = true } },
            )
        }

        // ---- 탭: 찜 ----
        composable(Routes.FAVORITES) {
            if (!accessGranted) return@composable
            if (isGuest) GuestMemberTab("찜", openLogin = { openLogin() })
            else FavoritesScreen(onSongClick = { nav.navigate(Routes.song(it, Routes.FAVORITES)) }, onSetlistClick = { nav.navigate(Routes.setlist(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) },
                onPractice = { nav.navigate(Routes.practice(it)) })
        }
        composable(Routes.SETLIST, arguments = listOf(navArgument("setlistId") { type = NavType.LongType })) { entry ->
            if (isGuest || !accessGranted) return@composable
            val setlistId = entry.arguments?.getLong("setlistId") ?: return@composable
            SetlistScreen(setlistId = setlistId, onBack = { nav.popBackStack() }, onSongClick = { nav.navigate(Routes.song(it, Routes.FAVORITES)) })
        }

        // ---- 설정 ----
        composable(Routes.SETTINGS) {
            if (isGuest || !accessGranted) return@composable
            SettingsScreen(onBack = { nav.popBackStack() }, onLogout = { goLogin() },
                onEditPreferences = { nav.navigate(Routes.EDIT_PREFERENCES) })
        }

        // ---- 개발용: 디자인 카탈로그 (adb shell am start -d "micon://app/dev/catalog") ----
        // 리디자인 부품(토큰·원자·분자·유기체)을 한 화면에 모아 둔 곳. 새 화면을 만들기 전에
        // 여기서 부품을 고르고, 토큰을 바꿨을 때 앱 전체에 어떻게 퍼지는지 여기서 본다.
        // release 빌드에는 들어가지 않는다.
        composable(Routes.DEV_CATALOG, deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.DEV_CATALOG}" })) {
            if (!BuildConfig.DEBUG) return@composable
            DesignCatalogScreen(onBack = { nav.popBackStack() })
        }

        // ---- 개발용: 음성 프로필 예시를 채우고 리포트 탭으로 (adb shell am start -d "micon://app/dev/seed-profile") ----
        composable(Routes.DEV_SEED_PROFILE, deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.DEV_SEED_PROFILE}" })) {
            if (!accessGranted) return@composable
            com.micon.app.data.local.UserStore.setProfile(com.micon.app.data.local.UserStore.VoiceProfile())
            // 선호 가수도 같이 심어 "선호 가수" 섹션까지 보이게 (개발용 카탈로그에 분석된 곡이 있는 가수)
            com.micon.app.data.local.UserStore.preferredArtists = setOf("한로로", "백아", "볼빨간사춘기", "이승철", "조항조", "싸이")
            com.micon.app.data.local.UserStore.nickname = "유림"
            androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate(Routes.REPORT) { popUpTo(Routes.DEV_SEED_PROFILE) { inclusive = true } } }
        }
    }
}

@Composable
private fun GuestMemberTab(title: String, openLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onLogin = openLogin)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("아직 표시할 $title 콘텐츠가 없어요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text("로그인하면 나만의 $title 콘텐츠를 이용할 수 있어요", fontSize = 14.sp, color = Grey)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("로그인하기", openLogin, Modifier.fillMaxWidth())
        }
    }
}

/** Survives configuration changes, but a fresh process must validate its server session. */
class NavigationSessionViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    /** 프로토타입: 앱을 열면 바로 목소리 분석. 로그인·설문은 결과를 저장할 때 만난다 */
    val initialRoute = Routes.HOLO
    var verified by mutableStateOf(false)
    var guest by mutableStateOf(false)

    fun rememberDestination(nav: NavHostController) {
        val entry = nav.currentBackStackEntry ?: return
        val destination = loginReturnDestination(entry.destination.route,
            entry.savedStateHandle.get<String>("detailSongId") ?: entry.arguments?.getString("songId"),
            entry.arguments?.getLong("setlistId"), entry.arguments?.getString("source")) ?: return
        savedState["loginDestination"] = destination
    }

    fun rememberDestinationRoute(route: String) { savedState["loginDestination"] = route }
    fun consumeDestination(): String = savedState.remove<String>("loginDestination") ?: Routes.RECOMMEND
    fun clearDestination() { savedState.remove<String>("loginDestination") }
}

/** Initial preferences lead directly into voice onboarding; settings edits keep their own return path. */
internal fun NavHostController.completeInitialPreferenceSurvey(session: NavigationSessionViewModel) {
    if (currentDestination?.route != Routes.SURVEY) return
    session.clearDestination()
    session.guest = false
    session.verified = true
    replaceAll(Routes.VOICE_ONBOARDING)
}

/** Discard all protected history, including saved tab stacks, on account/gate changes. */
private fun NavHostController.replaceAll(route: String) {
    BottomTab.routes.forEach { clearBackStack(it) }
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
