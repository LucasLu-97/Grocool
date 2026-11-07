package com.music.bitchord.data.kugou

import com.grocool.kugou.api.QrLoginState
import com.grocool.kugou.api.checkQrLogin
import com.grocool.kugou.api.createQrLoginKey
import com.grocool.kugou.api.registerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The QR login flow for the KuGou Concept account, as one observable state.
 *
 * Drives the chain verified live in the Grocool smoke: device registration
 * (best effort — a restored session already has its dfid, and a failure here
 * must not block the QR), then a QR key per round with a ~150s poll window
 * each, auto-renewing the code up to 30 rounds when KuGou expires it (~2min
 * apiece), which is what made the smoke scan succeed without a race against
 * the clock. Scanning with the Concept app confirms and hands the session
 * its token + userid; the flow persists them through [KugouRuntime] — the
 * store that survives a data reset — before announcing success.
 */
sealed interface KugouLoginState {
    /** Registering the device and fetching the first QR. */
    data object Preparing : KugouLoginState

    /**
     * @param qrContent the URL the KuGou app's scanner expects, rendered by
     *   the app's own [com.music.bitchord.ui.components.QrCode].
     * @param round which renewal this code is, 1..30, shown so the user can
     *   see it is alive.
     * @param scanned the app scanned but nobody pressed confirm yet.
     */
    data class ShowingQr(
        val qrContent: String,
        val round: Int,
        val scanned: Boolean = false,
    ) : KugouLoginState

    data class Success(val userid: Long) : KugouLoginState
    data class Failed(val message: String) : KugouLoginState
}

class KugouLoginManager {

    private val _state = MutableStateFlow<KugouLoginState>(KugouLoginState.Preparing)
    val state: StateFlow<KugouLoginState> = _state

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        job?.cancel()
        _state.value = KugouLoginState.Preparing
        job = scope.launch {
            val client = KugouRuntime.clientOrNull() ?: run {
                _state.value = KugouLoginState.Failed("运行时尚未就绪，请稍后再试")
                return@launch
            }
            runCatching { client.registerDevice() }

            var round = 0
            while (round < 30 && isActive) {
                round++
                val keyResp = runCatching { client.createQrLoginKey() }.getOrNull()
                val key = (keyResp?.body as? JsonObject)
                    ?.get("data")?.jsonObject
                    ?.get("qrcode")?.jsonPrimitive?.content
                if (key.isNullOrBlank()) {
                    _state.value = KugouLoginState.Failed("二维码获取失败，请稍后重试")
                    return@launch
                }
                val qrContent =
                    "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"
                _state.value = KugouLoginState.ShowingQr(qrContent, round)

                var waited = 0
                var expired = false
                while (waited < 150 && !expired && isActive) {
                    delay(3000)
                    waited += 3
                    when (val st = runCatching { client.checkQrLogin(key) }.getOrNull()) {
                        is QrLoginState.AwaitingScan -> { /* still showing */ }
                        is QrLoginState.AwaitingConfirm ->
                            _state.value = KugouLoginState.ShowingQr(qrContent, round, scanned = true)
                        is QrLoginState.Confirmed -> {
                            KugouRuntime.persist()
                            _state.value = KugouLoginState.Success(client.session.userid ?: 0L)
                            return@launch
                        }
                        is QrLoginState.Expired -> expired = true
                        is QrLoginState.Failed -> {
                            _state.value = KugouLoginState.Failed(st.message ?: "登录失败")
                            return@launch
                        }
                        null -> { /* transient error, keep polling */ }
                    }
                }
                // expired → next round mints a fresh code; state stays on the
                // old ShowingQr until the new one arrives a moment later.
            }
            _state.value = KugouLoginState.Failed("长时间未完成扫码，已停止")
        }
    }

    fun stop() {
        job?.cancel()
    }
}
