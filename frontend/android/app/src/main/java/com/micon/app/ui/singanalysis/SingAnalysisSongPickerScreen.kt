package com.micon.app.ui.singanalysis

import androidx.compose.runtime.Composable
import com.micon.app.ui.songlist.SongListScreen

/**
 * 불러서 분석하기 1단계 — 곡 선택. Figma 프레임 없음: "노래 연습 - 곡 선택" 목록을 뒤로가기 헤더로 재사용한다.
 * 하단 탭은 보이지 않는다(리포트 하위 흐름).
 */
@Composable
fun SingAnalysisSongPickerScreen(onBack: () -> Unit, onSongClick: (String) -> Unit) {
    SongListScreen(
        onSongClick = onSongClick,
        backTitle = "목소리 분석 · 곡 선택",
        onBack = onBack,
        subtitle = "부를 곡을 고르면 가사와 반주가 준비돼요",
    )
}
