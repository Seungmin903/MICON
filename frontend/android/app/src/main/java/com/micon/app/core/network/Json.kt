package com.micon.app.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 앱 공용 Moshi. 리플렉션 어댑터(KSP/직렬화 플러그인 불필요) — DTO 는 기본값 있는 data class 로 둔다.
 * Retrofit 컨버터·로컬 JSON 저장소가 모두 이 인스턴스를 쓴다(어댑터 캐시 공유).
 */
object Json {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
}
