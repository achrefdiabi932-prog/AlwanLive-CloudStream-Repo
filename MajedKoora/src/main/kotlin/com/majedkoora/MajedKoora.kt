package com.majedkoora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SITE = "https://majed-koora.live"
private const val MATCHES_API = "$SITE/api/v1/matches"

private data class Team(
    val id: String = "",
    val name: String = "",
    val logo: String = "",
    val score: Int? = null
)

private data class Tournament(
    val id: String = "",
    val name: String = "",
    val logo: String = ""
)

private data class Broadcast(
    val channel: String = "",
    val commentator: String = ""
)

private data class MatchItem(
    val id: String = "",
    val date: String = "",
    val time: String = "",
    val start_at: String = "",
    val state: String = "",
    val state_text: String = "",
    val home_team: Team = Team(),
    val away_team: Team = Team(),
    val tournament: Tournament = Tournament(),
    val broadcast: Broadcast = Broadcast(),
    val watch_ready: Boolean = false,
    val watch_url: String = "",
    val watch_available: Boolean = false
)

private data class MatchesPayload(
    val success: Boolean = false,
    val date: String = "",
    val matches: List<MatchItem> = emptyList()
)

private data class MatchData(
    val match: MatchItem,
    val matchDate: String
)

private fun siteUrl(path: String): String {
    if (path.isBlank()) return ""
    return if (path.startsWith("http://") || path.startsWith("https://")) path
    else SITE.trimEnd('/') + "/" + path.trimStart('/')
}

private fun isOfficialWatchPage(url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "majed-koora.live" || host.endsWith(".majed-koora.live")) &&
            uri.userInfo == null
    }.getOrDefault(false)
}

class MajedKoora : MainAPI() {
    override var name = "Majed Koora"
    override var mainUrl = SITE
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("today" to "مباريات اليوم")

    private fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun MatchItem.displayTitle(): String {
        val teams = listOf(home_team.name, away_team.name)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val score = if (home_team.score != null || away_team.score != null) {
            "${home_team.score ?: "-"} : ${away_team.score ?: "-"}"
        } else ""
        val competition = tournament.name
        val status = state_text.ifBlank { state }
        return listOf(teams, score, competition, time, status)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .ifBlank { "مباراة من ماجد كورة" }
    }

    private fun MatchItem.detailsText(matchDate: String): String =
        listOf(
            tournament.name.takeIf { it.isNotBlank() }?.let { "البطولة: $it" },
            matchDate.takeIf { it.isNotBlank() }?.let { "التاريخ: $it" },
            time.takeIf { it.isNotBlank() }?.let { "الوقت: $it" },
            state_text.ifBlank { state }.takeIf { it.isNotBlank() }?.let { "الحالة: $it" },
            broadcast.channel.takeIf { it.isNotBlank() }?.let { "القناة: $it" },
            broadcast.commentator.takeIf { it.isNotBlank() }?.let { "المعلق: $it" },
            "المشاهدة الرسمية: $SITE"
        ).filterNotNull().joinToString("\n")

    private fun MatchItem.homeLogo(): String? =
        siteUrl(home_team.logo).takeIf { it.isNotBlank() }

    private suspend fun todayMatches(): List<SearchResponse> {
        val date = todayDate()
        val endpoint = "$MATCHES_API?lang=ar&date=$date&scope=all"
        val response = app.get(endpoint)
        val payload = parseJson<MatchesPayload>(response.text)
        if (!payload.success) return emptyList()

        return payload.matches.map { match ->
            val data = MatchData(match, payload.date.ifBlank { date }).toJson()
            newLiveSearchResponse(match.displayTitle(), data, TvType.Live, true) {
                posterUrl = match.homeLogo()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = runCatching { todayMatches() }.getOrDefault(emptyList())
        return newHomePageResponse("مباريات اليوم", results, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return runCatching { todayMatches() }
            .getOrDefault(emptyList())
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val item = parseJson<MatchData>(url)
        val match = item.match
        val poster = siteUrl(match.tournament.logo).ifBlank { match.homeLogo().orEmpty() }
        return newLiveStreamLoadResponse(match.displayTitle(), url, url) {
            posterUrl = poster
            plot = match.detailsText(item.matchDate)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = parseJson<MatchData>(data)
        val match = item.match
        if (!match.watch_available || !match.watch_ready || match.watch_url.isBlank()) {
            return false
        }

        val watchPage = siteUrl(match.watch_url)
        if (!isOfficialWatchPage(watchPage)) return false

        return runCatching {
            var foundSource = false
            val extractorMatched = loadExtractor(
                watchPage,
                SITE,
                subtitleCallback
            ) { link ->
                foundSource = true
                callback(link)
            }
            extractorMatched && foundSource
        }.getOrDefault(false)
    }
}
