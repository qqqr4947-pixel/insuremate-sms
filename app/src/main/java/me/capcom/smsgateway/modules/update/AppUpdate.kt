package me.capcom.smsgateway.modules.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import me.capcom.smsgateway.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 앱 안 [업데이트] (박 대표 2026-10-18 「새로 업데이트 받는게 귀찮을거같은데」 → 「좋아 그렇게 하자」)
 *
 * 1) 우리 주소의 판 정보(/sms-app.json · versionName·versionCode·sha256)를 6시간에 한 번 본다
 * 2) 새 판이면 홈 맨 위 카드 → [업데이트] → 받는 곳은 늘 같은 한 길(/sms/app.apk · 배포 때 바꾸는 그 주소)
 * 3) 받은 파일의 지문(sha256)이 판 정보와 같고 · 패키지 이름이 우리 것이어야 설치 화면을 연다
 *    (서명이 다르면 안드로이드가 설치를 막는다 — 지문은 그 앞에서 한 번 더 거르는 것)
 */
object AppUpdate {
    private const val TAG = "AppUpdate"
    // 실판 = https://insuremate.co.kr · 시험판만 연습 서버(빌드 값 imSiteBase)
    private val BASE = BuildConfig.IM_SITE_BASE + "/sms"
    // 판 정보는 맨 위 파일 하나 — «sms/» 폴더를 만들면 /sms 화면 주소와 부딪칠 수 있어 피한다
    private val INFO_URL = BuildConfig.IM_SITE_BASE + "/sms-app.json"
    private const val PREFS = "im_app_update"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    data class Info(val versionName: String, val versionCode: Int, val sha256: String)

    /** 새 판이 있으면 Info · 없거나 못 물으면 null. 작업 스레드에서 부른다 */
    fun check(context: Context, force: Boolean = false): Info? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val raw = if (!force && now - p.getLong("at", 0) < CHECK_EVERY_MS) p.getString("json", null) else {
            val got = fetchText(INFO_URL)
            if (got != null) p.edit().putString("json", got).putLong("at", now).apply()
            got ?: p.getString("json", null)
        }
        val j = try { JSONObject(raw ?: return null) } catch (e: Throwable) { return null }
        val info = Info(j.optString("versionName"), j.optInt("versionCode"), j.optString("sha256").lowercase())
        if (info.versionName.isBlank() || !Regex("^[0-9a-f]{64}$").matches(info.sha256)) return null
        // 같은 날 두 번 내면 versionCode 가 같다(빌드 날짜로 센다) → 이름이 다르고 번호가 작지 않으면 새 판
        val newer = info.versionCode > BuildConfig.VERSION_CODE ||
            (info.versionCode == BuildConfig.VERSION_CODE && info.versionName != BuildConfig.VERSION_NAME)
        return info.takeIf { newer }
    }

    /** 받기 + 지문 대조 → 설치 화면 Intent(실패면 null) · 작업 스레드에서 부른다 */
    fun download(context: Context, info: Info): Intent? {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "app.apk")
        return try {
            var url = URL("$BASE/app.apk")
            var conn: HttpURLConnection
            var hops = 0
            // 우리 주소 → 깃허브 → 깃허브 파일 서버로 넘어간다. https 끼리만 따라간다
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    val next = URL(url, conn.getHeaderField("Location") ?: return null)
                    conn.disconnect()
                    if (next.protocol != "https") return null
                    url = next; hops++
                    continue
                }
                if (code != 200) return null
                break
            }
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != info.sha256) {
                Log.w(TAG, "지문이 다르다 — 설치 안 함")
                file.delete()
                return null
            }
            @Suppress("DEPRECATION")
            val pkg = context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
            if (pkg != context.packageName) {
                Log.w(TAG, "다른 앱 파일 — 설치 안 함")
                file.delete()
                return null
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "받기 실패", e)
            file.delete()
            null
        }
    }

    private fun fetchText(u: String): String? = try {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        val text = if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        conn.disconnect()
        text?.takeIf { it.length < 4096 }
    } catch (e: Throwable) {
        null
    }
}
