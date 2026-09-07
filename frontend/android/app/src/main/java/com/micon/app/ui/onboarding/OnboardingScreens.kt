package com.micon.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.GreyButton
import com.micon.app.ui.common.MiconLogo
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// 시작 (스플래시)
// ---------------------------------------------------------------------------

/** Figma "시작": 흰 배경 가운데 로고. 1초 뒤 로그인 여부로 분기. */
@Composable
fun SplashScreen(onLoggedIn: () -> Unit, onNeedLogin: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1000)
        if (TokenManager.isLoggedIn()) onLoggedIn() else onNeedLogin()
    }
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        MiconLogo(barHeight = 40.dp, fontSize = 40.sp)
    }
}

// ---------------------------------------------------------------------------
// 랜딩 (로그인)
// ---------------------------------------------------------------------------

/** Figma "랜딩": 로고, 이메일/비밀번호, 로그인하기, 비밀번호 찾기·회원가입, 또는, 구글 로그인 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSignup: () -> Unit, onFindPassword: () -> Unit = {}) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(140.dp))
        MiconLogo(barHeight = 34.dp, fontSize = 34.sp)
        Spacer(Modifier.height(72.dp))
        MiconTextField("이메일", email, { email = it }, "이메일을 입력해주세요", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(18.dp))
        MiconTextField("비밀번호", password, { password = it }, "비밀번호를 입력해주세요", isPassword = true)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            "로그인하기",
            onClick = {
                // TODO: BackendApi 로그인 → TokenManager.saveTokens. 지금은 바로 진입.
                onLoggedIn()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("비밀번호 찾기", fontSize = 14.sp, color = InkSoft, modifier = Modifier.clickable(onClick = onFindPassword))
            Text("회원가입", fontSize = 14.sp, color = InkSoft, modifier = Modifier.clickable(onClick = onSignup))
        }
        Spacer(Modifier.height(44.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
            Text("또는", fontSize = 13.sp, color = Grey, modifier = Modifier.padding(horizontal = 14.dp))
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
        }
        Spacer(Modifier.height(64.dp))
        GreyButton(
            "구글 로그인",
            onClick = { /* TODO: play-services-auth 연동 (libs.bundles.login 활성화 후) */ onLoggedIn() },
            modifier = Modifier.fillMaxWidth(),
            leading = { GoogleG() },
        )
        Spacer(Modifier.height(40.dp))
    }
}

/** 구글 "G" 로고 대용(라이브러리 없이). 실제 브랜드 에셋으로 교체 가능. */
@Composable
private fun GoogleG() {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "G",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.sweepGradient(listOf(Color(0xFF4285F4), Color(0xFF34A853), Color(0xFFFBBC05), Color(0xFFEA4335), Color(0xFF4285F4))),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// 회원가입
// ---------------------------------------------------------------------------

/** Figma "회원가입": 이메일(중복 오류 빨강), 비밀번호(규칙 초록), 비밀번호 확인, 회원가입하기 */
@Composable
fun SignupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val emailTaken = false // TODO: BackendApi 이메일 중복 확인
    val pwOk = password.length >= 8 && password.any { it.isDigit() } && password.any { it.isLetter() } && password.any { !it.isLetterOrDigit() }
    val confirmOk = confirm.isEmpty() || confirm == password

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        MiconLogo(barHeight = 34.dp, fontSize = 34.sp)
        Spacer(Modifier.height(28.dp))
        Text("회원가입", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkSoft)
        Spacer(Modifier.height(40.dp))
        MiconTextField(
            "이메일", email, { email = it }, "이메일을 입력해주세요", keyboardType = KeyboardType.Email,
            helper = if (emailTaken) "이미 사용중인 이메일입니다." else null, helperColor = Red,
        )
        Spacer(Modifier.height(18.dp))
        MiconTextField(
            "비밀번호", password, { password = it }, "비밀번호를 입력해주세요", isPassword = true,
            helper = "영문자, 숫자, 특수문자(#, *, -, @) 포함 8자 이상", helperColor = if (password.isEmpty() || pwOk) GreenChipText else Red,
        )
        Spacer(Modifier.height(18.dp))
        MiconTextField(
            "비밀번호 확인", confirm, { confirm = it }, "비밀번호를 다시 입력해주세요", isPassword = true,
            helper = if (!confirmOk) "비밀번호가 일치하지 않습니다." else null, helperColor = Red,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            "회원가입하기",
            onClick = { /* TODO: BackendApi 회원가입 */ onDone() },
            modifier = Modifier.fillMaxWidth(),
            enabled = email.isNotBlank() && pwOk && confirm == password,
        )
        Spacer(Modifier.height(16.dp))
        Text("로그인으로 돌아가기", fontSize = 14.sp, color = Grey, modifier = Modifier.clickable(onClick = onBack))
        Spacer(Modifier.height(40.dp))
    }
}

// ---------------------------------------------------------------------------
// 랜딩 페이지 - 가수 / 장르 선호 조사
// ---------------------------------------------------------------------------

private val SAMPLE_ARTISTS = listOf(
    "장범준", "최유리", "로이킴", "아이유", "박효신", "허밍우", "에일리", "한로로", "백예린", "권진아", "정다운", "이소라",
    "성시경", "태연", "폴킴", "볼빨간사춘기", "10CM", "김광석",
)
private val SAMPLE_GENRES = listOf("발라드", "댄스", "R&B", "팝", "록", "힙합", "레게", "인디", "재즈", "포크", "트로트", "OST")

/**
 * Figma "랜딩 페이지 - 가수 선호 조사" → "장르 선호 조사". 상단 알약(아티스트/장르)으로 단계 표시.
 * 결과는 UserStore.preferredArtists/Genres 에 저장(백엔드 users 프로필 연동 예정).
 */
@Composable
fun PreferenceSurveyScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) } // 0 아티스트, 1 장르
    val artists = remember { mutableStateOf(setOf<String>()) }
    val genres = remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(96.dp))
        Text(if (step == 0) "어떤 음악을 좋아하세요?" else "어떤 장르를 좋아하세요?", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("*복수 선택 가능", fontSize = 13.sp, color = Pink)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PillChip("아티스트", selected = step == 0, onClick = { step = 0 }, fontSize = 14.sp)
            PillChip("장르", selected = step == 1, onClick = { step = 1 }, fontSize = 14.sp)
        }
        Spacer(Modifier.height(18.dp))

        if (step == 0) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(SAMPLE_ARTISTS) { name ->
                    val selected = name in artists.value
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { artists.value = if (selected) artists.value - name else artists.value + name }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF6D6D80), Color(0xFF2E2E3E)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            // TODO: 아티스트 이미지(AsyncImage). 지금은 이름 첫 글자.
                            Text(name.take(1), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (selected) {
                                Box(Modifier.fillMaxSize().background(Purple.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Favorite, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(name, fontSize = 13.sp, color = Ink, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("다음", onClick = { step = 1 }, modifier = Modifier.fillMaxWidth())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(SAMPLE_GENRES) { g ->
                    val selected = g in genres.value
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(if (selected) PurpleMid else Lavender)
                            .clickable { genres.value = if (selected) genres.value - g else genres.value + g },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(g, fontSize = 15.sp, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinePurpleButton("이전으로", onClick = { step = 0 }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "완료",
                onClick = {
                    UserStore.preferredArtists = artists.value
                    UserStore.preferredGenres = genres.value
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(48.dp))
    }
}
