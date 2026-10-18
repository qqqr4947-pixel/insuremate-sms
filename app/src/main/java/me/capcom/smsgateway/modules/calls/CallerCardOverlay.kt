package me.capcom.smsgateway.modules.calls

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 통화 화면 위 고객 카드 한 장. 손으로 ✕ 를 누르거나 30초가 지나면 닫힌다(통화 상태는 보지 않는다 — 특허 선).
 * 30초 = 벨이 울리는 동안 + 받고 첫마디까지 · 통화가 끝났는지는 모르니 짧게 둔다(10-17 실측: 2분이면 끊은 뒤에도 남았다).
 * 잠긴 화면에서는 이름·내용을 가린다.
 */
object CallerCardOverlay {
    private const val TAG = "CallerCard"
    private const val AUTO_CLOSE_MS = 30_000L
    private var shown: View? = null

    fun show(context: Context, cards: JSONArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "다른 앱 위에 표시가 꺼져 있다")
            return
        }
        hide(context)
        val ui = ContextThemeWrapper(context, R.style.Theme_SmsGateway)
        val v = LayoutInflater.from(ui).inflate(R.layout.overlay_caller_card, null)
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        bind(ui, v, cards, locked)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(context, 48)
        }
        try {
            context.getSystemService(WindowManager::class.java).addView(v, lp)
            shown = v
            v.postDelayed({ if (shown === v) hide(context) }, AUTO_CLOSE_MS)
        } catch (e: Throwable) {
            Log.w(TAG, "카드를 못 띄웠다", e)
        }
    }

    fun hide(context: Context) {
        val v = shown ?: return
        shown = null
        try {
            context.getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Throwable) {
            Log.w(TAG, "카드 닫기 실패", e)
        }
    }

    private fun bind(ui: Context, v: View, cards: JSONArray, locked: Boolean) {
        val head = v.findViewById<TextView>(R.id.cardHead)
        val name = v.findViewById<TextView>(R.id.cardName)
        val rows = v.findViewById<LinearLayout>(R.id.cardRows)
        val open = v.findViewById<TextView>(R.id.cardOpen)
        v.findViewById<View>(R.id.cardClose).setOnClickListener { hide(ui.applicationContext) }

        if (locked) {
            head.setText(R.string.caller_card_head)
            name.setText(R.string.caller_card_locked_name)
            rows.addView(text(ui, ui.getString(R.string.caller_card_locked_sub), R.color.im_sub, 14f))
            open.visibility = View.GONE
            return
        }
        if (cards.length() > 1) {
            head.text = ui.getString(R.string.caller_card_head_many, cards.length())
            name.visibility = View.GONE
            for (i in 0 until cards.length()) {
                val c = cards.optJSONObject(i) ?: continue
                val line = listOf(c.optString("name"), c.optString("sub")).filter { it.isNotBlank() }.joinToString("  ·  ")
                rows.addView(text(ui, line, R.color.im_ink, 15f))
            }
            open.visibility = View.GONE
            return
        }
        val c = cards.optJSONObject(0) ?: JSONObject()
        head.setText(R.string.caller_card_head)
        name.text = c.optString("name")
        val chips = c.optJSONArray("chips")
        if (chips != null && chips.length() > 0) {
            v.findViewById<TextView>(R.id.cardChips).apply {
                text = (0 until chips.length()).joinToString("  ·  ") { chips.optString(it) }
                visibility = View.VISIBLE
            }
        }
        val alerts = c.optJSONArray("alerts") ?: JSONArray()
        val alertBox = v.findViewById<LinearLayout>(R.id.cardAlerts)
        for (i in 0 until alerts.length()) {
            val a = alerts.optJSONObject(i) ?: continue
            alertBox.addView(alert(ui, a.optString("text"), a.optString("tone")))
        }
        val list = c.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until list.length()) {
            val pair = list.optJSONArray(i) ?: continue
            rows.addView(row(ui, pair.optString(0), pair.optString(1)))
        }
        val url = c.optString("url")
        if (url.startsWith("https://")) {
            open.setOnClickListener {
                hide(ui.applicationContext)
                ui.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else open.visibility = View.GONE
    }

    private fun row(ui: Context, label: String, value: String): View =
        LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ui, 8), 0, 0)
            addView(text(ui, label, R.color.im_sub, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ui, 76), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(text(ui, value, R.color.im_ink, 14.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

    /** 해당될 때만 오는 알림 한 줄 — 빨강(조심) · 주황(날짜) · 파랑(참고) */
    private fun alert(ui: Context, s: String, tone: String): View {
        val (bg, fg) = when (tone) {
            "red" -> R.color.im_danger_bg to R.color.im_danger_fg
            "orange" -> R.color.im_warn_bg to R.color.im_warn_fg
            else -> R.color.im_blue_tint to R.color.im_on_tint
        }
        return text(ui, s, fg, 14f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(ui, bg))
                cornerRadius = dp(ui, 10).toFloat()
            }
            setPadding(dp(ui, 10), dp(ui, 8), dp(ui, 10), dp(ui, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ui, 6) }
        }
    }

    private fun text(ui: Context, s: String, color: Int, sp: Float) = TextView(ui).apply {
        text = s
        setTextColor(ContextCompat.getColor(ui, color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(ui, 4), 0, 0)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
