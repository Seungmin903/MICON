package com.micon.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BottomTabNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private val loads = mutableMapOf<String, Int>()
    private var evaluationData = "old evaluation"

    private fun showGraph(member: Boolean = true) {
        compose.setContent { MiconTheme {
            nav = rememberNavController()
            val backStack by nav.currentBackStackEntryAsState()
            val snapshots = androidx.compose.runtime.remember { mutableStateMapOf<String, String>() }
            Column {
                MiconTabBar(backStack?.destination?.route,
                    backStack?.arguments?.getString("source"), nav::openBottomTab)
                NavHost(nav, startDestination = if (member) Routes.SPLASH else Routes.REPORT) {
                    composable(Routes.SPLASH) { Text("splash") }
                    BottomTab.entries.forEach { tab ->
                        composable(tab.route) {
                            RefreshOnResume {
                                loads[tab.route] = (loads[tab.route] ?: 0) + 1
                                snapshots[tab.route] = if (tab == BottomTab.EVALUATION) evaluationData else tab.route
                            }
                            Text(snapshots[tab.route].orEmpty(), Modifier.testTag("root-${tab.route}"))
                        }
                    }
                    composable("song-detail") { Text("song detail") }
                    composable("setlist-detail") { Text("setlist detail") }
                }
            }
        } }
        compose.waitForIdle()
        if (member) {
            compose.runOnIdle {
                nav.navigate(Routes.REPORT) { popUpTo(nav.graph.id) { inclusive = true } }
            }
            compose.waitForIdle()
        }
    }

    private fun tap(tab: BottomTab) {
        compose.onNodeWithContentDescription(tab.label, useUnmergedTree = true).performClick()
        compose.onNodeWithTag("root-${tab.route}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(tab.route, nav.currentDestination?.route) }
    }

    @Test fun allTabsOpenTheirRootAfterLoginRemovesSplash() {
        showGraph()
        repeat(2) { BottomTab.entries.forEach(::tap) }
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test fun savedSongAndSetlistDetailsDoNotReplaceTheTappedTabRoot() {
        showGraph()
        tap(BottomTab.PRACTICE)
        val practiceEntry = nav.currentBackStackEntry!!.id
        compose.runOnIdle { nav.navigate("song-detail") }
        tap(BottomTab.FAVORITES)
        compose.runOnIdle { nav.navigate("setlist-detail") }
        tap(BottomTab.PRACTICE)
        compose.runOnIdle { assertEquals(practiceEntry, nav.currentBackStackEntry!!.id) }
        tap(BottomTab.FAVORITES)
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test fun restoredEvaluationTabFetchesNewData() {
        showGraph()
        tap(BottomTab.EVALUATION)
        compose.onNodeWithText("old evaluation").assertIsDisplayed()
        tap(BottomTab.PRACTICE)
        compose.runOnIdle { evaluationData = "new evaluation" }
        tap(BottomTab.EVALUATION)
        compose.onNodeWithText("new evaluation").assertIsDisplayed()
    }

    @Test fun reselectingCurrentTabReloadsItWithoutAddingHistory() {
        showGraph()
        tap(BottomTab.EVALUATION)
        compose.runOnIdle { evaluationData = "reloaded evaluation" }
        tap(BottomTab.EVALUATION)
        compose.onNodeWithText("reloaded evaluation").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue((loads[Routes.EVALUATION] ?: 0) >= 2)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test fun guestReportStartAlsoReturnsToTheRootOfEveryTab() {
        showGraph(member = false)
        BottomTab.entries.forEach(::tap)
        tap(BottomTab.REPORT)
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }
}
