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

        // im11 · 설정 안쪽 화면 «← 화면 이름» — 안쪽 화면은 같은 칸을 갈아 끼우므로(뒤로 쌓기) 쌓인 수로 보인다/숨는다
        binding.backButton.setOnClickListener { supportFragmentManager.popBackStack() }
        supportFragmentManager.addOnBackStackChangedListener {
            while (backTitles.size > supportFragmentManager.backStackEntryCount) backTitles.removeAt(backTitles.size - 1)
            renderBackBar()
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderBackBar()
        })

        processIntent(intent)
    }

    private val backTitles = mutableListOf<CharSequence>()

    /** 설정 줄을 눌러 안쪽 화면을 열 때 그 줄 이름을 기억한다(BasePreferenceFragment 가 부른다) */
    fun pushBackTitle(title: CharSequence?) {
        // 메뉴 이름 끝의 «…»(더 있음 표시)는 머리줄에선 뗀다
        backTitles.add((title ?: "").toString().trimEnd('…', '.', ' '))
    }

    private fun renderBackBar() {
        val inside = binding.viewPager.currentItem == TAB_INDEX_SETTINGS &&
            supportFragmentManager.backStackEntryCount > 0
        binding.backBar.visibility = if (inside) android.view.View.VISIBLE else android.view.View.GONE
        binding.backTitle.text = backTitles.lastOrNull() ?: ""
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        // im12 · 인슈어메이트 «폰 연결» 화면(sms-connect.html)이 연결 코드를 실어 앱을 켠다 — 홈이 onResume 에서 받아 바로 연결
        intent.getStringExtra(EXTRA_CONNECT_CODE)?.takeIf { it.isNotBlank() }?.let { pendingConnectCode = it }
        intent.removeExtra(EXTRA_CONNECT_CODE)   // 화면을 돌리거나 다시 켤 때 같은 코드로 또 연결하지 않게

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
        private const val EXTRA_CONNECT_CODE = "im_code"

        /** 인터넷에서 넘겨받은 연결 코드 — 홈이 한 번 꺼내 쓰면 비운다 */
        private var pendingConnectCode: String? = null
        fun takePendingConnectCode(): String? = pendingConnectCode.also { pendingConnectCode = null }

        fun starter(context: Context, tabIndex: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TAB_INDEX, tabIndex)
            }
        }
    }
}