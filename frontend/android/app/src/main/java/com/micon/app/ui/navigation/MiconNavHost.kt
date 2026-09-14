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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.micon.app.ui.evaluation.PracticeHistoryScreen
import com.micon.app.ui.favorites.FavoritesScreen
import com.micon.app.ui.favorites.SetlistScreen
import com.micon.app.ui.onboarding.LoginScreen
import com.micon.app.ui.onboarding.PasswordResetScreen
import com.micon.app.ui.onboarding.PreferenceSurveyScreen
import com.micon.app.ui.onboarding.SignupScreen
import com.micon.app.ui.onboarding.SplashScreen
import com.micon.app.ui.practice.PracticeScreen
import com.micon.app.ui.recommend.RecommendScreen
import com.micon.app.ui.recommend.SongTurntableScreen
import com.micon.app.ui.report.QuickAnalysisScreen
import com.micon.app.ui.report.VoiceReportScreen
import com.micon.app.ui.singanalysis.SingAnalysisScreen
import com.micon.app.ui.singanalysis.SingAnalysisSongPickerScreen
import com.micon.app.ui.result.ResultScreen
import com.micon.app.ui.settings.SettingsScreen
import com.micon.app.ui.songdetail.SongDetailScreen
import com.micon.app.ui.songlist.SongListScreen
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.PrimaryButton

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
    val showBottomBar = accessGranted && currentRoute in Routes.withBottomBar

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
        val publicRoutes = setOf(Routes.SPLASH, Routes.LOGIN, Routes.SIGNUP, Routes.PASSWORD_RESET, Routes.SURVEY)
        if (currentRoute != null && currentRoute !in publicRoutes && !accessGranted) {
            // Also guards restored back stacks and incoming protected deep links.
            nav.replaceAll(Routes.REPORT)
        }
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = { if (showBottomBar) MiconBottomBar(nav, currentRoute) },
    ) { padding ->
        MiconNavHost(
            nav = nav,
            modifier = Modifier.padding(padding),
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
    }
}

@Composable
private fun MiconBottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        BottomTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route || (tab == BottomTab.PRACTICE && currentRoute == Routes.SONG) || (tab == BottomTab.FAVORITES && currentRoute == Routes.SETLIST)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label, Modifier.size(26.dp)) },
                label = { Text(tab.label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Purple, selectedTextColor = Purple,
                    unselectedIconColor = Grey, unselectedTextColor = Grey,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
    @Suppress("UNUSED_VARIABLE") val divider = GreyLight
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

    NavHost(navController = nav, startDestination = initialRoute, modifier = modifier) {

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
            )
        }
        composable(Routes.PASSWORD_RESET) {
            PasswordResetScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack(Routes.LOGIN, inclusive = false) })
        }
        composable(Routes.SIGNUP) { SignupScreen(onBack = { nav.popBackStack() }, onDone = { checkSession() }) }
        composable(Routes.SURVEY) {
            PreferenceSurveyScreen(onDone = { goMain() }, onSessionExpired = { goLogin() }, onBack = { goLogin() })
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
            else RecommendScreen(onSongClick = { nav.navigate(Routes.songMatch(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SONG_MATCH, arguments = listOf(navArgument("songId") { type = NavType.LongType })) { entry ->
            if (isGuest || !accessGranted) return@composable
            val songId = entry.arguments?.getLong("songId") ?: return@composable
            // 턴테이블 → 아래로 스크롤하면 검은 시트가 올라오며 곡 분석. 캐러셀로 곡이 바뀌면 그 곡으로 연습에 들어간다.
            SongTurntableScreen(songId = songId, onBack = { nav.popBackStack() }, onPractice = { nav.navigate(Routes.practice(it)) })
        }

        // ---- 탭: 리포트 ----
        composable(Routes.REPORT) {
            if (!accessGranted) return@composable
            VoiceReportScreen(
                onQuickAnalysis = { nav.navigate(Routes.VOICE_ONBOARDING) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLogin = if (isGuest) ({ openLogin() }) else null,
            )
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
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
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
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.SONG}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            val songId = entry.arguments?.getLong("songId") ?: return@composable
            SongDetailScreen(
                songId = songId,
                onBack = { nav.popBackStack() },
                onPractice = { nav.navigate(Routes.practice(songId)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLogin = if (isGuest) ({ openLogin() }) else null,
            )
        }
        composable(
            Routes.PRACTICE_RUN,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.PRACTICE_RUN}" }),
        ) { entry ->
            if (!accessGranted) return@composable
            val songId = entry.arguments?.getLong("songId") ?: return@composable
            PracticeScreen(songId = songId, onBack = { nav.popBackStack() }, onOpenResult = { practiceId -> nav.navigate(Routes.result(practiceId)) })
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
            else FavoritesScreen(onSongClick = { nav.navigate(Routes.song(it)) }, onSetlistClick = { nav.navigate(Routes.setlist(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETLIST, arguments = listOf(navArgument("setlistId") { type = NavType.LongType })) { entry ->
            if (isGuest || !accessGranted) return@composable
            val setlistId = entry.arguments?.getLong("setlistId") ?: return@composable
            SetlistScreen(setlistId = setlistId, onBack = { nav.popBackStack() }, onSongClick = { nav.navigate(Routes.song(it)) })
        }

        // ---- 설정 ----
        composable(Routes.SETTINGS) {
            if (isGuest || !accessGranted) return@composable
            SettingsScreen(onBack = { nav.popBackStack() }, onLogout = { goGuest() },
                onEditPreferences = { nav.navigate(Routes.EDIT_PREFERENCES) })
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
    val initialRoute = if (TokenManager.isLoggedIn()) Routes.SPLASH else Routes.REPORT
    var verified by mutableStateOf(false)
    var guest by mutableStateOf(initialRoute == Routes.REPORT)

    fun rememberDestination(nav: NavHostController) {
        val entry = nav.currentBackStackEntry ?: return
        val destination = loginReturnDestination(entry.destination.route,
            entry.arguments?.getLong("songId"), entry.arguments?.getLong("setlistId")) ?: return
        savedState["loginDestination"] = destination
    }

    fun consumeDestination(): String = savedState.remove<String>("loginDestination") ?: Routes.RECOMMEND
    fun clearDestination() { savedState.remove<String>("loginDestination") }
}

/** Discard all protected history, including saved tab stacks, on account/gate changes. */
private fun NavHostController.replaceAll(route: String) {
    BottomTab.routes.forEach { clearBackStack(it) }
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
