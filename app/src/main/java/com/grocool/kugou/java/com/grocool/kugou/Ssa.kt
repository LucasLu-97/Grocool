package com.grocool.kugou

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * SSA behaviour-fingerprint simulation, a port of KuGouMusicApi's
 * `util/generate_simulate.js`: what the browser WASM produces for the slider
 * captcha, synthesised on-device instead.
 *
 *  - data: bezier-curve mouse path + scroll + window events, encoded as
 *    "3,dt,idx,x,y : 5,dt,idx : 6,dt,idx,w,h" entries with sentinel records.
 *  - plaintext: "mid=..;userid=..;dfid=..;webgl=..;webdriver=0;ts=..;data=.."
 *  - edt = AES-128-CBC(plaintext, key, iv="kugousecurity123") -> base64
 *  - sid = RSA-OAEP-SHA256(key) -> base64 (2048-bit key, MGF1 SHA-256)
 *
 * When a response carries an `ssa-code` header, attach the pair as
 * `edt`/`sid` on the retry request, exactly like request.js does.
 */
object Ssa {

    data class Tokens(val edt: String, val sid: String)

    private const val PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoW2+Ylo8ALePSQTP0xBF
lFmEOHvBD9tS+s7DBlfKEu3RzzvZTaX1JtYbX4+AVUqj6ARz8IM+CKByqGFvbHN/
W64XxNI+q7z36ajCL3VTJ2W5G9MCJitc6oGbire4NQfhaEq0nC+hxBWQvCbIFflA
2ItrLUbSU7z1bHA/a+jlQm4OWvY+IKnTryOJTPuT1yNOVjbJ8wBLKy2DgQr9pPqW
PmEQtGpR5IM9V8Kao6PaSdKYOWGbX3i2+RzIKhvZUxxtJwdVbqPlDPlW9h4/xIBc
56Lgvr4aIl8nFtwbj4UJVUTFuGrs0tY9H/tXvZ22dUCKuGxW/gW7ZF+gXz6vHtYa
rQIDAQAB
-----END PUBLIC KEY-----"""

    private const val IV = "kugousecurity123"

    fun generate(
        mid: String?,
        userid: Long?,
        dfid: String?,
        webglHash: String? = null,
    ): Tokens {
        // regenerated per call, like the module-level SENTINEL in the JS
        val sentinel = 0xffffffffL - Random.nextLong(0, 20)

        // AES-128 key = md5(random 16)[0,16]
        val key = KugouCrypto.md5Hex(DeviceIdentity.randomString(16)).substring(0, 16)

        val points = ri(30, 60)
        val startX = ri(200, 600)
        val startY = ri(200, 500)
        val endX = ri(500, 700)
        val endY = ri(80, 150)

        val webgl = webglHash ?: randomWebglHash()
        val ts = System.currentTimeMillis()
        val data = buildData(startX, startY, endX, endY, points, sentinel)
        val plaintext = plaintext(mid ?: "0", userid ?: 0, dfid ?: "0", webgl, ts, data)

        // EDT: AES-128-CBC -> base64 (CryptoJS .toString() shape)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        val edt = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))

        // SID: RSA-OAEP with SHA-256 for both the digest and MGF1 (JDK's
        // "OAEPWithSHA-256AndMGF1Padding" defaults MGF1 to SHA-1, so the spec
        // has to be explicit to match forge's sha256/sha256 combination).
        val body = PUBLIC_KEY_PEM.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").replace("\\s".toRegex(), "")
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val sid = Base64.getEncoder().encodeToString(rsa.doFinal(key.toByteArray(Charsets.UTF_8)))

        return Tokens(edt = edt, sid = sid)
    }

    internal fun plaintext(mid: String, userid: Long, dfid: String, webgl: String, ts: Long, data: String) =
        "mid=$mid;userid=$userid;dfid=$dfid;webgl=$webgl;webdriver=0;ts=$ts;data=$data"

    /** The encoded event stream: entries joined with ':' as in generateEDTData. */
    internal fun buildData(startX: Int, startY: Int, endX: Int, endY: Int, points: Int, sentinel: Long): String {
        val entries = ArrayList<String>()
        var ts = 0
        var ei = 0

        // initial two zero type-5 events, each with its sentinel record
        repeat(2) {
            entries += f5(0, 0)
            entries += fs5(0, sentinel)
        }

        // window event (mobile-ish 750x500)
        ts += ri(5, 20)
        entries += f6(ts, ei, 750, 500)
        entries += fs6(ei, 750, 500, sentinel)
        ei++

        // three uneven scrolls
        repeat(3) {
            ts += ri(80, 600)
            entries += f5(ts, ei)
            entries += fs5(ei, sentinel)
            ei++
        }

        // bezier mouse path
        val path = bezierPath(startX, startY, endX, endY, points)
        var si = 0
        for (i in path.indices) {
            val (x, y) = path[i]
            ts += ri(8, 50)
            entries += f3(ts, si, Math.round(x).toInt(), Math.round(y).toInt())
            entries += fs3(si, Math.round(x).toInt(), Math.round(y).toInt(), sentinel)
            if (i > 0 && i % 12 == 0) {
                ts += ri(20, 60)
                entries += f5(ts, ei)
                entries += fs5(ei, sentinel)
                ei++
            }
            si = (si + 1) % 2
        }

        // final micro-adjusted point
        ts += ri(5, 30)
        entries += f3(ts, 1, endX + ri(-5, 5), endY + ri(-5, 5))
        entries += fs3(1, endX, endY, sentinel)

        return entries.joinToString(":")
    }

    private fun bezierPath(sx: Int, sy: Int, ex: Int, ey: Int, n: Int): List<Pair<Double, Double>> {
        val c1x = sx + (ex - sx) * 0.3 + ri(-80, 80)
        val c1y = sy + (ey - sy) * 0.2 + ri(-60, 60)
        val c2x = sx + (ex - sx) * 0.7 + ri(-60, 60)
        val c2y = sy + (ey - sy) * 0.8 + ri(-40, 40)
        val pts = ArrayList<Pair<Double, Double>>(n + 1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            val u = 1 - t
            val x = u * u * u * sx + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * ex
            val y = u * u * u * sy + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ey
            val jitter = maxOf(0.5, 3 - t * 2.5)
            pts += (x + (Random.nextDouble() - 0.5) * jitter) to (y + (Random.nextDouble() - 0.5) * jitter)
        }
        return pts
    }

    private fun f3(t: Int, i: Int, x: Int, y: Int) = "3,$t,$i,$x,$y"
    private fun f5(t: Int, i: Int) = "5,$t,$i"
    private fun f6(t: Int, i: Int, x: Int, y: Int) = "6,$t,$i,$x,$y"
    private fun fs3(i: Int, x: Int, y: Int, sentinel: Long) = "3,$sentinel,$i,$x,$y"
    private fun fs5(i: Int, sentinel: Long) = "5,$sentinel,$i"
    private fun fs6(i: Int, x: Int, y: Int, sentinel: Long) = "6,$sentinel,$i,$x,$y"

    private fun ri(min: Int, max: Int): Int = Random.nextInt(min, max + 1)

    /** generateWebGLHash Node branch: a random uint64 in decimal. */
    internal fun randomWebglHash(): String {
        val bytes = ByteArray(8)
        Random.nextBytes(bytes)
        return BigInteger(1, bytes).toString()
    }
}
