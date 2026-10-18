package me.capcom.smsgateway.modules.calls

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import me.capcom.smsgateway.BuildConfig
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 전화 오면 고객 카드 — 안드로이드 «전화 걸러내기 앱» 역할(ROLE_CALL_SCREENING · 안드 10+).
 * 특허 선: 폰에 고객 명단 저장·동기화 없음(번호 하나만 그때 묻는다) · 통화 종료 감지 없음 · 메모 입력 없음.
 * 안드로이드는 5초 안에 답해야 해서 통화는 먼저 그대로 보내고, 조회는 그다음 따로 한다.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CallerCardService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val t0 = SystemClock.elapsedRealtime()
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val tel = normalize(details.handle?.schemeSpecificPart) ?: return
        Log.i(TAG, "수신 → 통화 보냄 ${SystemClock.elapsedRealtime() - t0}ms")

        val app = applicationContext
        val pass = CallerCardPass.get(app)
        // 통행증이 없으면 이번 벨은 카드 없이 보낸다(정식 증명은 최대 6초라 벨에 못 맞춘다) — 다음 벨부터 뜬다
        if (pass == null && !BuildConfig.IM_API_BASE.startsWith("http://127.0.0.1")) {
            Log.i(TAG, "통행증 없음 → 받으러 감")
            CallerCardPass.refresh(app)
            return
        }
        thread(name = "im-caller-card") {
            val res = CallRequestHandler.post(
                JSONObject().put("action", "callerLookup").put("tel", tel).put("pass", pass ?: ""),
                LIMIT_MS.toInt(),
            )
            val took = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "조회 끝 ${took}ms · ok=${res?.optBoolean("ok")}")
            // 거절(pass)·깨진 통행증(bad_request) = 지우고 새로 받는다 · 넘침(rate)은 그대로 둔다
            if (res?.optString("reason") == "pass" || res?.optString("reason") == "bad_request") {
                CallerCardPass.clear(app)
                CallerCardPass.refresh(app)
            }
            // 박 대표 10-16 「전화오면 바로 받아줘야하는데 늦으면 고객을 놓치겠지」 — 1초 넘게 걸린 카드는 안 띄운다
            if (took > LIMIT_MS) return@thread
            val cards = res?.takeIf { it.optBoolean("ok") }?.optJSONArray("cards")
            if (cards == null || cards.length() == 0) return@thread
            Handler(Looper.getMainLooper()).post {
                CallerCardOverlay.show(app, cards)
                Log.i(TAG, "카드 표시 ${SystemClock.elapsedRealtime() - t0}ms")
            }
        }
    }

    private fun normalize(raw: String?): String? {
        val d = raw?.filter { it.isDigit() } ?: return null
        val local = if (d.startsWith("82") && d.length in 10..12) "0" + d.substring(2) else d
        return local.takeIf { it.length in 9..11 }
    }

    private companion object {
        const val TAG = "CallerCard"
        const val LIMIT_MS = 1_000L
    }
}
