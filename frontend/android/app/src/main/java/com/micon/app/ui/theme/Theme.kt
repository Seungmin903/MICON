package com.micon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush

// ---------------------------------------------------------------------------
// Figma(MICON) 팔레트. 흰 배경 + 보라 포인트.
// 색 의미(FIGMA_PRACTICE_SCREENS.md §8): 보라 = 곡/기본, 연두 = 안정, 빨강 = 불안정·음이탈·도전
// hex 는 Figma 화면을 기준으로 맞춘 값이다. 디자이너가 토큰을 확정하면 여기만 바꾼다.
// ---------------------------------------------------------------------------
val Purple = Color(0xFF6C5CE7)          // 주 색: 버튼, 활성 탭, 날짜 배지, 토글
val PurpleDark = Color(0xFF5546C9)
val PurpleMid = Color(0xFF9F8DF7)       // 선택된 장르 원, 곡 음역 막대
val PurpleDim = Color(0xFF7C6FD6)       // 오버레이 뒤 비활성 버튼
val Lavender = Color(0xFFEEEBFF)        // 하이라이트 줄, 카드 강조, 선택 안 된 원
val LavenderDeep = Color(0xFFDCD6FF)    // key 배지 배경, 내 음역 막대
val Ink = Color(0xFF15142B)             // 본문 글자
val InkSoft = Color(0xFF3A3A4A)
val Grey = Color(0xFF8C8C96)            // 보조 글자(가수명, 비활성 탭)
val GreyLight = Color(0xFFE6E6EB)       // 구분선, 트랙
val GreyBg = Color(0xFFF2F2F5)          // 검색창, 회색 버튼, 서브카드
val Paper = Color(0xFFFFFFFF)
val Pink = Color(0xFFFF5C8A)            // *복수 선택 가능, 회원 탈퇴, 최고음 도전
val PinkBg = Color(0xFFFFE3EC)          // 하이라이트 배지 배경
val Moss = Color(0xFF3F9D5A)            // good (scoring-ui.ts 와 동일)
val GreenBar = Color(0xFF9BD65A)        // 안정적/편안한 음역 막대
val GreenChipBg = Color(0xFFE9F7D8)
val GreenChipText = Color(0xFF4EA72E)
val Orange = Color(0xFFF5A623)          // 추천곡 음역 막대
val Red = Color(0xFFE8556A)             // 불안정 음역, 음정 불일치 바늘
val RedChipBg = Color(0xFFFFE3E6)
val Teal = Color(0xFF2ED3B0)            // 음정 일치 바늘
val Black = Color(0xFF111111)

/** 프로토타입 화면 호환용 별칭 */
val LavenderSoft = Lavender

/**
 * 추천 탭 배경: 위아래 흰색 → 가운데 남보라(#3120B3) 세로 그라데이션 (시안 `추천곡 (2).png` 에서 픽셀로 뽑은 값).
 * PNG 를 깔면 화면 비율마다 늘어나므로 브러시로 그린다 — 어떤 기기에서도 같은 모양이고 가운데가 항상 화면 한가운데다.
 * (방사형도 만들어 봤지만 진한 부분이 LP 에 가려져 연보라로만 보여 선형으로 되돌렸다)
 */
val RecommendBackground: Brush = Brush.verticalGradient(
    0.0f to Color(0xFFFFFFFF),
    0.1f to Color(0xFFD5D1EF),
    0.2f to Color(0xFFACA5E0),
    0.3f to Color(0xFF8277D1),
    0.4f to Color(0xFF574AC1),
    0.5f to Color(0xFF3120B3),
    0.6f to Color(0xFF5B4DC2),
    0.7f to Color(0xFF8479D1),
    0.8f to Color(0xFFADA6E0),
    0.9f to Color(0xFFD5D2EF),
    1.0f to Color(0xFFFFFFFF),
)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Paper,
    primaryContainer = Lavender,
    onPrimaryContainer = PurpleDark,
    secondary = Moss,
    onSecondary = Paper,
    error = Red,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = GreyBg,
    onSurfaceVariant = Grey,
    outline = GreyLight,
)

/** 앱 테마. 다크 모드는 Figma 에 없어 라이트 고정이다. */
@Composable
fun MiconTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = MiconTypography, content = content)
}
