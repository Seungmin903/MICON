package com.micon.app.ui.navigation

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
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
import com.micon.app.ui.onboarding.PreferenceSurveyScreen
import com.micon.app.ui.onboarding.SignupScreen
import com.micon.app.ui.onboarding.SplashScreen
import com.micon.app.ui.practice.PracticeScreen
import com.micon.app.ui.recommend.RecommendScreen
import com.micon.app.ui.recommend.SongMatchDetailScreen
import com.micon.app.ui.report.QuickAnalysisScreen
import com.micon.app.ui.report.VoiceReportScreen
import com.micon.app.ui.result.ResultScreen
import com.micon.app.ui.settings.SettingsScreen
import com.micon.app.ui.songdetail.SongDetailScreen
import com.micon.app.ui.songlist.SongListScreen
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Purple

/** 앱 루트: 하단 탭 Scaffold + NavHost. Figma 에서 하단 바가 있는 화면(Routes.withBottomBar)에서만 보인다. */
@Composable
fun MiconRoot() {
    val nav = rememberNavController()
    val session: NavigationSessionViewModel = viewModel()
    val sessionVerified = session.verified && TokenManager.isLoggedIn()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = sessionVerified && currentRoute in Routes.withBottomBar

    LaunchedEffect(nav) {
        SessionEvents.events.collect {
            // Ignore an already-handled expiration if a new login has since succeeded.
            if (!TokenManager.isLoggedIn()) {
                session.verified = false
                nav.replaceAll(Routes.LOGIN)
            }
        }
    }
    LaunchedEffect(currentRoute, sessionVerified) {
        val publicRoutes = setOf(Routes.SPLASH, Routes.LOGIN, Routes.SIGNUP, Routes.SURVEY)
        if (currentRoute != null && currentRoute !in publicRoutes && !sessionVerified) {
            // Also guards restored back stacks and incoming protected deep links.
            nav.replaceAll(Routes.SPLASH)
        }
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = { if (showBottomBar) MiconBottomBar(nav, currentRoute) },
    ) { padding ->
        MiconNavHost(nav, Modifier.padding(padding), sessionVerified) { session.verified = it }
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
                        popUpTo(Routes.RECOMMEND) { saveState = true }
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
private fun MiconNavHost(nav: NavHostController, modifier: Modifier, sessionVerified: Boolean, onVerified: (Boolean) -> Unit) {
    fun goMain() { onVerified(true); nav.replaceAll(Routes.RECOMMEND) }
    fun goLogin() {
        // A failed parallel request and SessionEvents may report the same expiration.
        if (nav.currentDestination?.route == Routes.LOGIN) return
        TokenManager.clear()
        onVerified(false)
        nav.replaceAll(Routes.LOGIN)
    }
    fun checkSession() { onVerified(false); nav.replaceAll(Routes.SPLASH) }

    NavHost(navController = nav, startDestination = Routes.SPLASH, modifier = modifier) {

        // ---- 온보딩 ----
        composable(Routes.SPLASH) {
            SplashScreen(onLoggedIn = { goMain() }, onNeedLogin = { goLogin() },
                onNeedSurvey = { nav.replaceAll(Routes.SURVEY) })
        }
        composable(Routes.LOGIN) { LoginScreen(onLoggedIn = { checkSession() }, onSignup = { nav.navigate(Routes.SIGNUP) }) }
        composable(Routes.SIGNUP) { SignupScreen(onBack = { nav.popBackStack() }, onDone = { checkSession() }) }
        composable(Routes.SURVEY) {
            PreferenceSurveyScreen(onDone = { goMain() }, onSessionExpired = { goLogin() }, onBack = { goLogin() })
        }
        composable(Routes.EDIT_PREFERENCES) {
            if (!sessionVerified) return@composable
            PreferenceSurveyScreen(onDone = { nav.popBackStack() }, onSessionExpired = { goLogin() },
                onBack = { nav.popBackStack() }, isEditing = true)
        }

        // ---- 탭: 추천 ----
        composable(Routes.RECOMMEND) {
            if (!sessionVerified) return@composable
            RecommendScreen(onSongClick = { nav.navigate(Routes.songMatch(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SONG_MATCH, arguments = listOf(navArgument("songId") { type = NavType.StringType })) { entry ->
            if (!sessionVerified) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            SongMatchDetailScreen(songId = songId, onBack = { nav.popBackStack() }, onPractice = { nav.navigate(Routes.practice(songId)) })
        }

        // ---- 탭: 리포트 ----
        composable(Routes.REPORT) {
            if (!sessionVerified) return@composable
            VoiceReportScreen(onQuickAnalysis = { nav.navigate(Routes.QUICK_ANALYSIS) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.QUICK_ANALYSIS) {
            if (!sessionVerified) return@composable
            QuickAnalysisScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }

        // ---- 탭: 연습 ----
        composable(Routes.PRACTICE) {
            if (!sessionVerified) return@composable
            SongListScreen(onSongClick = { nav.navigate(Routes.song(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(
            Routes.SONG,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.SONG}" }),
        ) { entry ->
            if (!sessionVerified) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            SongDetailScreen(songId = songId, onBack = { nav.popBackStack() }, onPractice = { nav.navigate(Routes.practice(songId)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(
            Routes.PRACTICE_RUN,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.PRACTICE_RUN}" }),
        ) { entry ->
            if (!sessionVerified) return@composable
            val songId = entry.arguments?.getString("songId") ?: return@composable
            PracticeScreen(songId = songId, onBack = { nav.popBackStack() }, onOpenResult = { jobId -> nav.navigate(Routes.result(jobId)) })
        }

        // ---- 탭: 평가 ----
        composable(Routes.EVALUATION) {
            if (!sessionVerified) return@composable
            PracticeHistoryScreen(onOpenResult = { jobId -> nav.navigate(Routes.result(jobId)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(
            Routes.RESULT,
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.RESULT}" }),
        ) { entry ->
            if (!sessionVerified) return@composable
            val jobId = entry.arguments?.getString("jobId") ?: return@composable
            ResultScreen(
                jobId = jobId,
                onBack = { nav.popBackStack() },
                onHome = { nav.navigate(Routes.PRACTICE) { popUpTo(Routes.PRACTICE) { inclusive = false }; launchSingleTop = true } },
            )
        }

        // ---- 탭: 찜 ----
        composable(Routes.FAVORITES) {
            if (!sessionVerified) return@composable
            FavoritesScreen(onSongClick = { nav.navigate(Routes.song(it)) }, onSetlistClick = { nav.navigate(Routes.setlist(it)) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETLIST, arguments = listOf(navArgument("setlistId") { type = NavType.StringType })) { entry ->
            if (!sessionVerified) return@composable
            val setlistId = entry.arguments?.getString("setlistId") ?: return@composable
            SetlistScreen(setlistId = setlistId, onBack = { nav.popBackStack() }, onSongClick = { nav.navigate(Routes.song(it)) })
        }

        // ---- 설정 ----
        composable(Routes.SETTINGS) {
            if (!sessionVerified) return@composable
            SettingsScreen(onBack = { nav.popBackStack() }, onLogout = { goLogin() },
                onEditPreferences = { nav.navigate(Routes.EDIT_PREFERENCES) })
        }

        // ---- 개발용: 음성 프로필 예시를 채우고 리포트 탭으로 (adb shell am start -d "micon://app/dev/seed-profile") ----
        composable(Routes.DEV_SEED_PROFILE, deepLinks = listOf(navDeepLink { uriPattern = "${Routes.DEEP_LINK}/${Routes.DEV_SEED_PROFILE}" })) {
            if (!sessionVerified) return@composable
            com.micon.app.data.local.UserStore.setProfile(com.micon.app.data.local.UserStore.VoiceProfile())
            androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate(Routes.REPORT) { popUpTo(Routes.DEV_SEED_PROFILE) { inclusive = true } } }
        }
    }
}

/** Survives configuration changes, but a fresh process must validate its server session. */
class NavigationSessionViewModel : ViewModel() {
    var verified by mutableStateOf(false)
}

/** Discard all protected history, including saved tab stacks, on account/gate changes. */
private fun NavHostController.replaceAll(route: String) {
    BottomTab.routes.forEach { clearBackStack(it) }
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
