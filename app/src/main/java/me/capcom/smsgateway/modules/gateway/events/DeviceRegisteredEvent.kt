package me.capcom.smsgateway.modules.gateway.events

import me.capcom.smsgateway.modules.events.AppEvent

sealed class DeviceRegisteredEvent(
    val server: String,
) : AppEvent(NAME) {
    class Success(
        server: String,
        val login: String,
        val password: String?,
    ) : DeviceRegisteredEvent(server)

    class Failure(
        server: String,
        val reason: String,
        // 11-12 · 이유 이름표(대리가 보내는 why · 앱이 정한 network/server) — 홈이 이유별 창을 고른다
        val why: String? = null,
        val phoneLast4: String? = null,
        val accountLast4: String? = null,
    ) : DeviceRegisteredEvent(server)

    companion object {
        const val NAME = "DeviceRegisteredEvent"
    }
}