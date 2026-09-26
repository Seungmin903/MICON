package com.micon.app

import android.app.Application
import android.content.Context
import com.micon.app.core.auth.TokenManager
import com.micon.app.core.network.BackendClient
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.backend.RemoteSongRepository
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.favorites.RemoteFavoriteRepository
import com.micon.app.data.favorites.RemoteSetlistRepository
import com.micon.app.data.favorites.SetlistRepository
import com.micon.app.data.local.UserStore
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.PracticeRecordingStore
import com.micon.app.data.practice.RemotePracticeRepository
import com.micon.app.data.recommend.BackendRecommendRepository
import com.micon.app.data.settings.RemoteSettingsRepository
import com.micon.app.data.settings.SettingsRepository
import com.micon.app.data.singanalysis.BackendSongAnalysisSubmitter
import com.micon.app.data.singanalysis.SingAnalysisRecordingStore
import com.micon.app.data.singanalysis.SongAnalysisSubmitter
import com.micon.app.data.voice.RemoteVoiceAnalysisRepository
import com.micon.app.data.voice.ScaleAnalysisSubmitter
import com.micon.app.data.voice.VoiceAnalysisRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MiconApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        TokenManager.init(this)
        com.micon.app.ui.settings.restoreNotation(this)
        com.micon.app.data.onboarding.VoiceOnboardingStore.clearInterruptedGuests(this)
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

    /** 연습 기록. 백엔드 `/practices`(세션 생성·완료·라이브 채점 저장·목록·리포트). 로컬 엔진 job 은 더 쓰지 않는다. */
    val practices: PracticeRepository = RemotePracticeRepository(backend.api)

    /** 로그인 계정별 연습 녹음. 서버 업로드와 Android 백업에 포함하지 않는다. */
    val practiceRecordings = PracticeRecordingStore(context)

    /** 찜. 백엔드 `/users/me/favorites`. [FavoriteRepository.likedIds] 를 곡 목록·찜 탭·세트리스트의 하트가 구독한다. */
    val favorites: FavoriteRepository = RemoteFavoriteRepository(backend.api)

    /** 세트리스트. 백엔드 `/setlists`. */
    val setlists: SetlistRepository = RemoteSetlistRepository(backend.api)

    /** 설정(원본 녹음 보관)·음성 데이터 삭제. 백엔드 `/users/me/settings`, `/users/me/voice-data-deletions`. */
    val settings: SettingsRepository = RemoteSettingsRepository(backend.api)

    /**
     * 반주 등 미디어 스트리밍용 HTTP 클라이언트. 백엔드가 발급한 만료 URL(S3)로 재생하므로
     * 인증 헤더를 붙이는 [backend] 클라이언트를 쓰면 안 된다 — S3 가 Authorization 헤더가 있는 요청을 거절한다.
     */
    val mediaHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 곡 카탈로그·연습 데이터. 백엔드 `GET /songs`, `/songs/{id}`, `/songs/{id}/practice-assets`. */
    val songs: SongRepository = RemoteSongRepository(backend.api)

    /** 불러서 분석하기 녹음(WAV + 메타 JSON) 로컬 저장소. `filesDir/sing-analysis/`. */
    val singRecordings: SingAnalysisRecordingStore = SingAnalysisRecordingStore(context)

    /** 음성 녹음 업로드·분석 요청·리포트 조회. 백엔드 `/recordings`, `/voice-analyses`. 리포트 탭·불러서 분석하기가 쓴다. */
    val voiceAnalyses: VoiceAnalysisRepository = RemoteVoiceAnalysisRepository.forClient(backend)

    /** 음역 결과와 노래 녹음을 하나의 백엔드 분석 세션으로 묶는다. */
    val voiceOnboarding: com.micon.app.data.onboarding.VoiceOnboardingGateway =
        com.micon.app.data.onboarding.BackendVoiceOnboardingGateway(backend.api)

    /**
     * 불러서 분석하기 녹음 제출: 업로드 → 분석 생성(SONG). 서버 id 는 녹음 메타 JSON 에 남겨 재요청 때 다시 올리지 않는다.
     * 비회원은 서버를 부르지 않고, AI 연동이 꺼진 서버(503)는 "미준비" 로 화면에 알린다. 화면은 인터페이스만 본다.
     */
    val songAnalysis: SongAnalysisSubmitter = BackendSongAnalysisSubmitter(
        voiceAnalyses, isLoggedIn = { TokenManager.isLoggedIn() }, persist = { singRecordings.update(it) },
    )

    /** 간편 분석(기준음 5개) 녹음 제출: 업로드 → 분석 생성(SCALE). 비회원은 서버를 부르지 않는다. */
    val scaleAnalysis: ScaleAnalysisSubmitter = ScaleAnalysisSubmitter(voiceAnalyses, isLoggedIn = { TokenManager.isLoggedIn() })

    /**
     * 추천·적합도. 백엔드 `/recommendations`(최근 추천·생성 요청)·`/songs/{id}/fit` 을 쓰고, 서버 추천이 없거나 생성 중·미준비·비회원이면
     * 앱 안 음역 계산([LocalRecommendRepository])으로 내려간다. 추천 서버(8788) 직접 호출은 제거했다 — 앱은 백엔드만 본다.
     */
    val recommend: RecommendRepository = BackendRecommendRepository(
        backend.api, songs, LocalRecommendRepository(songs), isLoggedIn = { TokenManager.isLoggedIn() },
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MiconApplication).container
