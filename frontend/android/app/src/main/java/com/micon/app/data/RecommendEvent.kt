package com.micon.app.data

import android.util.Log

/**
 * 추천 카드 상호작용 로그 1건. AI 파트 지시(2026-09-07): 이 이벤트가 음색 두 축의 비율·개인화를 정하는 **유일한 데이터원**이다.
 * 개인화 로직은 클라이언트에 만들지 않는다 — 기록만 한다.
 */
data class RecommendEvent(
    val userId: String,
    val songId: Long,
    val timbreAxis: TimbreAxis?,
    /** 추천 목록에서의 위치, 1부터 */
    val position: Int,
    val action: Action,
    val scoringVersion: String,
    val compositionVersion: String?,
    val atMillis: Long = System.currentTimeMillis(),
) {
    enum class Action { TAP, PLAY, SAVE }
}

/** 이벤트 기록. BE 수집 엔드포인트가 생기면 여기서 POST 로 바꾼다 — 호출부는 그대로. */
object RecommendEventLog {
    // ponytail: Logcat 출력만. BE `POST recommend-events` 계약이 오면 BackendApi 호출 + 오프라인 큐로 교체.
    fun log(event: RecommendEvent) {
        Log.i("RecommendEvent", event.toString())
    }
}
