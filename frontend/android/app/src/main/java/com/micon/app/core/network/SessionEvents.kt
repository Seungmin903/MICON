package com.micon.app.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 네트워크 계층에서 UI 로 올리는 세션 이벤트.
 * MiconRoot 에서 collect 해 세션 만료 시 로그인/랜딩으로 보내면 된다(TODO: 인증 계약 확정 후).
 */
object SessionEvents {
    enum class Event { EXPIRED }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun notifyExpired() {
        _events.tryEmit(Event.EXPIRED)
    }
}
