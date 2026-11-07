package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouCrypto
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `module/song_url_new.js` - the v6 priv_url playback link request.
 *
 * The full quality ladder is requested at once; the tracker key inside is
 * md5(hash + LITE_SIGNKEY_SALT + "1005" + mid + userid) - the salt and the
 * literal 1005 appid are hard-coded on the JS side and kept verbatim.
 */
suspend fun KugouClient.songUrlNew(
    hash: String,
    albumAudioId: Long?,
    freePart: Boolean = false,
): KugouResponse {
    session.ensureDeviceIdentity()
    // Two things the upstream JS gets wrong for the Concept dialect, found by
    // smoke: the key's appid must be the request dialect's own (3116, not the
    // hard-coded 1005), and the hash must be folded to lowercase - the server
    // looks the resource up by it ("audio is not found" with uppercase).
    val hash = hash.trim().lowercase()
    val cookie = session.asCookieMap()
    val userid = cookie["userid"]?.toLongOrNull() ?: 0L
    val mid = cookie["KUGOU_API_MID"] ?: ""
    val vipToken = cookie["vip_token"] ?: ""
    val vipType = cookie["vip_type"]?.toIntOrNull() ?: 0
    val clienttimeMs = System.currentTimeMillis()

    // The JS hard-codes appid 1005 in this key, but that is the MAIN capture
    // context; the server re-derives the key from the request's own appid, so
    // under the Concept dialect (3116) the literal 1005 fails the check.
    val trackerKey = KugouCrypto.md5Hex(
        "$hash${KugouConfig.LITE_SIGNKEY_SALT}${config.appid}$mid$userid",
    )

    val resource = LinkedHashMap<String, Any>()
    // The upstream passes album_audio_id as a *string*; when absent the JS
    // key disappears from the JSON entirely (undefined is skipped by
    // stringify), and the server's strict unmarshal rejects a numeric 0
    // (error 20010 "param error, unmarshal failed").
    if (albumAudioId != null) resource["album_audio_id"] = albumAudioId.toString()
    resource["collect_list_id"] = "3"
    resource["collect_time"] = clienttimeMs
    resource["hash"] = hash
    resource["id"] = 0
    resource["page_id"] = 1
    resource["type"] = "audio"

    val trackerParam = LinkedHashMap<String, Any>()
    trackerParam["all_m"] = 1
    trackerParam["auth"] = ""
    trackerParam["is_free_part"] = if (freePart) 1 else 0
    trackerParam["key"] = trackerKey
    trackerParam["module_id"] = 0
    trackerParam["need_climax"] = 1
    trackerParam["need_xcdn"] = 1
    trackerParam["open_time"] = ""
    trackerParam["pid"] = "411"
    trackerParam["pidversion"] = "3001"
    trackerParam["priv_vip_type"] = "6"
    trackerParam["viptoken"] = vipToken

    val data = LinkedHashMap<String, Any>()
    data["area_code"] = "1"
    data["behavior"] = "play"
    data["qualities"] = listOf(
        "128", "320", "flac", "high", "multitrack",
        "viper_atmos", "viper_tape", "viper_clear", "super",
    )
    data["resource"] = resource
    data["token"] = cookie["token"] ?: ""
    data["tracker_param"] = trackerParam
    data["userid"] = userid.toString()
    data["vip"] = vipType

    return request(
        RequestSpec(
            url = "/v6/priv_url",
            method = "POST",
            data = data,
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "tracker.kugou.com"),
        ),
    )
}

/**
 * `module/song_url.js` - the older, battle-tested v5 stream endpoint
 * (GET /v5/url via x-router trackercdn.kugou.com). The `key` here is the
 * gateway's own signKey (request-layer `encryptKey`), not a hand-rolled
 * tracker key; page_id/pid/ppage_id differ between MAIN and LITE.
 * `quality` also accepts the magic remixes: piano/acappella/subwoofer/
 * ancient/dj/surnay.
 */
suspend fun KugouClient.songUrlV5(
    hash: String,
    quality: String = "128",
    albumId: Long? = null,
    albumAudioId: Long? = null,
    freePart: Boolean = false,
): KugouResponse {
    session.ensureDeviceIdentity()
    val magic = listOf("piano", "acappella", "subwoofer", "ancient", "dj", "surnay")
    val q = if (quality in magic) "magic_$quality" else quality

    val params = LinkedHashMap<String, Any>()
    params["album_id"] = albumId ?: 0
    params["area_code"] = 1
    params["hash"] = hash.trim().lowercase() // v5 folds, unlike v6
    params["ssa_flag"] = "is_fromtrack"
    params["version"] = 11430
    params["page_id"] = if (config.isLite) 967177915 else 151369488
    params["quality"] = q.ifBlank { "128" }
    params["album_audio_id"] = albumAudioId ?: 0
    params["behavior"] = "play"
    params["pid"] = if (config.isLite) 411 else 2
    params["cmd"] = 26
    params["pidversion"] = 3001
    params["IsFreePart"] = if (freePart) 1 else 0
    params["ppage_id"] = if (config.isLite) {
        "356753938,823673182,967485191"
    } else {
        "463467626,350369493,788954147"
    }
    params["cdnBackup"] = 1
    params["module"] = ""
    params["clientver"] = 11430

    return request(
        RequestSpec(
            url = "/v5/url",
            method = "GET",
            params = params,
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "trackercdn.kugou.com"),
            encryptKey = true,
            // The upstream sends v5 unsigned (notSignature), but the server
            // answered "err signature" (20006) with that shape in the smoke,
            // so the signature field is now required - send it.
        ),
    )
}

