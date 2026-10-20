package com.technewz.app.data

import com.technewz.app.net.GitHubRising
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * "Rising on GitHub": the 15 repos gaining stars fastest right now (all of GitHub, and AI/ML only), updated
 * every 3rd morning. If no new repo makes the list, the list stays exactly as it was.
 */
class RisingRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.rising()

    data class Result(val listed: Int, val added: List<String>, val changed: Boolean, val limited: Boolean, val unreachable: Boolean)

    /** Runs the scheduled every-3rd-morning update if it is due; returns null when it isn't. */
    suspend fun refreshIfDue(now: Long = System.currentTimeMillis()): Result? {
        val s = settings.current()
        if (!Rising.isDue(s.lastRisingScheduled, s.lastRisingCheck, now, ZoneId.systemDefault())) return null
        return refresh(scheduled = true)
    }

    /**
     * Checks GitHub now. [scheduled] runs move the every-3rd-morning clock; a manual check doesn't.
     * At most one check an hour, so GitHub's free limit (60 requests an hour) is never wasted.
     */
    suspend fun refresh(scheduled: Boolean = false): Result = withContext(HeavyWork.dispatcher) {
        val now = System.currentTimeMillis()
        val existing = dao.all().associateBy { it.fullName.lowercase() }
        var limited = false

        // 1. Discover: GitHub's Trending page (stars gained this week) + new repos taking off, via GitHub search.
        val trending = runCatching { GitHubRising.trendingThisWeek() }.getOrDefault(emptyList())
        val week = trending.associate { it.fullName.lowercase() to it.weekStars }
        val since = LocalDate.now().minusDays(Rising.NEW_REPO_DAYS.toLong())
        val repos = LinkedHashMap<String, GitHubRising.Repo>()
        for (q in Rising.searches(since)) {
            if (limited) break
            try {
                GitHubRising.search(q).forEach { repos.putIfAbsent(it.fullName.lowercase(), it) }
            } catch (e: GitHubRising.RateLimited) {
                limited = true
            } catch (e: Exception) {
                // one failed search never blocks the others
            }
        }

        // 2. Confirm each trending repo with GitHub's API, and re-check listed repos so their growth is measured.
        val listedNames = existing.values.filter { it.rankAll != null || it.rankAi != null }.map { it.fullName }
        val toFetch = (trending.map { it.fullName } + listedNames).distinctBy { it.lowercase() }.filter { it.lowercase() !in repos }.take(40)
        val gone = mutableSetOf<String>()
        for (name in toFetch) {
            if (limited) break
            try {
                val r = GitHubRising.repo(name)
                if (r == null) gone += name.lowercase() else repos[r.fullName.lowercase()] = r
            } catch (e: GitHubRising.RateLimited) {
                limited = true
            } catch (e: Exception) {
                // unreachable right now: not counted either way
            }
        }
        val unreachable = trending.isEmpty() && repos.isEmpty()
        if (unreachable || limited) {
            // Never guess: keep the current list exactly as it is and retry later.
            settings.update { it.copy(lastRisingCheck = now) }
            return@withContext Result(listedNames.size, emptyList(), false, limited, unreachable)
        }

        // 3. Score every candidate by stars gained per day, using the best evidence available for it.
        data class Cand(val repo: GitHubRising.Repo, val growth: Rising.Growth?, val prevStars: Int?, val prevAt: Long?, val isAi: Boolean)
        val cands = repos.values.filter { Rising.eligible(it, now) }.map { r ->
            val prev = existing[r.fullName.lowercase()]
            // Snapshot: the previous check (at least ~a day old) is what growth is measured against.
            val (ps, pa) = if (prev != null && now - prev.checkedAt >= Rising.MIN_MEASURE_MS) prev.stars to prev.checkedAt else prev?.prevStars to prev?.prevAt
            Cand(r, Rising.growth(week[r.fullName.lowercase()], r.stars, ps, pa, r.createdAt, now), ps, pa, Rising.isAi(r))
        }
        val ranked = cands.filter { it.growth != null }.sortedByDescending { it.growth!!.perDay }
        val currentAll = existing.values.filter { it.rankAll != null }.sortedBy { it.rankAll }.map { it.fullName }
        val currentAi = existing.values.filter { it.rankAi != null }.sortedBy { it.rankAi }.map { it.fullName }
        val newAll = Rising.nextList(ranked.map { it.repo.fullName }, currentAll)
        val newAi = Rising.nextList(ranked.filter { it.isAi }.map { it.repo.fullName }, currentAi)
        val listedNow = (newAll + newAi).map { it.lowercase() }.toSet()

        // 4. "What it does": the README's first paragraph, quoted (refreshed weekly).
        val out = mutableListOf<RisingRepoEntity>()
        for (c in cands) {
            val key = c.repo.fullName.lowercase()
            val prev = existing[key]
            var readme = prev?.readme
            var readmeAt = prev?.readmeCheckedAt ?: 0
            if (key in listedNow && (readmeAt == 0L || now - readmeAt > TimeUnit.DAYS.toMillis(7))) {
                runCatching { GitHubRising.readme(c.repo.fullName) }.onSuccess { md ->
                    readme = md?.let { GitHubRising.readmeIntro(it) }
                    readmeAt = now
                }
            }
            val g = c.growth
            out += RisingRepoEntity(
                fullName = c.repo.fullName, url = c.repo.url, description = c.repo.description,
                readme = readme, readmeCheckedAt = readmeAt, language = c.repo.language, topics = c.repo.topics.joinToString("|"),
                stars = c.repo.stars, forks = c.repo.forks, createdAt = c.repo.createdAt, pushedAt = c.repo.pushedAt,
                weekStars = week[key], prevStars = c.prevStars, prevAt = c.prevAt,
                growthPerDay = g?.perDay ?: 0.0, growthLabel = g?.label.orEmpty(), growthSource = g?.source.orEmpty(),
                isAi = c.isAi,
                rankAll = newAll.indexOfFirst { it.equals(c.repo.fullName, true) }.takeIf { it >= 0 },
                rankAi = newAi.indexOfFirst { it.equals(c.repo.fullName, true) }.takeIf { it >= 0 },
                firstFeaturedAt = prev?.firstFeaturedAt ?: if (key in listedNow) now else null,
                checkedAt = now,
            )
        }
        // Previously listed repos that dropped out (or were deleted/made private) leave the lists.
        val written = out.map { it.fullName.lowercase() }.toSet()
        existing.values.filter { (it.rankAll != null || it.rankAi != null) && it.fullName.lowercase() !in written }
            .forEach { out += it.copy(rankAll = null, rankAi = null) }
        dao.upsertAll(out)
        dao.prune(now - TimeUnit.DAYS.toMillis(30))

        val before = (currentAll + currentAi).map { it.lowercase() }.toSet()
        val added = (newAll + newAi).distinctBy { it.lowercase() }.filter { it.lowercase() !in before }
        settings.update { it.copy(lastRisingCheck = now, lastRisingScheduled = if (scheduled || it.lastRisingScheduled == 0L) now else it.lastRisingScheduled) }
        Result((newAll + newAi).distinctBy { it.lowercase() }.size, added, newAll != currentAll || newAi != currentAi, false, false)
    }
}

