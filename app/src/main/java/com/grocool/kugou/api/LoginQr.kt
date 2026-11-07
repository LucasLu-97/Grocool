package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `module/login_qr_key.js` + `module/login_qr_check.js` — QR code login.
 *
 * createQrLoginKey() asks for a fresh QR key (web type uses appid 1014,
 * the app-type default here uses 1001). checkQrLogin(key) polls the state:
 * 0 = expired, 1 = awaiting scan, 2 = scanned awaiting confirm,
 * 4 = authorized (token + userid returned, stored into the session).
 */
sealed class QrLoginState {
    data object Expired : QrLoginState()
    data object AwaitingScan : QrLoginState()
    data object AwaitingConfirm : QrLoginState()
    data class Confirmed(val raw: JsonObject) : QrLoginState()
    data class Failed(val raw: JsonObject?, val message: String?) : QrLoginState()
}

suspend fun KugouClient.createQrLoginKey(webType: Boolean = false): KugouResponse {
    session.ensureDeviceIdentity()
    val queryAppid = if (webType) 1014 else 1001
    return request(
        RequestSpec(
            url = "/v2/qrcode",
            method = "GET",
            baseURL = "https://login-user.kugou.com",
            params = mapOf(
                "appid" to queryAppid,
                "type" to 1,
                "plat" to 4,
                "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KugouConfig.APPID}&",
                "srcappid" to KugouConfig.SRCAPPID,
            ),
            encryptType = EncryptType.WEB,
        ),
    )
}

suspend fun KugouClient.checkQrLogin(key: String): QrLoginState {
    session.ensureDeviceIdentity()
    val response = runCatching {
        request(
            RequestSpec(
                url = "/v2/get_userinfo_qrcode",
                method = "GET",
                baseURL = "https://login-user.kugou.com",
                params = mapOf(
                    "plat" to 4,
                    "appid" to KugouConfig.APPID,
                    "srcappid" to KugouConfig.SRCAPPID,
                    "qrcode" to key,
                    "dev" to (session.dev ?: ""),
                ),
                encryptType = EncryptType.WEB,
            ),
        )
    }.getOrElse { e ->
        if (e is com.grocool.kugou.KugouApiException) {
            return QrLoginState.Failed(e.response.body as? JsonObject, e.message)
        }
        throw e
    }

    val body = response.body as? JsonObject
        ?: return QrLoginState.Failed(null, "non-JSON login response")
    val data = body["data"] as? JsonObject
    val status = data?.get("status")?.jsonPrimitive?.content?.toIntOrNull()
    return when (status) {
        0 -> QrLoginState.Expired
        1 -> QrLoginState.AwaitingScan
        2 -> QrLoginState.AwaitingConfirm
        4 -> {
            val token = data?.get("token")?.jsonPrimitive?.content
            val userid = data?.get("userid")?.jsonPrimitive?.content?.toLongOrNull()
            if (token.isNullOrBlank() || userid == null) {
                QrLoginState.Failed(body, "authorized without token/userid")
            } else {
                session.updateFromCookies(listOf("token=$token", "userid=$userid"))
                QrLoginState.Confirmed(body)
            }
        }
        else -> QrLoginState.Failed(body, "unexpected qr status $status")
    }
}
