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

        data class Variant(val url: String, val height: Int)

        item.servers.forEachIndexed { index, server ->
            val stream = server.url.trim()
            if (!stream.startsWith("https://") && !stream.startsWith("http://")) return@forEachIndexed

            val headers = buildMap {
                server.userAgent?.let { put("User-Agent", it) }
                server.referer?.let { put("Referer", it) }
            }

            // Read the HLS master playlist and extract its actual variant resolutions.
            val playlist = runCatching {
                app.get(stream, headers = headers).text
            }.getOrNull()

            val variants = mutableListOf<Variant>()
            if (!playlist.isNullOrBlank()) {
                val lines = playlist.lines()
                for (lineIndex in lines.indices) {
                    val line = lines[lineIndex].trim()
                    if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue

                    val resolution = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                    val uriLine = lines.drop(lineIndex + 1)
                        .firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                        ?.trim() ?: continue
                    val variantUrl = runCatching {
                        java.net.URI(stream).resolve(uriLine).toString()
                    }.getOrDefault(uriLine)

                    variants.add(Variant(variantUrl, resolution))
                }
            }

            val selected = if (variants.isNotEmpty()) {
                variants.sortedByDescending { it.height }
            } else {
                listOf(Variant(stream, 0))
            }

            selected.forEach { variant ->
                callback.invoke(newExtractorLink(
                    source = name,
                    name = buildString {
                        append(item.title)
                        append(" • ")
                        append(server.name.ifBlank { "SERVER ${index + 1}" })
                        if (variant.height > 0) append(" • ${variant.height}p")
                    },
                    url = variant.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    server.referer?.let { referer = it }
                    if (headers.isNotEmpty()) this.headers = headers
                    quality = variant.height
                })
                count++
            }
        }
        return count > 0
    }
}
