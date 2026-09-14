package com.micon.app.ui.common

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Explain microphone use before asking; a denial offers both retry and the app's permission settings. */
@Composable
fun rememberMicrophoneAccess(onAllowed: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val allowed by rememberUpdatedState(onAllowed)
    val denied by rememberUpdatedState(onDenied)
    var purpose by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { blocked = false; allowed() } else { blocked = true; denied() }
    }
    if (purpose) AlertDialog(onDismissRequest = { purpose = false },
        title = { Text("목소리를 들려주세요") },
        text = { Text("음역과 음색을 분석하기 위해 마이크를 사용해요. 측정이나 녹음을 시작할 때만 목소리를 들어요.") },
        confirmButton = { TextButton(onClick = { purpose = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("계속") } },
        dismissButton = { TextButton(onClick = { purpose = false }) { Text("나중에") } })
    if (blocked) AlertDialog(onDismissRequest = { blocked = false },
        title = { Text("마이크 권한이 필요해요") },
        text = { Text("권한을 허용하면 측정을 시작할 수 있어요. 요청 창이 나오지 않으면 앱 설정에서 마이크를 허용해 주세요.") },
        confirmButton = { TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
            blocked = false
        }) { Text("앱 설정 열기") } },
        dismissButton = { TextButton(onClick = { blocked = false; permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("다시 요청") } })
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) allowed()
        else purpose = true
    }
}
