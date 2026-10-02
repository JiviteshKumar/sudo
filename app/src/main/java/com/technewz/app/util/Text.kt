package com.technewz.app.util

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Text {
    /** HTML (possibly entity-escaped) to clean plain text. */
    fun htmlToText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = html
        if (s.contains("&lt;")) s = Parser.unescapeEntities(s, false)
        return Jsoup.parse(s).text().replace(Regex("\\s+"), " ").trim()
    }

    /** HTML to text that keeps paragraph / list breaks, for job descriptions. */
    fun htmlToReadable(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = html
        if (s.contains("&lt;")) s = Parser.unescapeEntities(s, false)
        val doc = Jsoup.parse(s)
        doc.select("br").before("\\n")
        doc.select("p, div, h1, h2, h3, h4, h5, li").forEach { el ->
            if (el.tagName() == "li") el.prepend("\\n• ") else el.prepend("\\n\\n")
        }
        return doc.text().replace("\\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Feed text without feed-specific labels (arXiv listings prefix every abstract with its ID and announce type). */
    fun cleanFeedText(html: String?): String =
        htmlToText(html).replace(Regex("^arXiv:\\S+\\s+Announce Type:\\s*\\S+\\s+Abstract:\\s*", RegexOption.IGNORE_CASE), "")

    fun firstImage(html: String?): String? {
        if (html.isNullOrBlank() || !html.contains("<img")) return null
        return Jsoup.parse(html).selectFirst("img[src]")?.absUrl("src")?.takeIf { it.startsWith("http") }
            ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
    }

    fun wordCount(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Trim to at most [max] words, ending on a sentence boundary when one is close. */
    fun trimWords(s: String, max: Int = 50): String {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= max) return words.joinToString(" ")
        val cut = words.take(max).joinToString(" ")
        val lastStop = cut.lastIndexOf(". ").takeIf { it > cut.length * 0.6 }
        return if (lastStop != null) cut.substring(0, lastStop + 1) else "$cut…"
    }

    fun domainOf(url: String): String = runCatching {
        URI(url).host.orEmpty().removePrefix("www.").removePrefix("m.")
    }.getOrDefault("")

    fun canonicalUrl(url: String): String = runCatching {
        val u = URI(url.trim())
        val query = u.rawQuery?.split('&')
            ?.filterNot { it.startsWith("utm_") || it.startsWith("ref=") || it.startsWith("fbclid") || it.startsWith("gclid") }
            ?.joinToString("&")?.takeIf { it.isNotEmpty() }
        URI(u.scheme?.lowercase() ?: "https", u.authority?.lowercase(), u.path?.trimEnd('/'), null, null).toString() +
            (query?.let { "?$it" } ?: "")
    }.getOrDefault(url.trim())

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private val stop = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "its", "it's",
        "at", "by", "from", "as", "that", "this", "be", "has", "have", "new", "how", "why", "what", "after", "will",
        "can", "now", "your", "you", "about", "into", "over", "more", "than", "just", "says", "say", "report", "here",
    )

    fun titleTokens(title: String): Set<String> =
        title.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.length > 2 && it !in stop }
            .map { it.removeSuffix("s") }
            .toSet()

    /** Are two headlines about the same story? Conservative so different stories never merge. */
    fun sameStory(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val inter = a.intersect(b).size
        val jaccard = inter.toDouble() / a.union(b).size
        val overlap = inter.toDouble() / minOf(a.size, b.size)
        return jaccard >= 0.45 || (inter >= 4 && overlap >= 0.6)
    }

    // ---- dates ----
    private val rfcPatterns = listOf(
        "EEE, d MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm zzz", "d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss Z",
    ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (f in rfcPatterns) runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        runCatching { return java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        runCatching { return java.time.LocalDate.parse(s.take(10)).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    fun timeAgo(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - epochMs).coerceAtLeast(0) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            diff < 86400 * 7 -> "${diff / 86400}d ago"
            diff < 86400 * 30 -> "${diff / (86400 * 7)}w ago"
            diff < 86400 * 365 -> "${diff / (86400 * 30)}mo ago"
            else -> "${diff / (86400 * 365)}y ago"
        }
    }
}
