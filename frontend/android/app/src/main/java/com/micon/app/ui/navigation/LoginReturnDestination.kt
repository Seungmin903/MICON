package com.micon.app.ui.navigation

import com.micon.app.data.isValidSongId

/** Preserve a screen destination, never guest analysis data or an in-progress recording. */
fun loginReturnDestination(route: String?, songId: String? = null, setlistId: Long? = null, source: String? = null): String? =
    when (route) {
        Routes.REPORT, Routes.RECOMMEND, Routes.PRACTICE, Routes.EVALUATION,
        Routes.FAVORITES, Routes.SETTINGS, Routes.EDIT_PREFERENCES -> route
        Routes.SONG -> songId?.takeIf(::isValidSongId)?.let { Routes.song(it, source ?: Routes.PRACTICE) }
        Routes.SONG_MATCH -> songId?.takeIf(::isValidSongId)?.let(Routes::songMatch)
        Routes.SETLIST -> setlistId?.takeIf { it > 0 }?.let(Routes::setlist)
        Routes.VOICE_ONBOARDING, Routes.QUICK_ANALYSIS, Routes.SING_ANALYSIS_PICK, Routes.SING_ANALYSIS_RUN -> Routes.REPORT
        Routes.PRACTICE_RUN -> songId?.takeIf(::isValidSongId)?.let { Routes.song(it) } ?: Routes.PRACTICE
        Routes.RESULT -> Routes.EVALUATION
        else -> null
    }
