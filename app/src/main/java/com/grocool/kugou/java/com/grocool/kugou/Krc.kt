package com.grocool.kugou

import java.util.Base64
import java.util.zip.Inflater

/**
 * KuGou KRC (word-by-word) lyric decryption, mirroring `decodeLyrics` in
 * `util/util.js`: base64 -> skip 4 header bytes -> XOR with the fixed 16-byte
 * key -> zlib inflate.
 */
object Krc {

    private val XOR_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110.toByte(), 105.toByte(),
    )

    fun decode(base64: String): String = decode(Base64.getDecoder().decode(base64))

    fun decode(bytes: ByteArray): String {
        require(bytes.size > 4) { "KRC payload too short" }
        val krc = bytes.copyOfRange(4, bytes.size)
        for (i in krc.indices) {
            krc[i] = (krc[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return inflateZlib(krc)
    }

    private fun inflateZlib(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = ByteArray(1 shl 19)
        val out = StringBuilder()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(output)
                if (n == 0 && inflater.needsInput()) break
                out.append(String(output, 0, n, Charsets.UTF_8))
            }
        } finally {
            inflater.end()
        }
        return out.toString()
    }
}
