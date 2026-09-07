package com.micon.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 앱 백엔드 Access/Refresh Token 을 암호화 저장한다. Application.onCreate 에서 init 필수.
 * (PickUs 에서 검증된 EncryptedSharedPreferences 구성 그대로)
 */
object TokenManager {
    private const val PREF_NAME = "micon_auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).putString(KEY_REFRESH_TOKEN, refreshToken).apply()
    }

    fun saveUserId(userId: Long) {
        prefs.edit().putLong(KEY_USER_ID, userId).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, -1L)
    fun isLoggedIn(): Boolean = !getRefreshToken().isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
