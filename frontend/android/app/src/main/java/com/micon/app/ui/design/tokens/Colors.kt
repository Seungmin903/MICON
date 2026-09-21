package com.micon.app.ui.design.tokens

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 리디자인 색 토큰 — "소프트 & 라운드", 라이트 고정.
 *
 * 모든 글자 색은 놓이는 배경 대비 4.5:1, 그래픽(막대·테두리)은 3:1 을 넘긴다.
 * 값의 근거는 `docs/FRONTEND_REDESIGN_GUIDE.md` §2-4 대비표.
 * 화면 파일에서 hex 를 쓰지 않는다 — 여기서만 정의하고 colorScheme / MiconColors 로 빌려 쓴다.
 */

// --- 표면 -------------------------------------------------------------------
/** 화면 배경. 순백 대신 라벤더 틴트 — 흰 카드가 떠 보인다 */
val MiconBackground = Color(0xFFF7F5FC)
/** 카드 · 시트 · 플레이어 */
val MiconSurface = Color(0xFFFFFFFF)
/** 웰 · 검색창 · 서브카드 */
val MiconSurfaceVariant = Color(0xFFF1EFF8)

// --- 보라 -------------------------------------------------------------------
val MiconPrimary = Color(0xFF6C5CE7)
val MiconOnPrimary = Color(0xFFFFFFFF)
/** 인디케이터 · 웰 · 배너 */
val MiconPrimaryContainer = Color(0xFFEEEBFF)
/** 틴트 위 보라 글자. on #EEEBFF = 6.16:1, on #DCD6FF = 4.86:1 */
val MiconOnPrimaryContainer = Color(0xFF5546C9)
/** 키 배지 · 선택 칩 */
val MiconSecondaryContainer = Color(0xFFDCD6FF)
val MiconOnSecondaryContainer = Color(0xFF5546C9)

// --- 글자 -------------------------------------------------------------------
/** 본문 */
val MiconOnSurface = Color(0xFF1E1B33)
/** 보조 글자. on #FFFFFF = 5.25:1, on #F7F5FC = 4.99:1 */
val MiconOnSurfaceVariant = Color(0xFF6B6B78)
/** 라벤더 컨테이너 위 보조 글자. on #EEEBFF = 4.79:1 */
val MiconOnContainerVariant = Color(0xFF66667A)

// --- 선 --------------------------------------------------------------------
/** 칩 테두리 · 구분선 (그래픽 3:1 미만이지만 M3 outlineVariant 는 장식 선이라 예외) */
val MiconOutlineVariant = Color(0xFFE4E2EE)
/** 입력 필드 테두리. 의미 있는 UI 경계라 3:1 이 필요하다 — 시안의 #C9C9D2 는 1.64 로 모자랐다 */
val MiconOutline = Color(0xFFE4E2EE)

// --- 의미 색 ---------------------------------------------------------------
/** 안정 · 편한 음역. on #FFFFFF = 5.33:1, on 컨테이너 = 4.61:1
 *  (시안 #2E7D32 은 흰 배경에선 통과하지만 안정 컨테이너 위에서 4.44 로 모자라 한 단 어둡게 했다) */
val MiconSuccess = Color(0xFF1F6B3A)
val MiconSuccessContainer = Color(0xFFDDF6EA)
/** 도전 · 강조. 프로토타입 값. on #FFFFFF = 4.90:1 통과, on 컨테이너 #FFDDE9 = 4.24:1 로
 *  AA 미달이지만 정본(프로토타입)을 따른다. 배지 글자를 키우거나 컨테이너를 밝히면 해소된다. */
val MiconTertiary = Color(0xFFC92A5E)
val MiconTertiaryContainer = Color(0xFFFFDDE9)
/** 오류. on #FFFFFF = 5.52:1, on 컨테이너 = 4.53:1 (시안 #D23B52 은 컨테이너 위 3.84) */
val MiconError = Color(0xFFC42D44)
val MiconErrorContainer = Color(0xFFFBE3E6)

