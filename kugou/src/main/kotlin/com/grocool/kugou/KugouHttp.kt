package com.grocool.kugou

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Kotlin port of KuGouMusicApi's `util/request.js` - the shared request layer
 * every API module calls.
 *
 * `request()` is `createRequest`: default parameter injection (dfid/mid/uuid/
 * appid/clientver/clienttime, plus token/userid when signed in), per-salt
 * signatures, the fixed device headers, Set-Cookie collection and the
 * `status === 0 || error_code !== 0` failure rule.
 *
 * `cloudRequest()` is `createCloudRequest`: the cloudlist protocol - a random
 * 6-char session string, AES-CBC both ways keyed off md5(session), and the
 * RSA-encrypted session portrait in the `p` query parameter.
 *
 * Deviations from the JS, deliberately:
 *  - Responses are returned, not thrown: call `ensureOk()` where a module
 *    would have relied on the JS promise rejection.
 *  - `mid` is never the JS accident `"undefined"` - sessions are expected to
 *    have a real MID, which they get at device registration time.
 */
enum class EncryptType { ANDROID, WEB, REGISTER }

enum class BodyMode { JSON, BYTES }

data class RequestSpec(
    val url: String,
    val method: String = "GET",
    val baseURL: String = "https://gateway.kugou.com",
    val params: Map<String, Any?> = emptyMap(),
    /** String / ByteArray / Map / JsonElement - serialized like axios would. */
    val data: Any? = null,
    val headers: Map<String, String> = emptyMap(),
    val encryptType: EncryptType = EncryptType.ANDROID,
    /** request.js `encryptKey`: add `key` = signKey(hash, mid, userid, appid). */
    val encryptKey: Boolean = false,
    val clearDefaultParams: Boolean = false,
    val clearDefaultHeaders: Boolean = false,
    val notSignature: Boolean = false,
    /** BYTES keeps `bodyBytes` untouched for the caller (e.g. register_dev). */
    val bodyMode: BodyMode = BodyMode.JSON,
)

class KugouResponse(
    /** False when the JS layer would have rejected (status 502). */
    val ok: Boolean,
    /** 200 on success, 502 on the failure rule - mirrors answer.status. */
    val code: Int,
    val body: JsonElement,
    val bodyBytes: ByteArray,
    val cookies: List<String>,
    val headers: Map<String, String>,
    val ssaCode: String? = null,
) {
    fun ensureOk(): KugouResponse {
        if (!ok) throw KugouApiException(this)
        return this
    }
}

class KugouApiException(val response: KugouResponse) :
    Exception("KuGou API error code=${response.code} ssa=${response.ssaCode} body=${response.body.toString().take(400)}")

