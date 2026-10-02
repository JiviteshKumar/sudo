package com.technewz.app

import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.JobSources
import com.technewz.app.net.RawJob
import com.technewz.app.net.RssParser
import com.technewz.app.net.TrendingSources
import com.technewz.app.util.ScamFilter
import com.technewz.app.util.Skills
import com.technewz.app.util.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hits the real sources the app uses and checks the parsed output is sane.
 * Run with: gradlew :app:testDebugUnitTest --tests "*LiveSourcesTest*" -i
 */
class LiveSourcesTest {

    @Test
    fun feedsParseWithDatesAndLinks() = runBlocking {
        val now = System.currentTimeMillis()
        val results = Feeds.all.map { f -> async { f to runCatching { RssParser.parse(Http.get(f.url)) } } }.awaitAll()
        var healthy = 0
        val titles = mutableListOf<Pair<String, String>>()
        for ((feed, res) in results) {
            val items = res.getOrElse { println("FAIL  ${feed.name}: ${it.message}"); emptyList() }
            val dated = items.count { it.published != null }
            val fresh = items.count { (it.published ?: 0) > now - 72 * 3600_000L }
            val withText = items.count { Text.htmlToText(it.descriptionHtml).length > 80 }
            val withImg = items.count { it.imageUrl != null }
            val newest = items.maxByOrNull { it.published ?: 0 }
            println(
                "%-24s items=%3d dated=%3d fresh72h=%3d text=%3d img=%3d newest=%s".format(
                    feed.name, items.size, dated, fresh, withText, withImg, newest?.published?.let { Text.timeAgo(it) }
                )
            )
            if (items.isNotEmpty() && dated == items.size && items.all { it.link.startsWith("http") }) healthy++
            items.filter { (it.published ?: 0) > now - 48 * 3600_000L }.forEach { titles += feed.name to it.title }
        }
        println("Healthy feeds: $healthy / ${Feeds.all.size}")
        assertTrue("Too many broken feeds", healthy >= Feeds.all.size - 3)

        // Clustering sanity: print merged groups so false merges are visible.
        val toks = titles.map { Triple(it.first, it.second, Text.titleTokens(it.second)) }
        val seen = mutableSetOf<Int>()
        var clusters = 0
        toks.forEachIndexed { i, a ->
            if (i in seen) return@forEachIndexed
            val group = toks.indices.filter { j -> j != i && j !in seen && Text.sameStory(a.third, toks[j].third) }
            if (group.isNotEmpty()) {
                clusters++
                println("\nCLUSTER: [${a.first}] ${a.second}")
                group.forEach { j -> seen += j; println("     +  [${toks[j].first}] ${toks[j].second}") }
            }
        }
        println("\n$clusters multi-outlet clusters among ${titles.size} recent headlines")
    }

    @Test
    fun jobSourcesReturnValidListings() = runBlocking {
        val sources: List<Pair<String, suspend () -> List<RawJob>>> = listOf(
            "Remotive" to { JobSources.remotive() },
            "Remote OK" to { JobSources.remoteOk() },
            "The Muse" to { JobSources.theMuse() },
            "Arbeitnow" to { JobSources.arbeitnow() },
            "Greenhouse/anthropic" to { JobSources.greenhouse("anthropic") },
            "Greenhouse/stripe" to { JobSources.greenhouse("stripe") },
            "Lever/palantir" to { JobSources.lever("palantir") },
            "Ashby/openai" to { JobSources.ashby("openai") },
            "Ashby/linear" to { JobSources.ashby("linear") },
        )
        val me = Skills.normalize(listOf("Python", "PyTorch", "SQL", "Pandas", "Machine Learning", "Docker", "React", "Git"))
        var ok = 0
        for ((name, fetch) in sources) {
            val t0 = System.currentTimeMillis()
            val jobs = runCatching { fetch() }.getOrElse { println("FAIL  $name: ${it.message}"); emptyList() }
            val ms = System.currentTimeMillis() - t0
            val validUrl = jobs.count { it.url.startsWith("http") }
            val dated = jobs.count { it.postedAt != null }
            val described = jobs.count { it.description.length > 200 }
            val interns = jobs.count { it.employmentType == "Internship" }
            val remote = jobs.count { it.isRemote }
            val flagged = jobs.count { ScamFilter.flags(it.title + it.description).isNotEmpty() }
            println("%-22s jobs=%3d url=%3d dated=%3d desc=%3d intern=%3d remote=%3d flagged=%d  (%d ms)".format(name, jobs.size, validUrl, dated, described, interns, remote, flagged, ms))
            jobs.take(2).forEach { j ->
                val m = Skills.match(me, j.title + "\n" + j.description)
                println("     · ${j.title} — ${j.company} — ${j.location} — ${j.employmentType} — posted ${j.postedAt?.let { Text.timeAgo(it) }} — match=${m.score} have=${m.matched} gaps=${m.missing.take(5)}")
            }
            if (jobs.isNotEmpty() && validUrl == jobs.size) ok++
        }
        println("Healthy job sources: $ok / ${sources.size}")
        assertTrue(ok >= sources.size - 2)
    }

    @Test
    fun trendingSources() = runBlocking {
        listOf<Pair<String, suspend () -> List<TrendingSources.Item>>>(
            "HF models" to { TrendingSources.hfModels() },
            "HF papers" to { TrendingSources.hfPapers() },
            "GitHub repos" to { TrendingSources.githubRepos() },
        ).forEach { (name, f) ->
            val items = runCatching { f() }.getOrElse { println("FAIL $name: ${it.message}"); emptyList() }
            println("$name: ${items.size}")
            items.take(3).forEach { println("   ${it.title} | ${it.subtitle.take(60)} | ${it.metric}") }
        }
    }

    @Test
    fun textHelpers() {
        val s = Text.trimWords((1..80).joinToString(" ") { "word$it" }, 50)
        assertTrue(Text.wordCount(s) <= 50)
        assertTrue(Text.parseDate("Thu, 01 Oct 2026 17:00:00 GMT") != null)
        assertTrue(Text.parseDate("Tue, 29 Sep 2026 18:38:27 +0000") != null)
        assertTrue(Text.parseDate("2026-10-01T19:11:00Z") != null)
        assertTrue(Text.parseDate("2026-10-01T19:11:00.123-07:00") != null)
        assertTrue(Text.parseDate("Wed, 30 Sep 2026 20:01:45 EST") != null)
        assertTrue(Text.sameStory(Text.titleTokens("OpenAI launches GPT-6 with new reasoning mode"), Text.titleTokens("OpenAI launches GPT-6, its new reasoning model")))
        assertTrue(!Text.sameStory(Text.titleTokens("Apple announces new iPhone camera"), Text.titleTokens("Google announces new Pixel camera features")))
        val m = Skills.match(setOf("Python", "SQL"), "We need Python, SQL, Kubernetes and Spring 2027 interns who love React.")
        println("match=$m")
        assertTrue("Spring" !in m.missing)
        assertTrue(ScamFilter.flags("Pay a registration fee and contact us on WhatsApp").size == 2)
    }
}
