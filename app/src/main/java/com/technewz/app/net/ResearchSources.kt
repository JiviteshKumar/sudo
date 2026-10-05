package com.technewz.app.net

import com.technewz.app.data.AppJson
import com.technewz.app.util.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Independent sources used to verify and explain emerging terms. */
object ResearchSources {

    /** Daily arXiv listings used as the "what is new in research" corpus. */
    val arxivListings = listOf(
        "https://rss.arxiv.org/rss/cs.AI",
        "https://rss.arxiv.org/rss/cs.LG",
        "https://rss.arxiv.org/rss/cs.CL",
        "https://rss.arxiv.org/rss/cs.CV",
        "https://rss.arxiv.org/rss/stat.ML",
    )

    // ------------------------------------------------------------- arXiv API (1 request / 3 s, per arXiv's terms)
    private val arxivGate = Mutex()
    private var lastArxiv = 0L
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)

    private suspend fun arxiv(query: String, max: Int, sortAsc: Boolean = false): String = arxivGate.withLock {
        val wait = 3100 - (System.currentTimeMillis() - lastArxiv)
        if (wait > 0) delay(wait)
        try {
            Http.get(
                "https://export.arxiv.org/api/query?search_query=${URLEncoder.encode(query, "UTF-8")}" +
                    "&sortBy=submittedDate&sortOrder=${if (sortAsc) "ascending" else "descending"}&max_results=$max"
            )
        } finally {
            lastArxiv = System.currentTimeMillis()
        }
    }

    private fun total(xml: String): Int =
        Regex("<opensearch:totalResults[^>]*>(\\d+)").find(xml)?.groupValues?.get(1)?.toInt() ?: 0

    private fun phrase(p: String) = "abs:\"${p.replace("\"", "")}\""

    private fun window(daysAgoFrom: Int, daysAgoTo: Int): String {
        val now = Instant.now()
        return "submittedDate:[${stamp.format(now.minusSeconds(daysAgoFrom * 86400L))} TO ${stamp.format(now.minusSeconds(daysAgoTo * 86400L))}]"
    }

    /**
     * arXiv categories that make up "the AI / ML / data-science field". Counts and explanation sources are
     * restricted to these, so a term that is common elsewhere (e.g. software engineering) is not treated as an
     * AI/ML/DS trend.
     */
    val aiCategories = listOf("cs.LG", "cs.AI", "cs.CL", "cs.CV", "stat.ML", "cs.IR", "cs.NE", "cs.RO", "cs.MA")
    private val aiFilter = aiCategories.joinToString(" OR ", "(", ")") { "cat:$it" }

    /** Number of AI/ML/DS arXiv papers whose abstract contains [p], submitted between [fromDaysAgo] and [toDaysAgo]. */
    suspend fun arxivCount(p: String, fromDaysAgo: Int, toDaysAgo: Int): Int =
        total(arxiv("${phrase(p)} AND $aiFilter AND ${window(fromDaysAgo, toDaysAgo)}", 1))

    data class Paper(val title: String, val abstract: String, val url: String, val published: Long)

    private fun papers(xml: String): List<Paper> = RssParser.parse(xml).map {
        Paper(it.title.replace(Regex("\\s+"), " "), Text.htmlToText(it.descriptionHtml), it.link.replace(Regex("v\\d+$"), ""), it.published ?: 0)
    }

    /** Most recent papers mentioning [p] (abstracts are used as explanation sources). */
    suspend fun arxivRecent(p: String, days: Int, max: Int): List<Paper> =
        papers(arxiv("${phrase(p)} AND $aiFilter AND ${window(days, 0)}", max))

    // ------------------------------------------------------------- OpenAlex (all scholarly works, independent of arXiv)
    /** Number of scholarly works per publication year whose title or abstract contains the exact phrase. */
    suspend fun openAlexYears(p: String): Map<Int, Int>? = runCatching {
        val q = URLEncoder.encode("\"" + p.replace("\"", "") + "\"", "UTF-8")
        val json = Http.get("https://api.openalex.org/works?filter=title_and_abstract.search:$q&group_by=publication_year&per-page=200")
        AppJson.parseToJsonElement(json).jsonObject["group_by"]!!.jsonArray.associate {
            val o = it.jsonObject
            o.s("key").toInt() to o.s("count").toInt()
        }
    }.getOrNull()

    fun openAlexUrl(p: String) =
        "https://openalex.org/works?filter=title_and_abstract.search:" + URLEncoder.encode("\"" + p.replace("\"", "") + "\"", "UTF-8")

    /** A spread-out sample of AI/ML/NLP papers from 6–18 months ago, used as the "already known" baseline. */
    suspend fun arxivBaseline(perWindow: Int = 300): List<Paper> {
        // Same categories as [arxivListings], so the comparison is like-for-like.
        val q = arxivListings.joinToString(" OR ", "(", ")") { "cat:" + it.substringAfterLast('/') }
        return listOf(180, 270, 365, 540).flatMap { daysAgo ->
            runCatching { papers(arxiv("$q AND ${window(daysAgo + 20, daysAgo)}", perWindow)) }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }

    // ------------------------------------------------------------- Wikipedia
    data class Wiki(val title: String, val extract: String, val url: String)

    /** Finds the Wikipedia article for a term via Wikipedia search; only accepts a page whose title contains the term. */
    suspend fun wikipediaFor(term: String, shortForm: String?): Wiki? = runCatching {
        val json = Http.get("https://en.wikipedia.org/w/api.php?action=opensearch&namespace=0&limit=5&format=json&search=" + URLEncoder.encode(term, "UTF-8"))
        val titles = AppJson.parseToJsonElement(json).jsonArray[1].jsonArray.map { it.jsonPrimitive.content }
        val key = term.lowercase().replace(Regex("[^a-z0-9]"), "")
        val title = titles.firstOrNull { it.lowercase().replace(Regex("[^a-z0-9]"), "").contains(key) } ?: return null
        wikipedia(title, shortForm)
    }.getOrNull()

    /** Wikipedia summary for [title], only if the page really is about that term (not a disambiguation/other topic). */
    suspend fun wikipedia(title: String, alsoMatch: String?): Wiki? = runCatching {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val o = AppJson.parseToJsonElement(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/$path")).jsonObject
        if (o["type"]?.jsonPrimitive?.content != "standard") return null
        val pageTitle = o["titles"]?.jsonObject?.get("normalized")?.jsonPrimitive?.content ?: return null
        val extract = o["extract"]?.jsonPrimitive?.content?.trim().orEmpty()
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val about = norm(pageTitle) == norm(title) || norm(extract).contains(norm(title)) ||
            (alsoMatch != null && extract.contains(alsoMatch))
        if (!about || extract.length < 40) return null
        val url = o["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.content
            ?: "https://en.wikipedia.org/wiki/$path"
        Wiki(pageTitle, extract, url)
    }.getOrNull()

    // ------------------------------------------------------------- GitHub (unauthenticated search: 10 requests / minute)
    private val ghGate = Mutex()
    private var lastGh = 0L

    data class Repo(val fullName: String, val description: String, val stars: Int, val createdAt: Long, val url: String)

    /** The most-starred repository whose name is exactly [name] (ignoring case and separators). */
    suspend fun githubRepo(name: String): Repo? = ghGate.withLock {
        val wait = 6500 - (System.currentTimeMillis() - lastGh)
        if (wait > 0) delay(wait)
        try {
            val json = Http.get(
                "https://api.github.com/search/repositories?q=${URLEncoder.encode("$name in:name", "UTF-8")}&sort=stars&order=desc&per_page=10",
                mapOf("Accept" to "application/vnd.github+json"),
            )
            val key = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            AppJson.parseToJsonElement(json).jsonObject["items"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it.s("name").lowercase().replace(Regex("[^a-z0-9]"), "") == key }
                ?.let {
                    Repo(
                        it.s("full_name"), it.s("description"), it.s("stargazers_count").toIntOrNull() ?: 0,
                        Text.parseDate(it.s("created_at")) ?: 0, it.s("html_url"),
                    )
                }
        } finally {
            lastGh = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------- package registries (independent confirmation)
    data class Package(val registry: String, val name: String, val summary: String, val links: List<String>, val firstRelease: Long?, val url: String)

    suspend fun pypi(name: String): Package? = runCatching {
        val o = AppJson.parseToJsonElement(Http.get("https://pypi.org/pypi/${URLEncoder.encode(name, "UTF-8")}/json", maxBytes = 12_000_000)).jsonObject
        val info = o["info"]!!.jsonObject
        val links = buildList {
            info["project_urls"]?.let { runCatching { it.jsonObject.values.forEach { v -> add(v.jsonPrimitive.content) } } }
            info["home_page"]?.let { runCatching { add(it.jsonPrimitive.content) } }
        }
        val first = o["releases"]?.jsonObject?.values?.flatMap { r -> runCatching { r.jsonArray.mapNotNull { f -> Text.parseDate(f.jsonObject.s("upload_time_iso_8601")) } }.getOrDefault(emptyList()) }?.minOrNull()
        Package("PyPI", info.s("name"), info.s("summary"), links, first, "https://pypi.org/project/$name/")
    }.getOrNull()

    suspend fun npm(name: String): Package? = runCatching {
        val o = AppJson.parseToJsonElement(Http.get("https://registry.npmjs.org/${URLEncoder.encode(name, "UTF-8")}", maxBytes = 30_000_000)).jsonObject
        val repo = o["repository"]?.let { r -> runCatching { r.jsonObject.s("url") }.getOrNull() ?: runCatching { r.jsonPrimitive.content }.getOrNull() }
        val created = o["time"]?.jsonObject?.s("created")?.let { Text.parseDate(it) }
        Package("npm", o.s("name"), o.s("description"), listOfNotNull(repo, o.s("homepage")), created, "https://www.npmjs.com/package/$name")
    }.getOrNull()

    private fun JsonObject.s(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
}
