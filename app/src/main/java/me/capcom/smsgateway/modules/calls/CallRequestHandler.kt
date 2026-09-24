package me.capcom.smsgateway.modules.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 인슈어메이트 PC 「전화 걸기」 → 이 폰에 번호 채운 전화 화면(ACTION_DIAL).
 * 통화는 사람이 누른다(CALL_PHONE 안 씀). 통화 끝 감지 · 고객 명단 없음 · 주기적으로 묻지 않음(특허 선).
 *
 * 푸시에는 요청 id 만 온다 → 우리 서버(smsAppApi callFetch)에서 번호를 «한 번» 받아온다(앱 접속 열쇠로 증명).
 * 1) «다른 앱 위에 표시»가 켜져 있으면 전화 화면을 곧바로 연다(안드 10+ 백그라운드 시작 예외).
 * 2) 아니면(또는 열기가 실패하면) 알림 — 누르면 같은 전화 화면.
 */
object CallRequestHandler : KoinComponent {
    const val EVENT = "CallRequested"

    private const val TAG = "CallRequest"
    private const val CHANNEL_ID = "call_requests"
    private const val NOTIFICATION_ID = 7301
    private const val TIMEOUT_MS = 5_000  // FCM 처리 한도(≈10~20초) 안에 들도록 · 연결 5 + 읽기 5

    private val settings by inject<GatewaySettings>()

    /** FCM 수신 스레드에서 불린다 — FirebaseMessagingService 는 작업 스레드(EnhancedIntentService)에서 부른다(09-24 logcat 실측) */
    fun handle(context: Context, data: String?) {
        val id = try {
            JSONObject(data ?: "{}").optString("id")
        } catch (e: Throwable) {
            ""
        }
        if (id.isBlank()) return

        val direct = canOpenDirectly(context)
        val canShow = direct || notificationsEnabled(context)
        val res = post(
            JSONObject()
                .put("action", "callFetch")
                .put("id", id)
                .put("notify", canShow)
        )
        if (res == null || !res.optBoolean("ok")) {
            Log.w(TAG, "번호를 못 받았다: ${res?.optString("reason")}")
            if (res == null) notifyText(context, context.getString(R.string.call_fetch_failed))
            return
        }
        val tel = res.optString("tel").filter { it.isDigit() }
        if (tel.isBlank()) return
        val name = res.optString("name").take(20)

        if (direct && openDialer(context, tel)) {
            Log.i(TAG, "전화 화면을 곧바로 열었다")
            return
        }
        notifyDial(context, tel, name)
    }

    /** 알림 주소(FCM 토큰)를 우리 서버에 알린다 — 앱 시작 · 새 토큰 때마다. 연결 전이면 조용히 건너뛴다 */
    fun reportPushToken(token: String?) {
        if (token.isNullOrBlank()) return
        if (settings.registrationInfo?.token == null) return
        thread(name = "im-push-token") {
            val res = post(JSONObject().put("action", "pushToken").put("token", token))
            Log.i(TAG, "알림 주소 보고: ${res?.optBoolean("ok")}")
        }
    }

    private fun post(body: JSONObject): JSONObject? {
        val info = settings.registrationInfo ?: return null
        return try {
            val conn = URL(BuildConfig.IM_API_BASE + "/smsAppApi").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + info.token)
            body.put("deviceId", info.id)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            JSONObject(text ?: "{}")
        } catch (e: Throwable) {
            Log.w(TAG, "서버에 못 닿았다", e)
            null
        }
    }

    private fun canOpenDirectly(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun notificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(CHANNEL_ID)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun dialIntent(tel: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openDialer(context: Context, tel: String): Boolean = try {
        context.startActivity(dialIntent(tel))
        true
    } catch (e: Throwable) {
        Log.w(TAG, "전화 화면 열기 실패 → 알림으로", e)
        false
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.call_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun lockedVersion(context: Context) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.call_locked_title))
            .build()

    private fun notifyDial(context: Context, tel: String, name: String) {
        ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            dialIntent(tel),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val shown = formatTel(tel)
        val body = if (name.isNotBlank()) "$name · $shown" else shown
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.call_title))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockedVersion(context))
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.call_action), pending)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        notifySafe(context, notification)
    }

    private fun notifyText(context: Context, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.call_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        notifySafe(context, notification)
    }

    private fun notifySafe(context: Context, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 권한 없음", e)
        }
    }

    private fun formatTel(tel: String): String = when {
        tel.startsWith("02") && tel.length == 10 -> "${tel.take(2)}-${tel.substring(2, 6)}-${tel.substring(6)}"
        tel.startsWith("02") && tel.length == 9 -> "${tel.take(2)}-${tel.substring(2, 5)}-${tel.substring(5)}"
        tel.length == 11 -> "${tel.take(3)}-${tel.substring(3, 7)}-${tel.substring(7)}"
        tel.length == 10 -> "${tel.take(3)}-${tel.substring(3, 6)}-${tel.substring(6)}"
        else -> tel
    }
}
