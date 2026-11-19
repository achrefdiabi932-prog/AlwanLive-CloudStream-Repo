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
        parseJson<List<Channel>>(app.get(CATALOG_URL).text)

    private fun Channel.response(): SearchResponse {
        val data = Payload(id, title, logo, servers).toJson()
        return newLiveSearchResponse(title, data, TvType.Live, true) { posterUrl = logo }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse("قنوات Alwan Live", channels().map { it.response() }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return channels().filter { it.title.contains(query, ignoreCase = true) }.map { it.response() }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = parseJson<Payload>(url)
        return newLiveStreamLoadResponse(item.title, url, url) { posterUrl = item.logo }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = parseJson<Payload>(data)
        var count = 0
        // Prefer streams whose URL advertises a higher resolution. Keep unknown
        // variants available, and move explicitly low-resolution streams last.
        fun qualityRank(url: String): Int {
            val value = url.lowercase()
            return when {
                "2160" in value || "4k" in value -> 100
                "1080" in value -> 90
                "720" in value -> 80
                "hd" in value || "high" in value -> 70
                "360" in value || "240" in value -> 10
                else -> 50
            }
        }
        item.servers
            .sortedByDescending { qualityRank(it.url) }
            .forEachIndexed { index, server ->
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
