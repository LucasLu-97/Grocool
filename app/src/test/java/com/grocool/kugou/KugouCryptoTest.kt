package com.grocool.kugou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KugouCryptoTest {

    @Test
    fun md5KnownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", KugouCrypto.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", KugouCrypto.md5Hex(""))
        // UTF-8 multibyte, matching CryptoJS Utf8 handling
        assertEquals(32, KugouCrypto.md5Hex("酷狗概念版").length)
        assertTrue(KugouCrypto.md5Hex("酷狗概念版").matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun sha1KnownVector() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", KugouCrypto.sha1Hex("abc"))
    }

    @Test
    fun aesCbcRoundTrip() {
        val key = KugouCrypto.md5Hex("session").substring(0, 32) // AES-256, 32 bytes
        val iv = key.substring(key.length - 16)
        val plain = """{"list":["周杰伦","晴天"],"n":42}""".toByteArray(Charsets.UTF_8)
        val hex = KugouCrypto.aesEncryptCbcPkcs7Hex(plain, key, iv)
        val decrypted = KugouCrypto.aesDecryptCbcPkcs7Hex(hex, key, iv)
        assertEquals(String(plain, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun aesDerivedKeyRoundTrip() {
        val tempKey = "0f607264fc6318a92b9e13c65db7cd3c"
        val text = """{"clienttime":1770000000,"token":"abc"}"""
        val hex = KugouCrypto.aesEncryptDerivedHex(text.toByteArray(Charsets.UTF_8), tempKey)
        assertEquals(text, KugouCrypto.aesDecryptDerivedHex(hex, tempKey))
    }

    @Test
    fun playlistAesRoundTrip() {
        val session = "ab12cd"
        val json = """{"brand":"Redmi","uuid":"550e8400-e29b-41d4-a716-446655440000"}"""
        val cipher = KugouCrypto.playlistAesEncrypt(json, session)
        // The JS returns base64 ciphertext; round-trip must restore the JSON.
        assertEquals(json, KugouCrypto.playlistAesDecrypt(cipher, session))
    }

    @Test
    fun rsaPkcs1ProducesKeySizedHex() {
        val hex = KugouCrypto.rsaEncryptPkcs1Hex(
            """{"aes":"ab12cd","uid":123,"token":"t"}""".toByteArray(Charsets.UTF_8),
            KugouConfig.LITE_PUBLIC_KEY_PEM,
        )
        assertEquals(256, hex.length) // 1024-bit modulus -> 128 bytes -> 256 hex chars
        assertTrue(hex.matches(Regex("[0-9a-f]{256}")))
    }

    @Test
    fun rsaRawIsDeterministicAndKeySized() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val first = KugouCrypto.rsaRawEncryptHex(payload, KugouConfig.MAIN_PUBLIC_KEY_PEM)
        val second = KugouCrypto.rsaRawEncryptHex(payload, KugouConfig.MAIN_PUBLIC_KEY_PEM)
        assertEquals(first, second, "textbook RSA must be deterministic")
        assertEquals(256, first.length)
    }
}
