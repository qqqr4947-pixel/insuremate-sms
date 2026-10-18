package me.capcom.smsgateway.modules.calls

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 고객 카드용 통행증 — 벨이 울릴 때 서버가 문자 서버에 열쇠를 다시 묻지 않게(최대 6초) 미리 받아 둔다.
 * 받는 길 = 앱 접속 열쇠로 정식 증명(cardPass) · 7일 · 이틀 남으면 다시 받는다 · 서버가 거절하면 지우고 다시 받는다.
 * 통행증으로 할 수 있는 일은 «번호 하나 묻기»뿐이다(서버가 기기당 시간 60번으로 막는다).
 */
object CallerCardPass {
    private const val TAG = "CallerCard"
    private const val PREFS = "im_caller_card"
    private const val RENEW_BEFORE_MS = 2L * 24 * 60 * 60 * 1000
    private const val RETRY_AFTER_FAIL_MS = 60L * 60 * 1000

    @Volatile private var busy = false

    fun get(context: Context): String? =
        prefs(context).getString("pass", null)?.takeIf { prefs(context).getLong("exp", 0) > System.currentTimeMillis() }

    fun clear(context: Context) {
        prefs(context).edit().remove("pass").remove("exp").apply()
    }

    /** 없거나 곧 끝나면 새로 받는다 — 앱이 켜질 때 · 카드 조회가 거절됐을 때 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val exp = prefs(app).getLong("exp", 0)
        val now = System.currentTimeMillis()
        if (prefs(app).getString("pass", null) != null && exp - now > RENEW_BEFORE_MS) return
        // 못 받았으면 한 시간은 다시 묻지 않는다 — 연결 끊은 폰이 벨마다 6초짜리 증명을 두드리지 않게
        if (now - prefs(app).getLong("failAt", 0) < RETRY_AFTER_FAIL_MS) return
        if (busy) return
        busy = true
        thread(name = "im-card-pass") {
            try {
                val res = CallRequestHandler.post(JSONObject().put("action", "cardPass"))
                val pass = res?.takeIf { it.optBoolean("ok") }?.optString("pass")
                if (!pass.isNullOrBlank()) {
                    prefs(app).edit().putString("pass", pass).putLong("exp", res.optLong("expiresAt")).remove("failAt").apply()
                } else {
                    prefs(app).edit().putLong("failAt", System.currentTimeMillis()).apply()
                }
                Log.i(TAG, "통행증 받기: ${!pass.isNullOrBlank()}")
            } finally {
                busy = false
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
