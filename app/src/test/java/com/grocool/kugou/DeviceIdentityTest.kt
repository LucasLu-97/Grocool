package com.grocool.kugou

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceIdentityTest {

    @Test
    fun calculateMidMatchesBigIntegerOfMd5Hex() {
        // md5("abc") = 900150983cd24fb0d6963f7d28e17f72, a well-known vector.
        val expected = BigInteger("900150983cd24fb0d6963f7d28e17f72", 16).toString()
        assertEquals(expected, DeviceIdentity.calculateMid("abc"))
    }

    @Test
    fun midIsPlainDecimalString() {
        val mid = DeviceIdentity.calculateMid(DeviceIdentity.newGuid())
        assertTrue(mid.matches(Regex("\\d+")))
        assertTrue(mid.length in 30..40, "2^128 renders as 30-40 decimal digits")
    }

    @Test
    fun guidLooksLikeUuidV4() {
        val guid = DeviceIdentity.newGuid()
        assertTrue(
            guid.matches(
                Regex(
                    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                ),
            ),
            "expected a UUID v4 shape, got $guid",
        )
    }

    @Test
    fun randomStringsUseTheKuGouAlphabet() {
        val s = DeviceIdentity.randomString(24)
        assertEquals(24, s.length)
        assertTrue(s.all { DeviceIdentity.KEY_STRING.contains(it) })
        val n = DeviceIdentity.randomNumber(10)
        assertEquals(10, n.length)
        assertTrue(n.all { it in '0'..'9' })
    }
}
