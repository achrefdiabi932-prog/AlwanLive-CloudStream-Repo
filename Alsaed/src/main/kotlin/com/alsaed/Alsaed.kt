package com.alsaed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SITE = "https://www.alsaed.online"
private const val TODAY_PAGE = "$SITE/p/today-matches.html"
private const val TOMORROW_PAGE = "$SITE/p/tomorrow-matches.html"

private data class MatchPage(
    val title: String,
    val url: String,
    val day: String,
    val description: String = ""
)

private fun absoluteUrl(base: String, value: String): String =
    runCatching { URI(base).resolve(value).toString() }.getOrDefault("")

private fun validPage(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in
        setOf("alsaed.online", "www.alsaed.online") && uri.userInfo == null
}.getOrDefault(false)

class Alsaed : MainAPI() {
    override var name = "Alsaed"
    override var mainUrl = SITE
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf(
        "today" to "مباريات اليوم",
        "tomorrow" to "مباريات الغد"
    )

    private fun date(dayOffset: Int): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
    }

    private fun extractMatches(html: String, pageUrl: String, day: String): List<SearchResponse> {
        val document = Jsoup.parse(html, pageUrl)
        val output = linkedMapOf<String, SearchResponse>()

        // The match cards use team-logo images hosted on malik-web.com.
        // Their image URLs are not match-page URLs, so identify cards from
        // the two team names rather than treating every anchor as a fixture.
        val teamImages = document.select("img[alt]")
            .filter { image ->
                val alt = image.attr("alt").trim()
                alt.length in 2..70 &&
                    !alt.contains("الصعيد") &&
                    !alt.contains("مباريات")
            }

        for (image in teamImages) {
            var card: org.jsoup.nodes.Element? = null
            var parent = image.parent()
            while (parent != null) {
                val names = parent.select("img[alt]").map { it.attr("alt").trim() }
                    .filter { it.length in 2..70 }
                    .distinct()
                if (names.size >= 2 && names.size <= 4) {
                    card = parent
                    break
                }
                parent = parent.parent()
            }
            val matchCard = card ?: continue
            val teams = matchCard.select("img[alt]").map { it.attr("alt").trim() }
                .filter { it.length in 2..70 }
                .distinct()
                .take(2)
            if (teams.size != 2) continue

            val title = "${teams[0]} - ${teams[1]}"
            val matchUrl = matchCard.select("a[href]")
                .map { absoluteUrl(pageUrl, it.attr("href")) }
                .firstOrNull { href ->
                    validPage(href) &&
                        href.substringBefore("#").substringBefore("?") !=
                        pageUrl.substringBefore("#").substringBefore("?")
                } ?: pageUrl

            val key = "${title}_${matchUrl}"
            if (output.containsKey(key)) continue

            val text = matchCard.text().replace(Regex("\\s+"), " ").trim()
            val data = MatchPage(title, matchUrl, day, text).toJson()
            output[key] = newLiveSearchResponse(title, data, TvType.Live, true) {
                posterUrl = image.absUrl("src").ifBlank { null }
            }
        }
        return output.values.toList()
    }

    private suspend fun matches(dayOffset: Int): List<SearchResponse> {
        val page = if (dayOffset == 0) TODAY_PAGE else TOMORROW_PAGE
        val response = app.get(page)
        return extractMatches(response.text, page, date(dayOffset))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val offset = if (request.name.contains("الغد") || request.data == "tomorrow") 1 else 0
        val results = runCatching { matches(offset) }.getOrDefault(emptyList())
        return newHomePageResponse(if (offset == 0) "مباريات اليوم" else "مباريات الغد", results, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return (runCatching { matches(0) }.getOrDefault(emptyList()) +
            runCatching { matches(1) }.getOrDefault(emptyList()))
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = AppUtils.parseJson<MatchPage>(url)
        return newLiveStreamLoadResponse(item.title, url, url) {
            posterUrl = null
            plot = buildString {
                append("التاريخ: ").append(item.day)
                if (item.description.isNotBlank()) append("\n").append(item.description)
                append("\nصفحة المباراة: ").append(item.url)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = AppUtils.parseJson<MatchPage>(data)
        if (!validPage(item.url)) return false

        return runCatching {
            val response = app.get(item.url, referer = SITE)
            val doc = Jsoup.parse(response.text, item.url)
            val candidates = linkedSetOf<String>()

            doc.select("iframe[src], video[src], video source[src], source[src]").forEach {
                val src = it.absUrl("src").ifBlank { absoluteUrl(item.url, it.attr("src")) }
                if (src.startsWith("https://") || src.startsWith("http://")) candidates.add(src)
            }
            val patterns = listOf(
                Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE),
                Regex("""(?:file|source|src|url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            )
            patterns.forEach { regex ->
                regex.findAll(response.text).forEach { match ->
                    val raw = match.groupValues.last().replace("\\/", "/")
                    val resolved = absoluteUrl(item.url, raw)
                    if (resolved.startsWith("https://") || resolved.startsWith("http://")) candidates.add(resolved)
                }
            }

            var found = false
            for (candidate in candidates) {
                if (candidate == item.url || candidate == SITE) continue
                val direct = candidate.contains(".m3u8", true) || candidate.contains(".mp4", true)
                if (direct) {
                    callback(newExtractorLink(
                        source = name,
                        name = item.title,
                        url = candidate,
                        type = if (candidate.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = item.url
                        quality = Qualities.Unknown.value
                    })
                    found = true
                } else {
                    runCatching {
                        loadExtractor(candidate, item.url, subtitleCallback) {
                            callback(it)
                            found = true
                        }
                    }
                }
            }
            found
        }.getOrDefault(false)
    }
}
