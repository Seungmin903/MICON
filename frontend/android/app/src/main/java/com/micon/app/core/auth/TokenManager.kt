package com.micon.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.micon.app.data.local.UserStore

/** Tokens and account identity are written together to encrypted storage. */
object TokenManager : AuthTokenStore {
    private const val PREF_NAME = "micon_auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private lateinit var prefs: SharedPreferences
    private var generation = 0L

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            context, PREF_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    override fun snapshot() = SessionSnapshot(
        prefs.getString(KEY_ACCESS_TOKEN, null), prefs.getString(KEY_REFRESH_TOKEN, null),
        prefs.getLong(KEY_USER_ID, -1L), generation,
    )

    @Synchronized
    override fun saveSession(userId: Long, accessToken: String, refreshToken: String) {
        require(userId > 0 && accessToken.isNotBlank() && refreshToken.isNotBlank())
        UserStore.clearUserData()
        generation++
        prefs.edit().clear().putLong(KEY_USER_ID, userId)
            .putString(KEY_ACCESS_TOKEN, accessToken).putString(KEY_REFRESH_TOKEN, refreshToken).apply()
    }

    @Synchronized
    override fun saveUserId(userId: Long) {
        require(userId > 0)
        if (getUserId() != userId) UserStore.clearUserData()
        prefs.edit().putLong(KEY_USER_ID, userId).apply()
    }

    @Synchronized
    override fun rotateTokens(expected: SessionSnapshot, accessToken: String, refreshToken: String): Boolean {
        if (snapshot() != expected || accessToken.isBlank() || refreshToken.isBlank()) return false
        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).putString(KEY_REFRESH_TOKEN, refreshToken).apply()
        return true
    }

    fun getAccessToken(): String? = snapshot().accessToken
    fun getRefreshToken(): String? = snapshot().refreshToken
    fun getUserId(): Long = snapshot().userId
    fun isLoggedIn(): Boolean = snapshot().isLoggedIn

    @Synchronized
    override fun clearIfCurrent(expected: SessionSnapshot): Boolean {
        if (snapshot() != expected) return false
        clear()
        return true
    }

    @Synchronized
    override fun clear() {
        generation++
        prefs.edit().clear().apply()
        UserStore.clearUserData()
    }
}
