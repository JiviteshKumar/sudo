package com.technewz.app

import com.technewz.app.ml.TermMiner
import com.technewz.app.net.NewsFilter
import com.technewz.app.net.RawItem
import com.technewz.app.net.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline tests for the rules that keep fabricated, injected or misleading content out of the app. */
class TrustRulesTest {
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private fun item(link: String, title: String = "Real headline about chips", ageH: Long = 2) =
        RawItem(title, link, "<p>body</p>", now - ageH * hour, null)

    // ---------------------------------------------------------------- news acceptance rules

    @Test fun acceptsItemFromSamePublisherEvenOnDifferentHost() {
        assertNull(NewsFilter.reject("https://feeds.arstechnica.com/arstechnica/index", null, item("https://arstechnica.com/ai/2026/10/story/"), now))
    }

    @Test fun rejectsInjectedOffSiteLink() {
        val reason = NewsFilter.reject("https://www.theverge.com/rss/index.xml", "https://www.theverge.com", item("https://totally-real-news.example/shock"), now)
        assertNotNull(reason); assertTrue(reason!!.startsWith("links off-site"))
    }

    @Test fun usesFeedDeclaredSiteForFeedProxies() {
        // Feed served by a proxy (feedburner) but declaring its site in <channel><link>.
        assertNull(NewsFilter.reject("https://feeds.feedburner.com/TheHackersNews", "https://thehackernews.com", item("https://thehackernews.com/2026/10/x.html"), now))
        assertNotNull(NewsFilter.reject("https://feeds.feedburner.com/TheHackersNews", "https://thehackernews.com", item("https://evil.example/x"), now))
    }

    @Test fun countryDomainsShareBrand() {
        assertEquals("bbc.co.uk", NewsFilter.registrableDomain("feeds.bbc.co.uk"))
        assertNull(NewsFilter.reject("https://feeds.bbci.co.uk/news/technology/rss.xml", "https://www.bbc.co.uk/news/technology", item("https://www.bbc.com/news/articles/abc"), now))
    }

    @Test fun rejectsMissingFutureAndStaleDates() {
        val feed = "https://www.wired.com/feed/rss"
        assertEquals("no publish date", NewsFilter.reject(feed, null, RawItem("t", "https://www.wired.com/a", "", null, null), now))
        assertEquals("dated in the future", NewsFilter.reject(feed, null, item("https://www.wired.com/a", ageH = -10), now))
        assertEquals("too old", NewsFilter.reject(feed, null, item("https://www.wired.com/a", ageH = 100), now))
    }

    @Test fun rejectsNonWebLinksBlankTitlesAndCommerce() {
        val feed = "https://www.wired.com/feed/rss"
        assertEquals("not a web link", NewsFilter.reject(feed, null, item("javascript:alert(1)"), now))
        assertEquals("no headline", NewsFilter.reject(feed, null, item("https://www.wired.com/a", title = " "), now))
        assertEquals("commerce/deals content", NewsFilter.reject(feed, null, item("https://www.wired.com/a", title = "Nike Promo Codes: 30% Off"), now))
        assertEquals("commerce/deals content", NewsFilter.reject(feed, null, item("https://www.wired.com/a", title = "Last 24 hours to exhibit at Disrupt 2026"), now))
        assertNull(NewsFilter.reject(feed, null, item("https://www.wired.com/a", title = "Judge dismisses antitrust lawsuits over AI Overviews"), now))
    }

    @Test fun siteLinkFromRssAndAtom() {
        assertEquals("https://example.com/", NewsFilter.siteLink("<rss><channel><title>x</title><link>https://example.com/</link><item><link>https://other/</link></item></channel></rss>"))
        assertEquals("https://blog.example.org/", NewsFilter.siteLink("<feed><link rel=\"self\" href=\"https://feeds.x/atom\"/><link rel=\"alternate\" href=\"https://blog.example.org/\"/><entry></entry></feed>"))
    }

