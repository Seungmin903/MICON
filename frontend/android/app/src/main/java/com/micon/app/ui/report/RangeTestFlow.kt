package com.micon.app.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeRepository
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Grey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test APK entry point: guided range plus model verification, without an account database. */
@Composable
fun RangeTestFlow() {
    val nav = rememberNavController()
    fun results() = nav.navigate("device-results") { popUpTo(nav.graph.id); launchSingleTop = true }
    Scaffold(containerColor = Color.White) { padding ->
        NavHost(navController = nav, startDestination = "device-measure", modifier = Modifier.padding(padding)) {
            composable("device-measure") {
                FreeRangeScreen(onBack = { results() })
            }
            composable("device-results") {
                val context = LocalContext.current
                var report by remember { mutableStateOf<RangeReport?>(null) }
                LaunchedEffect(Unit) {
                    report = withContext(Dispatchers.IO) {
                        RangeRepository(context).lastSessionReport()
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("음역 측정 결과", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    PrimaryButton("새로 측정하기", { nav.navigate("device-measure") }, Modifier.fillMaxWidth())
                    report?.let { RangeReportCards(it) }
                        ?: Text("측정 결과 없음", color = Grey)
                }
            }
        }
    }
}
