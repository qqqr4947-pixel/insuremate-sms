package me.capcom.smsgateway.modules.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import java.util.concurrent.TimeUnit

/**
 * im16 · 새 판 알림 (박 대표 2026-11-13 «기존에 받은 사람들 화면에서 업데이트 됐다고 뜨게»)
 * 이 앱은 뒤에서만 돌아 홈을 거의 안 연다 → 홈 맨 위 카드만으로는 못 본다.
 * 6시간마다 판 정보를 보고, 새 판이면 알림 한 줄(같은 판은 한 번만) · 누르면 홈 → 맨 위 [업데이트].
 */
class UpdateNotifyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val info = AppUpdate.check(ctx)
        if (info == null) {   // 이미 새 판을 깔았으면 남은 알림을 걷는다
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            return Result.success()
        }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_NOTIFIED, null) == info.versionName) return Result.success()
        // 알림 허락이 꺼져 있으면 안드로이드가 조용히 버린다(안드 13 새 설치 실측 11-13) → «보냄» 기록을 남기지 않고 다음에 다시
        if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Result.success()
        notify(ctx, info.versionName)
        p.edit().putString(KEY_NOTIFIED, info.versionName).apply()
        return Result.success()
    }

    private fun notify(ctx: Context, versionName: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.im_update_notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.notif_send)
            .setContentTitle(ctx.getString(R.string.im_update_notify_title))
            .setContentText(ctx.getString(R.string.im_update_notify_body, versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val NAME = "UpdateNotifyWorker"
        private const val CHANNEL_ID = "im_update"
        private const val NOTIFICATION_ID = 7716
        private const val PREFS = "im_update_notify"
        private const val KEY_NOTIFIED = "notifiedVersion"

        fun start(context: Context) {
            val work = PeriodicWorkRequestBuilder<UpdateNotifyWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
