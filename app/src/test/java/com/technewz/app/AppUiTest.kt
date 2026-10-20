package com.technewz.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.technewz.app.data.AppDatabase
import com.technewz.app.data.ArticleEntity
import com.technewz.app.data.JobEntity
import com.technewz.app.data.Section
import com.technewz.app.data.SummaryKind
import com.technewz.app.data.TermEntity
import com.technewz.app.data.TermStatus
import com.technewz.app.data.TrendingEntity
import com.technewz.app.ui.AppRoot
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end UI tests of the real app (navigation, filters, apply flow, tracker, bookmarks, radar,
 * onboarding) against a seeded in-memory database. "Last refreshed: now" is set so no network is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var c: AppContainer
    private lateinit var db: AppDatabase
    private val now = System.currentTimeMillis()

    private fun article(id: String, title: String, topic: String, section: String = Section.TECH, mins: Int = 30) = ArticleEntity(
        id = id, url = "https://example.com/$id", title = title, source = "Ars Technica", sourceDomain = "arstechnica.com",
        section = section, topic = topic, imageUrl = null, publishedAt = now - mins * 60_000L, fetchedAt = now,
        excerpt = "$title. Body text.", summary = "Summary of $title taken from the article.", summaryKind = SummaryKind.ON_DEVICE,
        whyItMatters = null, clusterId = id,
    )

    private fun job(id: String, title: String, company: String, type: String, remote: Boolean) = JobEntity(
        id = id, title = title, company = company, location = if (remote) "Remote · Worldwide" else "Bengaluru, India",
        isRemote = remote, employmentType = type, isInternship = type == "Internship", url = "https://boards.greenhouse.io/x/$id",
        source = "Greenhouse", directFromEmployer = true, description = "We use Python and SQL. Responsibilities include building models.",
        postedAt = now - 86_400_000L, fetchedAt = now, salary = null, matchScore = null, matchedSkills = "", missingSkills = "Python|SQL", scamFlags = "",
    )

    @Before fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        c = AppContainer(app, db)
        c.settings.update { it.copy(onboarded = true, lastNewsRefresh = now, lastJobsRefresh = now, lastRadarRefresh = now, keywords = emptyList(), radarVersion = com.technewz.app.data.RadarRepository.RULES_VERSION, lastRisingCheck = now, lastRisingScheduled = now) }
        c.settings.saveProfile(com.technewz.app.data.Profile(skills = listOf("Rust")))
        db.articles().insertAll(
            listOf(
                article("a1", "Chipmaker unveils 2nm processor", "Gadgets"),
                article("a2", "Ransomware gang hits hospital network", "Security", mins = 90),
                article("a3", "Lab releases open reasoning model", "Industry", section = Section.AI),
            )
        )
        db.jobs().upsertAll(listOf(job("j1", "ML Intern, Summer", "Databricks", "Internship", false), job("j2", "Senior Data Engineer", "Stripe", "Full-time", true)))
        db.rising().upsertAll(listOf(rising("acme/agentkit", "Build AI agents that run anywhere.", isAi = true, rankAll = 0, rankAi = 0),
            rising("acme/tinydb", "A tiny embedded database written in Rust.", isAi = false, rankAll = 1, rankAi = null)))
        db.trending().upsertAll(listOf(TrendingEntity("papers", "2610.1", 0, "A trending paper", "arXiv 2610.1", "https://huggingface.co/papers/2610.1", "▲ 12", now)))
        db.terms().upsert(
            TermEntity(
                key = "cache augmented generation", term = "Cache-Augmented Generation (CAG)", shortForm = "CAG", kind = "Concept",
                status = TermStatus.RISING, reason = "arXiv: 12 papers in the last 30 days vs 9 in the 150 days before",
                what = "Cache-augmented generation preloads documents into the model context.", whatSource = "arXiv: Paper", whatUrl = "https://arxiv.org/abs/1",
                usage = null, usageSource = null, usageUrl = null, simpleWhat = null, simpleUsage = null, papers30 = 12, papersPrior = 9,
                papersTotal = 40, firstSeen = now - 300L * 86_400_000L, newsMentions = 1, jobMentions = 0, jobCompanies = "", repo = null,
                repoStars = null, evidence = "[]", score = 3.0, checkedAt = now, discoveredAt = now,
            )
        )
        // A term first spotted ~6 weeks ago, with a supporting source, for the 3-month timeline.
        db.terms().upsert(
            TermEntity(
                key = "world action model", term = "World action models (WAMs)", shortForm = "WAMs", kind = "Concept",
                status = TermStatus.NEW, reason = "Took off in 2026", what = "World action models (WAMs) are large embodied policies.",
                whatSource = "arXiv: Paper", whatUrl = "https://arxiv.org/abs/2", usage = null, usageSource = null, usageUrl = null,
                simpleWhat = null, simpleUsage = null, papers30 = 60, papersPrior = 90, papersTotal = 334, firstSeen = null,
                newsMentions = 0, jobMentions = 0, jobCompanies = "", repo = null, repoStars = null,
                evidence = """[{"title":"SplineWAM: Adaptive Action Horizons","url":"https://arxiv.org/abs/3","source":"arXiv","type":"paper"}]""",
                score = 2.0, checkedAt = now, discoveredAt = now - 42L * 86_400_000L,
            )
        )
    }

    private fun rising(name: String, description: String, isAi: Boolean, rankAll: Int?, rankAi: Int?) = com.technewz.app.data.RisingRepoEntity(
        fullName = name, url = "https://github.com/$name", description = description,
        readme = "$name is the README's first paragraph, quoted.", readmeCheckedAt = now, language = if (isAi) "Python" else "Rust",
        topics = if (isAi) "llm|agents" else "database", stars = 12_000, forks = 300, createdAt = now - 20 * 86_400_000L, pushedAt = now,
        weekStars = 4_200, prevStars = null, prevAt = null, growthPerDay = 600.0, growthLabel = "+4.2k stars this week",
        growthSource = "GitHub Trending", isAi = isAi, rankAll = rankAll, rankAi = rankAi, firstFeaturedAt = now - 86_400_000L, checkedAt = now,
    )

    // The in-memory DB is not closed here: @After runs before the compose rule disposes the UI.

    private fun start(expect: String = "Tech Pulse") {
        rule.setContent { AppRoot(c, null) {} }
        // Let the launch intro finish (it plays for ~2.4 s on every cold start).
        rule.waitUntil(30_000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("sudo intro")).fetchSemanticsNodes().isEmpty() }
        shown(expect)
    }

    /** Waits (up to 15 s) until [text] is on screen; data loads on background threads the test clock does not track. */
    private fun shown(text: String) {
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess }
    }

    private fun gone(text: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun homeShowsVerifiedFeed() {
        start()
        shown("Tech Pulse")
        shown("Chipmaker unveils 2nm processor")
        rule.onAllNodesWithText("Key sentences · on-device").onFirst().assertIsDisplayed()
    }

    @Test fun topicFilterShowsOnlyThatTopic() {
        start()
        shown("Security  1") // counts appear once articles load
        rule.onNodeWithText("Security  1").performClick()
        rule.waitForIdle()
        shown("Ransomware gang hits hospital network")
        gone("Chipmaker unveils 2nm processor")
    }

    @Test fun risingOnGitHubWithFiltersAndDetails() {
        start()
        shown("Rising on GitHub")
        shown("acme/agentkit")
        shown("acme/tinydb")
        // AI/ML shows only repos the app classified as AI/ML; "For you" only those matching the profile (Rust).
        rule.onNodeWithText("AI/ML").performClick(); rule.waitForIdle()
        shown("acme/agentkit"); gone("acme/tinydb")
        rule.onNodeWithText("For you").performClick(); rule.waitForIdle()
        shown("acme/tinydb"); gone("acme/agentkit")
        // Tapping a repo shows what it is, what it does (README) and a direct link.
        rule.onNodeWithText("acme/tinydb").performClick(); rule.waitForIdle()
        shown("What it does")
        shown("acme/tinydb is the README's first paragraph, quoted.")
        rule.onAllNodesWithText("Open on GitHub").onFirst().assertExists()
    }

    @Test fun aiTabReposTabIsTheRisingAiList() {
        start()
        rule.onNodeWithContentDescription("AI & Data").performClick()
        rule.waitForIdle()
        shown("Trending now")
        rule.onNodeWithText("Repos").performClick(); rule.waitForIdle()
        shown("Rising on GitHub · AI/ML repos gaining stars fastest right now")
        shown("acme/agentkit")
        gone("acme/tinydb")
    }

    @Test fun aiTabShowsSkillsRadarAndOpensIt() {
        start()
        rule.onNodeWithContentDescription("AI & Data").performClick()
        rule.waitForIdle()
        shown("Skills Radar")
        shown("Cache-Augmented Generation (CAG)")
        rule.onNodeWithText("See all", substring = true).performClick()
        rule.waitForIdle()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("WHAT IT IS").fetchSemanticsNodes().isNotEmpty() }
        shown("“Cache-augmented generation preloads documents into the model context.”")
    }

    @Test fun radarKeepsAThreeMonthTimelineWithSources() {
        start()
        rule.onNodeWithContentDescription("AI & Data").performClick()
        rule.waitForIdle()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("See all", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("See all", substring = true).performClick()
        rule.waitForIdle()
        shown("THIS WEEK · 1 TERM")
        val month = java.time.Instant.ofEpochMilli(now - 42L * 86_400_000L).atZone(java.time.ZoneId.systemDefault()).let {
            it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + it.year
        }
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("World action models (WAMs)"))
        shown("World action models (WAMs)")
        rule.onAllNodesWithText("$month · 1 term".uppercase()).onFirst().assertExists()
        // Supporting sources are visible without expanding anything.
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("SplineWAM: Adaptive Action Horizons"))
        shown("SplineWAM: Adaptive Action Horizons")
    }

    @Test fun jobsInternshipFilter() {
        start()
        rule.onNodeWithContentDescription("Jobs").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Internships").performClick()
        rule.waitForIdle()
        shown("ML Intern, Summer")
        gone("Senior Data Engineer")
    }

    @Test fun jobDetailAssistedApplyAndTracker() {
        start()
        rule.onNodeWithContentDescription("Jobs").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Senior Data Engineer").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("About the role").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Apply").performClick()
        rule.waitForIdle()
        shown("Assisted apply")
        rule.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Open application page")))
            .performScrollToNode(hasText("Open application page"))
        shown("Open application page")
        // Saved job appears in the tracker.
        runBlocking { assert(db.applications().get("j2")?.status == "Saved") }
    }

    @Test fun bookmarkAppearsInSaved() {
        start()
        rule.onAllNodesWithContentDescriptionCompat("Bookmark").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Saved").performClick()
        rule.waitForIdle()
        shown("Chipmaker unveils 2nm processor")
    }

    @Test fun onboardingCompletesToHome() {
        runBlocking { c.settings.update { it.copy(onboarded = false) } }
        start("Tech, distilled.")
        shown("Tech, distilled.")
        rule.onNodeWithText("Continue").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Continue").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Skip — start reading").performClick(); rule.waitForIdle()

        shown("Tech Pulse")
    }


    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCompat(d: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d))
}
