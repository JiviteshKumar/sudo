package com.technewz.app.data

import android.content.Context
import com.technewz.app.ml.SentenceSplitter
import com.technewz.app.ml.TermMiner
import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.ResearchSources
import com.technewz.app.net.RssParser
import com.technewz.app.util.Text
import com.technewz.app.work.Notifications
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit
import kotlin.math.ln

@Serializable
data class Evidence(val title: String, val url: String, val source: String, val date: Long? = null, val type: String = "")

/**
 * Skills Radar: discovers new and rising AI/ML/DS terms and tools from live data, verifies each one
 * against independent sources, and explains it only with text taken from those sources.
 */
class RadarRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val ai: AiFeatures,
) {
    private val dao = db.terms()
    private val day = TimeUnit.DAYS.toMillis(1)
    private val evidenceSer = ListSerializer(Evidence.serializer())
    private val baselineSer = ListSerializer(TermMiner.Doc.serializer())

    data class Result(val candidates: Int, val checked: Int, val accepted: List<String>, val rejected: Int)

    suspend fun refresh(force: Boolean = false, maxChecks: Int = 15, onProgress: (String) -> Unit = {}): Result? = coroutineScope {
        val s = settings.current()
        val now = System.currentTimeMillis()
        if (!force && now - s.lastRadarRefresh < TimeUnit.HOURS.toMillis(12)) return@coroutineScope null

        onProgress("Reading new research, news, repos and job posts…")
        val recent = recentCorpus(now)
        val baseline = baselineCorpus(now)
        if (baseline.size < 300) {
            onProgress("Couldn't load the research baseline — will retry later")
            return@coroutineScope Result(0, 0, emptyList(), 0)
        }
        val candidates = TermMiner.candidates(recent, baseline)
        val known = dao.all().associateBy { it.key }
        val firstRun = known.isEmpty()

        // Re-check accepted terms every 3 days, rejected ones every 10 days; otherwise reuse.
        val toCheck = candidates.filter { c ->
            val k = known[c.key] ?: return@filter true
            val age = now - k.checkedAt
            if (k.status == TermStatus.REJECTED) age > 10 * day else age > 3 * day
        }.take(maxChecks)

        val accepted = mutableListOf<String>()
        var rejected = 0
        toCheck.forEachIndexed { i, c ->
            onProgress("Verifying “${c.display}” (${i + 1}/${toCheck.size})…")
            val t = runCatching { verifyConcept(c, now, known[c.key]) }.getOrNull() ?: return@forEachIndexed
            dao.upsert(t)
            if (t.status == TermStatus.REJECTED) rejected++
            else if (known[c.key]?.status.let { it == null || it == TermStatus.REJECTED }) accepted += t.term
        }

        onProgress("Checking new tools in job postings…")
        val tools = runCatching { verifyTools(now, known) }.getOrDefault(emptyList())
        tools.forEach { t ->
            dao.upsert(t)
            if (t.status != TermStatus.REJECTED && known[t.key]?.status.let { it == null || it == TermStatus.REJECTED }) accepted += t.term
            if (t.status == TermStatus.REJECTED) rejected++
        }

        dao.prune(now - 60 * day)
        if (s.alertsEnabled && !firstRun && accepted.isNotEmpty()) {
            Notifications.radarAlert(context, accepted)
        }
        settings.update { it.copy(lastRadarRefresh = now) }
        Result(candidates.size, toCheck.size + tools.size, accepted, rejected)
    }

    // ------------------------------------------------------------------ corpora

    private suspend fun recentCorpus(now: Long): List<TermMiner.Doc> = coroutineScope {
        val papers = ResearchSources.arxivListings.map { url ->
            async { runCatching { RssParser.parse(Http.get(url)) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.link }.map {
            TermMiner.Doc(it.link, it.title, Text.cleanFeedText(it.descriptionHtml), it.link, "arXiv", "paper", it.published ?: now)
        }
        val news = db.articles().since(now - 14 * day).map {
            TermMiner.Doc(it.id, it.title, it.excerpt, it.url, it.source, "news", it.publishedAt)
        }
        val repos = db.trending().allOnce().filter { it.kind != "models" }.map {
            TermMiner.Doc(it.kind + it.id, it.title, it.subtitle, it.url, if (it.kind == "repos") "GitHub" else "Hugging Face", if (it.kind == "repos") "repo" else "paper", it.fetchedAt)
        }
        val jobs = db.jobs().all().map {
            TermMiner.Doc(it.id, it.title, it.description.take(4000), it.url, it.source, "job", it.postedAt, it.company)
        }
        papers + news + repos + jobs
    }

    /**
     * What "already known" looks like: a sample of arXiv AI/ML/NLP papers from 6–18 months ago (cached for
     * 30 days) plus older posts from official AI blogs. A term only ranks as new if it is far more common
     * in today's papers than in these.
     */
    private suspend fun baselineCorpus(now: Long): List<TermMiner.Doc> = coroutineScope {
        val cache = java.io.File(context.filesDir, "radar_baseline_v2.json")
        val papers: List<TermMiner.Doc> = runCatching {
            if (cache.exists() && now - cache.lastModified() < 30 * day) AppJson.decodeFromString(baselineSer, cache.readText())
            else null
        }.getOrNull() ?: runCatching {
            ResearchSources.arxivBaseline().map { TermMiner.Doc(it.url, it.title, it.abstract.take(1500), it.url, "arXiv", "paper", it.published) }
                .also { if (it.size > 200) cache.writeText(AppJson.encodeToString(baselineSer, it)) }
        }.getOrDefault(emptyList())
        papers + Feeds.all.filter { it.section == Section.AI && !it.name.startsWith("arXiv") }.map { f ->
            async { runCatching { RssParser.parse(Http.get(f.url)) }.getOrDefault(emptyList()).map { f to it } }
        }.awaitAll().flatten()
            .filter { (_, it) -> (it.published ?: now) < now - 180 * day }
            .map { (f, it) -> TermMiner.Doc(it.link, it.title, Text.htmlToText(it.descriptionHtml).take(1500), it.link, f.name, "news", it.published ?: 0) }
    }

    // ------------------------------------------------------------------ verification: concepts

    private suspend fun verifyConcept(c: TermMiner.Candidate, now: Long, previous: TermEntity?): TermEntity {
        val q = c.longForm
        val r30 = ResearchSources.arxivCount(q, 30, 0)
        val prior = ResearchSources.arxivCount(q, 180, 31)
        val (first, total) = ResearchSources.arxivFirst(q)
        val firstSeen = first?.published?.takeIf { it > 0 }
        val growth = (r30 / 30.0 + 0.01) / (prior / 150.0 + 0.01)
        val isNew = firstSeen != null && now - firstSeen < 365 * day && r30 >= 2
        val isRising = r30 >= 5 && growth >= 1.5
        val counts = "arXiv: $r30 papers in the last 30 days vs $prior in the 150 days before; $total all-time" +
            (firstSeen?.let { "; earliest arXiv use of the phrase ${Text.timeAgo(it, now)}" } ?: "")

        fun rejected(reason: String) = base(c, now, previous).copy(
            status = TermStatus.REJECTED, reason = reason, papers30 = r30, papersPrior = prior, papersTotal = total, firstSeen = firstSeen,
        )
        if (total == 0) return rejected("Not found in arXiv")
        if (!isNew && !isRising) return rejected("Not new or rising — $counts")
        // Maturity check: a long-established concept with a temporary bump is not an upcoming skill.
        if (!isNew) {
            val yearly = r30 * 12
            if (total > 2 * yearly) return rejected("Established — $total papers already exist, over two years' worth at today's rate; $counts")
            val then = ResearchSources.arxivCount(q, 1460, 1095)
            if (then >= 0.25 * yearly) return rejected("Established — $then papers 3–4 years ago; $counts")
        }

        // Explanation strictly from sources: Wikipedia (if a page exists for exactly this term) or a paper/news sentence.
        val recentPapers = ResearchSources.arxivRecent(q, 90, 25)
        val sourceDocs = recentPapers.map { TermMiner.Doc(it.url, it.title, it.abstract, it.url, "arXiv", "paper", it.published) } +
            listOfNotNull(first?.let { TermMiner.Doc(it.url, it.title, it.abstract, it.url, "arXiv", "paper", it.published) }) +
            c.docs.filter { it.type == "paper" || it.type == "news" }
        val wiki = ResearchSources.wikipediaFor(q, c.shortForm)
        val def = TermMiner.definition(q, c.shortForm, sourceDocs)
        val use = TermMiner.usage(q, c.shortForm, sourceDocs, exclude = def?.sentence)
        val what: Triple<String, String, String> = when {
            wiki != null -> Triple(SentenceSplitter.split(wiki.extract).take(2).joinToString(" "), "Wikipedia", wiki.url)
            def != null -> Triple(def.sentence, def.doc.source + ": " + def.doc.title.take(90), def.doc.url)
            else -> return rejected("No source sentence explains it yet — $counts")
        }

        val jobs = c.docs.filter { it.type == "job" }
        val news = c.docs.filter { it.type == "news" }
        val repos = c.docs.filter { it.type == "repo" }
        val evidence = buildList {
            first?.let { add(Evidence(it.title, it.url, "arXiv · first paper", it.published, "paper")) }
            recentPapers.filter { it.url != first?.url }.take(3).forEach { add(Evidence(it.title, it.url, "arXiv", it.published, "paper")) }
            news.take(3).forEach { add(Evidence(it.title, it.url, it.source, it.date, "news")) }
            repos.take(2).forEach { add(Evidence(it.title, it.url, it.source, it.date, "repo")) }
            jobs.take(3).forEach { add(Evidence("${it.title} · ${it.company}", it.url, it.source, it.date, "job")) }
        }

        var simpleWhat: String? = null
        var simpleUsage: String? = null
        val s = settings.current()
        if (s.hasAi && s.aiSummaries) runCatching {
            val sources = listOf(what.first) + listOfNotNull(use?.sentence) + sourceDocs.take(6).map { it.text }
            val (w, u) = ai.simplifyTerm(c.display, what.first, use?.sentence, sources)
            if (TermMiner.grounded(w, sources)) simpleWhat = w
            if (u != null && TermMiner.grounded(u, sources)) simpleUsage = u
        }

        val score = ln(1.0 + r30) * (if (isNew) 1.6 else 1.0) * (1 + ln(growth.coerceAtLeast(1.0))) +
            0.3 * ln(1.0 + jobs.size + news.size)
        return base(c, now, previous).copy(
            status = if (isNew) TermStatus.NEW else TermStatus.RISING,
            reason = counts,
            what = what.first, whatSource = what.second, whatUrl = what.third,
            usage = use?.sentence, usageSource = use?.let { it.doc.source + ": " + it.doc.title.take(90) }, usageUrl = use?.doc?.url,
            simpleWhat = simpleWhat, simpleUsage = simpleUsage,
            papers30 = r30, papersPrior = prior, papersTotal = total, firstSeen = firstSeen,
            newsMentions = news.size, jobMentions = jobs.size,
            jobCompanies = jobs.map { it.company }.filter { it.isNotBlank() }.distinct().take(6).joinToString("|"),
            evidence = AppJson.encodeToString(evidenceSer, evidence), score = score,
        )
    }

    private fun base(c: TermMiner.Candidate, now: Long, previous: TermEntity?) = TermEntity(
        key = c.key, term = c.display, shortForm = c.shortForm, kind = "Concept", status = TermStatus.REJECTED, reason = "",
        what = null, whatSource = null, whatUrl = null, usage = null, usageSource = null, usageUrl = null,
        simpleWhat = null, simpleUsage = null, papers30 = 0, papersPrior = 0, papersTotal = 0, firstSeen = null,
        newsMentions = 0, jobMentions = 0, jobCompanies = "", repo = null, repoStars = null, evidence = "[]", score = 0.0,
        checkedAt = now, discoveredAt = previous?.discoveredAt ?: now,
    )

    // ------------------------------------------------------------------ verification: tools in job postings

    // Mixed-case product names (PyTorch, LangGraph, vLLM, Llama3); all-caps acronyms (API, AWS, SQL) are not tools.
    private val toolToken = Regex("(?<![A-Za-z0-9@/.])([A-Z][a-z]+[A-Z][A-Za-z0-9]*|[a-z]+[A-Z][A-Za-z0-9]*|[A-Za-z]*[a-z][A-Za-z]*[0-9][A-Za-z0-9]*)(?![A-Za-z0-9@/])")

    /** Mixed-case product names (PyTorch-style) that several different employers ask for, confirmed on GitHub. */
    private suspend fun verifyTools(now: Long, known: Map<String, TermEntity>, maxChecks: Int = 6): List<TermEntity> {
        val jobs = db.jobs().all()
        if (jobs.isEmpty()) return emptyList()
        val companies = jobs.map { TermMiner.normalize(it.company) }.toSet()
        val byToken = HashMap<String, MutableSet<JobEntity>>()
        for (j in jobs) toolToken.findAll(j.description).map { it.value }.toSet().forEach { byToken.getOrPut(it) { mutableSetOf() } += j }
        val ranked = byToken.entries
            .map { (tok, js) -> Triple(tok, js, js.map { it.company }.distinct().size) }
            .filter { (tok, _, nCompanies) -> nCompanies >= 3 && tok.length in 3..24 && TermMiner.normalize(tok) !in companies }
            .sortedByDescending { it.third }
        val out = mutableListOf<TermEntity>()
        var checks = 0
        for ((tok, js, nCompanies) in ranked) {
            if (checks >= maxChecks) break
            val key = "tool:" + TermMiner.normalize(tok)
            val k = known[key]
            if (k != null && now - k.checkedAt < (if (k.status == TermStatus.REJECTED) 10 else 3) * day) continue
            checks++
            val repo = ResearchSources.githubRepo(tok)
            // Independent confirmation: a PyPI or npm package of the same name must point at this exact repository.
            val pkg = repo?.let { r ->
                listOfNotNull(ResearchSources.pypi(tok.lowercase()), ResearchSources.npm(tok.lowercase()))
                    .firstOrNull { p -> p.links.any { it.contains("github.com/" + r.fullName, ignoreCase = true) } }
            }
            val recentShare = js.count { now - it.postedAt < 45 * day }.toDouble() / jobs.count { now - it.postedAt < 45 * day }.coerceAtLeast(1)
            val olderShare = js.count { now - it.postedAt >= 45 * day }.toDouble() / jobs.count { now - it.postedAt >= 45 * day }.coerceAtLeast(1)
            val rising = js.count { now - it.postedAt < 45 * day } >= 3 && recentShare >= 1.5 * olderShare
            val released = pkg?.firstRelease ?: repo?.createdAt
            val isNew = released != null && now - released < 730 * day
            val companiesList = js.map { it.company }.distinct()
            val reason = "Asked for by $nCompanies employers in ${js.size} current postings" +
                (repo?.let { "; GitHub ${it.fullName}: ${it.stars} stars" } ?: "; no matching GitHub project") +
                (pkg?.let { "; confirmed by ${it.registry} package “${it.name}”, first released ${it.firstRelease?.let { t -> Text.timeAgo(t, now) } ?: "unknown"}" }
                    ?: if (repo != null) "; not confirmed by PyPI/npm" else "")
            val baseEntity = TermEntity(
                key = key, term = tok, shortForm = null, kind = "Tool", status = TermStatus.REJECTED, reason = reason,
                what = null, whatSource = null, whatUrl = null, usage = null, usageSource = null, usageUrl = null,
                simpleWhat = null, simpleUsage = null, papers30 = 0, papersPrior = 0, papersTotal = 0, firstSeen = released,
                newsMentions = 0, jobMentions = js.size, jobCompanies = companiesList.take(6).joinToString("|"),
                repo = repo?.fullName, repoStars = repo?.stars, evidence = "[]", score = 0.0, checkedAt = now,
                discoveredAt = k?.discoveredAt ?: now,
            )
            if (repo == null || pkg == null || repo.stars < 1000 || (pkg.summary.isBlank() && repo.description.isBlank()) || (!isNew && !rising)) {
                out += baseEntity
                continue
            }
            val evidence = listOf(Evidence(repo.fullName + " — " + repo.description.take(80), repo.url, "GitHub", repo.createdAt, "repo")) +
                js.sortedByDescending { it.postedAt }.take(4).map { Evidence("${it.title} · ${it.company}", it.url, it.source, it.postedAt, "job") }
            out += baseEntity.copy(
                status = if (isNew) TermStatus.NEW else TermStatus.RISING,
                what = pkg.summary.ifBlank { repo.description },
                whatSource = "${pkg.registry} · ${pkg.name} (the project's own description)", whatUrl = pkg.url,
                usage = "Asked for in ${js.size} job postings, including at ${companiesList.take(4).joinToString(", ")}.",
                usageSource = "Computed from current job listings", usageUrl = null,
                evidence = AppJson.encodeToString(evidenceSer, evidence),
                score = ln(1.0 + nCompanies) * (if (isNew) 1.6 else 1.0) + ln(1.0 + repo.stars / 1000.0) * 0.3,
            )
        }
        return out
    }

    fun evidenceOf(t: TermEntity): List<Evidence> = runCatching { AppJson.decodeFromString(evidenceSer, t.evidence) }.getOrDefault(emptyList())
}
