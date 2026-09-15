package com.micon.app.data

import com.micon.app.core.network.Json
import com.micon.app.data.backend.dto.BeatGrid
import com.micon.app.data.backend.dto.KaraokeNote

/**
 * 테스트 픽스처 `fixtures/tj-XXXXX.job.json`(VocalScope 곡 처리 결과)에서 곡 데이터만 읽는다.
 * 앱에는 더 이상 엔진 job DTO 가 없으므로 필요한 필드(`result.karaokeNotes`·`timeSignature`·`beatGrid`·`lyrics.text`)만 test 전용으로 정의한다.
 * 백엔드 `practice-assets` 의 `targetNotes`·`beatGrid` 가 같은 형식이라 파싱 규칙을 고정하는 용도로 유지한다.
 */
data class SongDataFixture(val id: String = "", val result: Result? = null) {
    data class Result(
        val timeSignature: String? = null,
        val karaokeNotes: List<KaraokeNote>? = null,
        val beatGrid: BeatGrid? = null,
        val lyrics: Lyrics? = null,
    )
    data class Lyrics(val text: String? = null)

    companion object {
        private val adapter = Json.moshi.adapter(SongDataFixture::class.java)

        fun load(name: String): SongDataFixture {
            val stream = checkNotNull(SongDataFixture::class.java.classLoader!!.getResourceAsStream("fixtures/$name")) { "fixture $name" }
            return checkNotNull(adapter.fromJson(stream.bufferedReader().readText()))
        }

        fun karaokeNotes(name: String): List<KaraokeNote> = checkNotNull(load(name).result?.karaokeNotes) { "$name has no karaokeNotes" }
    }
}
