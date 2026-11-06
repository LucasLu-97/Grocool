package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `module/privilege_lite.js` - per-track quality/VIP entitlement lookup
 * (x-router media.store.kugou.com), plus the upstream normalisation rules
 * documented there:
 *
 *  - relate_goods levels: 2=128, 4=320, 5=flac, 6=Hi-Res, 7=DSD
 *  - privilege 0 = free, non-zero = VIP/paid (paid songs observed at 10/pay_type 3)
 *  - viper_tape / viper_clear / viper_atmos need the SUPER VIP tier; concept
 *    VIP accounts cannot fetch them
 *  - 128/320 are served as ogg on Concept (need_ogg), with hash/size in
 *    trans_param.ogg_128_* / ogg_320_*
 *  - Concept "free listen": 128 stays obtainable for non-VIP even on paid songs
 */
@Serializable
data class GoodsInfo(
    val bitrate: Int? = 0,
    val filesize: Long? = 0,
    val extname: String? = "",
)

@Serializable
data class RelateGoodsItem(
    val level: Int? = 0,
    val quality: String? = "",
    val privilege: Int? = -1,
    val status: Int? = 0,
    val pay_type: Int? = -1,
    val price: Int? = 0,
    val fail_process: Int? = 0,
    val hash: String? = "",
    val info: GoodsInfo? = null,
    @SerialName("_msg") val msg: String? = "",
)

@Serializable
data class TransParam(
    @SerialName("ogg_128_hash") val ogg128Hash: String? = "",
    @SerialName("ogg_128_filesize") val ogg128Filesize: Long? = 0,
    @SerialName("ogg_320_hash") val ogg320Hash: String? = "",
    @SerialName("ogg_320_filesize") val ogg320Filesize: Long? = 0,
)

@Serializable
data class PrivilegeResource(
    val relate_goods: List<RelateGoodsItem> = emptyList(),
    val trans_param: TransParam? = null,
    val name: String? = "",
    val singername: String? = "",
    val albumname: String? = "",
    val hash: String? = "",
    val album_id: JsonElement? = null,
    val album_audio_id: JsonElement? = null,
)

data class QualityItem(
    val quality: String,
    val exists: Boolean,
    val level: Int,
    val bitrate: Int,
    val extname: String,
    val hash: String,
    val size: Long,
    val sizeText: String,
    val privilege: Int,
    val status: Int,
    val payType: Int,
    val price: Int,
    val failProcess: Int,
    val needVip: Boolean,
    val obtainable: Boolean,
    val freeListen: Boolean,
    val requireVipType: String,
    val msg: String,
)

data class QualityVip(
    val hash: String,
    val albumId: String,
    val albumAudioId: String,
    val name: String,
    val singerName: String,
    val albumName: String,
    val freeSong: Boolean,
    val freeListen: Boolean,
    val qualities: List<QualityItem>,
    val freeQualities: List<String>,
    val vipQualities: List<String>,
    val superVipQualities: List<String>,
    val blockedQualities: List<String>,
    val missingQualities: List<String>,
    val bestFreeQuality: String,
)

/** Ordered low -> high, used to break ties between same-level special tiers. */
private val QUALITY_ORDER = listOf(
    "128", "320", "flac", "high", "super", "viper_clear", "viper_tape", "viper_atmos", "multitrack",
)

/** Tiers that require the SUPER VIP (??? VIP cannot play them). */
private val QUALITY_VIP_TYPE = mapOf(
    "viper_tape" to "suvip",
    "viper_clear" to "suvip",
    "viper_atmos" to "suvip",
)

/** The full ladder the upstream request carries; the output is filtered after. */
private val UPSTREAM_QUALITIES = listOf(
    "128", "320", "flac", "high", "viper_atmos", "viper_tape", "viper_clear", "super", "multitrack",
)

/** relate_goods level(s) picking the entry Concept actually uses per tier. */
private val QUALITY_LEVEL = mapOf(
    "128" to listOf(1, 2), // 1 = the ogg rendition Concept plays, 2 = mp3
    "320" to listOf(4),
    "flac" to listOf(5),
    "high" to listOf(6),
    "super" to listOf(7),
)

private val DEFAULT_QUALITIES = listOf("128", "320", "flac", "high", "viper_tape", "viper_clear", "viper_atmos")

private val parser = Json { ignoreUnknownKeys = true }

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1024L * 1024 * 1024 -> "%.1fG".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1fM".format(bytes / 1024.0 / 1024)
    else -> "%.1fK".format(bytes / 1024.0)
}

private fun pickRelate(relateGoods: List<RelateGoodsItem>, quality: String): RelateGoodsItem? {
    for (level in QUALITY_LEVEL[quality].orEmpty()) {
        relateGoods.firstOrNull { it.level == level && it.quality == quality }?.let { return it }
    }
    return relateGoods.firstOrNull { it.quality == quality }
}

private data class Ogg(val hash: String?, val size: Long?)

private fun oggOf(quality: String, resource: PrivilegeResource): Ogg {
    val trans = resource.trans_param ?: return Ogg(null, null)
    return when (quality) {
        "128" -> Ogg(trans.ogg128Hash, trans.ogg128Filesize)
        "320" -> Ogg(trans.ogg320Hash, trans.ogg320Filesize)
        else -> Ogg(null, null)
    }
}

