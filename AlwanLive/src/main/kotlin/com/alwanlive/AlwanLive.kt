package com.alwanlive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

private const val CATALOG_URL = "https://raw.githubusercontent.com/achrefdiabi932-prog/AlwanLive-CloudStream-Repo/main/channels.json"

private data class Server(val name: String, val url: String, val referer: String? = null, val userAgent: String? = null)
private data class Channel(val id: Int, val title: String, val logo: String? = null, val servers: List<Server> = emptyList())
private data class Payload(val id: Int, val title: String, val logo: String?, val servers: List<Server>)

class AlwanLive : MainAPI() {
    override var name = "Alwan Live"
    override var mainUrl = "https://alwanlive.stream"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("channels" to "قنوات Alwan Live")

    private suspend fun channels(): List<Channel> =
        app.get(CATALOG_URL).text.parseJson<List<Channel>>()

    private fun Channel.response(): SearchResponse {
        val data = Payload(id, title, logo, servers).toJson()
        return newLiveSearchResponse(title, data, TvType.Live, true) { posterUrl = logo }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(channels().map { it.response() }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return channels().filter { it.title.contains(query, ignoreCase = true) }.map { it.response() }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = url.parseJson<Payload>()
        return newLiveStreamLoadResponse(item.title, url, url) { posterUrl = item.logo }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = data.parseJson<Payload>()
        var count = 0
        item.servers.forEachIndexed { index, server ->
            val stream = server.url.trim()
            if (!stream.startsWith("https://") && !stream.startsWith("http://")) return@forEachIndexed
            callback.invoke(newExtractorLink(
                source = name,
                name = "${item.title} • ${server.name.ifBlank { "SERVER ${index + 1}" }}",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                server.referer?.let { referer = it }
                server.userAgent?.let { headers = mapOf("User-Agent" to it) }
                quality = Qualities.Unknown.value
            })
            count++
        }
        return count > 0
    }
}
