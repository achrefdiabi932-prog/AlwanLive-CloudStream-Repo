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

        for (anchor in document.select("a[href]")) {
            val href = absoluteUrl(pageUrl, anchor.attr("href"))
            if (!validPage(href) || href.substringBefore("#").substringBefore("?") ==
                pageUrl.substringBefore("#").substringBefore("?")) continue

            val card = anchor.closest("article, li, .match, .match-card, .match-item, .fixture")
                ?: anchor.parent()?.parent() ?: anchor.parent() ?: anchor
            val imageNames = card.select("img[alt]").map { it.attr("alt").trim() }
                .filter { it.isNotBlank() && it.length < 70 }
            val rawText = card.text().replace(Regex("\\s+"), " ").trim()
            val title = when {
                imageNames.size >= 2 -> "${imageNames[0]} - ${imageNames[1]}"
                rawText.length in 5..180 -> rawText
                anchor.text().isNotBlank() -> anchor.text().trim()
                else -> imageNames.firstOrNull().orEmpty()
            }
            if (title.length < 5 || title.contains("مباريات اليوم") ||
                title.contains("مباريات الغد") || title.contains("عرض المزيد")) continue

            val data = MatchPage(title, href, day, rawText).toJson()
            output.putIfAbsent(href, newLiveSearchResponse(title, data, TvType.Live, true) {
                posterUrl = card.selectFirst("img[src]")?.absUrl("src")
            })
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
                Regex("""https?:\\?/\\?/[^"'\\s<>]+?\\.(?:m3u8|mp4)(?:\\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
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
                    callback(
                        ExtractorLink(
                            name, item.title, candidate, item.url,
                            Qualities.Unknown.value, isM3u8 = candidate.contains(".m3u8", true)
                        )
                    )
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