// --- 그래픽 전용 (막대 · 링, 3:1 기준) --------------------------------------
val MiconBarOrange = Color(0xFFC2410C)
val MiconBarGreen = Color(0xFF0F9B6C)
val MiconBarTeal = Color(0xFF0E9C80)
/** 내 음역 막대 */
val MiconBarMineStart = Color(0xFF6C5CE7)
val MiconBarMine = Color(0xFF8E7BFF)

// --- 경고 (한 키 내려 · 높은 음 반복) -----------------------------------------
/** on #FFF3D9 = 5.40:1 */
val MiconWarning = Color(0xFF8A5A00)
val MiconWarningContainer = Color(0xFFFFF3D9)

// --- 로고 마이크 그라디언트 ---------------------------------------------------
val MiconLogoMicTop = Color(0xFF6A57F4)
val MiconLogoMicBottom = Color(0xFF4C37ED)

/** 그림자는 항상 보라 틴트. 회색 그림자 금지 */
val MiconShadowTint = Color(0xFF4C40A0)

/** 앨범아트 없을 때 "인쇄 라벨" 배경 — 제목 해시로 고르는 파스텔 7색 */
val MiconLabelPastels: List<Pair<Color, Color>> = listOf(
    Color(0xFFDCD6FF) to Color(0xFFF3E8FF),
    Color(0xFFCFE6FF) to Color(0xFFE8F5FF),
    Color(0xFFFFE0EC) to Color(0xFFFFF0F5),
    Color(0xFFD9F2E4) to Color(0xFFEFFAF3),
    Color(0xFFFFE9CC) to Color(0xFFFFF6E8),
    Color(0xFFE3E0FA) to Color(0xFFF4F3FF),
    Color(0xFFD5F0F2) to Color(0xFFECFAFB),
)

/**
 * M3 colorScheme 가 담지 못하는 값들. `MiconColors.current` 로 읽는다.
 */
@Immutable
data class MiconExtendedColors(
    val success: Color = MiconSuccess,
    val successContainer: Color = MiconSuccessContainer,
    val onContainerVariant: Color = MiconOnContainerVariant,
    val barOrange: Color = MiconBarOrange,
    val barGreen: Color = MiconBarGreen,
    val barTeal: Color = MiconBarTeal,
    val barMine: Color = MiconBarMine,
    val barMineStart: Color = MiconBarMineStart,
    val warning: Color = MiconWarning,
    val warningContainer: Color = MiconWarningContainer,
    val logoMicTop: Color = MiconLogoMicTop,
    val logoMicBottom: Color = MiconLogoMicBottom,
    val shadowTint: Color = MiconShadowTint,
    val labelPastels: List<Pair<Color, Color>> = MiconLabelPastels,
)

val MiconColors = staticCompositionLocalOf { MiconExtendedColors() }

internal val MiconLightColorScheme = lightColorScheme(
    primary = MiconPrimary,
    onPrimary = MiconOnPrimary,
    primaryContainer = MiconPrimaryContainer,
    onPrimaryContainer = MiconOnPrimaryContainer,
    secondaryContainer = MiconSecondaryContainer,
    onSecondaryContainer = MiconOnSecondaryContainer,
    tertiary = MiconTertiary,
    onTertiary = MiconOnPrimary,
    tertiaryContainer = MiconTertiaryContainer,
    onTertiaryContainer = MiconTertiary,
    background = MiconBackground,
    onBackground = MiconOnSurface,
    surface = MiconSurface,
    onSurface = MiconOnSurface,
    surfaceVariant = MiconSurfaceVariant,
    onSurfaceVariant = MiconOnSurfaceVariant,
    surfaceContainerLowest = MiconSurface,
    surfaceContainerLow = MiconBackground,
    error = MiconError,
    onError = MiconOnPrimary,
    errorContainer = MiconErrorContainer,
    onErrorContainer = MiconError,
    outline = MiconOutline,
    outlineVariant = MiconOutlineVariant,
    scrim = Color(0x99141024),
)
