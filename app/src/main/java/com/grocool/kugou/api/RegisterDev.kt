package com.grocool.kugou.api

import com.grocool.kugou.BodyMode
import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouApiException
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouCrypto
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * `module/register_dev.js` — device registration, the source of `dfid`.
 *
 * Device info JSON -> playlistAesEncrypt (random 6-char session) -> POST body;
 * the session string rides along RSA-PKCS1-encrypted in the `p` query param.
 * The response arrives as encrypted bytes; decrypt with the same session and
 * pull `dfid` into the session.
 */
suspend fun KugouClient.registerDevice(): JsonObject {
    session.ensureDeviceIdentity()
    val guid = session.guid ?: ""
    val userid = session.userid ?: 0L
    val token = session.token ?: ""

    val aesSession = KugouCrypto.newSessionString()
    val deviceJson = buildDeviceJson(guid)
    val aesBody = KugouCrypto.playlistAesEncrypt(deviceJson, aesSession)
    val p = KugouCrypto.rsaEncryptPkcs1Hex(
        """{"aes":"$aesSession","uid":$userid,"token":"$token"}""".toByteArray(Charsets.UTF_8),
        config.rsaPublicKeyPem,
    )

    val response = request(
        RequestSpec(
            url = "/risk/v3/r_register_dev",
            method = "POST",
            baseURL = "https://userservice.kugou.com",
            params = mapOf("part" to 1, "platid" to 1, "p" to p),
            data = aesBody,
            encryptType = EncryptType.ANDROID,
            bodyMode = BodyMode.BYTES,
        ),
    )

    val plain = runCatching {
        KugouCrypto.playlistAesDecrypt(
            Base64.getEncoder().encodeToString(response.bodyBytes),
            aesSession,
        )
    }.getOrDefault("")
    val body = runCatching { Json.parseToJsonElement(plain).jsonObject }
        .getOrElse {
            throw KugouApiException(
                KugouResponse(false, 502, JsonPrimitive(plain), response.bodyBytes, response.cookies, response.headers),
            )
        }

    val status = body["status"]?.jsonPrimitive?.content
    if (status != "1") {
        throw KugouApiException(
            KugouResponse(false, 502, body, response.bodyBytes, response.cookies, response.headers),
        )
    }
    val dfid = body["data"]?.jsonObject?.get("dfid")?.jsonPrimitive?.content
    if (!dfid.isNullOrBlank()) {
        session.updateFromCookies(listOf("dfid=$dfid"))
    }
    return body
}

/** register_dev.js's default device profile — Redmi 'marble', all sensors off. */
private fun buildDeviceJson(guid: String): String {
    val sb = StringBuilder("{")
    val fields: List<Pair<String, Any>> = listOf(
        "availableRamSize" to 4983533568L,
        "availableRomSize" to 48114719L,
        "availableSDSize" to 48114717L,
        "basebandVer" to "",
        "batteryLevel" to 100,
        "batteryStatus" to 3,
        "brand" to "Redmi",
        "buildSerial" to "unknown",
        "device" to "marble",
        "imei" to guid,
        "imsi" to "",
        "manufacturer" to "Xiaomi",
        "uuid" to guid,
        "accelerometer" to false,
        "accelerometerValue" to "",
        "gravity" to false,
        "gravityValue" to "",
        "gyroscope" to false,
        "gyroscopeValue" to "",
        "light" to false,
        "lightValue" to "",
        "magnetic" to false,
        "magneticValue" to "",
        "orientation" to false,
        "orientationValue" to "",
        "pressure" to false,
        "pressureValue" to "",
        "step_counter" to false,
        "step_counterValue" to "",
        "temperature" to false,
        "temperatureValue" to "",
    )
    fields.forEachIndexed { i, (k, v) ->
        if (i > 0) sb.append(',')
        sb.append('"').append(k).append("\":")
        when (v) {
            is String -> sb.append('"').append(v).append('"')
            else -> sb.append(v.toString())
        }
    }
    sb.append('}')
    return sb.toString()
}
