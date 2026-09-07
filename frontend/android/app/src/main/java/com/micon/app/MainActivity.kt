package com.micon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.micon.app.ui.navigation.MiconRoot
import com.micon.app.ui.theme.MiconTheme

/** 단일 Activity. 화면 전환은 전부 Compose Navigation(ui/navigation) 이 담당한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiconTheme {
                MiconRoot()
            }
        }
    }
}