/** The rules behind "Rising on GitHub", kept pure so tests exercise exactly what the app runs. */
object Rising {
    const val LIST_SIZE = 15
    /** Repos younger than this can be ranked by their average daily stars since launch. */
    const val NEW_REPO_DAYS = 45
    private val day = TimeUnit.DAYS.toMillis(1)
    val MIN_MEASURE_MS = TimeUnit.HOURS.toMillis(20)

    data class Growth(val perDay: Double, val label: String, val source: String)

    /** GitHub searches for new repos that are taking off (all of GitHub, plus AI/ML topics). */
    fun searches(since: LocalDate): List<String> = listOf(
        "created:>$since stars:>500 archived:false fork:false",
        "topic:llm created:>$since stars:>100 archived:false fork:false",
        "topic:ai-agents created:>$since stars:>100 archived:false fork:false",
        "topic:machine-learning created:>$since stars:>50 archived:false fork:false",
    )

    /** Real, maintained projects only: not archived, not a fork, described by its author, pushed to this month. */
    fun eligible(r: GitHubRising.Repo, now: Long): Boolean =
        !r.archived && !r.fork && r.description.isNotBlank() && (r.pushedAt == 0L || now - r.pushedAt <= 30 * day)

    /**
     * Stars gained per day, from the strongest evidence available: GitHub Trending's "stars this week";
     * otherwise the gain sudo measured between two of its own checks; otherwise, for a repo launched in the last
     * [NEW_REPO_DAYS] days, its average since launch. Null means there's no evidence it is rising.
     */
    fun growth(weekStars: Int?, stars: Int, prevStars: Int?, prevAt: Long?, createdAt: Long, now: Long): Growth? {
        val measured = if (prevStars != null && prevAt != null && now - prevAt >= MIN_MEASURE_MS && stars > prevStars) {
            val days = (now - prevAt).toDouble() / day
            (stars - prevStars) to days
        } else null
        val measuredLabel = measured?.let { (g, d) -> "+${human(g)} stars in ${daysLabel(d)}" }
        if (weekStars != null && weekStars > 0) {
            return Growth(weekStars / 7.0, "+${human(weekStars)} stars this week" + (measuredLabel?.let { " · $it since the last update" } ?: ""), "GitHub Trending")
        }
        if (measured != null) {
            return Growth(measured.first / measured.second, "$measuredLabel since the last update", "Measured by sudo from GitHub's star counts")
        }
        val ageDays = (now - createdAt).toDouble() / day
        if (createdAt > 0 && stars > 0 && ageDays <= NEW_REPO_DAYS) {
            return Growth(stars / max(ageDays, 1.0), "+${human(stars)} stars since it launched ${daysLabel(ageDays)} ago", "GitHub API")
        }
        return null
    }

