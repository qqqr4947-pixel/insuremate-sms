package me.capcom.smsgateway.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import me.capcom.smsgateway.MainActivity

/**
 * im16 · «다른 앱 위에 표시» 스위치를 켜는 순간 이 앱으로 데려온다 (박 대표 2026-11-13 «제대로 바로 한번에 간소화»)
 * 설정 화면은 켠 뒤 뒤로가기를 직접 눌러야 했다. 0.5초마다 재다가 켜지면 홈을 맨 앞으로(설정 화면은 걷어 낸다).
 * 이 허락을 받은 앱은 뒤에서도 화면을 열 수 있어(안드 10 뒤 제한의 예외) 이 길이 막히지 않는다.
 * 2분 지나거나 사람이 직접 돌아오면(홈 onResume → stop) 그만 잰다.
 */
object OverlayWatch {
    private const val TAG = "OverlayWatch"
    private const val EVERY_MS = 500L
    private const val GIVE_UP_MS = 120_000L
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    fun start(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        stop()
        val until = System.currentTimeMillis() + GIVE_UP_MS
        val r = object : Runnable {
            override fun run() {
                if (Settings.canDrawOverlays(app)) {
                    tick = null
                    try {
                        app.startActivity(
                            Intent(app, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        )
                    } catch (e: Throwable) {
                        Log.w(TAG, "앱으로 못 돌아옴 · 사람이 뒤로가기로 오면 된다", e)
                    }
                    return
                }
                if (System.currentTimeMillis() < until) handler.postDelayed(this, EVERY_MS) else tick = null
            }
        }
        tick = r
        handler.postDelayed(r, EVERY_MS)
    }

    fun stop() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
    }
}
