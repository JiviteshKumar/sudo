package com.technewz.app

import com.technewz.app.data.Rising
import com.technewz.app.net.GitHubRising
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** "Rising on GitHub": parsing, README quoting, growth evidence, the keep-or-renew rule and the schedule. */
class RisingTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val day = 86_400_000L
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun repo(
        name: String = "acme/tool", description: String = "A fast tool.", topics: List<String> = emptyList(),
        archived: Boolean = false, fork: Boolean = false, pushedAt: Long = System.currentTimeMillis(),
    ) = GitHubRising.Repo(name, "https://github.com/$name", description, "Python", topics, 1000, 10, 0, pushedAt, archived, fork)

    // Same structure as github.com/trending (Oct 2026), trimmed: a sponsor link and the star button come first.
    private val trendingHtml = """
        <html><body>
        <article class="Box-row">
          <div class="float-right"><a href="/login?return_to=%2Facme%2Frocket">Star</a></div>
          <h2 class="h3 lh-condensed"><a href="/acme/rocket" class="Link"><span class="text-normal">acme /</span> rocket</a></h2>
          <p class="col-9 color-fg-muted my-1">Launch &amp; monitor rockets from your terminal.</p>
          <div class="f6 color-fg-muted mt-2">
            <span><span itemprop="programmingLanguage">Rust</span></span>
            <a href="/acme/rocket/stargazers" class="Link">12,345</a>
            <a href="/acme/rocket/forks" class="Link">678</a>
            <span class="d-inline-block float-sm-right">4,321 stars this week</span>
          </div>
        </article>
        <article class="Box-row">
          <a href="/sponsors/someone">Sponsor</a>
          <h2 class="h3 lh-condensed"><a href="/beta/agentkit" class="Link"><span>beta /</span> agentkit</a></h2>
          <div class="f6"><a href="/beta/agentkit/stargazers">987</a><span class="float-sm-right">1 star this week</span></div>
        </article>
        <article class="Box-row"><h2><a href="/no-week/repo">x</a></h2><p>No weekly count shown.</p></article>
        </body></html>
    """.trimIndent()

    @Test fun parsesGitHubTrendingRows() {
        val rows = GitHubRising.parseTrending(trendingHtml)
        assertEquals(listOf("acme/rocket", "beta/agentkit"), rows.map { it.fullName })
        val r = rows.first()
        assertEquals("Launch & monitor rockets from your terminal.", r.description)
        assertEquals("Rust", r.language)
        assertEquals(12345, r.stars)
        assertEquals(4321, r.weekStars)
        assertEquals(1, rows[1].weekStars)
    }

    @Test fun readmeIntroIsTheFirstRealParagraphQuoted() {
        val md = """
            <p align="center"><img src="logo.png" alt="logo"></p>
            <h1 align="center">Rocket</h1>

            [![CI](https://img.shields.io/badge/ci-passing-green)](https://ci) [![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

            English | [简体中文](README_zh.md)

            # Rocket

            > [!NOTE]
            > This project is in beta.

            **Rocket** is a command-line tool that launches and monitors [model rockets](https://example.com) from your terminal. It talks to the flight computer over USB and streams telemetry live. A third sentence goes on about many more things so that the whole paragraph runs well past the sixty word limit that the app applies to every quote it shows, which keeps cards short and readable on a phone screen at all times.

            ## Install
            ```bash
            pip install rocket
            ```
        """.trimIndent()
        val intro = GitHubRising.readmeIntro(md)!!
        assertTrue(intro, intro.startsWith("Rocket is a command-line tool that launches and monitors model rockets from your terminal."))
        assertTrue(intro.contains("streams telemetry live."))
        assertFalse("cut to whole sentences within 60 words", intro.contains("A third sentence"))
        assertFalse(intro.contains("**") || intro.contains("]("))
    }

    @Test fun readmeIntroSkipsImageCaptionsAndNonSentences() {
        // Shapes seen in real READMEs (Oct 2026): a captioned preview image, a language switcher, a tagline
        // block that ends in a platform list, and a Chinese block quote.
        val captioned = """
            <p align="center"><b>English</b> · <a href="README.zh-CN.md">简体中文</a> · <a href="README.ja.md">日本語</a></p>

            <h3 align="center">Run a state-of-the-art model on your own gaming PC</h3>

            <p align="center"><a href="v.mp4"><img src="preview.webp" alt="A voxel garden"></a><br>
            <sub>A voxel pagoda garden, 1 shot prompt running on an RTX 5070 with this tool and a long context window.</sub></p>

            Strata runs **[Qwen](https://hf.co/qwen)**, a large open model, on ordinary consumer hardware with one click.
        """.trimIndent()
        assertEquals("Strata runs Qwen, a large open model, on ordinary consumer hardware with one click.", GitHubRising.readmeIntro(captioned))

        val tagline = """
            <p align="center">
              <b>The PDF workbench; an open-source, modern PDF reader and editor, built in pure Rust.</b><br>
              Read, organize, combine, split and secure PDFs in a fast, native app.<br>
              macOS · Windows · Linux · FreeBSD · the web
            </p>
        """.trimIndent()
        assertEquals(
            "The PDF workbench; an open-source, modern PDF reader and editor, built in pure Rust. Read, organize, combine, split and secure PDFs in a fast, native app.",
            GitHubRising.readmeIntro(tagline),
        )

        val chinese = "> 你只需在 dsh 里装上这个插件，无需登录、注册、填 API Key 或任何其它操作，\n> 就能用上包括多个前沿模型在内的模型——完全免费，不限量。\n>\n> *All you do is install this plugin.*"
        assertEquals("你只需在 dsh 里装上这个插件，无需登录、注册、填 API Key 或任何其它操作， 就能用上包括多个前沿模型在内的模型——完全免费，不限量。", GitHubRising.readmeIntro(chinese))
    }

    @Test fun readmeWithoutProseHasNoIntro() {
        assertNull(GitHubRising.readmeIntro("# Tool\n\n- fast\n- small\n\n```\ncode here\n```\n\n| a | b |\n|---|---|"))
    }

    @Test fun growthUsesTheStrongestEvidence() {
        val now = at(2026, 10, 20, 8)
        // GitHub Trending's own weekly count comes first; sudo's measured gain is shown alongside it.
        val g1 = Rising.growth(7000, 50_000, 48_000, now - 3 * day, 0, now)!!
        assertEquals(1000.0, g1.perDay, 0.01)
        assertEquals("GitHub Trending", g1.source)
        assertTrue(g1.label, g1.label.startsWith("+7.0k stars this week") && g1.label.contains("+2.0k stars in 3 days"))
        // No weekly count: the gain measured between two of sudo's checks.
        val g2 = Rising.growth(null, 10_300, 10_000, now - 3 * day, 0, now)!!
        assertEquals(100.0, g2.perDay, 0.01)
        assertTrue(g2.source.startsWith("Measured by sudo"))
        // A brand-new repo: its average since launch.
        val g3 = Rising.growth(null, 2_000, null, null, now - 10 * day, now)!!
        assertEquals(200.0, g3.perDay, 0.01)
        assertTrue(g3.label.contains("since it launched 10 days ago"))
        // An old repo with no recent evidence is not "rising"; nor is one that lost stars.
        assertNull(Rising.growth(null, 90_000, null, null, now - 900 * day, now))
        assertNull(Rising.growth(null, 9_900, 10_000, now - 3 * day, now - 900 * day, now))
        // A check from a few hours ago is too recent to measure growth against.
        assertNull(Rising.growth(null, 10_300, 10_000, now - 2 * 3_600_000L, now - 900 * day, now))
    }

    @Test fun listStaysTheSameUnlessNewReposMakeIt() {
        val current = listOf("a/one", "b/two", "c/three")
        // Same repos, different order today: the list is kept exactly as it was.
        assertEquals(current, Rising.nextList(listOf("c/three", "a/one", "b/two", "d/four"), current, size = 3))
        // A new repo made the top: the list is renewed in ranked order.
        assertEquals(listOf("d/four", "a/one", "b/two"), Rising.nextList(listOf("d/four", "a/one", "b/two", "c/three"), current, size = 3))
        // Case differences are the same repo.
        assertEquals(current, Rising.nextList(listOf("A/One", "B/Two", "C/Three"), current, size = 3))
    }

    @Test fun updatesEveryThirdMorning() {
        val last = at(2026, 10, 20, 7) // Tuesday 07:00
        assertTrue("first run is always due", Rising.isDue(0, 0, last, zone))
        assertFalse(Rising.isDue(last, last, at(2026, 10, 22, 8), zone)) // 2 days later
        assertTrue(Rising.isDue(last, last, at(2026, 10, 23, 7), zone)) // 3rd morning
        assertFalse(Rising.isDue(last, last, at(2026, 10, 23, 15), zone)) // 3rd day, afternoon
        assertTrue("missed every morning: run anyway after 4 days", Rising.isDue(last, last, at(2026, 10, 24, 15), zone))
        assertFalse("never more than once an hour", Rising.isDue(last, at(2026, 10, 23, 6) + 1_800_000L, at(2026, 10, 23, 7), zone))
        assertEquals(LocalDate.of(2026, 10, 23), Rising.nextUpdate(last, zone))
        assertEquals(LocalDate.of(2026, 10, 24), Rising.nextUpdate(at(2026, 10, 20, 17), zone)) // evening run → morning after
    }

    @Test fun onlyMaintainedOriginalDescribedRepos() {
        val now = System.currentTimeMillis()
        assertTrue(Rising.eligible(repo(), now))
        assertFalse(Rising.eligible(repo(archived = true), now))
        assertFalse(Rising.eligible(repo(fork = true), now))
        assertFalse(Rising.eligible(repo(description = " "), now))
        assertFalse(Rising.eligible(repo(pushedAt = now - 45 * day), now))
    }

    @Test fun aiMlFilterUsesTheReposOwnTopicsAndWords() {
        assertTrue(Rising.isAi(repo(topics = listOf("llm"))))
        assertTrue(Rising.isAi(repo(description = "Reverse engineer anything with agents.")))
        assertTrue(Rising.isAi(repo(description = "Official directory of Claude Code plugins")))
        assertFalse(Rising.isAi(repo(description = "A fast user agent parser for Go")))
        assertFalse(Rising.isAi(repo(description = "Tool for porting PS5 executables to Linux and Windows")))
        assertFalse(Rising.isAi(repo(description = "Chain of email trails")))
        assertNotNull(Rising.human(1234))
        assertEquals("1.2k", Rising.human(1234))
        assertEquals("229k", Rising.human(229_268))
    }
}
