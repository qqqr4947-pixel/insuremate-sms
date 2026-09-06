package me.capcom.smsgateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import me.capcom.smsgateway.databinding.ActivityMainBinding
import me.capcom.smsgateway.ui.HolderFragment
import me.capcom.smsgateway.ui.HomeFragment
import me.capcom.smsgateway.ui.SettingsFragment
import me.capcom.smsgateway.helpers.LocaleHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = FragmentsAdapter(this)
        binding.viewPager.adapter = adapter

        // 아래 탭바 — 탭 순서는 어댑터 순서(홈 0 · 문자 1 · 설정 2)와 같다
        val tabs = listOf(binding.tabHome, binding.tabMessages, binding.tabSettings)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { binding.viewPager.currentItem = index }
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabs.forEachIndexed { index, tab -> tab.isSelected = index == position }
            }
        })
        tabs[binding.viewPager.currentItem].isSelected = true

        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        val tabIndex = intent.getIntExtra(EXTRA_TAB_INDEX, TAB_INDEX_HOME)

        binding.viewPager.currentItem = tabIndex
    }

    class FragmentsAdapter(activity: AppCompatActivity) :
        androidx.viewpager2.adapter.FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment.newInstance()
                1 -> HolderFragment.newInstance()
                else -> SettingsFragment.newInstance()
            }
        }

    }

    companion object {
        const val TAB_INDEX_HOME = 0
        const val TAB_INDEX_MESSAGES = 1
        const val TAB_INDEX_SETTINGS = 2

        private const val EXTRA_TAB_INDEX = "tabIndex"

        fun starter(context: Context, tabIndex: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TAB_INDEX, tabIndex)
            }
        }
    }
}