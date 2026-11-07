package com.grocool.kugou.api

import com.grocool.kugou.DeviceIdentity
import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouApiException
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouCrypto
import com.grocool.kugou.KugouPlatform
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Token refresh, `module/login_token.js` /v5/login_by_token.
 *
 * The request is sent in the **MAIN dialect regardless of platform** —
 * t1/t2 = 0, no dev field, the MAIN p3 key and the MAIN RSA key. That is the
 * recipe KGAutoCheckin-Android (KgApi.loginByToken) has been using against
 * this same Concept account for months; the JS *lite* branch (t1/t2 as
 * lite-encrypted device fingerprints) consistently returned error 20018 from
 * this endpoint in the Grocool smoke, so the proven path wins.
 *
 * `login.user.kugou.com` is plain HTTP upstream; on Android this needs a
 * cleartext traffic allowlist entry, handled in the app manifest later.
 */
suspend fun KugouClient.refreshLoginToken(): JsonObject {
    session.ensureDeviceIdentity()
    val cookie = session.asCookieMap()
    val dateNow = System.currentTimeMillis()
    val token = cookie["token"] ?: ""
    val userid = cookie["userid"]?.toLongOrNull() ?: 0L
    val dfid = cookie["dfid"] ?: "-"

    val p3 = KugouCrypto.aesEncryptCbcPkcs7Hex(
        """{"clienttime":${dateNow / 1000},"token":"$token"}""".toByteArray(Charsets.UTF_8),
        KugouConfig.LOGIN_TOKEN_KEY, KugouConfig.LOGIN_TOKEN_IV,
    )

    // cryptoAesEncrypt({}) — random tempKey, md5-derived key/iv
    val tempKey = DeviceIdentity.randomString(16).lowercase()
    val paramsCipher = KugouCrypto.aesEncryptDerivedHex("{}".toByteArray(Charsets.UTF_8), tempKey)

    // cryptoRSAEncrypt — raw modPow, MAIN public key (matches the MAIN dialect)
    val pk = KugouCrypto.rsaRawEncryptHex(
        """{"clienttime_ms":$dateNow,"key":"$tempKey"}""".toByteArray(Charsets.UTF_8),
        KugouConfig.MAIN_PUBLIC_KEY_PEM,
    )

    val data = LinkedHashMap<String, Any>()
    data["dfid"] = dfid
    data["p3"] = p3
    data["plat"] = 1
    data["t1"] = 0
    data["t2"] = 0
    data["t3"] = "MCwwLDAsMCwwLDAsMCwwLDA="
    data["pk"] = pk
    data["params"] = paramsCipher
    data["userid"] = userid.toString()
    data["clienttime_ms"] = dateNow

    // MAIN dialect over the same session: appid/clientver/salts all MAIN.
    val caller = if (config.platform == KugouPlatform.MAIN) this
    else withPlatform(KugouPlatform.MAIN)
    val response = caller.request(
        RequestSpec(
            url = "/v5/login_by_token",
            method = "POST",
            baseURL = "http://login.user.kugou.com",
            data = data,
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "login.user.kugou.com"),
        ),
    )

    val body = response.body as? JsonObject ?: throw KugouApiException(response)
    val status = body["status"]?.jsonPrimitive?.content
    if (status != "1") {
        // smoke diagnostics: the upstream error text tells which field is off
        println("refreshLoginToken failed body: " + body.toString().take(600))
    }
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
