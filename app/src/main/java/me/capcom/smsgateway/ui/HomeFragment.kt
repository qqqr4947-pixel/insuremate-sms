package me.capcom.smsgateway.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.databinding.FragmentHomeBinding
import me.capcom.smsgateway.databinding.ItemImRecentBinding
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.connection.ConnectionService
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.localserver.LocalServerService
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.localserver.events.IPReceivedEvent
import me.capcom.smsgateway.modules.messages.MessagesRepository
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.ui.dialogs.FirstStartDialogFragment
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 홈 — 인슈어메이트 배치 (시안 ⑦).
 *
 * 원본(capcom6)의 조작은 **하나도 없애지 않았다** — 「내 서버 쓰기 / 폰 안 서버 쓰기 / 폰 켤 때 시작」과
 * 서버 자격 값은 「고급」 카드 안에 그대로 있다. 바뀐 것은 «먼저 보이는 것»의 순서다:
 * 연결 여부 → 내 번호 → 오늘 보냄 → 최근 보낸 문자.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val settingsHelper: SettingsHelper by inject()
    private val localServerSettings: LocalServerSettings by inject()
    private val gatewaySettings: GatewaySettings by inject()
    private val messagesSettings: MessagesSettings by inject()
    private val messagesRepo: MessagesRepository by inject()
    private val connectionService: ConnectionService by inject()

    private val events: EventBus by inject()

    private val localServerSvc: LocalServerService by inject()
    private val gatewaySvc: GatewayService by inject()

    private val orchestratorSvc: OrchestratorService by inject()

    /** 코드로 스위치를 바꿀 때 리스너가 되받아 도는 것을 막는다 */
    private var muteSwitches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFragmentResultListener(FirstStartDialogFragment.REQUEST_KEY) { _, data ->
            when (FirstStartDialogFragment.getResult(data)) {
                FirstStartDialogFragment.Result.Canceled -> return@setFragmentResultListener

                FirstStartDialogFragment.Result.SignUp -> requestPermissionsAndStart()

                FirstStartDialogFragment.Result.SignIn -> {
                    val username = FirstStartDialogFragment.getUsername(data)
                    val password = FirstStartDialogFragment.getPassword(data)
                    lifecycleScope.launch {
                        try {
                            gatewaySvc.registerDevice(
                                requireContext(),
                                null,
                                GatewayService.RegistrationMode.WithCredentials(username, password)
                            )
                            requestPermissionsAndStart()
                        } catch (th: Throwable) {
                            toastRegisterFailed(th)
                        }
                    }
                }

                FirstStartDialogFragment.Result.SignInByCode -> {
                    val code = FirstStartDialogFragment.getCode(data)
                    lifecycleScope.launch {
                        try {
                            gatewaySvc.registerDevice(
                                requireContext(),
                                null,
                                GatewayService.RegistrationMode.WithCode(code)
                            )
                            requestPermissionsAndStart()
                        } catch (th: Throwable) {
                            toastRegisterFailed(th)
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textVersion.text = getString(R.string.im_version, BuildConfig.VERSION_NAME)
        binding.textConnectServer.text = shortServerName(gatewaySettings.serverUrl)

        binding.buttonSettings.setOnClickListener {
            requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.currentItem =
                MainActivity.TAB_INDEX_SETTINGS
        }

        binding.buttonPermission.setOnClickListener { requestPermissionsAndStart() }

        binding.buttonConnect.setOnClickListener { actionConnect() }

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (muteSwitches) return@setOnCheckedChangeListener
            actionStart(isChecked)
        }

        binding.switchAutostart.setOnCheckedChangeListener { _, isChecked ->
            if (muteSwitches) return@setOnCheckedChangeListener
            settingsHelper.autostart = isChecked
        }
        binding.switchUseRemoteServer.setOnCheckedChangeListener { _, isChecked ->
            if (muteSwitches) return@setOnCheckedChangeListener
            if (isChecked != gatewaySettings.enabled) {
                restartRequiredNotification()
            }

            gatewaySettings.enabled = isChecked
            binding.layoutRemoteServer.isVisible = isChecked
            renderCards()
        }
        binding.switchUseLocalServer.setOnCheckedChangeListener { _, isChecked ->
            if (muteSwitches) return@setOnCheckedChangeListener
            if (isChecked != localServerSettings.enabled) {
                restartRequiredNotification()
            }

            localServerSettings.enabled = isChecked
            renderCards()
        }

        // ── 내 서버 등록 결과 ──
        viewLifecycleOwner.lifecycleScope.launch {
            events.collect<DeviceRegisteredEvent.Success> { event ->
                binding.textRemoteAddress.text = getString(R.string.address_is, event.server)
                binding.textRemoteUsername.text = event.login
                binding.textRemotePassword.text =
                    event.password ?: getString(R.string.password_hidden)
                binding.textRemoteDeviceId.text =
                    gatewaySettings.deviceId ?: getString(R.string.n_a)
                renderCards()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            events.collect<DeviceRegisteredEvent.Failure> { event ->
                binding.textRemoteAddress.text = getString(R.string.address_is, event.server)
                binding.textRemoteUsername.text = getString(R.string.not_registered)
                binding.textRemotePassword.text = getString(R.string.n_a)
                binding.textRemoteDeviceId.text =
                    gatewaySettings.deviceId ?: getString(R.string.n_a)

                Toast.makeText(
                    requireContext(),
                    getString(R.string.failed_to_register_device, event.reason),
                    Toast.LENGTH_LONG
                ).show()
                renderCards()
            }
        }

        // ── 폰 안 서버 주소 ──
        viewLifecycleOwner.lifecycleScope.launch {
            events.collect<IPReceivedEvent> { event ->
                binding.textLocalUsername.text = localServerSettings.username
                binding.textLocalPassword.text = localServerSettings.password
                binding.textLocalIP.text = event.localIP
                    ?.let { "$it:${localServerSettings.port}" }
                    ?: getString(R.string.not_available)
                binding.textPublicIP.text = event.publicIP
                    ?.let { "$it:${localServerSettings.port}" }
                    ?: getString(R.string.not_available)
                binding.textLocalDeviceId.text =
                    localServerSettings.deviceId ?: getString(R.string.n_a)
            }
        }

        stateLiveData.observe(viewLifecycleOwner) { running ->
            muteSwitches = true
            binding.switchService.isChecked = running
            muteSwitches = false
            binding.switchService.text = getString(
                if (running) R.string.im_sending_on else R.string.im_sending_off
            )
            renderStatusChip(running)
        }

        connectionService.status.observe(viewLifecycleOwner) { online ->
            binding.textConnectionStatus.isVisible = !online
            binding.textConnectionStatus.text =
                getString(R.string.internet_connection_unavailable)
        }

        // ── 최근 보낸 문자 3줄 + 오늘 숫자 (같은 신호로 갱신) ──
        messagesRepo.selectLastWithRecipients(RECENT_LIMIT).observe(viewLifecycleOwner) { list ->
            renderRecent(list.orEmpty())
            refreshCounters()
        }
    }

    override fun onResume() {
        super.onResume()

        muteSwitches = true
        binding.switchUseRemoteServer.isChecked = gatewaySettings.enabled
        binding.switchUseLocalServer.isChecked = localServerSettings.enabled
        binding.switchAutostart.isChecked = settingsHelper.autostart
        muteSwitches = false

        binding.layoutRemoteServer.isVisible = gatewaySettings.enabled
        binding.textConnectServer.text = shortServerName(gatewaySettings.serverUrl)
        binding.textRemoteAddress.text = shortServerName(gatewaySettings.serverUrl)

        renderMyNumber()
        renderCards()
        refreshCounters()
    }

    // ══════════ 그리기 ══════════

    /** 어떤 카드를 보일지 — 연결 전이면 연결 카드, 연결되면 상태 카드 */
    private fun renderCards() {
        val binding = _binding ?: return
        val registered = gatewaySettings.registrationInfo != null

        binding.cardConnect.isVisible = !registered
        binding.cardStatus.isVisible = registered
        binding.cardPermission.isVisible = !hasSendPermission()
        binding.cardLocalServer.isVisible = localServerSettings.enabled
        binding.cardRecent.isVisible = registered || localServerSettings.enabled

        renderStatusChip(stateLiveData.value ?: false)
    }

    private fun renderStatusChip(running: Boolean) {
        val binding = _binding ?: return
        val registered = gatewaySettings.registrationInfo != null

        val (label, bg, fg) = when {
            !registered -> Triple(
                R.string.im_state_disconnected, R.color.im_hold_bg, R.color.im_hold_fg
            )

            running -> Triple(R.string.im_state_connected, R.color.im_ok_bg, R.color.im_ok_fg)
            else -> Triple(R.string.im_state_off, R.color.im_warn_bg, R.color.im_warn_fg)
        }

        binding.chipStatus.text = getString(label)
        binding.chipStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bg))
        binding.chipStatus.setTextColor(ContextCompat.getColor(requireContext(), fg))
    }

    private fun renderMyNumber() {
        val binding = _binding ?: return
        val number = runCatching {
            SubscriptionsHelper.getActiveSimCards(requireContext())
                .firstOrNull { !it.phoneNumber.isNullOrBlank() }
                ?.phoneNumber
        }.getOrNull()

        binding.textMyNumber.text = number ?: getString(R.string.im_number_unknown)
    }

    /** 오늘 보낸 수 · 실패 수 — DB 조회라 IO 에서 센다 */
    private fun refreshCounters() {
        val limit = if (messagesSettings.limitEnabled) messagesSettings.limitValue else 0

        viewLifecycleOwner.lifecycleScope.launch {
            val since = startOfToday()
            val stats = withContext(Dispatchers.IO) {
                runCatching {
                    messagesRepo.countProcessedFrom(since) to messagesRepo.countFailedFrom(since)
                }.getOrNull()
            } ?: return@launch

            val binding = _binding ?: return@launch
            val sent = stats.first.count
            val failed = stats.second.count

            binding.textTodayCount.text = when {
                limit > 0 -> getString(R.string.im_today_of, sent, limit)
                else -> sent.toString()
            }
            binding.gaugeToday.isVisible = limit > 0
            binding.gaugeToday.progress = when {
                limit > 0 -> (sent * 100 / limit).coerceIn(0, 100)
                else -> 0
            }
            binding.textFailedCount.text = failed.toString()

            val last = stats.first.lastTimestamp
            val server = shortServerName(gatewaySettings.serverUrl)
            binding.textLastSent.text = when {
                last > 0 -> getString(R.string.im_last_sent, timeText(last)) + " · " + server
                else -> server
            }
        }
    }

    private fun renderRecent(list: List<MessageWithRecipients>) {
        val binding = _binding ?: return
        val container = binding.containerRecent
        container.removeAllViews()

        val rows = list.take(RECENT_LIMIT)
        binding.textRecentEmpty.isVisible = rows.isEmpty()

        val inflater = LayoutInflater.from(requireContext())
        rows.forEach { item ->
            val row = ItemImRecentBinding.inflate(inflater, container, false)
            row.textRecentPhone.text = item.recipients.firstOrNull()?.phoneNumber
                ?: getString(R.string.n_a)
            row.textRecentTime.text = timeText(item.message.processedAt ?: item.message.createdAt)

            val state = item.state
            row.chipRecentState.text = getString(stateLabel(state))
            val colors = stateColors(state)
            row.chipRecentState.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colors.first))
            row.chipRecentState.setTextColor(
                ContextCompat.getColor(requireContext(), colors.second)
            )

            container.addView(row.root)
        }
    }

    private fun stateLabel(state: ProcessingState): Int = when (state) {
        ProcessingState.Delivered -> R.string.im_state_delivered
        ProcessingState.Sent, ProcessingState.Processed -> R.string.im_state_sent
        ProcessingState.Failed -> R.string.im_state_failed
        ProcessingState.Cancelled, ProcessingState.Cancelling -> R.string.im_state_cancelled
        ProcessingState.Pending -> R.string.im_state_pending
    }

    private fun stateColors(state: ProcessingState): Pair<Int, Int> = when (state) {
        ProcessingState.Delivered -> R.color.im_ok_bg to R.color.im_ok_fg
        ProcessingState.Sent, ProcessingState.Processed -> R.color.im_blue_tint to R.color.im_on_tint
        ProcessingState.Failed -> R.color.im_danger_bg to R.color.im_danger_fg
        else -> R.color.im_hold_bg to R.color.im_hold_fg
    }

    // ══════════ 동작 ══════════

    /** 「연결하기」 — 연결 코드를 저장하고 내 서버를 켠 뒤 등록을 시작한다 */
    private fun actionConnect() {
        val code = binding.editConnectCode.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            binding.layoutConnectCode.error = getString(R.string.im_connect_code_required)
            return
        }
        binding.layoutConnectCode.error = null

        gatewaySettings.privateToken = code
        gatewaySettings.enabled = true

        muteSwitches = true
        binding.switchUseRemoteServer.isChecked = true
        muteSwitches = false
        binding.layoutRemoteServer.isVisible = true

        requestPermissionsAndStart()
    }

    private fun actionStart(start: Boolean) {
        if (start) {
            if (gatewaySettings.enabled && gatewaySettings.registrationInfo == null) {
                cloudFirstStart()
                return
            }

            requestPermissionsAndStart()
        } else {
            stop()
        }
    }

    private fun cloudFirstStart() {
        FirstStartDialogFragment.newInstance()
            .show(parentFragmentManager, "signin")
    }

    private fun stop() {
        orchestratorSvc.stop(requireContext())
    }

    private fun start() {
        orchestratorSvc.start(requireContext().applicationContext, false)
    }

    private fun hasSendPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndStart() {
        val permissionsRequired =
            listOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_MMS,
                Manifest.permission.READ_PHONE_NUMBERS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            )
                .filterNotNull()
                .filter {
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        it
                    ) != PackageManager.PERMISSION_GRANTED
                }

        if (permissionsRequired.isEmpty()) {
            start()
            renderCards()
            renderMyNumber()
            return
        }

        permissionsRequest.launch(permissionsRequired.toTypedArray())
    }

    private fun restartRequiredNotification() {
        if (this.stateLiveData.value != true) {
            return
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.to_apply_the_changes_restart_the_app_using_the_button_below),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toastRegisterFailed(th: Throwable) {
        Toast.makeText(
            requireContext(),
            getString(R.string.failed_to_register_device, th.message ?: ""),
            Toast.LENGTH_LONG
        ).show()
    }

    private val permissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            Log.d(javaClass.name, "Permissions granted")
        }

        start()
        if (_binding != null) {
            renderCards()
            renderMyNumber()
        }
    }

    private val stateLiveData by lazy {
        object : MediatorLiveData<Boolean>() {
            private var gatewayStatus = false
            private var localServerStatus = false

            init {
                addSource(gatewaySvc.isActiveLiveData(requireContext())) {
                    gatewayStatus = it

                    value = gatewayStatus || localServerStatus
                }
                addSource(localServerSvc.isActiveLiveData(requireContext())) {
                    localServerStatus = it

                    value = gatewayStatus || localServerStatus
                }
            }
        }
    }

    // ══════════ 잔손 ══════════

    /** https://sms.insuremate.co.kr/api/mobile/v1 → sms.insuremate.co.kr */
    private fun shortServerName(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun timeText(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val RECENT_LIMIT = 3

        @JvmStatic
        fun newInstance() = HomeFragment()
    }
}
