package com.grocool.kugou.runtime

import android.content.Context
import android.content.SharedPreferences
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouPlatform
import com.grocool.kugou.KugouSession
import com.music.bitchord.auth.EncryptedPrefs

/**
 * Process-wide holder for the KuGou Concept client, with the signed-in session
 * persisted in its own encrypted store.
 *
 * The store is deliberately separate from every other preference file — it is
 * the one thing "reset app data" must keep, so it is the one file nothing
 * else is allowed to write to. Restores on Application init, saved on every
 * login/refresh change.
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
        val snapshot = c.session.asCookieMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        store.edit().putString(KEY_SESSION, snapshot).apply()
    }

    private fun parseSession(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }
}
