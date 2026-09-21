package com.micon.app.ui.recommend

/** 추천 화면 → 곡 상세로 "지금 보고 있는 섹션" 을 건네는 앱 안 메모리 통로. LP 턴테이블 화면은 CD 데크로 대체되어 삭제됐다. */

/**
 * 추천 화면이 "지금 보고 있는 섹션"을 턴테이블 화면에 건네주는 임시 통로.
 * 곡 목록을 네비게이션 인자로 넘기면 URL 이 길어져서, 앱 안 메모리로만 전달한다(백엔드 붙어도 그대로 쓸 수 있다).
 */
object SongCarousel {
    var ids: List<String> = emptyList()
    var title: String = ""

    fun set(songIds: List<String>, shelfTitle: String) {
        ids = songIds
        title = shelfTitle
    }

    /** 딥링크 등으로 목록 없이 들어오면 그 곡 하나만 */
    fun idsFor(songId: String): List<String> = ids.takeIf { songId in it } ?: listOf(songId)

    fun imageOf(songId: String): String? = SongCarouselArt.urls[songId]
}

/** 페이지 index → 앨범 이미지. 이미지는 추천에서 이미 받아 둔 것을 쓴다(추가 조회 없음). */
private object SongCarouselArt {
    var urls: Map<String, String?> = emptyMap()
    fun of(page: Int, ids: List<String>): String? = urls[ids.getOrNull(page)]
}

/** 추천 화면이 캐러셀에 쓸 곡 목록·제목·앨범 이미지를 넘긴다. */
fun setSongCarousel(ids: List<String>, title: String, images: Map<String, String?>) {
    SongCarousel.set(ids, title)
    SongCarouselArt.urls = images
}
