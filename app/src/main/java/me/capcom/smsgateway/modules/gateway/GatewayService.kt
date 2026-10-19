package me.capcom.smsgateway.modules.gateway

import android.content.Context
import android.os.Build
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.gateway.workers.SendStateWorker
import me.capcom.smsgateway.modules.gateway.workers.SettingsUpdateWorker
import me.capcom.smsgateway.modules.gateway.workers.WebhooksUpdateWorker
import me.capcom.smsgateway.modules.localserver.domain.SimCard
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.services.PushService
import java.util.Date

class GatewayService(
    private val messagesService: MessagesService,
    private val settings: GatewaySettings,
    private val events: EventBus,
    private val logsService: LogsService,
) {
    private val eventsReceiver by lazy { EventsReceiver() }

    private var _api: GatewayApi? = null

    private var _apiToken: String? = null

    // im13 · 연결 코드(privateToken)가 바뀌면 통로를 새로 만든다 — 전엔 처음 만든 통로가 옛 코드를 계속 실어
    // 새 코드로 연결해도 대리가 «그런 코드 없음»(no_such_code)으로 거절했다(10-19 공기계 · 코드는 창고에 멀쩡히 있었다)
    private val api
        get() = _api?.takeIf { _apiToken == settings.privateToken } ?: GatewayApi(
            settings.serverUrl,
            settings.privateToken
        ).also { _api = it; _apiToken = settings.privateToken }

    //region Start, stop, etc...
    fun start(context: Context) {
        if (!settings.enabled) return

        PushService.register(context)
        PullMessagesWorker.start(context)
        WebhooksUpdateWorker.start(context)
        SettingsUpdateWorker.start(context)

        eventsReceiver.start()
    }

    fun stop(context: Context) {
        eventsReceiver.stop()

        SSEForegroundService.stop(context)
        SettingsUpdateWorker.stop(context)
        WebhooksUpdateWorker.stop(context)
        PullMessagesWorker.stop(context)

        this._api = null
    }

    fun isActiveLiveData(context: Context) = PullMessagesWorker.getStateLiveData(context)
    //endregion

    //region Account
    suspend fun getLoginCode(): GatewayApi.GetUserCodeResponse {
        val password = settings.password
            ?: throw IllegalStateException("Password is not set")

        return getLoginCodeWithPassword(password)
    }

    suspend fun getLoginCodeWithPassword(password: String): GatewayApi.GetUserCodeResponse {
        val username = settings.username
            ?: throw IllegalStateException("Username is not set")

        return api.getUserCode(username to password)
    }

    suspend fun changePassword(current: String, new: String) {
        val info = settings.registrationInfo
            ?: throw IllegalStateException("The device is not registered on the server")

        this.api.changeUserPassword(
            info.token,
            GatewayApi.PasswordChangeRequest(current, new)
        )

        settings.registrationInfo = info.copy(password = new)

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                info.login,
                new,
            )
        )
    }
    //endregion

    //region Device
    internal suspend fun registerDevice(
        context: Context,
        pushToken: String?,
        registerMode: RegistrationMode
    ) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo
        val accessToken = settings?.token

        if (accessToken != null) {
            // if there's an access token, try to update push token
            try {
                updateDevice(context, pushToken)
                return
            } catch (e: ClientRequestException) {
                // if token is invalid, try to register new one
                if (e.response.status != HttpStatusCode.Unauthorized) {
                    throw e
                }
                // im12 · 서버가 이 폰을 모른다(PC 「연결 끊기」 등) — 옛 연결 정보를 지운다.
                // 안 지우면 홈이 «연결됨»으로 믿고 연결 칸을 영영 안 보였다(10-12 실측 · 다시 연결 불가).
                // 아래 새 등록이 성공하면 새 정보로 채워지고, 실패하면 홈이 ③ 연결 단계로 돌아간다.
                this.settings.registrationInfo = null
            }
        }

        try {
            val deviceName = "${Build.MANUFACTURER}/${Build.PRODUCT}"
            val simCards = SubscriptionsHelper.getActiveSimCards(context)
            val request = GatewayApi.DeviceRegisterRequest(
                deviceName,
                pushToken,
                simCards.toDTO(),
            )
            val response = when (registerMode) {
                RegistrationMode.Anonymous -> api.deviceRegister(request, null)
                is RegistrationMode.WithCode -> api.deviceRegister(request, registerMode.code)
                is RegistrationMode.WithCredentials -> api.deviceRegister(
                    request,
                    registerMode.login to registerMode.password
                )
            }

            this.settings.fcmToken = pushToken
            this.settings.registrationInfo = response

            events.emit(
                DeviceRegisteredEvent.Success(
                    api.hostname,
                    response.login,
                    response.password,
                )
            )
        } catch (th: Throwable) {
            // im13 · 연결 코드가 틀렸다·만료·이미 씀(401)이면 같은 코드로 다시 해도 영영 안 된다.
            // 전엔 저절로 다시 하기가 죽은 코드로 계속 두드려 5번 제한을 다 쓰고 새 코드까지 막았다(10-19 공기계 실측).
            // → 연결을 끄고 코드를 지운다. 다시 하기는 enabled=false 라 바로 끝나고, 홈은 연결 단계로 돌아간다.
            // 시도 너무 많음(429)은 같은 와이파이의 다른 폰 탓일 수도 있어 손대지 않는다 — 간격을 늘려 다시 하다 코드가 만료되면 401 로 여기서 멈춘다.
            val status = (th as? ClientRequestException)?.response?.status
            if (registerMode == RegistrationMode.Anonymous && status == HttpStatusCode.Unauthorized) {
                this.settings.privateToken = null
                this.settings.enabled = false
            }
            // 서버 안내 글({"message":"…"})만 보여 준다 — 전엔 요청 주소·영어가 섞인 통째 글이 떴다
            val raw = th.localizedMessage ?: th.message ?: th.toString()
            val reason = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: raw
            events.emit(
                DeviceRegisteredEvent.Failure(
                    api.hostname,
                    reason
                )
            )

            throw th
        }
    }

    internal suspend fun updateDevice(context: Context, pushToken: String?) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo ?: return
        val accessToken = settings.token
        val simCards = SubscriptionsHelper.getActiveSimCards(context)

        api.devicePatch(
            accessToken,
            GatewayApi.DevicePatchRequest(
                settings.id,
                pushToken,
                simCards.toDTO(),
            )
        )

        this.settings.fcmToken = pushToken

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                settings.login,
                settings.password,
            )
        )
    }

    private fun List<SimCard>.toDTO(): List<GatewayApi.SimCard> = map {
        GatewayApi.SimCard(
            it.slotIndex,
            it.simNumber,
            it.phoneNumber.redact(),
            it.carrierName.redact(),
            it.iccid.redact(),
        )
    }

    sealed class RegistrationMode {
        object Anonymous : RegistrationMode()
        class WithCredentials(val login: String, val password: String) : RegistrationMode()
        class WithCode(val code: String) : RegistrationMode()
    }
    //endregion

    //region Messages
    internal suspend fun getNewMessages(context: Context) {
        if (!settings.enabled) return
        val settings = settings.registrationInfo ?: return
        val processingOrder = when (messagesService.processingOrder) {
            MessagesSettings.ProcessingOrder.LIFO -> GatewayApi.ProcessingOrder.LIFO
            MessagesSettings.ProcessingOrder.FIFO -> GatewayApi.ProcessingOrder.FIFO
        }
        val messages = api.getMessages(settings.token, processingOrder)
        for (message in messages) {
            try {
                processMessage(context, message)
            } catch (th: Throwable) {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    MODULE_NAME,
                    "Failed to process message",
                    mapOf(
                        "message" to message,
                        "exception" to th.stackTraceToString(),
                    )
                )
                th.printStackTrace()
            }
        }
    }

    private suspend fun processMessage(context: Context, message: GatewayApi.Message) {
        when (message.state) {
            ProcessingState.Pending, null -> {}
            ProcessingState.Cancelling -> {
                try {
                    messagesService.cancelMessage(message.id)
                } catch (_: IllegalArgumentException) {
                    // message not found locally — nothing to cancel
                    sendState(
                        GatewayApi.MessagePatchRequest(
                            message.id,
                            ProcessingState.Cancelled,
                            message.phoneNumbers.map {
                                GatewayApi.RecipientState(
                                    it,
                                    ProcessingState.Cancelled,
                                    null
                                )
                            },
                            mapOf(ProcessingState.Cancelled to Date())
                        )
                    )
                    return
                } catch (_: IllegalStateException) {
                    // message not in Pending state — already sent/cancelled
                }
                return
            }

            ProcessingState.Cancelled,
            ProcessingState.Processed,
            ProcessingState.Sent,
            ProcessingState.Delivered,
            ProcessingState.Failed -> return
        }

        val messageState = messagesService.getMessage(message.id)
        if (messageState != null) {
            SendStateWorker.start(context, message.id)
            return
        }

        val request = SendRequest(
            EntitySource.Cloud,
            me.capcom.smsgateway.modules.messages.data.Message(
                message.id,
                when (val content = message.content) {
                    is GatewayApi.MessageContent.Text -> MessageContent.Text(content.text)
                    is GatewayApi.MessageContent.Data -> MessageContent.Data(
                        content.data,
                        content.port
                    )
                    is GatewayApi.MessageContent.Mms -> MessageContent.Mms(
                        subject = content.subject,
                        text = content.text,
                        attachments = content.attachments.map {
                            MessageContent.Mms.Attachment(
                                contentType = it.contentType,
                                name = it.name,
                                data = it.data,
                            )
                        }
                    )
                },
                message.phoneNumbers,
                message.isEncrypted ?: false,
                message.createdAt ?: Date(),
            ),
            SendParams(
                message.withDeliveryReport ?: true,
                skipPhoneValidation = true,
                simNumber = message.simNumber,
                validUntil = message.validUntil,
                scheduleAt = message.scheduleAt,
                priority = message.priority,
            )
        )
        messagesService.enqueueMessage(request)
    }

    internal suspend fun sendState(
        request: GatewayApi.MessagePatchRequest
    ) {
        val settings = settings.registrationInfo ?: return

        api.patchMessages(
            settings.token,
            listOf(request)
        )
    }
    //endregion

    //region Webhooks
    internal suspend fun getWebHooks(): List<GatewayApi.WebHook> {
        val settings = settings.registrationInfo
        return if (settings != null) {
            api.getWebHooks(settings.token)
        } else {
            emptyList()
        }
    }
    //endregion

    //region Settings
    internal suspend fun getSettings(): Map<String, *>? {
        val settings = settings.registrationInfo ?: return null

        return api.getSettings(settings.token)
    }
    //endregion

    //region Utility
    suspend fun getPublicIP(): String {
        return GatewayApi(
            settings.serverUrl,
            settings.privateToken
        )
            .getDevice(settings.registrationInfo?.token)
            .externalIp
    }
    //endregion

    private fun String?.redact(): String? = when {
        this == null -> null
        length > 4 -> replaceRange(
            0,
            length - 4,
            "*".repeat(length - 4)
        )

        else -> "****"
    }
}
