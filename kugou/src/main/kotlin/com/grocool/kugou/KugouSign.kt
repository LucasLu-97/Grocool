package com.grocool.kugou

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Request signing, mirroring KuGouMusicApi's `util/helper.js`.
 *
 * All algorithms are MD5 over `salt + sorted-params + data + salt` variants.
 * The JS sorts with `Object.keys().sort()`, which is a UTF-16 code-unit
 * comparison - identical to Kotlin's natural `String` order - so the sorted
 * concatenation below is byte-for-byte the same string the JS side builds.
 */
object KugouSign {

    private val json = Json

    /**
     * The sorted `key=value...` concatenation every salted signature starts
     * from. Object values stringify as compact JSON, exactly like
     * `JSON.stringify(params[key])` in helper.js.
     */
    internal fun sortedParamString(params: Map<String, Any?>): String =
        params.keys.sorted().joinToString("") { key ->
            "$key=${stringifyValue(params[key])}"
        }

    /** `signParams` variant: `key+value` with no `=`. */
    internal fun sortedKeyedValueString(params: Map<String, Any?>): String =
        params.keys.sorted().joinToString("") { key ->
            "$key${stringifyValue(params[key])}"
        }

    /** helper.js: values only (keys ignored), sorted, joined. */
    internal fun sortedValueString(params: Map<String, Any?>): String =
        params.values.map { stringifyValue(it) }.sorted().joinToString("")

    private fun stringifyValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Number, is Boolean -> value.toString()
        is JsonElement -> json.encodeToString(JsonElement.serializer(), value)
        is Map<*, *> -> json.encodeToString(
            JsonElement.serializer(),
            JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonElementOf(v) }),
        )
        is List<*> -> Json.encodeToString(
            JsonElement.serializer(),
            JsonArray(value.map { jsonElementOf(it) }),
        )
        else -> throw IllegalArgumentException(
            "Unsupported parameter type for signing: ${value::class.simpleName}",
        )
    }

    private fun jsonElementOf(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null as String?)
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is JsonElement -> value
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonElementOf(v) })
        is List<*> -> JsonArray(value.map { jsonElementOf(it) })
        else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
    }

    // ---------- The signatures themselves ----------

    /** helper.js `signatureWebParams` - WEB_SALT wraps both ends. */
    fun signatureWebParams(params: Map<String, Any?>, data: String = ""): String =
        KugouCrypto.md5Hex(
            KugouConfig.WEB_SALT + sortedParamString(params) + data + KugouConfig.WEB_SALT,
        )

    /** helper.js `signatureAndroidParams` - salt differs per platform. */
    fun signatureAndroidParams(params: Map<String, Any?>, data: String, salt: String): String =
        KugouCrypto.md5Hex(salt + sortedParamString(params) + data + salt)

    /**
     * `signatureAndroidParams` with a binary request body: the JS updates the
     * MD5 hasher with salt, param string, raw bytes, salt in that order.
     */
    fun signatureAndroidParams(params: Map<String, Any?>, data: ByteArray, salt: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        md.update(salt.toByteArray(Charsets.UTF_8))
        md.update(sortedParamString(params).toByteArray(Charsets.UTF_8))
        md.update(data)
        md.update(salt.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** helper.js `signatureRegisterParams` - values only, REGISTER_SALT ends. */
    fun signatureRegisterParams(params: Map<String, Any?>): String =
        KugouCrypto.md5Hex(
            KugouConfig.REGISTER_SALT + sortedValueString(params) + KugouConfig.REGISTER_SALT,
        )

    /** helper.js `signParams` - params + data + SIGN_SALT at the end only. */
    fun signParams(params: Map<String, Any?>, data: String = ""): String =
        KugouCrypto.md5Hex(sortedKeyedValueString(params) + data + KugouConfig.SIGN_SALT)

    /** helper.js `signKey` - MD5(hash + salt + appid + mid + userid). */
    fun signKey(hash: String?, mid: String, userid: Long, appid: Int, salt: String): String =
        KugouCrypto.md5Hex("${hash ?: ""}${salt}${appid}${mid}${userid}")

    /** helper.js `signParamsKey` - MD5(appid + salt + clientver + data). */
    fun signParamsKey(data: String, appid: Int, clientver: Int, salt: String): String =
        KugouCrypto.md5Hex("$appid$salt$clientver$data")

    /** helper.js `signCloudKey` - MD5("musicclound" + hash + pid + salt). */
    fun signCloudKey(hash: String, pid: String): String =
        KugouCrypto.md5Hex("musicclound$hash$pid${KugouConfig.CLOUD_SALT}")
}
