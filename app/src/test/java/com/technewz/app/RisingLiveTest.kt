package com.technewz.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.technewz.app.data.AppDatabase
import com.technewz.app.data.RisingRepoEntity
import com.technewz.app.net.GitHubRising
import com.technewz.app.net.Http
import com.technewz.app.net.HttpException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Independent checks of "Rising on GitHub" against GitHub itself, used by [RisingLiveTest] and the full
 * live truth audit. Pages are read from github.com (not the API) so the audit doesn't use up the app's
 * API allowance, and so it checks what a person opening the link would actually see.
 */
object RisingAudit {
    data class Outcome(val lines: List<String>, val failures: List<String>, val verified: Int, val checked: Int)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    suspend fun run(listed: List<RisingRepoEntity>): Outcome {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val trendingNow = runCatching { GitHubRising.trendingThisWeek() }.getOrNull()?.associateBy { it.fullName.lowercase() }
        var ok = 0
        for (r in listed.sortedBy { it.rankAll ?: (100 + (it.rankAi ?: 0)) }) {
            val problems = mutableListOf<String>()
            var unverifiable: String? = null
            // 1. The link opens the real repo, and its description is the author's own (as GitHub shows it).
            try {
                val page = Jsoup.parse(Http.get(r.url, maxBytes = 3_000_000))
                val shown = page.selectFirst("meta[property=og:description]")?.attr("content").orEmpty() +
                    " " + page.selectFirst("meta[name=description]")?.attr("content").orEmpty()
                val title = page.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                if (!title.contains(r.fullName, ignoreCase = true)) problems += "page title “$title” isn't ${r.fullName}"
                if (!norm(shown).contains(norm(r.description).take(80))) problems += "description differs from GitHub's"
            } catch (e: HttpException) {
                if (e.code == 404) problems += "link is dead (404)" else unverifiable = "HTTP ${e.code}"
            } catch (e: Exception) {
                unverifiable = e.javaClass.simpleName
            }
            // 2. "What it does" is quoted from the README: every sentence appears in the README's text.
            r.readme?.let { intro ->
                val md = runCatching { GitHubRising.readme(r.fullName) }.getOrNull()
                if (md == null) unverifiable = unverifiable ?: "README unreachable"
                else {
                    // Same markup removal as the app (links → their text, tags, **bold**) so only the words are compared.
                    val plain = norm(GitHubRising.clean(md.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").replace(Regex("<[^>]+>"), " ")))
                    val missing = com.technewz.app.ml.SentenceSplitter.split(intro)
                        .map { norm(it.removeSuffix("…")) }.filter { it.length > 15 && !plain.contains(it) }
                    if (missing.isNotEmpty()) problems += "README quote not found: “${missing.first().take(60)}…”"
                }
            }
            // 3. The weekly growth matches GitHub Trending (it moves during the day, so ±25% or it left the page).
            if (r.growthSource == "GitHub Trending" && r.weekStars != null && trendingNow != null) {
                val live = trendingNow[r.fullName.lowercase()]
                if (live != null && kotlin.math.abs(live.weekStars - r.weekStars) > maxOf(50.0, r.weekStars * 0.25)) {
                    problems += "stars this week: app ${r.weekStars} vs GitHub now ${live.weekStars}"
                }
            }
            val tag = listOfNotNull(r.rankAll?.let { "All #${it + 1}" }, r.rankAi?.let { "AI/ML #${it + 1}" }).joinToString(", ")
            when {
                problems.isNotEmpty() -> { lines += "- ✗ ${r.fullName} ($tag): ${problems.joinToString("; ")}"; failures += "${r.fullName}: ${problems.joinToString("; ")}" }
                unverifiable != null -> lines += "- ? ${r.fullName} ($tag): could not verify ($unverifiable)"
                else -> { ok++; lines += "- ✓ ${r.fullName} ($tag) — ${r.growthLabel} [${r.growthSource}]" }
            }
        }
        lines.add(0, "- Repos verified against GitHub (link, description, README quote, weekly stars): $ok/${listed.size}")
        return Outcome(lines, failures, ok, listed.size)
    }
}

/** Runs the real "Rising on GitHub" update against GitHub and audits the result. Run with LIVE=1. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RisingLiveTest {
    @Test fun risingOnGitHubIsTrue() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val c = AppContainer(app, db)
        val r = c.rising.refresh(scheduled = true)
        val listed = db.rising().listed()
        val all = listed.filter { it.rankAll != null }.sortedBy { it.rankAll }
        val ai = listed.filter { it.rankAi != null }.sortedBy { it.rankAi }
        println("result: $r")
        println("ALL (${all.size}):"); all.forEach { println("  ${it.rankAll!! + 1}. ${it.fullName} | ${it.growthLabel} | ${it.description.take(70)}\n      README: ${it.readme?.take(110)}") }
        println("AI/ML (${ai.size}):"); ai.forEach { println("  ${it.rankAi!! + 1}. ${it.fullName} | ${it.growthLabel} [${it.growthSource}] | topics: ${it.topics.take(60)}") }
        val audit = RisingAudit.run(listed)
        audit.lines.forEach(::println)
        File("../testing").mkdirs()
        File("../testing/RISING_REPORT.md").writeText(
            "# Rising on GitHub — live check\n\nRun: ${java.time.ZonedDateTime.now()}\n\n" +
                "## All of GitHub\n" + all.joinToString("\n") { "${it.rankAll!! + 1}. [${it.fullName}](${it.url}) — ${it.growthLabel} (${it.growthSource})" } +
                "\n\n## AI/ML\n" + ai.joinToString("\n") { "${it.rankAi!! + 1}. [${it.fullName}](${it.url}) — ${it.growthLabel} (${it.growthSource})" } +
                "\n\n## Verification\n" + audit.lines.joinToString("\n") + "\n"
        )
        db.close()
        assertTrue("GitHub was unreachable or rate-limited: $r", !r.unreachable && !r.limited)
        assertTrue("expected a full list, got ${all.size}", all.size >= 10)
        assertTrue("Audit failures: ${audit.failures}", audit.failures.isEmpty())
    }
}
