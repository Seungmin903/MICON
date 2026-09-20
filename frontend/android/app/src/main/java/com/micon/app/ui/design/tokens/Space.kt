package com.micon.app.ui.design.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 간격 · 치수 토큰. 4dp 그리드만 쓴다 — 54/27·18·14·6·5·10 같은 이탈 값을 새로 만들지 않는다.
 */
object MiconSpace {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** 화면 좌우 여백. 기존 28·32 를 20 으로 통일 */
    val screenHorizontal: Dp = 20.dp

    /** 터치 타깃 최소 한 변 (M3) */
    val touchTarget: Dp = 48.dp

    /** 주 버튼 높이 */
    val buttonHeight: Dp = 52.dp
    /** 칩 시각 높이 — 터치는 [touchTarget] 로 넓힌다 */
    val chipHeight: Dp = 36.dp
    /** 입력 필드 높이 */
    val fieldHeight: Dp = 52.dp
    /** 상단 바 최소 높이. 가이드 §4-4 의 "헤더 64" 이되 고정이 아니라 heightIn(min=) 이다 */
    val topBarMinHeight: Dp = 56.dp

    /** 플로팅 내비 알약 높이 */
    val navBarHeight: Dp = 68.dp
    /** 내비 알약 좌우 여백 */
    val navBarInset: Dp = 14.dp
    /** 시스템 바와 알약 사이 */
    val navBarBottomGap: Dp = 16.dp
    /** 내비 알약에 가리지 않도록 스크롤 콘텐츠가 확보할 하단 패딩 */
    val navBarContentPadding: Dp = 104.dp
}
