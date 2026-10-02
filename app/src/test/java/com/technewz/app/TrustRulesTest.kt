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

    @Test fun groundingCheckRejectsInventedFacts() {
        val src = listOf("Cache-augmented generation preloads all relevant documents into the model's context, so no retrieval step is needed at answer time.")
        assertTrue(TermMiner.grounded("Cache-augmented generation preloads the relevant documents into the model's context, so no retrieval step is needed.", src))
        assertFalse("invented number", TermMiner.grounded("Cache-augmented generation preloads documents and is 40 times faster.", src))
        assertFalse("invented name", TermMiner.grounded("Cache-augmented generation was invented at Microsoft to preload documents.", src))
        assertFalse("unsupported claim", TermMiner.grounded("It dramatically lowers hospital costs for patients worldwide.", src))
    }
}
