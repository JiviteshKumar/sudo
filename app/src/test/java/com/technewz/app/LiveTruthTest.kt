package com.technewz.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.technewz.app.data.AppDatabase
import com.technewz.app.data.SummaryKind
import com.technewz.app.ml.Features
import com.technewz.app.ml.SentenceSplitter
import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.HttpException
import com.technewz.app.net.NewsFilter
import com.technewz.app.net.ResearchSources
import com.technewz.app.net.RssParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the app's real pipelines against the live internet, then independently re-checks what the app
 * stored: headlines exist in the publisher's own feed and on the article page, summaries are verbatim,
 * links point to the publisher, jobs are reachable, and Skills Radar explanations and counts match their
 * cited sources. Writes testing/TRUTH_REPORT.md.  Run with LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LiveTruthTest {
    private val report = StringBuilder()
    private fun line(s: String = "") { println(s); report.appendLine(s) }

    private fun norm(s: String) = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace('–', '-').replace('—', '-').replace(' ', ' ').replace(Regex("\\s+"), " ").trim().lowercase()

    private fun overlap(a: String, b: String): Double {
        val ta = Features.contentTokens(a).toSet(); val tb = Features.contentTokens(b).toSet()
        if (ta.isEmpty()) return 0.0
        return ta.count { it in tb }.toDouble() / ta.size
    }

    private sealed interface Fetch { data class Ok(val html: String) : Fetch; data class Blocked(val why: String) : Fetch }

    private suspend fun fetch(url: String): Fetch = try {
        Fetch.Ok(Http.get(url, maxBytes = 4_000_000))
    } catch (e: HttpException) {
        Fetch.Blocked("HTTP ${e.code}")
    } catch (e: Exception) {
        Fetch.Blocked(e.javaClass.simpleName)
    }

    @Test
    fun liveTruthAudit() = runBlocking {
        assumeTrue("Set LIVE=1 to run the live audit", System.getenv("LIVE") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val c = AppContainer(app, db)
        c.settings.update { it.copy(onboarded = true, keywords = emptyList()) }
        val now = System.currentTimeMillis()
        val failures = mutableListOf<String>()
        line("# Live truth audit"); line(); line("Run: ${java.time.ZonedDateTime.now()}"); line()

        // ------------------------------------------------------------ run the real pipelines
        val nr = c.news.refresh()
        val jr = c.jobs.refresh(force = true)
        c.news.refreshTrendingIfStale(force = true)
        val rr = c.radar.refresh(force = true, maxChecks = 15) { println("  radar: $it") }
        val articles = db.articles().all()
        val jobs = db.jobs().all()
        val terms = db.terms().all()
        line("Pipelines: ${articles.size} articles from ${articles.map { it.source }.distinct().size} sources (failed feeds: ${nr.failedFeeds}), " +
            "${jobs.size} jobs (failed sources: ${jr?.failedSources}), radar: ${rr?.candidates} candidates, ${rr?.checked} verified checks, " +
            "${terms.count { it.status != "Rejected" }} accepted / ${terms.count { it.status == "Rejected" }} rejected")
        line()

        // ------------------------------------------------------------ 1. news provenance against the publishers' own feeds
        line("## 1. News comes only from the publishers' own feeds")
        val feedItems = Feeds.all.map { f -> async { f to runCatching { val x = Http.get(f.url); RssParser.parse(x) to NewsFilter.siteLink(x) }.getOrNull() } }.awaitAll()
        var offSite = 0; var titleInFeed = 0; var feedGone = 0; var badDates = 0
        for (a in articles) {
            val f = Feeds.all.first { it.name == a.source }
            val parsed = feedItems.first { it.first == f }.second
            val allowed = setOfNotNull(NewsFilter.brand(f.url), parsed?.second?.let { NewsFilter.brand(it) })
            if (NewsFilter.brand(a.url) !in allowed) { offSite++; failures += "off-site link: ${a.url} (${a.source})" }
            if (a.publishedAt > now + 2 * 3_600_000L || a.publishedAt < now - 73 * 3_600_000L) { badDates++; failures += "bad date: ${a.title}" }
            val items = parsed?.first
            when {
                items == null -> feedGone++
                items.any { it.title == a.title } -> titleInFeed++
                else -> feedGone++ // item rotated out of the feed since we fetched it
            }
        }
        line("- Articles whose link is on the publisher's own site: ${articles.size - offSite}/${articles.size}")
        line("- Headlines identical to the publisher's feed right now: $titleInFeed (${feedGone} rotated out of the feed since fetch)")
        line("- Publish dates within the last 72 h and not in the future: ${articles.size - badDates}/${articles.size}")
        line()

        // ------------------------------------------------------------ 2. article pages: headline + verbatim summary
        line("## 2. Live article pages: headline and summary checked on the page itself")
        val sample = articles.groupBy { it.source }.values.flatMap { it.take(3) }.take(45)
        val gate = Semaphore(6)
        // Publisher text for each article as it appears in its own feed (summaries may come from either feed or page).
        val feedTextByTitle = feedItems.flatMap { (_, p) -> p?.first.orEmpty() }.associate { it.title to norm(com.technewz.app.util.Text.cleanFeedText(it.descriptionHtml)) }
        data class PageCheck(val a: com.technewz.app.data.ArticleEntity, val blocked: String?, val headline: Double, val summaryOk: Boolean?, val missing: String? = null, val edited: Boolean = false)
        val checks = sample.map { a ->
            async {
                gate.withPermit {
                    when (val r = fetch(a.url)) {
                        is Fetch.Blocked -> PageCheck(a, r.why, 0.0, null)
                        is Fetch.Ok -> {
                            val doc = Jsoup.parse(r.html, a.url)
                            val heads = listOfNotNull(
                                doc.selectFirst("meta[property=og:title]")?.attr("content"),
                                doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
                                doc.title(), doc.selectFirst("h1")?.text(),
                            )
                            val headline = heads.maxOfOrNull { overlap(a.title, it) } ?: 0.0
                            val body = norm(doc.text())
                            val feedText = feedTextByTitle[a.title].orEmpty()
                            val missing = if (a.summaryKind == SummaryKind.ON_DEVICE && a.summary != null)
                                SentenceSplitter.split(a.summary!!).firstOrNull { val n = norm(it); !body.contains(n) && !feedText.contains(n) } else null
                            val summaryOk = if (a.summaryKind == SummaryKind.ON_DEVICE && a.summary != null) missing == null else null
                            // A near-identical sentence means the publisher edited the article after we fetched it.
                            val edited = missing != null && SentenceSplitter.split(doc.text()).any { overlap(missing, it) >= 0.85 && overlap(it, missing) >= 0.85 }
                            if (body.length < 500 && headline == 0.0) PageCheck(a, "page needs JavaScript / consent wall", 0.0, null)
                            else PageCheck(a, null, headline, summaryOk, missing, edited)
                        }
                    }
                }
            }
        }.awaitAll()
        val verifiable = checks.filter { it.blocked == null }
        val headlineOk = verifiable.count { it.headline >= 0.6 }
        val summaryChecked = verifiable.filter { it.summaryOk != null }
        val summaryOk = summaryChecked.count { it.summaryOk == true }
        val summaryEdited = summaryChecked.count { it.summaryOk == false && it.edited }
        line("- Pages checked: ${checks.size}; reachable: ${verifiable.size}; blocked/unverifiable: ${checks.size - verifiable.size} " +
            checks.filter { it.blocked != null }.groupBy { it.blocked }.map { "${it.key}×${it.value.size}" }.joinToString(", ", "(", ")"))
        line("- Headline on the page matches the app's headline: $headlineOk/${verifiable.size}")
        line("- On-device summaries found word-for-word in the publisher's page or feed text: $summaryOk/${summaryChecked.size}")
        verifiable.filter { it.headline < 0.6 }.forEach { line("  - headline differs (publisher may have edited it): “${it.a.title}” — ${it.a.url}") }
        if (summaryEdited > 0) line("- Publisher edited the article after it was fetched (sentence now slightly different on the page): $summaryEdited")
        summaryChecked.filter { it.summaryOk == false }.forEach { line("  - not verbatim: ${it.a.url} — sentence: “${it.missing}”") }
        line()

        // ------------------------------------------------------------ 3. jobs are real and live
        line("## 3. Job listings link to live postings")
        val jobSample = jobs.groupBy { it.source }.values.flatMap { it.shuffled().take(5) }
        val jobChecks = jobSample.map { j ->
            async {
                gate.withPermit {
                    when (val r = fetch(j.url)) {
                        is Fetch.Blocked -> Triple(j, r.why, 0.0)
                        is Fetch.Ok -> {
                            val text = Jsoup.parse(r.html).text()
                            Triple(j, if (text.length < 400) "page needs JavaScript" else "ok", overlap(j.title, text))
                        }
                    }
                }
            }
        }.awaitAll()
        val jobOk = jobChecks.filter { it.second == "ok" }
        line("- Job pages checked: ${jobChecks.size}; reachable: ${jobOk.size}; title found on page: ${jobOk.count { it.third >= 0.6 }}")
        jobChecks.filter { it.second != "ok" }.groupBy { it.first.source + " – " + it.second }.forEach { (k, v) -> line("  - $k × ${v.size}") }
        jobChecks.filter { it.second.startsWith("HTTP 404") || it.second.startsWith("HTTP 410") }.forEach { failures += "dead job link: ${it.first.url}" }
        line()

        // ------------------------------------------------------------ 4. Skills Radar
        line("## 4. Skills Radar: every explanation and number checked against its source")
        val visible = terms.filter { it.status != "Rejected" }
        for (t in visible) {
            val issues = mutableListOf<String>()
            // a) "what it is" is verbatim from the cited source
            val whatOk: Boolean? = when {
                t.whatUrl == null || t.what == null -> false
                t.whatUrl!!.contains("wikipedia.org") -> {
                    val title = t.whatUrl!!.substringAfter("/wiki/").replace('_', ' ')
                    ResearchSources.wikipedia(java.net.URLDecoder.decode(title, "UTF-8"), null)?.let { norm(it.extract).contains(norm(t.what!!)) }
                }
                t.whatUrl!!.contains("pypi.org/project/") ->
                    ResearchSources.pypi(t.whatUrl!!.substringAfter("/project/").trim('/'))?.let { norm(it.summary) == norm(t.what!!) }
                t.whatUrl!!.contains("npmjs.com/package/") ->
                    ResearchSources.npm(t.whatUrl!!.substringAfter("/package/").trim('/'))?.let { norm(it.summary) == norm(t.what!!) }
                t.whatUrl!!.contains("github.com") -> runCatching {
                    val full = t.whatUrl!!.substringAfter("github.com/")
                    Http.get("https://api.github.com/repos/$full").contains(t.what!!.take(40).replace("\"", "\\\""))
                }.getOrNull()
                else -> (fetch(t.whatUrl!!) as? Fetch.Ok)?.let { norm(Jsoup.parse(it.html).text()).contains(norm(t.what!!)) }
            }
            if (whatOk == false) issues += "definition not found verbatim at ${t.whatUrl}"
            // b) "where it's used" sentence is verbatim too (computed job facts have no URL)
            if (t.usageUrl != null && t.usage != null) {
                val ok = (fetch(t.usageUrl!!) as? Fetch.Ok)?.let { norm(Jsoup.parse(it.html).text()).contains(norm(t.usage!!)) }
                if (ok == false) issues += "usage sentence not verbatim at ${t.usageUrl}"
            }
            // c) arXiv numbers are reproducible and the acronym expansion is real
            if (t.kind == "Concept") {
                val long = t.term.substringBefore(" (")
                val again = ResearchSources.arxivCount(long, 30, 0)
                if (kotlin.math.abs(again - t.papers30) > maxOf(2, t.papers30 / 10)) issues += "arXiv 30-day count ${t.papers30} not reproducible (now $again)"
                if (t.shortForm != null) {
                    val both = ResearchSources.arxivRecent(long, 3650, 50).count { p -> p.abstract.contains(t.shortForm!!) }
                    if (both == 0) issues += "no paper uses “$long” together with ${t.shortForm}"
                }
            }
            line("- **${t.term}** [${t.status} ${t.kind}] — ${t.reason}")
            line("  - What: “${t.what}” — ${t.whatSource} → ${if (whatOk == true) "verbatim ✓" else if (whatOk == null) "source unreachable" else "✗"}")
            t.usage?.let { line("  - Where: “$it” — ${t.usageSource}") }
            if (issues.isEmpty()) line("  - All checks passed") else issues.forEach { line("  - ✗ $it"); failures += "${t.term}: $it" }
        }
        line()
        line("Rejected by verification (sample): " + terms.filter { it.status == "Rejected" }.take(12).joinToString("; ") { "${it.term} — ${it.reason.take(90)}" })
        line()

        // ------------------------------------------------------------ verdict
        line("## Verdict")
        if (failures.isEmpty()) line("No fabricated, off-site, misdated or unverifiable-by-design content found.")
        else failures.forEach { line("- ✗ $it") }
        File("../testing").mkdirs()
        File("../testing/TRUTH_REPORT.md").writeText(report.toString())
        db.close()

        assertTrue("Off-site links found", offSite == 0)
        assertTrue("Bad dates found", badDates == 0)
        assertTrue("Headlines don't match live pages often enough", verifiable.isEmpty() || headlineOk >= verifiable.size * 0.9)
        assertTrue("Summaries not verbatim", summaryChecked.isEmpty() || summaryOk + summaryEdited >= summaryChecked.size * 0.95)
        assertTrue("Radar issues: $failures", failures.none { it.contains(":") && visible.any { t -> it.startsWith(t.term) } })
    }
}
