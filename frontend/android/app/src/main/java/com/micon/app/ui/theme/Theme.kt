package com.micon.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
