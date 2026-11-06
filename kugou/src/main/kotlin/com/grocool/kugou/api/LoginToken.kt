package com.grocool.kugou.api

import com.grocool.kugou.DeviceIdentity
import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouApiException
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouCrypto
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `module/login_token.js` - token refresh (v5/login_by_token).
 *
 * The t1/t2 pairs are AES-encrypted device fingerprints. Note the JS encrypts
 * t1/t2 with the *lite* keys even on the MAIN platform and then simply posts 0
 * for both there - kept as-is here. `login.user.kugou.com` is plain HTTP in
 * the upstream; on Android this needs a cleartext traffic allowlist entry,
 * handled in the app manifest later.
 */
suspend fun KugouClient.refreshLoginToken(): JsonObject {
    session.ensureDeviceIdentity()
    val cookie = session.asCookieMap()
    val dateNow = System.currentTimeMillis()
    val token = cookie["token"] ?: ""
    val userid = cookie["userid"]?.toLongOrNull() ?: 0L
    val dfid = cookie["dfid"] ?: "-"

    val (p3Key, p3Iv) = if (config.isLite) {
        KugouConfig.LOGIN_TOKEN_LITE_KEY to KugouConfig.LOGIN_TOKEN_LITE_IV
    } else {
        KugouConfig.LOGIN_TOKEN_KEY to KugouConfig.LOGIN_TOKEN_IV
    }
    val p3 = KugouCrypto.aesEncryptCbcPkcs7Hex(
        """{"clienttime":${dateNow / 1000},"token":"$token"}""".toByteArray(Charsets.UTF_8),
        p3Key, p3Iv,
    )

    // cryptoAesEncrypt({}) - random tempKey, md5-derived key/iv
    val tempKey = DeviceIdentity.randomString(16).lowercase()
    val paramsCipher = KugouCrypto.aesEncryptDerivedHex("{}".toByteArray(Charsets.UTF_8), tempKey)

    // cryptoRSAEncrypt - the raw modPow variant, hex
    val pk = KugouCrypto.rsaRawEncryptHex(
        """{"clienttime_ms":$dateNow,"key":"$tempKey"}""".toByteArray(Charsets.UTF_8),
        config.rsaPublicKeyPem,
    )

    // t1/t2 use the fixed lite keys regardless of platform (upstream quirk)
    val guid = cookie["KUGOU_API_GUID"] ?: ""
    val mac = cookie["KUGOU_API_MAC"] ?: ""
    val dev = cookie["KUGOU_API_DEV"] ?: ""
    val t2 = KugouCrypto.aesEncryptCbcPkcs7Hex(
        "$guid|0f607264fc6318a92b9e13c65db7cd3c|$mac|$dev|$dateNow".toByteArray(Charsets.UTF_8),
        KugouConfig.LOGIN_TOKEN_LITE_T2_KEY, KugouConfig.LOGIN_TOKEN_LITE_T2_IV,
    )
    val priorT1 = cookie["t1"]
    val t1Plain = (if (priorT1.isNullOrBlank()) "" else priorT1) + "|" + dateNow
    val t1 = KugouCrypto.aesEncryptCbcPkcs7Hex(
        t1Plain.toByteArray(Charsets.UTF_8),
        KugouConfig.LOGIN_TOKEN_LITE_T1_KEY, KugouConfig.LOGIN_TOKEN_LITE_T1_IV,
    )

    val data = LinkedHashMap<String, Any>()
    data["dfid"] = dfid
    data["p3"] = p3
    data["plat"] = 1
    data["t1"] = if (config.isLite) t1 else 0
    data["t2"] = if (config.isLite) t2 else 0
    data["t3"] = "MCwwLDAsMCwwLDAsMCwwLDA="
    data["pk"] = pk
    data["params"] = paramsCipher
    data["userid"] = userid
    data["clienttime_ms"] = dateNow
    if (config.isLite) data["dev"] = dev

    val response = request(
        RequestSpec(
            url = "/v5/login_by_token",
            method = "POST",
            baseURL = "http://login.user.kugou.com",
            data = data,
            encryptType = EncryptType.ANDROID,
        ),
    )

    val body = response.body as? JsonObject ?: throw KugouApiException(response)
    val status = body["status"]?.jsonPrimitive?.content
    if (status == "1") {
        val dataObj = body["data"] as? JsonObject
        val secuParams = dataObj?.get("secu_params")?.jsonPrimitive?.content
        val cookieUpdates = ArrayList<String>()
        if (!secuParams.isNullOrBlank()) {
            val decrypted = runCatching {
                KugouCrypto.aesDecryptDerivedHex(secuParams, tempKey)
            }.getOrNull()
            if (decrypted != null) {
                val parsed = runCatching { Json.parseToJsonElement(decrypted) }.getOrNull()
                if (parsed is JsonObject) {
                    parsed.forEach { entry ->
                        cookieUpdates += "${entry.key}=${entry.value.jsonPrimitive.content}"
                    }
                } else {
                    cookieUpdates += "token=$decrypted"
                }
            }
        }
        dataObj?.get("t1")?.jsonPrimitive?.let { cookieUpdates += "t1=${it.content}" }
        dataObj?.get("token")?.jsonPrimitive?.let { cookieUpdates += "token=${it.content}" }
        dataObj?.get("userid")?.jsonPrimitive?.let { cookieUpdates += "userid=${it.content}" }
        dataObj?.get("vip_type")?.jsonPrimitive?.let { cookieUpdates += "vip_type=${it.content}" }
        dataObj?.get("vip_token")?.jsonPrimitive?.let { cookieUpdates += "vip_token=${it.content}" }
        session.updateFromCookies(cookieUpdates)
    }
    return body
}
