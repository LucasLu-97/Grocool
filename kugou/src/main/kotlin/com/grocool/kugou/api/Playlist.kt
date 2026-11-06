package com.grocool.kugou.api

import com.grocool.kugou.EncryptType
import com.grocool.kugou.KugouClient
import com.grocool.kugou.KugouResponse
import com.grocool.kugou.RequestSpec

/**
 * User playlist reads, mirroring `module/user_playlist.js`,
 * `module/playlist_detail.js` and `module/playlist_track_all_new.js`.
 * All three go through the gateway with an x-router header - the encrypted
 * cloudlist protocol (createCloudRequest) is only used by the *write* modules
 * (sort/update/pic), which arrive in the Phase 4 batch.
 */
suspend fun KugouClient.userPlaylists(
    page: Int = 1,
    pagesize: Int = 30,
): KugouResponse {
    val userid = session.userid ?: 0L
    val token = session.token ?: ""
    return request(
        RequestSpec(
            url = "/v7/get_all_list",
            method = "POST",
            data = mapOf(
                "userid" to userid,
                "token" to token,
                "total_ver" to 979,
                "type" to 2,
                "page" to page,
                "pagesize" to pagesize,
            ),
            params = mapOf(
                "plat" to 1,
                "userid" to userid,
                "token" to token,
            ),
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
        ),
    )
}

suspend fun KugouClient.playlistDetail(globalCollectionIds: List<String>): KugouResponse {
    val userid = session.userid ?: 0L
    val token = session.token ?: ""
    return request(
        RequestSpec(
            url = "/v3/get_list_info",
            method = "POST",
            data = mapOf(
                "data" to globalCollectionIds.map { mapOf("global_collection_id" to it) },
                "userid" to userid,
                "token" to token,
            ),
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "pubsongs.kugou.com"),
        ),
    )
}

suspend fun KugouClient.playlistTrackAll(
    listid: String,
    page: Int = 1,
    pagesize: Int = 300,
    type: Int = 0,
): KugouResponse {
    val userid = session.userid ?: 0L
    val token = session.token ?: ""
    return request(
        RequestSpec(
            url = "/v4/get_list_all_file_v3",
            method = "POST",
            data = mapOf(
                "listid" to listid,
                "userid" to userid,
                "type" to type,
                "page" to page,
                "pagesize" to pagesize,
                "area_code" to 1,
                "allplatform" to 1,
                "show_cover" to 1,
                "token" to token,
            ),
            encryptType = EncryptType.ANDROID,
            headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
        ),
    )
}