    // ---------------------------------------------------------------- feed parsing

    @Test fun parsesRssWithCdataAndMediaAndSkipsBrokenTail() {
        val xml = """<?xml version="1.0"?><rss xmlns:media="http://search.yahoo.com/mrss/"><channel><link>https://site.com</link>
            <item><title><![CDATA[Chip maker ships 2nm parts]]></title><link>https://site.com/a</link>
              <description><![CDATA[<p>First <b>para</b>.</p>]]></description><pubDate>Thu, 01 Oct 2026 17:00:00 GMT</pubDate>
              <media:content url="https://img/a.jpg" medium="image"/></item>
            <item><title>Second</title><link>https://site.com/b</link><pubDate>Thu, 01 Oct 2026 18:00:00 +0000</pubDate></item>
            <item><title>Broken &bogus; <unclosed"""
        val items = RssParser.parse(xml)
        assertTrue(items.size >= 2)
        assertEquals("Chip maker ships 2nm parts", items[0].title)
        assertEquals("https://img/a.jpg", items[0].imageUrl)
        assertNotNull(items[1].published)
    }

    @Test fun parsesAtom() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Atom post</title>
            <link rel="alternate" href="https://blog.x/post"/><published>2026-10-01T19:11:00Z</published>
            <summary type="html">&lt;p&gt;Hello world summary text&lt;/p&gt;</summary></entry></feed>"""
        val items = RssParser.parse(xml)
        assertEquals(1, items.size)
        assertEquals("https://blog.x/post", items[0].link)
        assertNotNull(items[0].published)
    }

    // ---------------------------------------------------------------- term mining (no hardcoded terms)

    @Test fun learnsAcronymDefinitionsFromText() {
        val pairs = TermMiner.abbreviations(
            "Unlike RAG, Cache-Augmented Generation (CAG) preloads documents. We propose Mixture-of-Translators (MoT), a framework. " +
                "Results in 2024 (USA) were good (see Fig. 2). Low-Rank Adaptation (LoRA) is cheap."
        ).toMap()
        assertEquals("Cache-Augmented Generation", pairs["CAG"])
        assertEquals("Mixture-of-Translators", pairs["MoT"])
        assertEquals("Low-Rank Adaptation", pairs["LoRA"])
        assertFalse(pairs.containsKey("USA"))
    }

    @Test fun ranksNewTermsAboveEstablishedOnes() {
        val d = { i: Int, text: String -> TermMiner.Doc("r$i", "Paper $i", text, "https://arxiv.org/abs/$i", "arXiv", "paper", now) }
        val recent = (1..4).map { d(it, "We study Speculative Cascade Decoding (SCD) for faster inference. Retrieval-Augmented Generation (RAG) is used too.") }
        val baseline = (1..30).map { TermMiner.Doc("b$it", "Old $it", "Retrieval-Augmented Generation (RAG) systems.", "https://x/$it", "Blog", "news", 0) }
        val ranked = TermMiner.candidates(recent, baseline)
        val scd = ranked.indexOfFirst { it.shortForm == "SCD" }
        val rag = ranked.indexOfFirst { it.shortForm == "RAG" }
        assertTrue("SCD should be a candidate", scd >= 0)
        assertTrue("new term must outrank an established one", rag == -1 || scd < rag)
    }

    @Test fun explanationsAreVerbatimSourceSentences() {
        val doc = TermMiner.Doc(
            "p1", "t", "Cache-augmented generation (CAG) is a technique that preloads all relevant documents into the model's context. " +
                "It is used in question answering systems where the knowledge base is small. We report 12% gains.",
            "https://arxiv.org/abs/1", "arXiv", "paper", now,
        )
        val def = TermMiner.definition("Cache-augmented generation", "CAG", listOf(doc))
        assertNotNull(def); assertTrue(doc.text.contains(def!!.sentence))
        val use = TermMiner.usage("Cache-augmented generation", "CAG", listOf(doc), def.sentence)
        // The usage sentence mentions "It", not the term, so it must not be attributed to the term.
        assertTrue(use == null || doc.text.contains(use.sentence))
    }

    // Real OpenAlex per-year counts, fetched 2026-10-05.
    @Test fun emergenceYearSeparatesNewFromEstablished() {
        val sdk = mapOf(2015 to 101, 2016 to 108, 2017 to 101, 2018 to 111, 2019 to 126, 2020 to 108, 2021 to 123, 2022 to 145, 2023 to 149, 2024 to 152, 2025 to 149, 2026 to 142)
        val vla = mapOf(2022 to 1, 2023 to 9, 2024 to 63, 2025 to 590, 2026 to 2395)
        val opsd = mapOf(2026 to 208)
        val fde = mapOf(2026 to 8)
        val flowMatching = mapOf(2015 to 20, 2016 to 22, 2017 to 15, 2018 to 23, 2019 to 21, 2020 to 17, 2021 to 13, 2022 to 26, 2023 to 69, 2024 to 315, 2025 to 1023, 2026 to 3038)
        assertNull("SDK has been in steady use for decades", TermMiner.emergenceYear(sdk, 2026))
        assertEquals(2024, TermMiner.emergenceYear(vla, 2026))
        assertEquals(2026, TermMiner.emergenceYear(opsd, 2026))
        assertNull("8 works is too little evidence", TermMiner.emergenceYear(fde, 2026))
        // An older, unrelated meaning of the phrase doesn't hide the recent surge.
        assertEquals(2024, TermMiner.emergenceYear(flowMatching, 2026))
        // Booming but long-established ideas are not "new" (these slipped through before this rule).
        val dp = mapOf(2016 to 318, 2017 to 489, 2018 to 629, 2019 to 846, 2020 to 1131, 2021 to 1421, 2022 to 1707, 2023 to 2101, 2024 to 2869, 2025 to 4447, 2026 to 5201)
        val worldModels = mapOf(2016 to 231, 2017 to 267, 2018 to 288, 2019 to 304, 2020 to 351, 2021 to 354, 2022 to 365, 2023 to 530, 2024 to 803, 2025 to 1742, 2026 to 5655)
        val conformal = mapOf(2016 to 23, 2017 to 46, 2018 to 48, 2019 to 61, 2020 to 86, 2021 to 106, 2022 to 162, 2023 to 286, 2024 to 598, 2025 to 1040, 2026 to 2288)
        assertNull("differential privacy (2006)", TermMiner.emergenceYear(dp, 2026))
        assertNull("world models", TermMiner.emergenceYear(worldModels, 2026))
        assertNull("conformal prediction", TermMiner.emergenceYear(conformal, 2026))
        val opd = mapOf(2016 to 1, 2017 to 3, 2018 to 6, 2019 to 9, 2020 to 15, 2021 to 13, 2022 to 21, 2023 to 27, 2024 to 41, 2025 to 95, 2026 to 632)
        val rlvr = mapOf(2024 to 4, 2025 to 227, 2026 to 950)
        assertEquals(2024, TermMiner.emergenceYear(opd, 2026))
        assertEquals(2025, TermMiner.emergenceYear(rlvr, 2026))
    }

    @Test fun groundingCheckRejectsInventedFacts() {
        val src = listOf("Cache-augmented generation preloads all relevant documents into the model's context, so no retrieval step is needed at answer time.")
        assertTrue(TermMiner.grounded("Cache-augmented generation preloads the relevant documents into the model's context, so no retrieval step is needed.", src))
        assertFalse("invented number", TermMiner.grounded("Cache-augmented generation preloads documents and is 40 times faster.", src))
        assertFalse("invented name", TermMiner.grounded("Cache-augmented generation was invented at Microsoft to preload documents.", src))
        assertFalse("unsupported claim", TermMiner.grounded("It dramatically lowers hospital costs for patients worldwide.", src))
    }
}
