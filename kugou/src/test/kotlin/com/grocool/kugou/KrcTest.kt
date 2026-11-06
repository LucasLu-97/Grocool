package com.grocool.kugou

import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals

class KrcTest {

    /** Builds a KRC payload exactly the way the KuGou client would. */
    private fun buildKrcBase64(text: String): String {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val buffer = ByteArray(1 shl 20)
        val n = deflater.deflate(buffer)
        val zlibData = buffer.copyOf(n)
        val xored = ByteArray(zlibData.size)
        for (i in zlibData.indices) {
            xored[i] = (zlibData[i].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
        }
        val krc = ByteArray(4 + xored.size)
        byteArrayOf('k'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
            .copyInto(krc)
        xored.copyInto(krc, 4)
        return Base64.getEncoder().encodeToString(krc)
    }

    @Test
    fun decodeRestoresWordTimedLyrics() {
        val lyric = "[id:0][ti:??][offset:0]\n[00:12.30]<00:12.30,500,0>?? <00:12.80,600,0>? <00:13.40,900,0>?\n"
        assertEquals(lyric, Krc.decode(buildKrcBase64(lyric)))
    }

    @Test
    fun decodeAcceptsRawBytes() {
        val lyric = "[00:01.00]<00:01.00,100,0>?<00:01.10,100,0>?"
        val bytes = Base64.getDecoder().decode(buildKrcBase64(lyric))
        assertEquals(lyric, Krc.decode(bytes))
    }

    companion object {
        // Same values as Krc's private key; duplicated only to build fixtures.
        private val KRC_KEY = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110.toByte(), 105.toByte(),
        )
    }
}