internal fun buildQualityVip(
    hash: String,
    albumId: String,
    albumAudioId: String,
    resource: PrivilegeResource,
    qualities: List<String>,
    isLite: Boolean,
): QualityVip {
    val relateGoods = resource.relate_goods

    val list = qualities.map { quality ->
        val relate = pickRelate(relateGoods, quality)
        val exists = relate != null
        val needVip = exists && (relate!!.privilege ?: 0) != 0
        val freeListen = isLite && quality == "128" && needVip
        val obtainable = freeListen ||
            (exists && !needVip && (relate!!.status ?: 0) != 0)
        val ogg = oggOf(quality, resource)
        // JS `||` semantics: empty string and 0 are falsy and fall through to
        // the relate_goods values; Kotlin `?:` alone only skips nulls.
        val oggHash = ogg.hash?.takeIf { it.isNotBlank() }
        val oggSize = ogg.size?.takeIf { it != 0L }
        val size = if (exists) {
            oggSize ?: relate!!.info?.filesize ?: 0L
        } else 0L
        QualityItem(
            quality = quality,
            exists = exists,
            level = relate?.level ?: 0,
            bitrate = relate?.info?.bitrate ?: 0,
            extname = if (exists) {
                if (oggHash != null) "ogg" else relate!!.info?.extname ?: ""
            } else "",
            hash = if (exists) oggHash ?: relate!!.hash ?: "" else "",
            size = size,
            sizeText = if (exists) formatSize(size) else "",
            privilege = relate?.privilege ?: -1,
            status = relate?.status ?: 0,
            payType = relate?.pay_type ?: -1,
            price = relate?.price ?: 0,
            failProcess = relate?.fail_process ?: 0,
            needVip = needVip,
            obtainable = obtainable,
            freeListen = freeListen,
            requireVipType = if (exists && !obtainable && needVip) {
                QUALITY_VIP_TYPE[quality] ?: "concept"
            } else "",
            msg = relate?.msg ?: "",
        )
    }

    val freeQualities = list.filter { it.obtainable }.map { it.quality }
    val vipQualities = list.filter { it.exists && it.needVip && !it.obtainable }.map { it.quality }
    val blockedQualities = list.filter { it.exists && !it.obtainable && !it.needVip }.map { it.quality }
    val missingQualities = list.filter { !it.exists }.map { it.quality }
    val superVipQualities = list.filter { it.requireVipType == "suvip" }.map { it.quality }
    val freeSong = list.isNotEmpty() && list.none { it.needVip }
    val bestFree = list.filter { it.obtainable }
        .sortedWith(
            compareBy({ it.level }, { QUALITY_ORDER.indexOf(it.quality) }),
        )
        .lastOrNull()

    return QualityVip(
        hash = hash,
        albumId = jsonScalarOr(resource.album_id, albumId),
        albumAudioId = jsonScalarOr(resource.album_audio_id, albumAudioId),
        name = resource.name ?: "",
        singerName = resource.singername ?: "",
        albumName = resource.albumname ?: "",
        freeSong = freeSong,
        freeListen = list.any { it.freeListen },
        qualities = list,
        freeQualities = freeQualities,
        vipQualities = vipQualities,
        superVipQualities = superVipQualities,
        blockedQualities = blockedQualities,
        missingQualities = missingQualities,
        bestFreeQuality = bestFree?.quality ?: "",
    )
}

private fun jsonScalarOr(element: JsonElement?, fallback: String): String {
    val value = element?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    return value?.takeIf { it.isNotBlank() } ?: fallback
}

suspend fun KugouClient.privilegeLite(
    hashes: List<String>,
    albumIds: List<Long?> = emptyList(),
    qualities: List<String> = DEFAULT_QUALITIES,
): List<QualityVip> {
    if (hashes.isEmpty()) throw IllegalArgumentException("no track hashes given")

    val resource = hashes.mapIndexed { i, hash ->
        mapOf(
            "type" to "audio",
            "page_id" to 0,
            "hash" to hash.trim().lowercase(),
            "album_id" to (albumIds.getOrNull(i) ?: 0L),
        )
    }
    val data = LinkedHashMap<String, Any>()
    // appid/clientver are the JS-side static config values (not platform-aware)
    data["appid"] = 1005
    data["area_code"] = 1
    data["behavior"] = "play"
    data["clientver"] = 20489
    data["need_hash_offset"] = 1
    data["relate"] = 1
    data["support_verify"] = 1
    data["resource"] = resource
    data["qualities"] = UPSTREAM_QUALITIES

    val response: KugouResponse = request(
        RequestSpec(
            url = "/v2/get_res_privilege/lite",
            method = "POST",
            data = data,
            encryptType = EncryptType.ANDROID,
            headers = mapOf(
                "x-router" to "media.store.kugou.com",
                "Content-Type" to "application/json",
            ),
        ),
    )

    val body = response.body as? JsonObject ?: return hashes.map { emptyResult(it) }
    val array = body["data"] as? JsonArray ?: return hashes.map { emptyResult(it) }
    val parsed = array.mapNotNull { runCatching { parser.decodeFromJsonElement(PrivilegeResource.serializer(), it.jsonObject) }.getOrNull() }

    return hashes.mapIndexed { i, hash ->
        val normalized = hash.trim().lowercase()
        val item = parsed.firstOrNull { it.hash?.lowercase() == normalized }
            ?: parsed.getOrNull(i)
            ?: PrivilegeResource()
        buildQualityVip(
            hash = normalized,
            albumId = (albumIds.getOrNull(i) ?: 0L).toString(),
            albumAudioId = "",
            resource = item,
            qualities = qualities,
            isLite = config.isLite,
        )
    }
}

private fun emptyResult(hash: String): QualityVip = buildQualityVip(
    hash = hash,
    albumId = "0",
    albumAudioId = "",
    resource = PrivilegeResource(),
    qualities = DEFAULT_QUALITIES,
    isLite = true,
)
