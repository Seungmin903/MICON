package com.micon.app

import android.app.Application
import android.content.Context
import com.micon.app.core.auth.TokenManager
import com.micon.app.core.network.BackendClient
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.backend.RemoteSongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import com.micon.app.data.recommend.RecommendClient
import com.micon.app.data.recommend.RemoteRecommendRepository
import java.io.File
import java.util.concurrent.TimeUnit

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
    /** 앱 백엔드(Spring/MySQL). 사용자·곡 카탈로그·연습 데이터·찜·연습 기록. 앱이 보는 서버는 이것 하나다. */
    val backend: BackendClient = BackendClient(BuildConfig.API_BASE_URL, logging = BuildConfig.HTTP_LOGGING)

    /**
     * VocalScope 엔진(FastAPI). 평가 탭(연습 기록·정밀 리포트)이 아직 엔진 작업(job)을 읽는다.
     * 백엔드 `/practices` 기록 API 로 옮기면 삭제한다. 곡 카탈로그·반주에는 더 쓰지 않는다.
     */
    val engine: EngineClient = EngineClient(BuildConfig.ENGINE_BASE_URL, logging = BuildConfig.HTTP_LOGGING)

    /**
     * 반주 등 미디어 스트리밍용 HTTP 클라이언트. 백엔드가 발급한 만료 URL(S3)로 재생하므로
     * 인증 헤더를 붙이는 [backend] 클라이언트를 쓰면 안 된다 — S3 가 Authorization 헤더가 있는 요청을 거절한다.
     */
    val mediaHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 곡 카탈로그·연습 데이터. 백엔드 `GET /songs`, `/songs/{id}`, `/songs/{id}/practice-assets`. */
    val songs: SongRepository = RemoteSongRepository(backend.api)

    /**
     * 추천·적합도. local.properties 의 RECOMMEND_BASE_URL 이 비어 있으면 앱 안 계산(음역만),
     * 있으면 추천 서버(8788) 응답을 쓰고 녹음이 없거나 서버가 모르는 모드만 로컬로 내려간다. 화면은 인터페이스만 본다.
     */
    val recommend: RecommendRepository = run {
        val local = LocalRecommendRepository(songs)
        if (BuildConfig.RECOMMEND_BASE_URL.isBlank()) local
     * 운영은 백엔드 `/recommendations` 로 옮길 예정(앱은 백엔드만 본다).
        else RemoteRecommendRepository(RecommendClient(BuildConfig.RECOMMEND_BASE_URL, logging = BuildConfig.HTTP_LOGGING), songs, local) {
            // 간편 분석이 남긴 녹음. 개발용: adb 로 cache/quick-analysis.wav 를 넣어도 된다.
            UserStore.lastRecording ?: File(context.cacheDir, "quick-analysis.wav").takeIf { it.exists() }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MiconApplication).container
