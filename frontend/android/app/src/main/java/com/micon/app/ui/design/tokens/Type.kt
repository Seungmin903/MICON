package com.micon.app.ui.design.tokens

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.micon.app.R
import androidx.compose.ui.unit.sp

/**
 * 타입 스케일 — 하드코딩 `fontSize = N.sp` 464곳(9~96sp 23종)을 대체한다.
 *
 * 값의 출처는 **`MICON_프로토타입_최종.html`** 이다. `docs/FRONTEND_REDESIGN_GUIDE.md` 와
 * 어긋나면 프로토타입이 이긴다.
 *
 * 규칙
 *  - 화면 파일에 `.sp` 가 보이면 이 스케일로 올린다.
 *  - **최소 12sp.** 프로토타입의 9~11.5px 는 전부 [MiconLabelMedium] 으로 올라온다.
 *  - 제목은 **Black(900)** 이다. 프로토타입의 `font-weight:900` 을 그대로 따른다.
 *  - 숫자는 [MiconMonoFamily] 를 쓴다 — 점수 카운트업에서 폭이 흔들리지 않게.
 */

/**
 * Google Play 서비스가 제공하는 Downloadable Fonts. APK 에 폰트를 넣지 않고 런타임에 받는다.
 * 인증서 배열은 AOSP 공식 `font_certs.xml`(Apache 2.0) 이다.
 *
 * 받아오기 전이나 Play 서비스가 없는 기기에서는 시스템 기본으로 폴백한다 — 레이아웃은
 * 그대로고 글꼴만 달라진다.
 */
private val MiconFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun googleFamily(name: String, vararg weights: FontWeight): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(weights.map { Font(googleFont = font, fontProvider = MiconFontProvider, weight = it) })
}

/** 본문. 프로토타입 `--sans: 'Noto Sans KR'` — 400·500·700·900 */
val MiconFontFamily: FontFamily = googleFamily(
    "Noto Sans KR",
    FontWeight.Normal, FontWeight.Medium, FontWeight.Bold, FontWeight.Black,
)

/**
 * 숫자 전용. 프로토타입 `--mono: 'JetBrains Mono'` — 400·500·700.
 * 음역(C3–C5) · 점수 · 시간(1:24 / 4:18) · 퍼센트 · 트랙번호가 전부 여기에 붙는다.
 * 고정폭이라 점수 카운트업에서 숫자 폭이 흔들리지 않는다.
 */
val MiconMonoFamily: FontFamily = googleFamily(
    "JetBrains Mono",
    FontWeight.Normal, FontWeight.Medium, FontWeight.Bold,
)

/** 로고 워드마크 전용. 프로토타입 `.logo { font-family: 'Montserrat'; font-weight: 900 }` — 800·900 */
val MiconLogoFamily: FontFamily = googleFamily(
    "Montserrat",
    FontWeight.ExtraBold, FontWeight.Black,
)

/** 화면에 허용되는 가장 작은 글자. 이보다 작은 값을 새로 만들지 않는다. */
const val MICON_MIN_TEXT_SP = 12

private fun micon(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
    family: FontFamily = MiconFontFamily,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
)

// --- 숫자 (전부 모노) --------------------------------------------------------
/** 연습 결과 점수 72. 프로토타입 `.bigscore` 72px */
val MiconScore = micon(72, 76, FontWeight.Black, -2.0, MiconMonoFamily)
/** 부르는 중 실시간 점수 60. 결과보다 한 단 작다 */
val MiconScoreLive = micon(60, 64, FontWeight.Black, -2.0, MiconMonoFamily)
/** 매치 % · 주간 평균 34 */
val MiconHeadline = micon(34, 42, FontWeight.Black, -1.0, MiconMonoFamily)
/** 히어로 수치 (음역 C3–C5) 30 */
val MiconNumber = micon(30, 38, FontWeight.Bold, -0.5, MiconMonoFamily)

/** 점수 이름이 바뀌기 전 호출부 호환 */
@Deprecated("MiconScore 로 이름이 바뀌었다", ReplaceWith("MiconScore"))
val MiconDisplay = MiconScore

// --- 글자 -------------------------------------------------------------------
/** 화면 제목 24/32 Black. 프로토타입 `h1.t` */
val MiconTitleLarge = micon(24, 32, FontWeight.Black, -0.4)
/** 카드 · 곡 제목 19/27 Black. 프로토타입 `h2.t` */
val MiconTitleMedium = micon(19, 27, FontWeight.Black, -0.3)
/** 목록 행 제목 15/21 Bold. 프로토타입 `.lrow .nm` */
val MiconTitleSmall = micon(15, 21, FontWeight.Bold, -0.2)
/** 입력 · 가사 16/24 */
val MiconBodyLarge = micon(16, 24)
/** 안내 · 가수명 14/22. 프로토타입 `.sub` 13.5px */
val MiconBodyMedium = micon(14, 22)
/** 버튼 · 칩 14/20 Bold */
val MiconLabelLarge = micon(14, 20, FontWeight.Bold)
/** eyebrow · 배지 · 캡션 12/16 Bold — 최소 크기. 프로토타입 11.5px/700 을 12 로 올렸다 */
val MiconLabelMedium = micon(12, 16, FontWeight.Bold, 0.4)

/**
 * M3 슬롯 매핑. 표에 없는 슬롯도 비워 두지 않고 가장 가까운 단계로 채운다 —
 * 서드파티 컴포넌트가 `displaySmall` 같은 슬롯을 읽어도 기본 폰트로 새지 않게.
 */
val MiconTypography = Typography(
    displayLarge = MiconScore,
    displayMedium = MiconScoreLive,
    displaySmall = MiconHeadline,
    headlineLarge = MiconHeadline,
    headlineMedium = MiconNumber,
    headlineSmall = MiconTitleLarge,
    titleLarge = MiconTitleLarge,
    titleMedium = MiconTitleMedium,
    titleSmall = MiconTitleSmall,
    bodyLarge = MiconBodyLarge,
    bodyMedium = MiconBodyMedium,
    bodySmall = MiconLabelMedium,
    labelLarge = MiconLabelLarge,
    labelMedium = MiconLabelMedium,
    labelSmall = MiconLabelMedium,
)
