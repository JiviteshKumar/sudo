package com.technewz.app.net

import org.jsoup.Jsoup

/** Pulls the readable body text and lead image from an article page (used only to improve summaries). */
object ArticleFetcher {
    data class Page(val text: String, val image: String?)

    suspend fun fetch(url: String): Page? = runCatching {
        val html = Http.get(url, maxBytes = 2_500_000)
        val doc = Jsoup.parse(html, url)
        val image = doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[name=twitter:image]")?.absUrl("content")?.takeIf { it.startsWith("http") }
        doc.select("script, style, nav, footer, aside, header, form, figure, noscript, .newsletter, .related, .ad").remove()
        val scope = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val paragraphs = scope.select("p")
            .map { it.text().trim() }
            .filter { it.length > 60 && !it.startsWith("Sign up") && !it.contains("cookie", ignoreCase = true) }
        val text = buildString {
            for (p in paragraphs) {
                if (length > 6000) break
                append(p).append("\n")
            }
        }.trim()
        Page(text, image)
    }.getOrNull()
}
