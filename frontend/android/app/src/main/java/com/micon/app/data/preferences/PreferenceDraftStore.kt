package com.micon.app.data.preferences

import android.content.Context
import com.micon.app.core.network.Json

data class PreferenceDraft(
    val genres: List<PreferenceGenre> = emptyList(),
    val artists: List<PreferenceArtist> = emptyList(),
    val step: Int = 0,
    val dirty: Boolean = false,
)

/** Account and flow are part of the key: one account's unfinished work never leaks to another. */
interface PreferenceDraftStore {
    fun read(userId: Long, isEditing: Boolean): PreferenceDraft?
    fun write(userId: Long, isEditing: Boolean, draft: PreferenceDraft)
    fun clear(userId: Long, isEditing: Boolean)
}

class SharedPreferenceDraftStore(context: Context) : PreferenceDraftStore {
    private val preferences = context.applicationContext.getSharedPreferences("music_preference_drafts", Context.MODE_PRIVATE)
    private val adapter = Json.moshi.adapter(PreferenceDraft::class.java)
    private fun key(userId: Long, editing: Boolean) = "user_${userId}_${if (editing) "edit" else "onboarding"}"

    override fun read(userId: Long, isEditing: Boolean): PreferenceDraft? {
        if (userId <= 0) return null
        val json = preferences.getString(key(userId, isEditing), null) ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    override fun write(userId: Long, isEditing: Boolean, draft: PreferenceDraft) {
        if (userId > 0) preferences.edit().putString(key(userId, isEditing), adapter.toJson(draft)).apply()
    }

    override fun clear(userId: Long, isEditing: Boolean) {
        preferences.edit().remove(key(userId, isEditing)).apply()
    }
}
