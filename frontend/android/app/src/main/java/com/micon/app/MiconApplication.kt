package com.micon.app

import android.app.Application
import android.content.Context
import com.micon.app.core.auth.TokenManager
import com.micon.app.core.network.BackendClient
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import com.micon.app.data.recommend.RecommendClient
import com.micon.app.data.recommend.RemoteRecommendRepository
import java.io.File
import com.micon.app.data.sqlite.SqliteSongRepository

class MiconApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        TokenManager.init(this)
        container = AppContainer(this)

        // 소셜 로그인을 켤 때: libs.bundles.login 주석 해제 후 아래를 활성화한다.
        // val kakaoKey = getString(R.string.kakao_native_app_key)
        // if (kakaoKey.isNotBlank()) com.kakao.sdk.common.KakaoSdk.init(this, kakaoKey)
    }
}

/**
 * 수동 DI 컨테이너. 화면·ViewModel 은 여기서 의존성을 받는다.
 * 화면 수가 늘어 관리가 어려워지면 Hilt 로 바꿔도 되지만 지금은 이걸로 충분하다.
 */
class AppContainer(context: Context) {
    /** VocalScope 엔진(FastAPI). 반주 스트리밍·채점 등 실시간 작업 전용. */
    val engine: EngineClient = EngineClient(BuildConfig.ENGINE_BASE_URL, logging = BuildConfig.HTTP_LOGGING)

    /** 앱 백엔드(Spring/MySQL, 예정). 사용자·찜·연습 기록 등. */
    val backend: BackendClient = BackendClient(BuildConfig.API_BASE_URL, logging = BuildConfig.HTTP_LOGGING)

    /**
     * 곡 카탈로그. 현재는 assets 의 개발용 SQLite.
     * 백엔드 API 가 생기면 Retrofit 구현으로 교체하고 sqlite 구현·asset 은 삭제한다.
     */
    val songs: SongRepository = SqliteSongRepository(context, EngineClient.moshi)

    /**
     * 추천·적합도. local.properties 의 RECOMMEND_BASE_URL 이 비어 있으면 앱 안 계산(음역만),
     * 있으면 추천 서버(8788) 응답을 쓰고 녹음이 없거나 서버가 모르는 모드만 로컬로 내려간다. 화면은 인터페이스만 본다.
     */
    val recommend: RecommendRepository = run {
        val local = LocalRecommendRepository(songs)
        if (BuildConfig.RECOMMEND_BASE_URL.isBlank()) local
        else RemoteRecommendRepository(RecommendClient(BuildConfig.RECOMMEND_BASE_URL, logging = BuildConfig.HTTP_LOGGING), songs, local) {
            // 간편 분석이 남긴 녹음. 개발용: adb 로 cache/quick-analysis.wav 를 넣어도 된다.
            UserStore.lastRecording ?: File(context.cacheDir, "quick-analysis.wav").takeIf { it.exists() }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MiconApplication).container
