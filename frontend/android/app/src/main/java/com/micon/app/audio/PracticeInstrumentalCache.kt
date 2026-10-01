package com.micon.app.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.micon.app.data.InstrumentalMedia
import okhttp3.OkHttpClient

/** 재생과 인접 키 선로딩이 공유하는, 용량 제한이 있는 앱 전용 임시 캐시. */
@OptIn(UnstableApi::class)
class PracticeInstrumentalCache private constructor(context: Context) {
    private val cache = SimpleCache(
        context.cacheDir.resolve("practice-instrumentals"),
        LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    )
    private val activeWriters = mutableSetOf<CacheWriter>()

    fun dataSourceFactory(http: OkHttpClient): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** 호출자는 IO 스레드에서 실행한다. 실패해도 일반 스트리밍 재생은 계속 가능하다. */
    fun prefetch(media: InstrumentalMedia, http: OkHttpClient) {
        if (!eligibleForPrefetch(media)) return
        val url = media.playbackUrl ?: return
        val key = cacheKey(media) ?: return
        val bytes = media.fileBytes ?: return
        if (cache.isCached(key, 0, bytes)) return
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).setLength(bytes).build()
        val writer = CacheWriter(dataSourceFactory(http).createDataSource(), spec, null, null)
        synchronized(activeWriters) { activeWriters.add(writer) }
        try {
            writer.cache()
        } finally {
            synchronized(activeWriters) { activeWriters.remove(writer) }
        }
    }

    fun cancelPrefetch() {
        synchronized(activeWriters) { activeWriters.forEach(CacheWriter::cancel) }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val MAX_PREFETCH_BYTES = 8L * 1024 * 1024

        @Volatile private var instance: PracticeInstrumentalCache? = null

        fun get(context: Context): PracticeInstrumentalCache = instance ?: synchronized(this) {
            instance ?: PracticeInstrumentalCache(context.applicationContext).also { instance = it }
        }

        /** 만료 URL이 새로 발급돼도 같은 파일은 같은 캐시 엔트리를 사용한다. */
        fun cacheKey(media: InstrumentalMedia): String? {
            val sha = media.sha256?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            return "practice-mr-$sha"
        }

        fun eligibleForPrefetch(media: InstrumentalMedia): Boolean =
            !media.playbackUrl.isNullOrBlank() && cacheKey(media) != null &&
                media.fileBytes != null && media.fileBytes in 1..MAX_PREFETCH_BYTES

        fun adjacentKeys(key: Int, min: Int, max: Int): List<Int> =
            listOf(key + 1, key - 1).filter { it in min..max }
    }
}
