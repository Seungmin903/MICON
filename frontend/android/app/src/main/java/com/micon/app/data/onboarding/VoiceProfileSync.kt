package com.micon.app.data.onboarding

import android.content.Context
import com.micon.app.data.local.UserStore
import com.micon.app.data.voice.VoiceProfileMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.micon.app.core.auth.TokenManager

/**
 * 저장된 목소리 프로필을 [UserStore.profile] 에 채운다. 연습·추천 상세와 로컬 적합도 계산이 모두 이 값을 읽는다.
 *
 * 서버의 최신 저장 프로필이 기준이다. 확정된 404만 캐시를 지우고, 네트워크 실패는 캐시를 보존한다.
 * 계정/프로필 세대가 바뀌면 이전 요청의 결과를 적용하지 않는다.
 */
object VoiceProfileSync {
    suspend fun restore(context: Context, ownerId: Long, gateway: VoiceOnboardingGateway) {
        val revision = UserStore.voiceDataRevision.value
        fun current() = TokenManager.getUserId() == ownerId && UserStore.voiceDataRevision.value == revision
        try {
            val saved = withContext(Dispatchers.IO) {
                val store = VoiceOnboardingStore(context.applicationContext, ownerId)
                val cached = store.savedProfile()
                val remote = gateway.latestSaved(ownerId)
                synchronized(UserStore) {
                    if (!current()) return@synchronized null
                    if (remote == null) store.clearSavedProfile()
                    val saved = if (remote != null && cached?.receipt?.sessionId == remote.receipt.sessionId)
                        remote.copy(range = cached.range.copy(backendSessionId = remote.range.backendSessionId)) else remote
                    saved?.also(store::saveProfile)
                }
            }
            if (current()) UserStore.setProfile(saved?.let { VoiceProfileMapper.fromOnboarding(it.range) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (current()) UserStore.setProfileLoadFailed(true)
        }
    }
}
