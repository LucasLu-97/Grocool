package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec

/**
 * `module/search_complex.js` — the v6 combined search (songs / artists /
 * albums / playlists / videos in one response).
 */
suspend fun KugouClient.searchComplex(
    keywords: String,
    page: Int = 1,
    pagesize: Int = 30,
): KugouResponse = request(
    RequestSpec(
        url = "/v6/search/complex",
        method = "GET",
        baseURL = "https://complexsearch.kugou.com",
        params = mapOf(
            "platform" to "AndroidFilter",
            "keyword" to keywords,
            "page" to page,
            "pagesize" to pagesize,
            "cursor" to 0,
        ),
        encryptType = EncryptType.ANDROID,
    ),
)
