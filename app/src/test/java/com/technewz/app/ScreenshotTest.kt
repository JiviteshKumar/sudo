package com.technewz.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.technewz.app.data.ArticleEntity
import com.technewz.app.data.JobEntity
import com.technewz.app.data.Profile
import com.technewz.app.data.Section
import com.technewz.app.data.SummaryKind
import com.technewz.app.data.ThemeMode
import com.technewz.app.net.Feeds
import com.technewz.app.ui.BottomBar
import com.technewz.app.ui.components.ChipRow
import com.technewz.app.ui.theme.Accents
import com.technewz.app.ui.theme.TechNewzTheme
import com.technewz.app.ui.screens.JobCard
import com.technewz.app.ui.screens.NewsCard
import com.technewz.app.ui.screens.NewsHeader
import com.technewz.app.ui.screens.StoryCluster
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders key screens to PNG (app/build/screens) so the design can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val now = System.currentTimeMillis()
    private val profile = Profile(fullName = "Jivitesh Kumar", skills = listOf("Python", "PyTorch", "SQL", "React"))

    private fun article(i: Int, title: String, source: String, domain: String, summary: String, why: String?, topic: String, kind: Int = SummaryKind.AI, mins: Int = 30) =
        ArticleEntity(
            id = "a$i", url = "https://$domain/$i", title = title, source = source, sourceDomain = domain, section = Section.TECH,
            topic = topic, imageUrl = null, publishedAt = now - mins * 60_000L, fetchedAt = now, excerpt = summary,
            summary = summary, summaryKind = kind, whyItMatters = why, clusterId = "a$i", bookmarked = i == 2,
        )

    private val stories = listOf(
        StoryCluster(
            article(1, "Judge dismisses antitrust lawsuits over Google’s AI Overviews", "The Verge", "theverge.com",
                "A federal judge dismissed lawsuits from Chegg and Penske Media that argued Google's AI Overviews unlawfully diverted traffic from publishers. The court found the plaintiffs failed to show the feature harmed competition, though the publishers can amend their complaints within 30 days, according to the ruling.",
                "If you build on search traffic or SEO data, this keeps AI answer boxes in place for now.", "Policy", mins = 42),
            listOf(article(11, "x", "Ars Technica", "arstechnica.com", "", null, "Policy"), article(12, "y", "Engadget", "engadget.com", "", null, "Policy")),
        ),
        StoryCluster(
            article(2, "Google announces Gemini 4 Argon, its most powerful model yet", "Ars Technica", "arstechnica.com",
                "Google unveiled Gemini 4 Argon, which it describes as its most capable model, with stronger reasoning and coding results on internal benchmarks. The model is not yet available to the public; Google says developer access through its API will begin later this year after additional safety testing.",
                "New SOTA coding model — worth benchmarking against your PyTorch workflows once API access opens.", "AI", mins = 95),
            emptyList(),
        ),
        StoryCluster(
            article(3, "Hearing tech startup Legato launches AI hearing glasses", "TechCrunch", "techcrunch.com",
                "Legato released glasses with built-in microphones and on-device AI that isolates voices in noisy rooms, aimed at people with mild hearing loss.",
                null, "Gadgets", kind = SummaryKind.EXCERPT, mins = 180),
            emptyList(),
        ),
    )

    private fun job(i: Int, title: String, company: String, loc: String, remote: Boolean, type: String, score: Int, direct: Boolean, salary: String? = null, days: Int = 2) = JobEntity(
        id = "j$i", title = title, company = company, location = loc, isRemote = remote, employmentType = type,
        isInternship = type == "Internship", url = "https://example.com", source = if (direct) "Greenhouse" else "Remotive",
        directFromEmployer = direct, description = "", postedAt = now - days * 86_400_000L, fetchedAt = now, salary = salary,
        matchScore = score, matchedSkills = "Python|SQL", missingSkills = "Docker|Kubernetes", scamFlags = "",
    )

    private val jobs = listOf(
        job(1, "Machine Learning Intern, Summer 2027", "Databricks", "Bengaluru, India", false, "Internship", 82, true),
        job(2, "Data Scientist, Growth", "Stripe", "Remote · India", true, "Full-time", 64, true, "₹28L–₹40L"),
        job(3, "Frontend Developer (React)", "KoboToolbox", "Remote · Worldwide", true, "Contract", 38, false, days = 6),
    )

    @Composable
    private fun Frame(mode: ThemeMode, tab: Int, content: @Composable () -> Unit) {
        TechNewzTheme(mode) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content()
                BottomBar(selected = tab, onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Composable
    private fun Feed() {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NewsHeader("Tech Pulse", Accents.tech, profile, now - 3 * 60_000, 128, false, {}, {})
            ChipRow(Feeds.techTopics, setOf("All"), Accents.tech, {}, counts = mapOf("All" to 128, "AI" to 41, "Security" to 12))
            stories.forEach { NewsCard(it, Accents.tech, {}, {}, {}, {}) }
        }
    }

    @Composable
    private fun Jobs() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NewsHeader("Jobs & Internships", Accents.jobs, profile, now - 20 * 60_000, 312, false, {}, {})
            ChipRow(listOf("Internships", "Full-time", "Remote", "On-site", "Near me"), setOf("Remote"), Accents.jobs, {})
            Spacer(Modifier.height(2.dp))
            jobs.forEach { JobCard(it, tracked = it.id == "j2", hasProfile = true, onClick = {}) }
        }
    }

    // Definition, usage and paper counts are copied from a live audit run (testing/TRUTH_REPORT.md);
    // the news/job counts and companies are illustrative values for layout only.
    private val vla = com.technewz.app.data.TermEntity(
        key = "vision language action", term = "Vision-Language-Action (VLA)", shortForm = "VLA", kind = "Concept",
        status = com.technewz.app.data.TermStatus.RISING,
        reason = "arXiv: 312 papers in the last 30 days vs 829 in the 150 days before; 2089 all-time",
        what = "In robot learning, a vision–language–action model (VLA) is a class of multimodal foundation models that integrates vision, language and actions.",
        whatSource = "Wikipedia", whatUrl = "https://en.wikipedia.org/wiki/Vision-language-action_model",
        usage = "Future prediction is increasingly used to improve vision-language-action (VLA) policies, based on the premise that anticipating scene evolution encourages representations useful for control.",
        usageSource = "arXiv: Where Predictive Supervision Goes Shapes What VLA Policies Learn", usageUrl = "https://arxiv.org/",
        simpleWhat = null, simpleUsage = null, papers30 = 312, papersPrior = 829, papersTotal = 2089, firstSeen = now - 3L * 365 * 86_400_000L,
        newsMentions = 2, jobMentions = 3, jobCompanies = "Scale AI|Databricks", repo = null, repoStars = null, evidence = "[]",
        score = 5.0, checkedAt = now, discoveredAt = now,
    )

    @Test fun brand() = captureRoboImage("build/screens/brand.png") {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val c = AppContainer(app, androidx.room.Room.inMemoryDatabaseBuilder(app, com.technewz.app.data.AppDatabase::class.java).build())
        TechNewzTheme(ThemeMode.DARK) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // Launcher icon as Android composes it: background + foreground layers, circular mask.
                Box(Modifier.padding(24.dp).size(120.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Box(Modifier.weight(1f)) { com.technewz.app.ui.screens.OnboardingScreen(c) {} }
            }
        }
    }

    @Test fun radarLight() = captureRoboImage("build/screens/radar_light.png") {
        TechNewzTheme(ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.padding(top = 24.dp)) { com.technewz.app.ui.screens.TermCard(vla, emptyList()) }
            }
        }
    }

    @Test fun pulseLight() = captureRoboImage("build/screens/pulse_light.png") { Frame(ThemeMode.LIGHT, 0) { Feed() } }
    @Test fun pulseDark() = captureRoboImage("build/screens/pulse_dark.png") { Frame(ThemeMode.DARK, 0) { Feed() } }
    @Test fun jobsLight() = captureRoboImage("build/screens/jobs_light.png") { Frame(ThemeMode.LIGHT, 2) { Jobs() } }
    @Test fun jobsDark() = captureRoboImage("build/screens/jobs_dark.png") { Frame(ThemeMode.DARK, 2) { Jobs() } }
}
