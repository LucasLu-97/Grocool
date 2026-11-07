package com.music.bitchord.data.sources

import com.music.bitchord.data.kugou.KugouRuntime
import com.grocool.kugou.api.songUrlAuthMerge
import com.grocool.kugou.api.searchComplex
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "BitChord"

/**
 * The KuGou Concept source — Grocool's core catalogue — plugged into the
 * chassis' standard [MusicSource] slot beside JioSaavn.
 *
 * `search` runs the v6 combined search and maps the song section into [Song]s
 * tagged with this source's id; `stream` walks the authorized chain
 * (user_verify -> song_auth -> /tracker/v5/url, verified live against the
 * Concept backend with a scanned VIP login: 128/320/flac all return CDN URLs)
 * and maps the tier the resolver asked for onto the Concept quality ladder.
 *
 * Track ids carry `hash|albumAudioId`, because the stream call needs both.
 * Unsigned-in searches still work; a stream without a login is a miss, not an
 * error — the resolver steps over this source to YouTube exactly like any
 * other that cannot serve a track.
 */
class KugouSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.KUGOU
    override val displayName: String get() = config.label.ifBlank { kind.label }

    override suspend fun health(): SourceHealth {
        if (KugouRuntime.clientOrNull() == null) {
            return SourceHealth.Unreachable("runtime not initialised")
        }
        return if (KugouRuntime.isSignedIn) {
            SourceHealth.Ok("已登录概念版账号")
        } else {
            // Searchable without a login; streams need one. Unreachable would
            // grey the row and suggest the server is down, which it isn't.
            SourceHealth.Ok("未登录 — 搜索可用，播放需扫码登录")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val client = KugouRuntime.clientOrNull() ?: return emptyList()
        val response = runCatching {
            client.searchComplex(query, pagesize = limit.coerceIn(1, 50)).ensureOk()
        }.getOrNull() ?: return emptyList()

        val data = (response.body as? JsonObject)?.get("data") as? JsonObject
        val sections = data?.get("lists") as? JsonArray
        val songSection = sections?.firstOrNull { section ->
            ((section as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNullSafe() == "song"
        }
        val songs = (songSection as? JsonObject)?.get("lists") as? JsonArray ?: return emptyList()

        return songs.take(limit).mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val hash = row.str("FileHash", "filehash", "hash") ?: return@mapNotNull null
            val audioid = row.str("Audioid", "AlbumAudioID", "album_audio_id")
            val rawName = row.str("FileName", "SongName", "name") ?: ""
            val (artist, title) = splitArtistTitle(rawName)
            Song(
                videoId = SourceRegistry.trackKey(configId, "$hash|$audioid"),
                title = title.ifBlank { rawName },
                artist = artist,
                thumbnailUrl = row.str("Image", "Img", "image"),
                durationText = row.str("SuperDuration", "Duration")?.toIntOrNull()
                    ?.let { seconds -> "%d:%02d".format(seconds / 60, seconds % 60) },
                albumName = row.str("AlbumName", "albumname"),
                albumId = row.str("AlbumID", "album_id"),
                // The Concept ladder reaches lossless (flac) for VIP accounts;
                // rows are ranked on this so lossless searches pick them first.
                sourceQuality = "LOSSLESS",
            )
        }
    }

    override suspend fun stream(
        trackId: String,
        request: StreamRequest,
    ): SourceStream? {
        val client = KugouRuntime.clientOrNull() ?: return null
        val parts = trackId.split('|', limit = 2)
        val hash = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val albumAudioId = parts.getOrNull(1)?.toLongOrNull()

        val quality = when (request) {
            is StreamRequest.Lossless -> "flac"
            is StreamRequest.Best -> "flac"
            is StreamRequest.Capped -> when {
                request.maxKbps <= 128 -> "128"
                request.maxKbps <= 320 -> "320"
                else -> "flac"
            }
        }

        val response = runCatching {
            client.songUrlAuthMerge(hash, quality, albumAudioId = albumAudioId).ensureOk()
        }.getOrNull() ?: return null

        val url = (((response.body as? JsonObject)?.get("url") as? JsonArray)
            ?.firstOrNull() as? JsonPrimitive)?.contentOrNullSafe()
            .takeUnless { it.isNullOrBlank() } ?: return null
        val durationSec = (((response.body as? JsonObject)?.get("timeLen") as? JsonPrimitive))
            ?.contentOrNullSafe()?.toIntOrNull()

        TrackLog.d(TAG, "✓ KuGou stream q=$quality ${url.take(80)}")
        return SourceStream(
            url = url,
            format = when (quality) {
                "128" -> StreamFormat(codec = "mp3", kbps = 128)
                "320" -> StreamFormat(codec = "mp3", kbps = 320)
                else -> StreamFormat(codec = "flac")
            },
            belowRequest = quality == "128" && request !is StreamRequest.Capped,
            durationSec = durationSec,
        )
    }

    private fun JsonObject.str(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
        (get(n) as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
    }

    private fun JsonPrimitive?.contentOrNullSafe(): String? =
        runCatching { this?.content }.getOrNull()

    /** KuGou filenames arrive as "artist - title". */
    private fun splitArtistTitle(raw: String): Pair<String, String> {
        val i = raw.indexOf(" - ")
        return if (i > 0) raw.substring(0, i).trim() to raw.substring(i + 3).trim()
        else "" to raw
    }
}