// ---------------------------------------------------------------------------
// The authorized stream chain - EchoMusic's proven path (its backend is the
// same KuGouMusicApi): user_verify hands out the playback authorization that
// carries this account's Concept VIP entitlements, song_auth exchanges it for
// per-track auth + open_time, and /tracker/v5/url (need_ogg=1, key-only
// signing, clientver 11561) resolves the actual URL. This - not vip_token -
// is how Concept VIP quality tiers (320/flac/hi-res) unlock.
// ---------------------------------------------------------------------------

/** `module/user_verify.js` - the account-level playback authorization. */
suspend fun KugouClient.userVerify(): KugouResponse = request(
    RequestSpec(
        url = "/v1/user_verify",
        method = "GET",
        params = mapOf("module_id" to 51),
        encryptType = EncryptType.ANDROID,
        headers = mapOf("x-router" to "trackercdngz.kugou.com"),
    ),
)

/** `module/song_auth.js` - per-track auth + open_time (preview window). */
suspend fun KugouClient.songAuth(
    hash: String,
    albumAudioId: Long?,
    authorization: String,
): KugouResponse = request(
    RequestSpec(
        url = "/v1/authorization",
        method = "GET",
        baseURL = "http://gateway.kugou.com",
        params = mapOf(
            "authorization" to authorization,
            "module_id" to 51,
            "album_audio_id" to (albumAudioId ?: 0),
            "clientver" to 11561,
            "hash" to hash.trim().lowercase(),
        ),
        encryptType = EncryptType.ANDROID,
        headers = mapOf("x-router" to "trackercdngz.kugou.com"),
    ),
)

/** `module/song_url_auth.js` - the authed v5: /tracker/v5/url, ogg ladder. */
suspend fun KugouClient.songUrlAuth(
    hash: String,
    quality: String = "128",
    albumId: Long? = null,
    albumAudioId: Long? = null,
    authorization: String? = null,
    openTime: Any? = null,
    ppageId: String? = null,
): KugouResponse {
    session.ensureDeviceIdentity()
    val magic = listOf("piano", "acappella", "subwoofer", "ancient", "dj", "surnay")
    val q = if (quality in magic) "magic_$quality" else quality

    val params = LinkedHashMap<String, Any>()
    params["album_id"] = albumId ?: 0
    params["area_code"] = 1
    params["module"] = ""
    params["hash"] = hash.trim().lowercase()
    params["need_m"] = 0
    params["ssa_flag"] = "is_fromtrack"
    params["version"] = 11430
    params["open_time"] = openTime ?: 0
    params["ptype"] = 0
    params["need_ogg"] = 1
    params["page_id"] = if (config.isLite) 967177915 else 151369488
    params["auth"] = authorization ?: ""
    params["mtype"] = 0
    params["quality"] = q.ifBlank { "128" }
    params["album_audio_id"] = albumAudioId ?: 0
    params["behavior"] = "play"
    params["pid"] = if (config.isLite) 411 else 2
    params["module_id"] = 51
    params["cmd"] = 26
    params["ppage_id"] = ppageId ?: if (config.isLite) {
        "356753938,823673182,967485191"
    } else {
        "463467626,350369493,788954147"
    }
    params["clientver"] = 11561
    params["pidversion"] = 3001
    params["cdnBackup"] = 1

    return request(
        RequestSpec(
            url = "/tracker/v5/url",
            method = "GET",
            params = params,
            encryptType = EncryptType.ANDROID,
            encryptKey = true,
            // Upstream says notSign here, but the server enforces the
            // signature field on every tracker stream route now (same 20006
            // "err signature" as /v5/url before it) - verified in smoke.
        ),
    )
}

/**
 * The full EchoMusic getSongUrl chain in one call:
 * user_verify -> song_auth -> authorized /tracker/v5/url.
 */
suspend fun KugouClient.songUrlAuthMerge(
    hash: String,
    quality: String = "128",
    albumId: Long? = null,
    albumAudioId: Long? = null,
): KugouResponse {
    val verify = userVerify()
    val auth = ((verify.body as? JsonObject)?.get("data") as? JsonObject)
        ?.get("auth")?.jsonPrimitive?.content.orEmpty()
    val sa = songAuth(hash, albumAudioId, auth)
    val data = ((sa.body as? JsonObject)?.get("data") as? JsonObject)
    val trackAuth = data?.get("auth")?.jsonPrimitive?.content ?: auth
    val openTime = data?.get("open_time") ?: JsonPrimitive(0)
    return songUrlAuth(hash, quality, albumId, albumAudioId, trackAuth, openTime)
}
