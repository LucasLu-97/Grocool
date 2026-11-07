package com.grocool.kugou.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivilegeLiteTest {

    private fun freeGoods(quality: String, level: Int) =
        RelateGoodsItem(level = level, quality = quality, privilege = 0, status = 1, hash = "H$quality")

    private fun vipGoods(quality: String, level: Int) =
        RelateGoodsItem(level = level, quality = quality, privilege = 10, status = 1, pay_type = 3, hash = "H$quality")

    private val resource = PrivilegeResource(
        name = "晴天",
        singername = "周杰伦",
        albumname = "叶惠美",
        hash = "abc123",
        relate_goods = listOf(
            freeGoods("128", 2),
            freeGoods("320", 4),
            vipGoods("flac", 5),
            vipGoods("viper_atmos", 0),
        ),
        trans_param = TransParam(ogg128Hash = "OGG128", ogg128Filesize = 3145728),
    )

    @Test
    fun normalisationSplitsFreeVipAndSuperVipTiers() {
        val v = buildQualityVip(
            "abc123", "123", "456", resource,
            listOf("128", "320", "flac", "high", "viper_tape", "viper_clear", "viper_atmos"),
            isLite = true,
        )
        assertEquals(listOf("128", "320"), v.freeQualities)
        // superVipQualities is a *subset* of vipQualities on the JS side:
        // every VIP-blocked tier lands in vipQualities, the suvip ones again
        // in superVipQualities.
        assertEquals(listOf("flac", "viper_atmos"), v.vipQualities)
        assertEquals(listOf("viper_atmos"), v.superVipQualities)
        assertEquals(listOf("high", "viper_tape", "viper_clear"), v.missingQualities)
        assertEquals("320", v.bestFreeQuality)
        assertEquals("晴天", v.name)
        assertTrue(!v.freeSong)
    }

    @Test
    fun oggRenditionWinsForConceptPlayback() {
        val v = buildQualityVip("abc123", "123", "456", resource, listOf("128", "320"), isLite = true)
        val q128 = v.qualities.first { it.quality == "128" }
        assertEquals("ogg", q128.extname)
        assertEquals("OGG128", q128.hash)
        assertEquals("3.0M", q128.sizeText)
        assertEquals(3145728L, q128.size)
        val q320 = v.qualities.first { it.quality == "320" }
        assertEquals("H320", q320.hash, "no ogg for 320 here -> relate hash")
    }

    @Test
    fun conceptFreeListenKeeps128ObtainableOnPaidTracks() {
        val paid = PrivilegeResource(
            relate_goods = listOf(vipGoods("128", 2)),
        )
        val lite = buildQualityVip("h", "1", "", paid, listOf("128"), isLite = true)
        assertTrue(lite.qualities.first().freeListen)
        assertTrue(lite.qualities.first().obtainable)
        assertTrue(lite.freeListen)

        val main = buildQualityVip("h", "1", "", paid, listOf("128"), isLite = false)
        assertTrue(!main.qualities.first().obtainable)
        assertEquals("concept", main.qualities.first().requireVipType)
    }

    @Test
    fun suvipTiersAreMarkedForBlockedViperQualities() {
        val v = buildQualityVip(
            "h", "1", "",
            PrivilegeResource(relate_goods = listOf(vipGoods("viper_tape", 0))),
            listOf("viper_tape"),
            isLite = true,
        )
        val item = v.qualities.first()
        assertEquals("suvip", item.requireVipType)
        assertEquals(listOf("viper_tape"), v.superVipQualities)
    }
}
