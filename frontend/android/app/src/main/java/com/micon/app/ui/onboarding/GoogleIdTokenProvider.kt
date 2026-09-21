package com.micon.app.ui.onboarding

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Google 계정 선택 UI를 열고 서버 검증용 ID 토큰만 반환한다. 토큰은 저장하지 않는다. */
object GoogleIdTokenProvider {

    suspend fun request(context: Context, webClientId: String): String {
        if (webClientId.isBlank()) {
            throw GoogleSignInException("구글 로그인 설정이 아직 완료되지 않았어요.")
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setServerClientId(webClientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential !is CustomCredential
                || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw GoogleSignInException("구글 인증 정보를 읽지 못했어요. 다시 시도해주세요.")
            }
            try {
                return GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (exception: Exception) {
                throw GoogleSignInException("구글 인증 정보를 읽지 못했어요. 다시 시도해주세요.", exception)
            }
        } catch (exception: GetCredentialException) {
            throw GoogleSignInException("구글 로그인을 완료하지 못했어요. 다시 시도해주세요.", exception)
        }
    }
}

class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)
