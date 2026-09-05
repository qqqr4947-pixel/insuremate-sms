package me.capcom.smsgateway.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import me.capcom.smsgateway.R

/**
 * 설정 목록을 인슈어메이트 «카드» 위에 올린다.
 *
 * 안드로이드 설정 부품(PreferenceFragmentCompat)은 항목을 맨몸 목록으로 그린다.
 * 항목 하나하나를 바꾸는 대신, 묶음 제목(PreferenceCategory) 사이의 «이어진 항목들» 뒤에
 * 흰 둥근 사각형(카드 · 라운딩 im_radius)을 한 장씩 깔아 준다 — 항목 부품은 원본 그대로다.
 *
 * 카드가 화면 위·아래로 이어져 있으면 그쪽 모서리는 화면 밖으로 밀어 «중간에 둥근 끝»이 안 보이게 한다.
 *
 * 몇 번째 줄이 묶음 제목인지는 설정 트리를 부품과 같은 규칙으로 펼쳐서 센다
 * (부품의 어댑터는 라이브러리 밖에서 못 쓰게 막혀 있다).
 */
class ImCardDecoration(
    context: Context,
    private val screen: () -> PreferenceGroup?
) : RecyclerView.ItemDecoration() {

    private val radius = context.resources.getDimension(R.dimen.im_radius)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.im_surface)
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    /** 부품(PreferenceGroupAdapter)과 같은 순서 — 보이는 항목만, 묶음은 같은 화면이면 펼친다 */
    private fun flatten(group: PreferenceGroup, into: MutableList<Preference>) {
        for (i in 0 until group.preferenceCount) {
            val p = group.getPreference(i)
            if (p.isVisible) into.add(p)
            if (p is PreferenceGroup && p.isOnSameScreenAsChildren) flatten(p, into)
        }
    }

    /** 목록 밖(위·아래 끝)도 «경계»로 본다 → 첫 카드·마지막 카드가 닫힌다 */
    private fun isBoundary(items: List<Preference>, position: Int): Boolean =
        position < 0 || position >= items.size || items[position] is PreferenceCategory

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val root = screen() ?: return
        val items = ArrayList<Preference>().also { flatten(root, it) }
        if (items.size != (parent.adapter?.itemCount ?: -1)) return // 펼친 수가 부품과 다르면 그리지 않는다(잘못 그리느니 안 그린다)

        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()

        var i = 0
        val count = parent.childCount
        while (i < count) {
            val first = parent.getChildAt(i)
            val firstPos = parent.getChildAdapterPosition(first)
            if (firstPos == RecyclerView.NO_POSITION || isBoundary(items, firstPos)) {
                i++
                continue
            }

            // 같은 카드에 속한 마지막 자식까지 이어 본다
            var j = i
            var last = first
            var lastPos = firstPos
            while (j + 1 < count) {
                val next = parent.getChildAt(j + 1)
                val nextPos = parent.getChildAdapterPosition(next)
                if (nextPos != lastPos + 1 || isBoundary(items, nextPos)) break
                j++
                last = next
                lastPos = nextPos
            }

            var top = first.top.toFloat()
            var bottom = last.bottom.toFloat()
            if (!isBoundary(items, firstPos - 1)) top -= radius * 2   // 위로 이어짐
            if (!isBoundary(items, lastPos + 1)) bottom += radius * 2 // 아래로 이어짐

            rect.set(left, top, right, bottom)
            c.drawRoundRect(rect, radius, radius, paint)
            i = j + 1
        }
    }
}