class KugouClient(
    val config: KugouConfig,
    val session: KugouSession = KugouSession(),
    private val http: HttpClient = defaultHttpClient(),
) {
    private val json = Json

    suspend fun request(spec: RequestSpec): KugouResponse {
        val cookie = session.asCookieMap()
        val dfid = cookie["dfid"] ?: "-"
        val mid = cookie["KUGOU_API_MID"] ?: "-"
        val token = cookie["token"] ?: ""
        val userid = cookie["userid"]?.toLongOrNull() ?: 0L
        val clienttime = System.currentTimeMillis() / 1000

        val params = if (spec.clearDefaultParams) {
            spec.params.toMutableMap()
        } else {
            buildMap<String, Any?> {
                put("dfid", dfid)
                put("mid", mid)
                put("uuid", "-")
                put("appid", config.appid)
                put("clientver", config.clientver)
                put("clienttime", clienttime)
                if (token.isNotEmpty()) put("token", token)
                if (userid != 0L) put("userid", userid)
            }.let { it + spec.params }.toMutableMap()
        }

        if (spec.encryptKey) {
            params["key"] = KugouSign.signKey(
                params["hash"] as? String,
                (params["mid"] as? String) ?: mid,
                (params["userid"] as? Long) ?: (params["userid"] as? Int)?.toLong() ?: userid,
                (params["appid"] as? Int) ?: config.appid,
                config.signKeySalt,
            )
        }

        val dataBytes: ByteArray? = when (val d = spec.data) {
            null -> null
            is ByteArray -> d
            is String -> d.toByteArray(Charsets.UTF_8)
            is Map<*, *> -> json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                jsonElementOfMap(d),
            ).toByteArray(Charsets.UTF_8)
            is JsonElement -> json.encodeToString(JsonElement.serializer(), d)
                .toByteArray(Charsets.UTF_8)
            else -> throw IllegalArgumentException("Unsupported body type: ${d::class.simpleName}")
        }
        val dataString = dataBytes?.toString(Charsets.UTF_8) ?: ""

        if (params["signature"] == null && !spec.notSignature) {
            params["signature"] = when (spec.encryptType) {
                EncryptType.WEB -> KugouSign.signatureWebParams(params, dataString)
                EncryptType.REGISTER -> KugouSign.signatureRegisterParams(params)
                EncryptType.ANDROID ->
                    if (spec.data is ByteArray && dataBytes != null) {
                        KugouSign.signatureAndroidParams(params, dataBytes, config.androidSignSalt)
                    } else {
                        KugouSign.signatureAndroidParams(params, dataString, config.androidSignSalt)
                    }
            }
        }

        val requestHeaders = if (spec.clearDefaultHeaders) {
            spec.headers.toMutableMap()
        } else {
            buildMap {
                put("User-Agent", KugouConfig.USER_AGENT)
                putAll(spec.headers)
                put("dfid", dfid)
                put("clienttime", clienttime.toString())
                put("mid", mid)
                put("kg-rc", KugouConfig.KG_RC)
                put("kg-thash", KugouConfig.KG_THASH)
                put("kg-rec", KugouConfig.KG_REC)
                put("kg-rf", KugouConfig.KG_RF)
            }
        }

        var url = spec.baseURL.trimEnd('/') + spec.url
        val openapicdn = spec.baseURL.contains("openapicdn")
        if (openapicdn) {
            val qs = params.entries.joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(stringifyForUrl(v))}"
            }
            if (qs.isNotEmpty()) url = "$url?$qs"
        }

        val response = http.request(url) {
            method = HttpMethod.parse(spec.method.uppercase())
            requestHeaders.forEach { (k, v) -> header(k, v) }
            if (dataBytes != null) {
                contentType(ContentType("application", "json").withParameter("charset", "utf-8"))
                setBody(dataBytes)
            }
            if (!openapicdn) {
                params.forEach { (k, v) ->
                    if (v != null) url.parameters.append(k, stringifyForUrl(v))
                }
            }
        }

        val bytes = response.bodyAsBytes()
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val cookies = setCookies.map { parseCookieString(it) }
        val ssaCode = response.headers["ssa-code"] ?: response.headers["SSA-CODE"]
        val bodyText = bytes.toString(Charsets.UTF_8)
        val body: JsonElement = try {
            json.parseToJsonElement(bodyText)
        } catch (_: Exception) {
            JsonPrimitive(bodyText)
        }

        val bodyObj = body as? JsonObject
        val statusText = bodyObj?.get("status")?.let { (it as? JsonPrimitive)?.content }
        val errorCodeText = bodyObj?.get("error_code")?.let { (it as? JsonPrimitive)?.content }
        val failed = statusText == "0" || (errorCodeText != null && errorCodeText != "0")

        val result = KugouResponse(
            ok = !failed,
            code = if (failed) 502 else 200,
            body = body,
            bodyBytes = bytes,
            cookies = cookies,
            headers = response.headers.entries().associate { (k, v) -> k to v },
            ssaCode = ssaCode,
        )
        session.updateFromCookies(cookies)
        return result
    }

    /**
     * `createCloudRequest` - the cloudlist protocol used by user playlists and
     * playlist editing (cloudlist.service.kugou.com via the gateway x-router).
     */
    suspend fun cloudRequest(
        url: String,
        dataJson: String,
        baseURL: String = "https://gateway.kugou.com",
        extraHeaders: Map<String, String> = emptyMap(),
    ): KugouResponse {
        val cookie = session.asCookieMap()
        val mid = cookie["KUGOU_API_MID"] ?: ""
        val dfid = cookie["dfid"] ?: "-"
        val userid = cookie["userid"]?.toLongOrNull() ?: 0L
        val token = cookie["token"] ?: ""
        val clienttime = System.currentTimeMillis() / 1000

        val sessionStr = KugouCrypto.newSessionString()
        val md5 = KugouCrypto.md5Hex(sessionStr)
        val aesKey = md5.substring(0, 16)
        val aesIv = md5.substring(16, 32)

        val key = KugouCrypto.md5Hex(
            "${config.appid}${config.androidSignSalt}${config.clientver}$clienttime",
        )
        val portrait = KugouCrypto.rsaEncryptPkcs1Hex(
            """{"aes":"$sessionStr","uid":$userid,"token":"$token"}"""
                .toByteArray(Charsets.UTF_8),
            config.rsaPublicKeyPem,
        ).uppercase()

        val cipherHex = KugouCrypto.aesEncryptCbcPkcs7Hex(
            dataJson.toByteArray(Charsets.UTF_8), aesKey, aesIv,
        )
        val bodyBytes = ByteArray(cipherHex.length / 2) { i ->
            cipherHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val fullUrl = buildString {
            append(baseURL.trimEnd('/'))
            append(url)
            append("?appid=").append(config.appid)
            append("&clientver=").append(config.clientver)
            append("&mid=").append(urlEncode(mid))
            append("&clienttime=").append(clienttime)
            append("&key=").append(urlEncode(key))
            append("&dfid=").append(urlEncode(dfid))
            append("&p=").append(urlEncode(portrait))
        }

        val response = http.request(fullUrl) {
            method = HttpMethod.Post
            contentType(ContentType("application", "json").withParameter("charset", "utf-8"))
            header("x-router", "cloudlist.service.kugou.com")
            extraHeaders.forEach { (k, v) -> header(k, v) }
            header("dfid", dfid)
            header("clienttime", clienttime.toString())
            header("mid", mid)
            header("kg-rc", KugouConfig.KG_RC)
            header("kg-thash", KugouConfig.KG_THASH)
            header("kg-rec", KugouConfig.KG_REC)
            header("kg-rf", KugouConfig.KG_RF)
            setBody(bodyBytes)
        }

        val raw = response.bodyAsBytes()
        val text = runCatching {
            String(
                KugouCrypto.aesDecryptCbcPkcs7Hex(
                    raw.joinToString("") { "%02x".format(it) }, aesKey, aesIv,
                ),
                Charsets.UTF_8,
            )
        }.getOrDefault("")
            .ifBlank { raw.toString(Charsets.UTF_8) }

        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val cookies = setCookies.map { parseCookieString(it) }
        val body: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw KugouApiException(
                KugouResponse(false, 502, JsonPrimitive(text), raw, cookies, emptyMap()),
            )
        }
        val bodyObj = body as? JsonObject
        val statusText = bodyObj?.get("status")?.let { (it as? JsonPrimitive)?.content }
        val errorCodeText = bodyObj?.get("error_code")?.let { (it as? JsonPrimitive)?.content }
        val failed = statusText == "0" || (errorCodeText != null && errorCodeText != "0")

        val result = KugouResponse(
            ok = !failed,
            code = if (failed) 502 else 200,
            body = body,
            bodyBytes = raw,
            cookies = cookies,
            headers = response.headers.entries().associate { (k, v) -> k to v },
        )
        session.updateFromCookies(cookies)
        return result
    }

    fun close() = http.close()

    private fun jsonElementOfMap(map: Map<*, *>): JsonElement = kotlinx.serialization.json.JsonObject(
        map.entries.associate { (k, v) ->
            k.toString() to when (v) {
                null -> JsonPrimitive(null as String?)
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is JsonElement -> v
                is Map<*, *> -> jsonElementOfMap(v)
                is List<*> -> kotlinx.serialization.json.JsonArray(
                    v.map { el ->
                        when (el) {
                            null -> JsonPrimitive(null as String?)
                            is String -> JsonPrimitive(el)
                            is Number -> JsonPrimitive(el)
                            is Boolean -> JsonPrimitive(el)
                            is JsonElement -> el
                            is Map<*, *> -> jsonElementOfMap(el)
                            else -> JsonPrimitive(el.toString())
                        }
                    },
                )
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun stringifyForUrl(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is JsonElement -> json.encodeToString(JsonElement.serializer(), value)
        else -> value.toString()
    }

    /** axios-style form encoding, with %20 for spaces instead of '+'. */
    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** util.js `parseCookieString` - strip Domain/path/expires/HttpOnly. */
    private fun parseCookieString(raw: String): String =
        raw.replace(Regex("""\s*(Domain|domain|path|expires)=[^(;|$)]+;*"""), "")
            .replace(";HttpOnly", "")

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 30_000
            }
        }
    }
}
