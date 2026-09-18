package com.micon.app.data.onboarding

import android.content.Context
import com.micon.app.data.local.UserStore
import com.micon.app.data.voice.VoiceProfileMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 저장된 목소리 프로필을 [UserStore.profile] 에 채운다. 연습·추천 상세와 로컬 적합도 계산이 모두 이 값을 읽는다.
 *
 * 리포트 탭이 읽는 것과 같은 출처다 — 기기 파일([VoiceOnboardingStore.savedProfile]) → 없으면 서버 최신 저장 프로필.
 * 로그인·앱 재실행(세션 확인 직후)과 온보딩 저장 직후에 부른다. 실패하면 profile 은 두고 [UserStore.profileLoadFailed] 만 켠다.
 */
object VoiceProfileSync {
    suspend fun restore(context: Context, ownerId: Long, gateway: VoiceOnboardingGateway) {
        try {
            val saved = withContext(Dispatchers.IO) {
                val store = VoiceOnboardingStore(context.applicationContext, ownerId)
                store.savedProfile() ?: gateway.latestSaved(ownerId)?.also(store::saveProfile)
            }
            UserStore.setProfile(saved?.let { VoiceProfileMapper.fromOnboarding(it.range) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UserStore.setProfileLoadFailed(true)
        }
    }
}
