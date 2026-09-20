package com.micon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.micon.app.ui.navigation.MiconRoot
import com.micon.app.ui.theme.MiconTheme
import com.micon.app.ui.theme.Paper
import kotlinx.coroutines.delay

/** 단일 Activity. 화면 전환은 전부 Compose Navigation(ui/navigation) 이 담당한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiconTheme {
                var showSplash by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(SPLASH_DURATION_MILLIS)
                    showSplash = false
                }

                if (showSplash) {
                    MiconSplashScreen()
                } else {
                    MiconRoot()
                }
            }
        }
    }
}

@Composable
private fun MiconSplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Paper),
        contentAlignment = Alignment.Center,
    ) {
        MiconAnimatedLogo()
    }
}

@Composable
private fun MiconAnimatedLogo() {
    val markScale = remember { Animatable(1f) }
    val assembleProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        markScale.animateTo(1.45f, tween(380, easing = FastOutSlowInEasing))
        markScale.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        assembleProgress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier.width(280.dp).height(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.micon_logo_letters),
            contentDescription = null,
            modifier = Modifier
                .width(280.dp)
                .height(72.dp)
                .graphicsLayer {
                    alpha = assembleProgress.value
                    scaleX = 0.88f + (0.12f * assembleProgress.value)
                    scaleY = 0.88f + (0.12f * assembleProgress.value)
                },
            contentScale = ContentScale.Fit,
        )
        Image(
            painter = painterResource(R.drawable.micon_logo_mark),
            contentDescription = "MICON",
            modifier = Modifier
                .width(29.4.dp)
                .height(72.dp)
                .offset(x = (-54.6).dp * assembleProgress.value)
                .scale(markScale.value),
            contentScale = ContentScale.Fit,
        )
    }
}

private const val SPLASH_DURATION_MILLIS = 2200L
