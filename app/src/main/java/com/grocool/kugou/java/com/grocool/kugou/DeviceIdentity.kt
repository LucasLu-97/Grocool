package com.grocool.kugou

import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/**
 * Device identity helpers, mirroring `util/util.js`:
 *  - `randomString` — the uppercase alphanumeric pool JS uses.
 *  - `randomNumber` — digits only.
 *  - `newGuid` — UUID v4.
 *  - `calculateMid` — the device MID: the MD5 hex of the input read as one
 *    big hexadecimal number, rendered in decimal. The JS uses `big-integer`
 *    for the base conversion; `BigInteger(hex, 16)` is the exact equivalent.
 */
object DeviceIdentity {

    const val KEY_STRING = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun randomString(length: Int = 16): String =
        buildString { repeat(length) { append(KEY_STRING.random()) } }

    fun randomNumber(length: Int = 16): String =
        buildString { repeat(length) { append("1234567890".random()) } }

    fun newGuid(): String = UUID.randomUUID().toString()

    fun calculateMid(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return BigInteger(digest, 16).toString()
    }
}
