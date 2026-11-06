package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.Krc
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * `module/search_lyric.js` + `module/lyric.js` - the KRC word-timed lyric
 * chain: find lyric candidates by track hash, then download the winning one.
 * KRC payloads are base64 -> skip-4 -> XOR -> zlib (see [Krc]); `lrc` and
 * plain-text responses come back as a direct base64 decode.
 */
suspend fun KugouClient.searchLyric(
    hash: String? = null,
    keywords: String? = null,
    duration: Int = 0,
    albumAudioId: Long? = null,
): KugouResponse = request(
    RequestSpec(
        url = "/v1/search",
        method = "GET",
        baseURL = "https://lyrics.kugou.com",
        params = buildMap {
            put("album_audio_id", albumAudioId ?: 0)
            put("appid", 1005)
            put("clientver", 20489)
            put("duration", duration)
            put("hash", hash ?: "")
            put("keyword", keywords ?: "")
            put("lrctxt", 1)
            put("man", "no")
        },
        encryptType = EncryptType.ANDROID,
        clearDefaultParams = true,
        notSignature = true,
    ),
)

/**
 * Returns the decoded lyric text, or null when the candidate has no content.
 */
suspend fun KugouClient.downloadLyric(
    id: String,
    accessKey: String,
    krc: Boolean = true,
): String? {
    val response = request(
        RequestSpec(
            url = "/download",
            method = "GET",
            baseURL = "https://lyrics.kugou.com",
            params = mapOf(
                "ver" to 1,
                "client" to "android",
                "id" to id,
                "accesskey" to accessKey,
                "fmt" to if (krc) "krc" else "lrc",
                "charset" to "utf8",
            ),
            encryptType = EncryptType.ANDROID,
        ),
    )
    val obj = response.body as? JsonObject ?: return null
    val content = obj["content"]?.jsonPrimitive?.content ?: return null
    val contentType = obj["contenttype"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
    return if (!krc || contentType != 0) {
        runCatching { String(Base64.getDecoder().decode(content), Charsets.UTF_8) }.getOrNull()
    } else {
        runCatching { Krc.decode(content) }.getOrNull()
    }
}
