package com.grocool.kugou

/**
 * The mutable cookie/credential state one signed-in device carries, mirroring
 * the cookie names KuGouMusicApi's server.js passes around.
 *
 * The API surface deliberately keeps the internal `KUGOU_API_*` cookie names
 * (MID, GUID, DEV, WEBGL) because individual API modules reference them.
 */
class KugouSession(initial: Map<String, String> = emptyMap()) {

    var dfid: String? = null
    var token: String? = null
    var userid: Long? = null
    var vipToken: String? = null
    var vipType: Int? = null
    var t1: String? = null
    var t2: String? = null

    /** KUGOU_API_MID */
    var mid: String? = null

    /** KUGOU_API_GUID */
    var guid: String? = null

    /** KUGOU_API_DEV */
    var dev: String? = null

    /** KUGOU_API_WEBGL */
    var webgl: String? = null

    var extra: MutableMap<String, String> = LinkedHashMap()

    init {
        updateFromCookies(initial.map { "${it.key}=${it.value}" })
    }

    val isSignedIn: Boolean get() = !token.isNullOrBlank() && (userid ?: 0L) != 0L

    /**
     * Merge "k=v" cookie strings from a response. Empty values are kept:
     * KuGou clears state that way.
     */
    fun updateFromCookies(cookies: List<String>) {
        for (raw in cookies) {
            val idx = raw.indexOf('=')
            if (idx <= 0) continue
            val k = raw.substring(0, idx).trim()
            val v = raw.substring(idx + 1).trim()
            when (k) {
                "dfid" -> dfid = v
                "token" -> token = v
                "userid" -> userid = v.toLongOrNull() ?: userid
                "vip_token" -> vipToken = v
                "vip_type" -> vipType = v.toIntOrNull() ?: vipType
                "t1" -> t1 = v
                "t2" -> t2 = v
                "KUGOU_API_MID" -> mid = v
                "KUGOU_API_GUID" -> guid = v
                "KUGOU_API_DEV" -> dev = v
                "KUGOU_API_WEBGL" -> webgl = v
                else -> extra[k] = v
            }
        }
    }

    /** Everything set, in the cookie-name convention the API modules expect. */
    fun asCookieMap(): Map<String, String> = buildMap {
        dfid?.let { put("dfid", it) }
        token?.let { put("token", it) }
        userid?.let { put("userid", it.toString()) }
        vipToken?.let { put("vip_token", it) }
        vipType?.let { put("vip_type", it.toString()) }
        t1?.let { put("t1", it) }
        t2?.let { put("t2", it) }
        mid?.let { put("KUGOU_API_MID", it) }
        guid?.let { put("KUGOU_API_GUID", it) }
        dev?.let { put("KUGOU_API_DEV", it) }
        webgl?.let { put("KUGOU_API_WEBGL", it) }
        putAll(extra)
    }
}
