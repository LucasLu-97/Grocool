package com.grocool.kugou

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thin wrappers over the JDK crypto primitives, matching the exact behaviour
 * of KuGouMusicApi's `util/crypto.js`:
 *
 *  - MD5/SHA1 over UTF-8 bytes, lowercase hex output.
 *  - AES-CBC/PKCS7 where the key and IV are the **UTF-8 bytes of the given
 *    strings** (CryptoJS `Utf8.parse`). A 32-char key is AES-256.
 *  - `rsaEncryptPkcs1` = forge `RSAES-PKCS1-V1_5`, hex output.
 *  - `rsaRawEncrypt` = the textbook modPow used by `cryptoRSAEncrypt`
 *    (no padding scheme, input zero-padded to the key length).
 *  - `playlistAes*` = the 6-char session-string playlist/cloudlist protocol,
 *    where the AES key is `md5(session)[0,16)` and IV is `md5(session)[16,32)`.
 */
object KugouCrypto {

    fun md5Hex(input: String): String = md5Hex(input.toByteArray(Charsets.UTF_8))

    fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).toHex()

    fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---------- AES ----------

    private fun aesKey(key: String): SecretKeySpec =
        SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")

    private fun aesIv(iv: String): IvParameterSpec =
        IvParameterSpec(iv.toByteArray(Charsets.UTF_8))

    fun aesEncryptCbcPkcs7Hex(plain: ByteArray, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // PKCS5 == PKCS7 on JVM
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(key), aesIv(iv))
        return cipher.doFinal(plain).toHex()
    }

    fun aesDecryptCbcPkcs7Hex(hex: String, key: String, iv: String): ByteArray =
        aesDecryptCbcPkcs7(hexToBytes(hex), key, iv)

    fun aesDecryptCbcPkcs7Base64(base64: String, key: String, iv: String): ByteArray =
        aesDecryptCbcPkcs7(Base64.getDecoder().decode(base64), key, iv)

    private fun aesDecryptCbcPkcs7(bytes: ByteArray, key: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(key), aesIv(iv))
        return cipher.doFinal(bytes)
    }

    /**
     * `cryptoAesEncrypt` with only a key given (the login flows):
     * key = md5(tempKey)[0,32), iv = that key's last 16 chars.
     */
    fun aesEncryptDerivedHex(plain: ByteArray, tempKey: String): String {
        val key = md5Hex(tempKey).substring(0, 32)
        val iv = key.substring(key.length - 16)
        return aesEncryptCbcPkcs7Hex(plain, key, iv)
    }

    fun aesDecryptDerivedHex(hex: String, tempKey: String): String {
        val key = md5Hex(tempKey).substring(0, 32)
        val iv = key.substring(key.length - 16)
        return String(aesDecryptCbcPkcs7Hex(hex, key, iv), Charsets.UTF_8)
    }

    // ---------- playlist / cloudlist session protocol ----------

    /** Random 6-char session string, like `playlistAesEncrypt` does. */
    fun newSessionString(): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString { repeat(6) { append(alphabet.random()) } }
    }

    /** Encrypts to base64 ciphertext, as `playlistAesEncrypt` returns `.str`. */
    fun playlistAesEncrypt(json: String, session: String): String {
        val md5 = md5Hex(session)
        val hex = aesEncryptCbcPkcs7Hex(
            json.toByteArray(Charsets.UTF_8),
            md5.substring(0, 16),
            md5.substring(16, 32),
        )
        return Base64.getEncoder().encodeToString(hexToBytes(hex))
    }

    fun playlistAesDecrypt(base64: String, session: String): String {
        val md5 = md5Hex(session)
        return String(
            aesDecryptCbcPkcs7Base64(base64, md5.substring(0, 16), md5.substring(16, 32)),
            Charsets.UTF_8,
        )
    }

    // ---------- RSA ----------

    private fun parsePublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    /** forge `key.encrypt(data, "RSAES-PKCS1-V1_5")` - standard PKCS#1 v1.5, hex. */
    fun rsaEncryptPkcs1Hex(plain: ByteArray, pem: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, parsePublicKey(pem))
        return cipher.doFinal(plain).toHex()
    }

    /**
     * `cryptoRSAEncrypt` - raw `m^e mod n` with the input zero-padded to the
     * modulus length. Deterministic (no padding scheme). Hex, zero-padded to
     * the key size, matching the JS `padStart(keyLength * 2, '0')`.
     */
    fun rsaRawEncryptHex(plain: ByteArray, pem: String): String {
        val key = parsePublicKey(pem)
        val modulus = (key as RSAPublicKey).modulus
        val exponent = key.publicExponent
        val keyLength = (modulus.bitLength() + 7) / 8
        require(plain.size <= keyLength) { "Data length exceeds key size" }
        val padded = ByteArray(keyLength)
        plain.copyInto(padded) // zero pad in front
        val message = BigInteger(1, padded)
        var hex = message.modPow(exponent, modulus).toString(16)
        if (hex.length < keyLength * 2) {
            hex = "0".repeat(keyLength * 2 - hex.length) + hex
        }
        return hex
    }
}
