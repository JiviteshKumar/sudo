package com.technewz.app.net

import com.technewz.app.data.AppJson
import com.technewz.app.ml.SentenceSplitter
import com.technewz.app.util.Text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder

/**
 * Where "Rising on GitHub" comes from. GitHub's own Trending page says how many stars each repo gained this
 * week; GitHub's public API confirms every repo and supplies its details. Nothing here is a list of repos.
 */
object GitHubRising {
    /** One row of github.com/trending: [weekStars] is GitHub's own "N stars this week". */
    data class TrendingRow(val fullName: String, val description: String, val language: String, val stars: Int, val weekStars: Int)

    data class Repo(
        val fullName: String,
        val url: String,
        val description: String,
        val language: String,
        val topics: List<String>,
        val stars: Int,
        val forks: Int,
        val createdAt: Long,
        val pushedAt: Long,
        val archived: Boolean,
        val fork: Boolean,
    )

    /** GitHub's free API limit (60 requests an hour per network without a token) was reached. */
    class RateLimited : IOException("GitHub's free hourly limit was reached")

    private val api = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    private val weekly = Regex("([\\d,]+) stars? this week")

    fun parseTrending(html: String): List<TrendingRow> = Jsoup.parse(html).select("article.Box-row").mapNotNull { row ->
        val name = row.selectFirst("h2 a[href]")?.attr("href")?.trim('/')?.takeIf { it.count { c -> c == '/' } == 1 }
            ?: return@mapNotNull null
        // The element whose whole text is "N stars this week" (not the row text, where numbers can run together).
        val weekText = row.select("span").map { it.text().trim() }.firstOrNull { weekly.matches(it) } ?: return@mapNotNull null
        val week = weekly.find(weekText)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return@mapNotNull null
        TrendingRow(
            fullName = name,
            description = row.selectFirst("p")?.text()?.trim().orEmpty(),
            language = row.selectFirst("[itemprop=programmingLanguage]")?.text()?.trim().orEmpty(),
            stars = row.selectFirst("a[href$=/stargazers]")?.text()?.replace(",", "")?.trim()?.toIntOrNull() ?: 0,
            weekStars = week,
        )
    }.distinctBy { it.fullName.lowercase() }

    suspend fun trendingThisWeek(): List<TrendingRow> =
        parseTrending(Http.get("https://github.com/trending?since=weekly", maxBytes = 3_000_000))

    private suspend fun apiGet(url: String, headers: Map<String, String> = api): String = try {
        Http.get(url, headers, maxBytes = 3_000_000)
    } catch (e: HttpException) {
        // For these public endpoints, 403/429 means the anonymous rate limit was hit.
        if (e.code == 403 || e.code == 429) throw RateLimited() else throw e
    }

