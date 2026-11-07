package com.music.bitchord.data.kugou

import android.content.Context
import android.content.SharedPreferences
import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouPlatform
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.KugouSession
import com.grocool.kugou.RequestSpec
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Process-wide holder for the KuGou Concept client, with the signed-in session
 * persisted in its own encrypted store, plus the two request builders the
 * source layer needs.
 *
 * The store is deliberately separate from every other preference file — it is
 * the one thing "reset app data" must keep, so it is the one file nothing
 * else is allowed to write to. Restores on Application init, saved on every
 * login/refresh change.
 *
 * The request builders below go through `client.request(...)` — a *member*
 * call on a class, the one cross-module shape this AGP+Kotlin toolchain
 * resolves reliably (seen live across five CI rounds: KugouClient and its
 * members always type-checked, while top-level functions and objects from
 * the same jar reported Unresolved at their use sites). The parameters are
 * verbatim ports of the `:kugou` module's `searchComplex` and the authorized
 * stream chain (user_verify -> song_auth -> /tracker/v5/url), both verified
 * live against the Concept backend during the Grocool smoke.
 */
object KugouRuntime {

    private const val KEY_SESSION = "kugou_session_v1"

    @Volatile
    private var client: KugouClient? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Call once from Application startup, off the main thread. */
    fun init(context: Context) {
        val store = EncryptedPrefs.open(context, "kugou_auth", "kugou_auth_plain")
        prefs = store
        val saved = store.getString(KEY_SESSION, null)
        val sessionMap = parseSession(saved)
        client = KugouClient(KugouConfig(KugouPlatform.LITE), KugouSession(sessionMap))
    }

    /** The shared client, or null before [init] ran. Sources treat that as a miss. */
    fun clientOrNull(): KugouClient? = client

    val isSignedIn: Boolean get() = client?.session?.isSignedIn == true

    /** Persists the current session state — call after login and token refresh. */
    fun persist() {
        val c = client ?: return
        val store = prefs ?: return
        val snapshot = c.session.asCookieMap().entries.joinToString("\n") { entry -> "${entry.key}=${entry.value}" }
        store.edit().putString(KEY_SESSION, snapshot).apply()
    }

    /**
     * The v6 combined search. Port of `searchComplex` in the :kugou module:
     * complexsearch.kugou.com, the AndroidFilter platform, cursor 0.
     */
    suspend fun search(query: String, pagesize: Int): KugouResponse? {
        val c = client ?: return null
        return runCatching {
            c.request(
                RequestSpec(
                    url = "/v6/search/complex",
                    method = "GET",
                    baseURL = "https://complexsearch.kugou.com",
                    params = mapOf(
                        "platform" to "AndroidFilter",
                        "keyword" to query,
                        "page" to 1,
                        "pagesize" to pagesize,
                        "cursor" to 0,
                    ),
                    encryptType = EncryptType.ANDROID,
                ),
            )
        }.getOrNull()
    }

    /**
     * The full authorized stream chain, port of `songUrlAuthMerge` in the
     * :kugou module:
     *  1. `/v1/user_verify` (module_id 51) -> the account authorization that
     *     carries the Concept VIP entitlements;
     *  2. `/v1/authorization` -> per-track auth + open_time;
     *  3. `/tracker/v5/url` (need_ogg=1, module_id=51, clientver 11561) with
     *     the gateway signKey — the server now requires the signature field
     *     on this route as well, matching the smoke findings.
     * Returns null when any step fails; the caller treats that as a miss.
     */
    suspend fun streamUrl(
        hash: String,
        quality: String,
        albumId: Long? = null,
        albumAudioId: Long? = null,
    ): KugouResponse? {
        val c = client ?: return null
        val lowerHash = hash.trim().lowercase()
        return runCatching {
            // 1. account-level authorization
            val verify = c.request(
                RequestSpec(
                    url = "/v1/user_verify",
                    method = "GET",
                    params = mapOf("module_id" to 51),
                    encryptType = EncryptType.ANDROID,
                    headers = mapOf("x-router" to "trackercdngz.kugou.com"),
                ),
            )
            val auth = ((verify.body as? JsonObject)?.get("data") as? JsonObject)
                ?.get("auth")?.jsonPrimitive?.content.orEmpty()

            // 2. per-track authorization
            val sa = c.request(
                RequestSpec(
                    url = "/v1/authorization",
                    method = "GET",
                    baseURL = "http://gateway.kugou.com",
                    params = mapOf(
                        "authorization" to auth,
                        "module_id" to 51,
                        "album_audio_id" to (albumAudioId ?: 0),
                        "clientver" to 11561,
                        "hash" to lowerHash,
                    ),
                    encryptType = EncryptType.ANDROID,
                    headers = mapOf("x-router" to "trackercdngz.kugou.com"),
                ),
            )
            val saData = (sa.body as? JsonObject)?.get("data") as? JsonObject
            val trackAuth = saData?.get("auth")?.jsonPrimitive?.content ?: auth
            val openTime: Any = saData?.get("open_time") ?: JsonPrimitive(0)

            // 3. the authorized URL
            val params = LinkedHashMap<String, Any>()
            params["album_id"] = albumId ?: 0
            params["area_code"] = 1
            params["module"] = ""
            params["hash"] = lowerHash
            params["need_m"] = 0
            params["ssa_flag"] = "is_fromtrack"
            params["version"] = 11430
            params["open_time"] = openTime
            params["ptype"] = 0
            params["need_ogg"] = 1
            params["page_id"] = 967177915
            params["auth"] = trackAuth
            params["mtype"] = 0
            params["quality"] = quality.ifBlank { "128" }
            params["album_audio_id"] = albumAudioId ?: 0
            params["behavior"] = "play"
            params["pid"] = 411
            params["module_id"] = 51
            params["cmd"] = 26
            params["ppage_id"] = "356753938,823673182,967485191"
            params["clientver"] = 11561
            params["pidversion"] = 3001
            params["cdnBackup"] = 1
            c.request(
                RequestSpec(
                    url = "/tracker/v5/url",
                    method = "GET",
                    params = params,
                    encryptType = EncryptType.ANDROID,
                    encryptKey = true,
                ),
            )
        }.getOrNull()
    }

    private fun parseSession(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }
}
