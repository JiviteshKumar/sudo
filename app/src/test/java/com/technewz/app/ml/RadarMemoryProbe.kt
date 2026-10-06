package com.technewz.app.ml

import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.JobSources
import com.technewz.app.net.ResearchSources
import com.technewz.app.net.RssParser
import com.technewz.app.data.Section
import com.technewz.app.util.Text
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the Skills Radar's mining step on today's real corpus and reports peak heap use, so it can be run
 * with a phone-sized heap (TEST_HEAP=256m) to catch out-of-memory crashes.  Run with LIVE=1.
 */
class RadarMemoryProbe {
    private fun usedMb(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1_048_576 }

    @Test fun mineRealCorpusWithinPhoneHeap(): Unit = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val now = System.currentTimeMillis()
        val papers = ResearchSources.arxivListings.flatMap { url -> runCatching { RssParser.parse(Http.get(url)) }.getOrDefault(emptyList()) }
            .distinctBy { it.link }.map { TermMiner.Doc(it.link, it.title, Text.cleanFeedText(it.descriptionHtml).take(1500), it.link, "arXiv", "paper", it.published ?: now) } +
            ResearchSources.arxivLastThreeMonths().map { TermMiner.Doc(it.url, it.title, it.abstract.take(1500), it.url, "arXiv", "paper", it.published) }
        val jobs = listOf<suspend () -> List<com.technewz.app.net.RawJob>>({ JobSources.remotive() }, { JobSources.remoteOk() }, { JobSources.arbeitnow() },
            { JobSources.greenhouse("anthropic") }, { JobSources.greenhouse("databricks") }, { JobSources.greenhouse("stripe") }, { JobSources.lever("palantir") }, { JobSources.ashby("openai") })
            .flatMap { runCatching { it() }.getOrDefault(emptyList()) }
            .map { TermMiner.Doc(it.externalId, it.title, it.description.take(4000), it.url, it.source, "job", it.postedAt ?: now, it.company) }
        val base = ResearchSources.arxivBaseline().map { TermMiner.Doc(it.url, it.title, it.abstract.take(1500), it.url, "arXiv", "paper", it.published) } +
            Feeds.all.filter { it.section == Section.AI && !it.name.startsWith("arXiv") }.flatMap { f -> runCatching { RssParser.parse(Http.get(f.url)) }.getOrDefault(emptyList()) }
                .map { TermMiner.Doc(it.link, it.title, Text.htmlToText(it.descriptionHtml).take(1500), it.link, "blog", "news", it.published ?: 0) }
        System.gc(); Thread.sleep(300)
        val before = usedMb()
        var peak = before
        val sampler = Thread { while (!Thread.currentThread().isInterrupted) { peak = maxOf(peak, usedMb()); try { Thread.sleep(20) } catch (_: InterruptedException) { break } } }.apply { isDaemon = true; start() }
        val t0 = System.currentTimeMillis()
        val result = runCatching { TermMiner.candidates(papers + jobs, base) }
        sampler.interrupt()
        val r = Runtime.getRuntime()
        println("corpus: ${papers.size} papers, ${jobs.size} jobs, ${base.size} baseline docs; heap limit ${r.maxMemory() / 1_048_576} MB")
        println("mining: ${result.map { "${it.size} candidates" }.getOrElse { "FAILED: $it" }} in ${System.currentTimeMillis() - t0} ms; heap before ${before} MB, peak ${peak} MB (+${peak - before} MB)")
        result.getOrThrow()
        Unit
    }
}
