package com.technewz.app.net

import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Every rule an item must pass before it can be shown as news. Kept pure so tests exercise exactly
 * what the app runs.
 */
object NewsFilter {
    private val commerce = Regex(
        "promo code|coupon|discount code|% off|\\bdeals?\\b.*(prime|black friday|cyber monday|sale)|best .* deals|deal alert|gift guide|" +
            "\\btickets?\\b|save up to|last (24 hours|chance|day) to|register now|early.?bird|exhibit at",
        RegexOption.IGNORE_CASE,
    )

    /** "news.bbc.co.uk" -> "bbc.co.uk", "feeds.arstechnica.com" -> "arstechnica.com". */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val tld = labels.last()
        val sld = labels[labels.size - 2]
        val threePart = tld.length == 2 && sld in setOf("co", "com", "org", "net", "ac", "gov", "edu")
        return labels.takeLast(if (threePart) 3 else 2).joinToString(".")
    }

    /** Publisher identity: the first label of the registrable domain ("bbc" for bbc.com and bbc.co.uk). */
    fun brand(url: String): String? = runCatching {
        val host = URI(url.trim()).host ?: return null
        registrableDomain(host).substringBefore('.')
    }.getOrNull()

    /** Site link declared by the feed itself (RSS <channel><link> or Atom feed-level alternate link). */
    fun siteLink(xml: String): String? {
        val head = xml.substringBefore("<item").substringBefore("<entry")
        Regex("<link>\\s*(?:<!\\[CDATA\\[)?\\s*(https?://[^<\\]\\s]+)").find(head)?.let { return it.groupValues[1] }
        Regex("<link[^>]*rel=[\"']alternate[\"'][^>]*href=[\"'](https?://[^\"']+)").find(head)?.let { return it.groupValues[1] }
        Regex("<link[^>]*href=[\"'](https?://[^\"']+)[\"'][^>]*rel=[\"']alternate").find(head)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * Returns null if the item is acceptable, otherwise the reason it is rejected.
     * - must have a real publish date that isn't in the future or older than [maxAgeHours]
     * - must link to the same publisher that publishes the feed (blocks injected/off-site links)
     * - must not be commerce content
     */
    fun reject(feedUrl: String, siteLink: String?, item: RawItem, now: Long, maxAgeHours: Long = 72): String? {
        val published = item.published ?: return "no publish date"
        if (published > now + TimeUnit.HOURS.toMillis(2)) return "dated in the future"
        if (published < now - TimeUnit.HOURS.toMillis(maxAgeHours)) return "too old"
        if (!item.link.startsWith("https://") && !item.link.startsWith("http://")) return "not a web link"
        val itemBrand = brand(item.link) ?: return "invalid link"
        val allowed = setOfNotNull(brand(feedUrl), siteLink?.let { brand(it) })
        if (itemBrand !in allowed) return "links off-site ($itemBrand not in $allowed)"
        if (item.title.isBlank()) return "no headline"
        if (commerce.containsMatchIn(item.title)) return "commerce/deals content"
        return null
    }
}
