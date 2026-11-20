package com.majedkoora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private const val SITE = "https://majed-koora.live"

private data class MatchCard(
    val title: String,
    val url: String,
    val poster: String?
)

class MajedKoora : MainAPI() {
    override var name = "Majed Koora"
    override var mainUrl = SITE
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("today" to "مباريات اليوم")

    private fun parseToday(html: String): List<MatchCard> {
        val document = org.jsoup.Jsoup.parse(html, mainUrl)
        val seen = linkedSetOf<String>()
        val matches = mutableListOf<MatchCard>()

        // Match cards are identified conservatively from links that contain team artwork.
        // Keep the page URL as the item data; the details page is fetched when the user opens it.
        document.select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { return@forEach }
            if (!href.startsWith("https://") && !href.startsWith("http://")) return@forEach
            if (href.trimEnd('/') == mainUrl.trimEnd('/')) return@forEach

            val images = anchor.select("img")
            if (images.size < 2) return@forEach

            val teamNames = images.mapNotNull { image ->
                (image.attr("alt").ifBlank { image.attr("title") }).trim().takeIf { it.isNotBlank() }
            }.distinct()
            if (teamNames.size < 2) return@forEach

            val title = teamNames.take(2).joinToString(" - ")
            if (!seen.add(href)) return@forEach

            val poster: String? = anchor.select("img").firstOrNull()?.let { image ->
                image.absUrl("src").ifBlank {
                    image.absUrl("data-src")
                }.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            matches.add(MatchCard(title, href, poster))
        }

        return matches
    }

    private suspend fun todayMatches(): List<SearchResponse> {
        val page = app.get(mainUrl).text
        return parseToday(page).map { match ->
            newLiveSearchResponse(match.title, match.url, TvType.Live, true) {
                posterUrl = match.poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(
            "مباريات اليوم",
            todayMatches(),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return todayMatches().filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.title().ifBlank { "مباراة من ماجد كورة" }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = 0

        // Only inspect media URLs exposed by the public match page.
        document.select("video[src], video source[src], source[src]").forEach { element ->
            val streamUrl = element.absUrl("src")
            if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) {
                return@forEach
            }
            val streamType = ExtractorLinkType.M3U8
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Majed Koora",
                    url = streamUrl,
                    type = streamType
                )
            )
            found++
        }

        val embeds = document.select("iframe[src]").map { it.absUrl("src") }.distinct()
        for (embed in embeds) {
            if (!embed.startsWith("http://") && !embed.startsWith("https://")) continue
            runCatching {
                loadExtractor(embed, data, subtitleCallback, callback)
            }.onSuccess {
                found++
            }
        }

        return found > 0
    }
}
