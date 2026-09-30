package com.micon.app.ui.holo

import com.micon.app.audio.VocalQuality

/** A local preview needs a usable pitch result; a song also needs enough audio for the AI. */
internal object HoloEarlyFinishGate {
    fun issue(localUsable: Boolean, songSelected: Boolean, quality: VocalQuality?): String? = when {
        !localUsable -> "목소리가 충분히 잡히지 않았어요. 조금 더 불러 주세요."
        songSelected && quality?.ready != true ->
            quality?.message ?: "목소리 구간을 확인하고 있어요. 잠시 후 다시 눌러 주세요."
        else -> null
    }
}
