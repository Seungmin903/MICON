package com.micon.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.micon.app.range.RangeRepository
import com.micon.app.ui.report.GuidedRangeScreen
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PreferenceSurveyNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var session: NavigationSessionViewModel

    private fun showGraph(start: String = Routes.SURVEY) {
        session = NavigationSessionViewModel(SavedStateHandle(mapOf("loginDestination" to Routes.FAVORITES)))
        session.verified = false
        session.guest = false
        compose.setContent { MiconTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable(Routes.SURVEY) { Text("최초 선호도 조사") }
                composable(Routes.LOGIN) { Text("로그인") }
                composable(Routes.REPORT) { Text("리포트") }
                BottomTab.routes.filter { it != Routes.REPORT }.forEach { route ->
                    composable(route) { Text(route) }
                }
                composable(Routes.EDIT_PREFERENCES) { Text("선호도 수정") }
                composable(Routes.VOICE_ONBOARDING) {
                    val context = LocalContext.current
                    val repository = remember {
                        RangeRepository(context, storageDirectory = File(context.cacheDir,
                            "preference-navigation-${UUID.randomUUID()}"), deviceOnly = true)
                    }
                    fun exit() { nav.navigate(Routes.REPORT) { popUpTo(nav.graph.id) { inclusive = true } } }
                    GuidedRangeScreen(onBack = ::exit, onDone = ::exit,
                        repository = repository, onboardingIntro = true)
                }
            }
        } }
        compose.waitForIdle()
    }

    @Test fun initialSurveyOpensTheExistingDarkMeasurementIntroWithVerifiedMemberAccess() {
        showGraph()
        compose.runOnIdle { nav.completeInitialPreferenceSurvey(session) }
        compose.onNodeWithTag("onboarding-range-intro").assertIsDisplayed()
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Routes.VOICE_ONBOARDING, nav.currentDestination?.route)
            assertTrue(session.verified)
            assertFalse(session.guest)
            assertNull(nav.previousBackStackEntry)
            assertEquals(Routes.RECOMMEND, session.consumeDestination())
        }
    }

    @Test fun repeatedCompletionDoesNotAddAnotherMeasurementOrRestoreTheSurveyOnExit() {
        showGraph()
        compose.runOnIdle {
            nav.completeInitialPreferenceSurvey(session)
            nav.completeInitialPreferenceSurvey(session)
        }
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("리포트").assertIsDisplayed()
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test fun settingsPreferenceEditsKeepTheirReturnStackAndMemberState() {
        showGraph(Routes.REPORT)
        compose.runOnIdle {
            session.verified = true
            nav.navigate(Routes.EDIT_PREFERENCES)
        }
        compose.runOnIdle {
            nav.completeInitialPreferenceSurvey(session)
            assertEquals(Routes.EDIT_PREFERENCES, nav.currentDestination?.route)
            assertTrue(session.verified)
            assertTrue(nav.popBackStack())
        }
        compose.onNodeWithText("리포트").assertIsDisplayed()
    }

    @Test fun completionArrivingAfterSessionExpirationDoesNotLeaveLoginOrVerifyTheSession() {
        showGraph()
        compose.runOnIdle {
            nav.navigate(Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } }
            nav.completeInitialPreferenceSurvey(session)
            assertEquals(Routes.LOGIN, nav.currentDestination?.route)
            assertFalse(session.verified)
            assertFalse(session.guest)
        }
        compose.onNodeWithText("로그인").assertIsDisplayed()
    }
}
