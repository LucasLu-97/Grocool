package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouCrypto
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec

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
    val cookie = session.asCookieMap()
    val userid = cookie["userid"]?.toLongOrNull() ?: 0L
    val mid = cookie["KUGOU_API_MID"] ?: ""
    val vipToken = cookie["vip_token"] ?: ""
    val vipType = cookie["vip_type"]?.toIntOrNull() ?: 0
    val clienttimeMs = System.currentTimeMillis()

    val trackerKey = KugouCrypto.md5Hex(
        "$hash${KugouConfig.LITE_SIGNKEY_SALT}1005$mid$userid",
    )

    val resource = LinkedHashMap<String, Any>()
    resource["album_audio_id"] = albumAudioId ?: 0
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
