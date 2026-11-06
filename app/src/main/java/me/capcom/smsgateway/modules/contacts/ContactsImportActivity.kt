package me.capcom.smsgateway.modules.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import me.capcom.smsgateway.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 고객 연락처 한 번에 저장 · 갤럭시 길 (박 대표 2026-11-06 「다운로드 되고 갤럭시 화면에 안뜨던데」 → 「추천대로」)
 *
 * 갤럭시 크롬은 연락처 파일을 «내려받기 목록»에만 넣고 연락처 화면을 안 연다.
 * 그래서 우리 화면 [연락처에 저장] → 이 앱(insuremate-sms://contacts?t=표&c=숫자) → 앱이 같은 주소에서 파일을 받아
 * → 폰 연락처 앱의 «가져오기» 화면을 바로 연다(삼성이 같은 사람은 건너뛴다).
 * 앱에는 고객 목록을 남기지 않는다 — 받은 파일은 다음 번에 지우고, 연락처에 넣는 건 폰 연락처 앱이 한다.
 */
class ContactsImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }   // 화면이 돌아가 다시 만들어진 것 — 이미 쓴 표로 또 받지 않는다(리뷰 11-06)
        val data: Uri? = intent?.data
        val token = data?.getQueryParameter("t").orEmpty()
        val code = data?.getQueryParameter("c").orEmpty()
        if (!TOKEN.matches(token) || !CODE.matches(code)) {
            say("링크가 맞지 않아요. 인슈어메이트에서 [폰에 저장]을 다시 눌러 주세요")
            finish(); return
        }
        val dir = File(cacheDir, "contacts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // 지난번 받은 파일은 여기서 치운다
        Toast.makeText(this, "고객 연락처를 받는 중이에요", Toast.LENGTH_SHORT).show()
        Thread {
            val result = fetch(token, code, File(dir, "insuremate-contacts.vcf"))
            runOnUiThread {
                when (result) {
                    is Got -> open(result.file)
                    is Fail -> say(result.message)
                }
                finish()
            }
        }.start()
    }

    private sealed class Result
    private class Got(val file: File) : Result()
    private class Fail(val message: String) : Result()

    /** 우리 화면과 같은 주소에 숫자를 보내고(POST) 연락처 파일을 받는다 */
    private fun fetch(token: String, code: String, out: File): Result = try {
        val conn = URL(BuildConfig.IM_SITE_BASE + "/c/" + token).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("code=" + URLEncoder.encode(code, "UTF-8")).toByteArray()) }
        val status = conn.responseCode
        val type = conn.contentType.orEmpty()
        val r = when {
            status == 200 && type.contains("vcard") -> {
                conn.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
                Got(out)
            }
            // 숫자가 틀리면 서버는 우리 화면(숫자 칸)을 다시 준다
            status == 200 -> Fail("숫자가 달라요. 문자 링크를 다시 열고 PC 화면의 숫자를 넣어 주세요")
            status == 410 || status == 404 -> Fail("링크가 닫혔어요. 3분이 지났거나 이미 저장했어요. 인슈어메이트에서 [폰에 저장]을 다시 눌러 주세요")
            else -> Fail("연락처를 못 받았어요. 문자 링크를 한 번 더 눌러 주세요")
        }
        conn.disconnect()
        r
    } catch (e: Throwable) {
        Log.w(TAG, "받기 실패", e)
        out.delete()
        Fail("인터넷이 끊겼어요. 와이파이나 데이터를 켜고 문자 링크를 다시 눌러 주세요")
    }

    /** 폰 연락처 앱의 «가져오기» 화면 */
    private fun open(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/x-vcard")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            say("이 폰에서 연락처 앱을 못 찾았어요. 연락처 앱을 켠 뒤 인슈어메이트에서 [폰에 저장]을 다시 눌러 주세요")
        }
    }

    private fun say(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "ContactsImport"
        private const val MAX_BYTES = 2L * 1024 * 1024   // 고객 2,000명이 넉넉히 들어가는 크기
        private val TOKEN = Regex("^[A-Za-z0-9_-]{20,64}$")   // 서버 /c/<표> 와 같은 모양
        private val CODE = Regex("^[0-9]{4}$")
    }
}
