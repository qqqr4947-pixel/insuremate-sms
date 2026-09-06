package me.capcom.smsgateway.ui.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import me.capcom.smsgateway.R

abstract class BasePreferenceFragment : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backgroundValue = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.colorBackground,
            backgroundValue,
            true
        )

        view.setBackgroundColor(backgroundValue.data)

        // 카드 사이에 안드로이드 기본 구분선이 겹쳐 그려지지 않게 (카드가 구분이다)
        setDivider(null)
        setDividerHeight(0)
    }

    /** 설정 목록을 지면 여백(im_page) 안에 놓고, 묶음마다 흰 카드를 깔아 준다 — 인슈어메이트 «설정도 동일» */
    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val list = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        val page = resources.getDimensionPixelSize(R.dimen.im_page)
        // 아래는 94(떠 있는 탭바 14+66+14) — 마지막 카드가 탭바에 안 가리게
        val bottom = resources.getDimensionPixelSize(R.dimen.im_content_bottom)
        list.setPadding(page, page, page, bottom)
        list.clipToPadding = false
        list.addItemDecoration(ImCardDecoration(requireContext()) { preferenceScreen })
        return list
    }

    protected fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    protected fun showToast(messageRes: Int) {
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }
}