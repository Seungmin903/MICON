package com.micon.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Figma 댓글 기준 폰트는 Pretendard 다. 폰트 파일(OFL)을 res/font/ 에 넣고
 * FontFamily(Font(R.font.pretendard_regular), ...) 로 교체하면 된다. 그 전까지는 시스템 기본.
 */
val MiconFontFamily: FontFamily = FontFamily.Default

val MiconTypography = Typography()
