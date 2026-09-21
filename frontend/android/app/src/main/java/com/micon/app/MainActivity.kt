package com.micon.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.micon.app.ui.navigation.MiconRoot
import com.micon.app.ui.design.tokens.MiconDesignTheme

/** 단일 Activity. 화면 전환은 전부 Compose Navigation(ui/navigation) 이 담당한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 인자 없는 enableEdgeToEdge() 는 시스템 테마를 따라간다. 앱은 라이트 고정이라
        // 시스템이 다크 모드인 사용자에게는 흰 배경 위에 흰 상태바 아이콘이 남았다.
        // light(...) 를 못 박아 아이콘을 항상 어둡게 한다. 두 인자는 scrim · darkScrim 이고
        // 둘 다 투명이라 배경은 화면이 그대로 비친다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            // 프로토타입: 스플래시·로그인 없이 첫 화면이 곧 목소리 분석이다.
            MiconDesignTheme { MiconRoot() }
        }
    }
}
