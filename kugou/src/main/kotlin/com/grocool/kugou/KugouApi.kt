package com.grocool.kugou

import com.grocool.kugou.api.searchComplex
import com.grocool.kugou.api.songUrlAuthMerge

/**
 * Class-front door to the module's top-level extension functions.
 *
 * The Android app's Kotlin compile resolves *class* imports from this jar
 * but fails to resolve top-level *function* imports from it (seen live on
 * CI: `import com.grocool.kugou.KugouClient` is fine while
 * `import com.grocool.kugou.api.songUrlAuthMerge` reports "Unresolved
 * reference 'grocool'", against a jar the dump shows is complete and
 * classpath-present). Importing an object and calling members on it uses the
 * same resolution path as the class imports that do work, so the Android
 * side goes through this facade instead.
 */
object KugouApi {

    suspend fun search(
        client: KugouClient,
        keywords: String,
        page: Int = 1,
        pagesize: Int = 30,
    ) = client.searchComplex(keywords, page, pagesize)

    /** The full authorized stream chain: user_verify -> song_auth -> URL. */
    suspend fun streamUrl(
        client: KugouClient,
        hash: String,
        quality: String = "128",
        albumId: Long? = null,
        albumAudioId: Long? = null,
    ) = client.songUrlAuthMerge(hash, quality, albumId, albumAudioId)
}
