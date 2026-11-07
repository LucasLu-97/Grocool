package com.grocool.kugou.smoke

import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouConfig
import com.grocool.kugou.KugouPlatform
import com.grocool.kugou.KugouSession
import com.grocool.kugou.api.QrLoginState
import com.grocool.kugou.api.checkQrLogin
import com.grocool.kugou.api.createQrLoginKey
import com.grocool.kugou.api.downloadLyric
import com.grocool.kugou.api.privilegeLite
import com.grocool.kugou.api.refreshLoginToken
import com.grocool.kugou.api.registerDevice
import com.grocool.kugou.api.searchComplex
import com.grocool.kugou.api.searchLyric
import com.grocool.kugou.api.songUrlNew
import com.grocool.kugou.api.songUrlV5
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real-backend smoke for the KuGou Concept (lite) chain. Run with :kugou:smoke.
 *
 * Persists the signed-in session in .temp/grocool-research/session.txt, so a
 * re-run resumes from the stored token and skips the QR login entirely.
 */
fun main() = runBlocking {
    writeQrLivePage()
    val sessionFile = java.io.File(".temp/grocool-research/session.txt")
    val session = if (sessionFile.exists()) {
        val map = sessionFile.readLines().filter { it.contains('=') }.associate {
            val i = it.indexOf('=')
            it.substring(0, i).trim() to it.substring(i + 1).trim()
        }
        KugouSession(map).also {
            println("session restored: userid=${it.userid} signedIn=${it.isSignedIn} dfid=${it.dfid}")
        }
    } else {
        KugouSession()
    }
    val client = KugouClient(KugouConfig(KugouPlatform.LITE), session)
    try {
        if (!client.session.isSignedIn) {
            println("=== [1/8] registerDevice ===")
            val reg = client.registerDevice()
            println("register status=${reg["status"]?.jsonPrimitive?.content} dfid=${client.session.dfid}")

            println("=== [2/8] QR login loop (auto-renews expired codes, up to 30 rounds) ===")
            var confirmed = false
            var round = 0
            val qrFile = java.io.File(".temp/grocool-research/qr-current.txt")
            while (!confirmed && round < 30) {
                round++
                val keyResp = client.createQrLoginKey()
                val keyData = (keyResp.body as? JsonObject)?.get("data") as? JsonObject
                val key = keyData?.get("qrcode")?.jsonPrimitive?.content.orEmpty()
                if (key.isBlank()) {
                    println("QR KEY MISSING, raw body: ${keyResp.body.toString().take(500)}")
                    return@runBlocking
                }
                val url = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"
                println("QR_ROUND=$round SCAN_URL=$url")
                runCatching { qrFile.writeText(url) }
                writeQr(url)
                var expired = false
                var waited = 0
                while (!confirmed && !expired && waited < 150) {
                    delay(3000)
                    waited += 3
                    when (val st = client.checkQrLogin(key)) {
                        is QrLoginState.AwaitingScan -> { /* quiet */ }
                        is QrLoginState.AwaitingConfirm ->
                            if (waited % 15 == 0) println("[round $round] scanned, confirm on the phone...")
                        is QrLoginState.Confirmed -> {
                            println("[round $round] LOGIN CONFIRMED")
                            confirmed = true
                        }
                        is QrLoginState.Expired -> {
                            println("[round $round] code expired, renewing...")
                            expired = true
                        }
                        is QrLoginState.Failed -> { println("QR FAILED: ${st.message}"); return@runBlocking }
                    }
                }
            }
            if (!confirmed) { println("TIMEOUT after 30 rounds"); return@runBlocking }
        } else {
            println("=== [1-3/8] skipped: session restored from file ===")
        }

        println("=== [4/8] refreshLoginToken ===")
        val tok = client.refreshLoginToken()
        println("token status=${tok["status"]?.jsonPrimitive?.content}")
        println("userid=${client.session.userid} vipType=${client.session.vipType} vipTokenSet=${!client.session.vipToken.isNullOrBlank()} t1=${client.session.t1 != null}")

        println("=== [5/8] searchComplex '???' ===")
        val search = client.searchComplex("???").ensureOk()
        val data = (search.body as? JsonObject)?.get("data") as? JsonObject
        val sections = data?.get("lists") as? JsonArray
        println("sections: ${sections?.size}")
        var first: JsonObject? = null
        for ((si, sec) in sections.orEmpty().withIndex()) {
            val s = sec as? JsonObject ?: continue
            val type = s["type"]?.jsonPrimitive?.content
            val inner = s["lists"] as? JsonArray ?: continue
            val firstInner = inner.firstOrNull() as? JsonObject
            println("section[$si] type=$type innerKeys=${firstInner?.keys?.take(10)}")
            if (firstInner != null && firstInner.keys.any { it.equals("FileHash", true) || it.equals("hash", true) }) {
                first = firstInner
                break
            }
        }
        if (first == null) {
            println("NO SONG FOUND, body: ${search.body.toString().take(600)}")
            return@runBlocking
        }
        fun str(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            (first?.get(n) as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
        }
        val hash = str("FileHash", "filehash", "hash") ?: ""
        val albumAudioId = str("AlbumAudioID", "Audioid", "album_audio_id", "HQAlbumAudioID")?.toLongOrNull()
        val albumId = str("AlbumID", "album_id")?.toLongOrNull()
        val songName = str("FileName", "SongName", "name")
        println("picked: name=$songName hash=$hash albumId=$albumId albumAudioId=$albumAudioId")
        if (hash.isBlank()) { println("NO HASH"); return@runBlocking }

        println("=== [6/8] privilegeLite ===")
        val pv = client.privilegeLite(listOf(hash))
        val v = pv.firstOrNull() ?: run { println("no privilege result"); return@runBlocking }
        println("song=${v.name} - ${v.singerName}")
        println("free=${v.freeQualities} vip=${v.vipQualities} suvip=${v.superVipQualities} best=${v.bestFreeQuality}")
        v.qualities.forEach { q ->
            println("  ${q.quality}: exists=${q.exists} obtainable=${q.obtainable} size=${q.sizeText} ${q.extname}")
        }

        println("=== [7/8] stream experiments ===")
        println("-- v5 song_url signed (quality=128) --")
        val v5 = runCatching { client.songUrlV5(hash, quality = "128", albumId = albumId, albumAudioId = albumAudioId) }
        println(v5.getOrNull()?.body?.toString()?.take(900) ?: "v5 threw: ${v5.exceptionOrNull()?.message}")
        println("-- v6 priv_url, resource WITHOUT album_audio_id --")
        val v6a = runCatching { client.songUrlNew(hash, null) }
        println(v6a.getOrNull()?.body?.toString()?.take(900) ?: "v6a threw: ${v6a.exceptionOrNull()?.message}")
        println("-- v6 priv_url, string album_audio_id --")
        val v6b = runCatching { client.songUrlNew(hash, albumAudioId) }
        println(v6b.getOrNull()?.body?.toString()?.take(900) ?: "v6b threw: ${v6b.exceptionOrNull()?.message}")

        println("=== [8/8] KRC lyric ===")
        val lowerHash = hash.trim().lowercase()
        val durationSec = str("SuperDuration", "Duration")?.toLongOrNull() ?: 0L
        val lyr = client.searchLyric(hash = lowerHash, durationMs = durationSec * 1000)
        println("lyric search code=${lyr.code} bytes=${lyr.bodyBytes.size} body=${lyr.body.toString().take(300)}")
        val cand = ((lyr.body as? JsonObject)?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
        if (cand != null) {
            val id = cand["id"]?.jsonPrimitive?.content ?: ""
            val ak = cand["accesskey"]?.jsonPrimitive?.content ?: ""
            if (id.isNotEmpty() && ak.isNotEmpty()) {
                val text = client.downloadLyric(id, ak, krc = true)
                println("lyric(first 300): ${text?.take(300)}")
            }
        } else {
            println("no lyric candidates, body: ${lyr.body.toString().take(300)}")
        }

        println("=== SMOKE COMPLETE ===")
    } catch (e: Exception) {
        println("SMOKE FAILED: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        runCatching {
            val snap = client.session.asCookieMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            sessionFile.writeText(snap + "\n")
        }
        println("SESSION_SNAPSHOT=" + client.session.asCookieMap())
        client.close()
    }
}

/** Self-refreshing page that always shows the live QR. */
private fun writeQrLivePage() {
    val html = """<!doctype html><html><head><meta charset="utf-8"><title>Grocool ????</title>
<style>body{background:#fff;text-align:center;font-family:sans-serif;margin-top:24px}img{width:420px;max-width:92vw;height:auto}p{color:#555}</style>
</head><body><img id="qr" src="qr.png"><p>????????????APP ???????(??????,??????)</p>
<script>setInterval(function(){document.getElementById('qr').src='qr.png?v='+Date.now()},3000)</script>
</body></html>"""
    java.io.File(".temp/grocool-research/qr-live.html").writeText(html)
}

/** Renders the login URL as a QR PNG next to qr-live.html (zxing, pure JVM). */
private fun writeQr(url: String) {
    runCatching {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 480, 480)
        val img = java.awt.image.BufferedImage(480, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 480) {
            for (y in 0 until 480) {
                img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        javax.imageio.ImageIO.write(img, "png", java.io.File(".temp/grocool-research/qr.png"))
    }.onFailure { println("qr render failed: ${it.message}") }
}
