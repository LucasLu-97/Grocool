package com.grocool.kugou

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SsaTest {

    @Test
    fun dataEncodingCarriesWindowScrollAndMouseEvents() {
        val sentinel = 0xffffffffL - 3
        val data = Ssa.buildData(300, 300, 600, 100, 30, sentinel)
        val entries = data.split(':')
        // initial zero events + window + 3 scrolls + mouse path
        assertEquals("5,0,0", entries.first(), "first entry is a zero type-5 event")
        assertTrue(entries.any { it.startsWith("6,") && it.endsWith(",750,500") }, "window event 750x500")
        assertTrue(entries.count { it.startsWith("3,") } > 30, "mouse events for every path point")
        assertTrue(entries.any { it.startsWith("3,$sentinel,") }, "sentinel records present")
        // every entry: type,dt then 1-3 numeric fields, dt is sentinel for marks;
        // coordinates may go slightly negative on the final micro-adjust point
        assertTrue(
            entries.all { it.matches(Regex("[356](,-?\\d+){2,4}")) },
            "entry shapes: 5,dt,i / 6,dt,i,w,h / 3,dt,i,x,y",
        )
    }

    @Test
    fun plaintextFollowsTheUpstreamLayout() {
        val p = Ssa.plaintext("MID", 42, "DFID", "W", 1700000000000, "3,0,0,1,2")
        assertEquals("mid=MID;userid=42;dfid=DFID;webgl=W;webdriver=0;ts=1700000000000;data=3,0,0,1,2", p)
    }

    @Test
    fun tokensAreWellFormedAndRandomised() {
        val a = Ssa.generate("MID", 42, "DFID")
        val b = Ssa.generate("MID", 42, "DFID")
        assertNotEquals(a.edt, b.edt, "random key/behaviour must differ per call")
        // SID: RSA-2048 -> 256 bytes -> base64 length 344 with '==' padding
        assertEquals(344, a.sid.length)
        assertTrue(a.sid.endsWith("=="), "256 % 3 == 1 -> '==' padding")
        // EDT: base64 of an AES-CBC ciphertext, multiple of 16 bytes
        val edtBytes = Base64.getDecoder().decode(a.edt)
        assertEquals(0, edtBytes.size % 16)
        assertTrue(edtBytes.size > 100, "behaviour payload is never tiny")
    }

    @Test
    fun webglHashIsDecimalUint64() {
        repeat(10) {
            val h = Ssa.randomWebglHash()
            assertTrue(h.matches(Regex("\\d{1,20}")))
        }
    }
}