    fun parseRepo(o: JsonObject): Repo? {
        val name = o.s("full_name").ifBlank { return null }
        return Repo(
            fullName = name,
            url = o.s("html_url").ifBlank { "https://github.com/$name" },
            description = o.s("description").trim(),
            language = o.s("language"),
            topics = (o["topics"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            stars = o.s("stargazers_count").toIntOrNull() ?: 0,
            forks = o.s("forks_count").toIntOrNull() ?: 0,
            createdAt = Text.parseDate(o.s("created_at")) ?: 0,
            pushedAt = Text.parseDate(o.s("pushed_at")) ?: 0,
            archived = o.s("archived") == "true",
            fork = o.s("fork") == "true",
        )
    }

    suspend fun repo(fullName: String): Repo? = try {
        parseRepo(AppJson.parseToJsonElement(apiGet("https://api.github.com/repos/$fullName")).jsonObject)
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e // 404: renamed, deleted or made private
    }

    /** Repositories matching a GitHub search [query], most-starred first. */
    suspend fun search(query: String, perPage: Int = 20): List<Repo> {
        val json = apiGet("https://api.github.com/search/repositories?q=${URLEncoder.encode(query, "UTF-8")}&sort=stars&order=desc&per_page=$perPage")
        return AppJson.parseToJsonElement(json).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { parseRepo(it.jsonObject) }
    }

    /** The repo's README as written (raw Markdown), or null if it has none. */
    suspend fun readme(fullName: String): String? {
        runCatching { return Http.get("https://raw.githubusercontent.com/$fullName/HEAD/README.md", maxBytes = 400_000) }
        return try {
            apiGet("https://api.github.com/repos/$fullName/readme", mapOf("Accept" to "application/vnd.github.raw+json"))
        } catch (e: HttpException) {
            null
        }
    }

    // ------------------------------------------------------------------ README → short "what it does"

    private val junk = Regex(
        "shields\\.io|badge|table of contents|star history|npm (i|install)|pip install|brew install|cargo install|" +
            "join (our|the) (discord|community)|^\\s*(english|简体中文|中文|日本語|한국어)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The README's first real paragraph, quoted as written: Markdown and HTML markup is removed; headings,
     * badges, images and their captions, lists, tables, code and language switchers are skipped; and it is cut
     * to whole sentences (at most [maxWords] words, or [maxChars] characters for text written without spaces,
     * such as Chinese). Returns null if the README has no such paragraph.
     */
    fun readmeIntro(markdown: String, maxWords: Int = 60, maxChars: Int = 320): String? {
        var s = markdown.replace("\r\n", "\n")
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "\n")
        s = s.replace(Regex("(?m)^\\s*(```|~~~).*?^\\s*(```|~~~)\\s*$", setOf(RegexOption.DOT_MATCHES_ALL)), "\n")
        s = s.replace(Regex("(?is)<h[1-6][^>]*>.*?</h[1-6]>"), "\n\n")
        s = s.replace(Regex("(?i)</?(p|div|ul|ol|li|table|tr|td|picture|details|summary|center|blockquote)(\\s[^>]*)?>"), "\n\n")
        // An empty quote line ("> ") separates paragraphs inside a block quote.
        s = s.replace(Regex("(?m)^\\s*>\\s*$"), "")
        for (raw in s.split(Regex("\\n\\s*\\n"))) {
            // Images, videos and the captions that go with them describe a picture, not the project.
            if (Regex("(?i)<(img|video|picture|source|sub|small)\\b|!\\[").containsMatchIn(raw)) continue
            var block = raw.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^\\]]+)]\\[[^\\]]*]"), "$1")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
            block = Parser.unescapeEntities(block, false)
            val lines = block.lines().map { it.trim().removePrefix(">").trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (lines.isEmpty()) continue
            if (lines.any { it.startsWith("#") || it.startsWith("|") || it.startsWith("[") || it.matches(Regex("^([-*+]|\\d+[.)])\\s.*")) || it.matches(Regex("^[-=*_]{3,}$")) }) continue
            // Trailing lines that aren't sentences (e.g. "macOS · Windows · Linux") are dropped.
            while (lines.size > 1 && !endsSentence(clean(lines.last()))) lines.removeAt(lines.lastIndex)
            val text = clean(lines.joinToString(" "))
            if (text.isEmpty() || junk.containsMatchIn(text) || text.contains(" | ") || text.contains("[!")) continue
            val cjk = text.count { Character.UnicodeScript.of(it.code) in cjkScripts }
            if (Text.wordCount(text) < 8 && cjk < 20) continue
            if (text.count { it.isLetter() } < text.length * 0.6 || !endsSentence(text)) continue
            val out = StringBuilder()
            for (sentence in SentenceSplitter.split(text)) {
                val next = (if (out.isEmpty()) "" else "$out ") + sentence.trim()
                if (out.isNotEmpty() && (Text.wordCount(next) > maxWords || next.length > maxChars)) break
                out.setLength(0)
                out.append(next)
            }
            var intro = out.toString().ifBlank { text }
            if (Text.wordCount(intro) > maxWords) intro = Text.trimWords(intro, maxWords)
            if (intro.length > maxChars) {
                val cut = intro.take(maxChars)
                val stop = cut.indexOfLast { it in "。！？.!?" }
                intro = if (stop > maxChars / 2) cut.substring(0, stop + 1) else cut.trimEnd() + "…"
            }
            return intro
        }
        return null
    }

    private val cjkScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    /** Removes Markdown emphasis (bold, italics, code ticks) without touching the words. */
    fun clean(t: String) = t.replace(Regex("\\*\\*|__|`"), "")
        .replace(Regex("(?<![\\w*])\\*(?=\\S)([^*]+?)\\*(?![\\w*])"), "$1")
        .replace(Regex("(?<![\\w_])_(?=\\S)([^_]+?)_(?![\\w_])"), "$1")
        .replace(Regex("\\s+"), " ").trim()

    private fun endsSentence(t: String) = t.isNotEmpty() && t.last() in ".!?。！？)\"”"

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    private fun JsonObject.s(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }.orEmpty()
}