    /**
     * The new list: the top [size] by growth — unless that is the same set of repos as now, in which case the
     * current list is kept exactly as it is (no reshuffling when nothing new made it).
     */
    fun nextList(rankedBest: List<String>, current: List<String>, size: Int = LIST_SIZE): List<String> {
        val top = rankedBest.distinctBy { it.lowercase() }.take(size)
        return if (top.map { it.lowercase() }.toSet() == current.map { it.lowercase() }.toSet()) current else top
    }

    /**
     * Every 3rd morning: due once 3 days (minus a few hours' slack) have passed and it's between 6 am and noon locally, or
     * after 4 days whatever the time (the phone was off or idle all morning). Never more than once an hour.
     */
    fun isDue(lastScheduled: Long, lastCheck: Long, now: Long, zone: ZoneId): Boolean {
        if (now - lastCheck < TimeUnit.HOURS.toMillis(1)) return false
        if (lastScheduled == 0L) return true
        val elapsed = now - lastScheduled
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return (elapsed >= 3 * day - TimeUnit.HOURS.toMillis(4) && hour in 6..11) || elapsed >= 4 * day
    }

    /** The morning the next scheduled update is expected. */
    fun nextUpdate(lastScheduled: Long, zone: ZoneId): LocalDate {
        val earliest = Instant.ofEpochMilli(lastScheduled + 3 * day - TimeUnit.HOURS.toMillis(4)).atZone(zone)
        return if (earliest.hour >= 12) earliest.toLocalDate().plusDays(1) else earliest.toLocalDate()
    }

    private val aiTopics = setOf(
        "ai", "artificial-intelligence", "machine-learning", "deep-learning", "llm", "llms", "large-language-models", "generative-ai",
        "genai", "nlp", "natural-language-processing", "computer-vision", "pytorch", "tensorflow", "jax", "transformers", "huggingface",
        "rag", "retrieval-augmented-generation", "ai-agent", "ai-agents", "agents", "agentic-ai", "multi-agent", "mcp",
        "model-context-protocol", "diffusion", "stable-diffusion", "diffusion-models", "reinforcement-learning", "neural-network",
        "neural-networks", "data-science", "mlops", "langchain", "openai", "chatgpt", "gpt", "claude", "claude-code", "gemini", "ollama",
        "llama", "speech-recognition", "text-to-speech", "tts", "asr", "vlm", "multimodal", "embeddings", "vector-database", "fine-tuning",
        "llm-inference", "vllm", "codex", "copilot", "ai-coding", "prompt-engineering",
    )
    private val aiText = Regex(
        "(?<![\\w-])(AI|LLMs?|GenAI|machine[- ]learning|deep[- ]learning|neural (networks?|nets?)|GPT[\\w.-]*|diffusion|RAG|" +
            "(?<!user[- ])agents?|agentic|Claude|Codex|Copilot|Gemini|OpenAI|Ollama|embeddings?|fine-?tun\\w*|MCP|" +
            "vision-language|speech recognition|text-to-speech|computer vision|reinforcement learning|data science)(?![\\w-])",
        RegexOption.IGNORE_CASE,
    )

    /** AI/ML repos: tagged with an AI/ML topic by their authors, or described as AI/ML in their own words. */
    fun isAi(r: GitHubRising.Repo): Boolean =
        r.topics.any { it.lowercase() in aiTopics } || aiText.containsMatchIn(r.description) || aiText.containsMatchIn(r.fullName.substringAfter('/').replace('-', ' '))

    fun human(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.0fk", n / 1_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    private fun daysLabel(d: Double): String {
        val n = max(1, Math.round(d).toInt())
        return if (n == 1) "1 day" else "$n days"
    }

    /** "Matches your skills": skills from the user's profile that this repo's own text mentions. */
    fun matchedSkills(userSkills: Set<String>, r: RisingRepoEntity): List<String> =
        if (userSkills.isEmpty()) emptyList()
        else com.technewz.app.util.Skills.match(userSkills, listOf(r.fullName, r.description, r.readme.orEmpty(), r.language, r.topics.replace('|', ' ')).joinToString("\n")).matched
}
