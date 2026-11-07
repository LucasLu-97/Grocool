package com.grocool.kugou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KugouSignTest {

    private val salt = KugouConfig.ANDROID_SALT

    @Test
    fun sortedParamStringMatchesJsConcatenation() {
        val params = linkedMapOf<String, Any?>("b" to 2, "a" to 1, "c" to "x")
        // JS: Object.keys().sort() -> a,b,c; join "a=1b=2c=x"
        assertEquals("a=1b=2c=x", KugouSign.sortedParamString(params))
        // signParams shape: key+value with no '='
        assertEquals("a1b2cx", KugouSign.sortedKeyedValueString(params))
        // register shape: values only, sorted
        assertEquals("12x", KugouSign.sortedValueString(params))
    }

    @Test
    fun nestedObjectValuesStringifyAsCompactJson() {
        val params = mapOf<String, Any?>(
            "resource" to mapOf("hash" to "ABC", "type" to "audio"),
            "a" to 1,
        )
        val s = KugouSign.sortedParamString(params)
        // sorted keys: a, resource -> a=1 + resource={"hash":"ABC","type":"audio"}
        assertEquals("""a=1resource={"hash":"ABC","type":"audio"}""", s)
    }

    @Test
    fun signatureAndroidIsDeterministicMd5() {
        val params = mapOf<String, Any?>("plat" to 4, "appid" to 1001)
        val data = ""
        val expected = KugouCrypto.md5Hex(salt + "appid=1001plat=4" + data + salt)
        assertEquals(expected, KugouSign.signatureAndroidParams(params, data, salt))
    }

    @Test
    fun signatureAndroidWithByteArrayBodyHashesBytes() {
        val params = mapOf<String, Any?>("appid" to 1005)
        val body = "encrypted-bytes".toByteArray(Charsets.UTF_8)
        val expected = KugouCrypto.md5Hex(
            (salt + "appid=1005").toByteArray(Charsets.UTF_8) + body +
                salt.toByteArray(Charsets.UTF_8),
        )
        assertEquals(expected, KugouSign.signatureAndroidParams(params, body, salt))
    }

    @Test
    fun signKeyFormula() {
        val expected = KugouCrypto.md5Hex(
            "HASH${KugouConfig.LITE_SIGNKEY_SALT}3116MID123",
        )
        assertEquals(
            expected,
            KugouSign.signKey("HASH", "MID", 123, 3116, KugouConfig.LITE_SIGNKEY_SALT),
        )
    }

    @Test
    fun signParamsKeyFormula() {
        val expected = KugouCrypto.md5Hex(
            "3116${KugouConfig.LITE_SALT}11440data",
        )
        assertEquals(
            expected,
            KugouSign.signParamsKey("data", 3116, 11440, KugouConfig.LITE_SALT),
        )
    }

    @Test
    fun platformSaltsDiffer() {
        val main = KugouConfig(KugouPlatform.MAIN)
        val lite = KugouConfig(KugouPlatform.LITE)
        assertNotEquals(main.androidSignSalt, lite.androidSignSalt)
        assertNotEquals(main.signKeySalt, lite.signKeySalt)
        assertNotEquals(main.rsaPublicKeyPem, lite.rsaPublicKeyPem)
        assertEquals(1005, main.appid)
        assertEquals(3116, lite.appid)
        assertEquals(20489, main.clientver)
        assertEquals(11440, lite.clientver)
        assertTrue(main.rsaPublicKeyPem.contains("BEGIN PUBLIC KEY"))
        assertTrue(lite.rsaPublicKeyPem.contains("BEGIN PUBLIC KEY"))
    }
}
