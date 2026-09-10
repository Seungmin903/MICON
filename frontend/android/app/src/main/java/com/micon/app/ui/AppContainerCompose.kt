package com.micon.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.micon.app.AppContainer
import com.micon.app.appContainer

/** Composable 안에서 DI 컨테이너를 얻는다. ViewModel 팩토리에 의존성을 넘길 때 사용. */
@Composable
fun rememberAppContainer(): AppContainer = LocalContext.current.appContainer
