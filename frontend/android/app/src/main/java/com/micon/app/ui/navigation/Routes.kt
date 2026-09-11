package com.micon.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 화면 경로. Figma 프레임 이름과의 대응은 README "화면 ↔ 패키지" 표 참고.
 * 인자가 있는 경로는 `Routes.xxx(id)` 헬퍼로 만든다.
 */
object Routes {
    // 온보딩 (Figma: 시작, 랜딩, 회원가입, 랜딩 페이지 - 가수/장르 선호 조사)
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val SURVEY = "survey"
    const val EDIT_PREFERENCES = "settings/preferences"

    // 하단 탭 5개 (Figma: 추천 · 리포트 · 연습 · 평가 · 찜)
    const val RECOMMEND = "recommend"
    const val REPORT = "report"
    const val PRACTICE = "practice"          // = 노래 연습 - 곡 선택
    const val EVALUATION = "evaluation"      // = 노래 연습 분석 (목록 + 아코디언 상세)
    const val FAVORITES = "favorites"        // = 노래 찜 목록 / 세트리스트

    // 하위 화면
    const val SONG_MATCH = "match/{songId}"          // song-match-detail
    const val QUICK_ANALYSIS = "report/quick"        // 음성 분석 리포트 - 음성 간편 분석
    const val SING_ANALYSIS_PICK = "report/sing"     // 불러서 분석하기 - 곡 선택 (Figma 없음)
    const val SING_ANALYSIS_RUN = "report/sing/{songId}" // 불러서 분석하기 - 범위 선택/부르기/저장 완료
    const val SONG = "song/{songId}"                 // 노래 연습 - 노래 상세정보 (하단 탭 보임)
    const val PRACTICE_RUN = "practice/{songId}"     // 노래 연습 - 연습전/연습중
    const val RESULT = "result/{jobId}"              // 노래 연습 분석 상세 (전체 화면)
    const val SETLIST = "setlist/{setlistId}"        // 노래 찜 목록 - 세트리스트 세부 (하단 탭 보임)
    const val SETTINGS = "settings"
    const val DEV_SEED_PROFILE = "dev/seed-profile"  // 개발용

    /** 개발용 딥링크 접두 (AndroidManifest 의 micon://app) */
    const val DEEP_LINK = "micon://app"

    fun songMatch(songId: Long) = "match/$songId"
    fun song(songId: Long) = "song/$songId"
    fun practice(songId: Long) = "practice/$songId"
    fun singAnalysis(songId: Long) = "report/sing/$songId"
    fun result(jobId: String) = "result/$jobId"
    fun setlist(setlistId: String) = "setlist/$setlistId"

    /** 하단 탭을 보이는 경로 (Figma 에서 하단 바가 있는 화면) */
    val withBottomBar: Set<String> = BottomTab.routes + setOf(SONG, SETLIST)
}

/** 하단 탭 정의. 순서·아이콘은 Figma 와 같다: 리포트(그래프) · 추천(음표 원) · 연습(마이크) · 평가(클립보드) · 찜(북마크 하트) */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    REPORT(Routes.REPORT, "리포트", Icons.Outlined.Assessment),
    RECOMMEND(Routes.RECOMMEND, "추천", Icons.Outlined.Explore),
    PRACTICE(Routes.PRACTICE, "연습", Icons.Outlined.Mic),
    EVALUATION(Routes.EVALUATION, "평가", Icons.Outlined.RateReview),
    FAVORITES(Routes.FAVORITES, "찜", Icons.Outlined.BookmarkBorder);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
    }
}
