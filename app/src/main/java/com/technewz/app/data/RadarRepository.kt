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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit
import kotlin.math.ln

@Serializable
data class Evidence(val title: String, val url: String, val source: String, val date: Long? = null, val type: String = "")

/**
 * Skills Radar: discovers new and rising AI/ML/DS terms and tools from the last 3 months of live data,
 * verifies each one against independent sources, explains it only with text taken from those sources,
 * and keeps a 3-month record of everything that passed.
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
    private val docsSer = ListSerializer(TermMiner.Doc.serializer())

    data class Result(val candidates: Int, val checked: Int, val accepted: List<String>, val rejected: Int, val paused: Boolean = false)

    suspend fun refresh(
        force: Boolean = false,
        maxChecks: Int = 12,
        onProgress: (String) -> Unit = {},
    ): Result? = withContext(HeavyWork.dispatcher) {
        val s = settings.current()
        ResearchSources.openAlexKey = s.openAlexKey
        val now = System.currentTimeMillis()
        // When the verification rules change, nothing is deleted: verified terms stay visible, are marked for
        // re-checking (checkedAt = 0) and are re-verified first; only those that fail the new rules are removed.
        val rulesChanged = s.radarVersion < RULES_VERSION
        if (rulesChanged) {
            dao.markAllForRecheck()
            settings.update { it.copy(radarVersion = RULES_VERSION) }
        }
        // Look for new terms in the background at most every 3 hours; everything found so far stays on screen.
        if (!force && !rulesChanged && now - s.lastRadarRefresh < TimeUnit.HOURS.toMillis(3)) return@withContext null

        // Memory-heavy phase (reading and mining text) runs under the shared lock; the slow, network-bound
        // verification below does not, so it never blocks news or jobs refreshes.
        onProgress("Reading the last 3 months of research, news and repos…")
        val candidates = HeavyWork.lock.withLock {
            val recent = recentCorpus(now)
            val baseline = baselineCorpus(now)
            // The comparison is only meaningful against real research text: without enough arXiv papers in the
            // baseline, textbook terms (e.g. "logistic regression") would look new. Skip rather than guess.
            val baselinePapers = baseline.count { it.source == "arXiv" }
            val recentPapers = recent.count { it.source == "arXiv" }
            if (baselinePapers < 500 || recentPapers < 300) null else TermMiner.candidates(recent, baseline)
        } ?: run {
            onProgress("Research sources didn't respond fully — will retry later rather than guess")
            // Retry in ~3 hours, not on every app open.
            settings.update { it.copy(lastRadarRefresh = now - TimeUnit.HOURS.toMillis(9)) }
            return@withContext Result(0, 0, emptyList(), 0, paused = true)
        }
        val known = dao.all().associateBy { it.key }
        val firstRun = known.none { it.value.status != TermStatus.REJECTED }

        // Terms awaiting a rules re-check go first (even if they aren't in today's candidates), then new candidates.
        // Verified terms are otherwise re-checked every 3 days, rejected ones every 10 days.
        val recheck = known.values.filter { it.checkedAt == 0L && it.kind == "Concept" && it.status != TermStatus.REJECTED }
            .map { t ->
                val long = t.term.substringBefore(" (")
                TermMiner.Candidate(t.key, long, t.shortForm, emptyList(), 0, t.score, listOf(long))
            }
        val fresh = candidates.filter { c ->
            val k = known[c.key] ?: return@filter true
            if (k.checkedAt == 0L) return@filter false
            val age = now - k.checkedAt
            if (k.status == TermStatus.REJECTED) age > 10 * day else age > 3 * day
        }
        val toCheck = recheck + fresh

        val accepted = mutableListOf<String>()
        var rejected = 0
        var arxivChecks = 0
        var budgetPaused = false
        var screened = 0
        jobTextCache = null
        for (c in toCheck) {
            // Cheap OpenAlex screening first; the slow arXiv checks are spent only on promising terms.
            // Only the top candidates per scan go to OpenAlex, which keeps well inside its free daily budget.
            if (arxivChecks >= maxChecks || screened >= 20 + recheck.size) break
            screened++
            onProgress("Verifying “${c.display}” (${arxivChecks + 1}/$maxChecks)…")
            val outcome = try {
                verifyConcept(c, now, known[c.key])
            } catch (e: ResearchSources.OpenAlexBudgetExhausted) {
                // Never guess: stop here; unchecked terms are simply checked on a later scan.
                budgetPaused = true
                break
            } catch (e: Exception) {
                null
            } ?: continue
            if (outcome.usedArxiv) arxivChecks++
            val prev = known[c.key]
            val t = outcome.term
            // A verified term stays in the record when a routine re-check is inconclusive, but a term that fails
            // a rules re-check (made under older, looser rules) is removed: it no longer meets the truth rules.
            if (t.status == TermStatus.REJECTED && prev != null && prev.status != TermStatus.REJECTED) {
                if (prev.checkedAt == 0L) dao.upsert(t) else dao.upsert(prev.copy(checkedAt = now))
                continue
            }
            dao.upsert(t)
            if (t.status == TermStatus.REJECTED) rejected++
            else if (prev?.status.let { it == null || it == TermStatus.REJECTED }) accepted += t.term
        }

        onProgress("Checking new tools in job postings…")
        val tools = runCatching { verifyTools(now, known) }.getOrDefault(emptyList())
        tools.forEach { t ->
            val prev = known[t.key]
            if (t.status == TermStatus.REJECTED && prev != null && prev.status != TermStatus.REJECTED) {
                dao.upsert(prev.copy(checkedAt = now)); return@forEach
            }
            dao.upsert(t)
            if (t.status != TermStatus.REJECTED && prev?.status.let { it == null || it == TermStatus.REJECTED }) accepted += t.term
            if (t.status == TermStatus.REJECTED) rejected++
        }
        jobTextCache = null

        // Verified terms are kept permanently; only rejected candidates are forgotten (after a month) so they can be re-checked.
        dao.pruneRejected(now - 30 * day)
        if (s.alertsEnabled && !firstRun && accepted.isNotEmpty()) Notifications.radarAlert(context, accepted)
        settings.update { it.copy(lastRadarRefresh = if (budgetPaused) now - TimeUnit.HOURS.toMillis(9) else now) }
        if (budgetPaused) onProgress("Paused: OpenAlex's free daily limit was reached — will continue on a later scan")
        Result(candidates.size, screened + tools.size, accepted, rejected, budgetPaused)
    }

    // ------------------------------------------------------------------ corpora

    /** Today's arXiv listings + a sample of the last 3 months of AI/ML papers + recent news and repos. */
    private suspend fun recentCorpus(now: Long): List<TermMiner.Doc> = coroutineScope {
        val today = ResearchSources.arxivListings.map { url ->
            async { runCatching { RssParser.parse(Http.get(url)) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.link }.map {
            TermMiner.Doc(it.link, it.title, Text.cleanFeedText(it.descriptionHtml).take(1500), it.link, "arXiv", "paper", it.published ?: now)
        }
        val lastThreeMonths = cached("radar_last3m.json", maxAgeDays = 7) {
            ResearchSources.arxivLastThreeMonths().map { TermMiner.Doc(it.url, it.title, it.abstract.take(1500), it.url, "arXiv", "paper", it.published) }
        }
        val news = db.articles().since(now - 90 * day).map {
            TermMiner.Doc(it.id, it.title, it.excerpt.take(1500), it.url, it.source, "news", it.publishedAt)
        }
        val repos = db.trending().allOnce().filter { it.kind != "models" }.map {
            TermMiner.Doc(it.kind + it.id, it.title, it.subtitle, it.url, if (it.kind == "repos") "GitHub" else "Hugging Face", if (it.kind == "repos") "repo" else "paper", it.fetchedAt)
        }
        (today + lastThreeMonths).distinctBy { it.url.removeSuffix("/").substringAfterLast("/") } + news + repos
    }

    /**
     * What "already known" looks like: a sample of arXiv AI/ML papers from 6–18 months ago (cached for
     * 30 days) plus older posts from official AI blogs.
     */
    private suspend fun baselineCorpus(now: Long): List<TermMiner.Doc> = coroutineScope {
        val papers = cached("radar_baseline_v2.json", maxAgeDays = 30) {
            ResearchSources.arxivBaseline().map { TermMiner.Doc(it.url, it.title, it.abstract.take(1500), it.url, "arXiv", "paper", it.published) }
        }
        papers + Feeds.all.filter { it.section == Section.AI && !it.name.startsWith("arXiv") }.map { f ->
            async { runCatching { RssParser.parse(Http.get(f.url)) }.getOrDefault(emptyList()).map { f to it } }
        }.awaitAll().flatten()
            .filter { (_, it) -> (it.published ?: now) < now - 180 * day }
            .sortedByDescending { it.second.published ?: 0 }.take(1500)
            .map { (f, it) -> TermMiner.Doc(it.link, it.title, Text.htmlToText(it.descriptionHtml).take(1000), it.link, f.name, "news", it.published ?: 0) }
    }

    private suspend fun cached(file: String, maxAgeDays: Int, load: suspend () -> List<TermMiner.Doc>): List<TermMiner.Doc> {
        val f = java.io.File(context.filesDir, file)
        val now = System.currentTimeMillis()
        runCatching { if (f.exists() && now - f.lastModified() < maxAgeDays * day) return AppJson.decodeFromString(docsSer, f.readText()) }
        return runCatching { load().also { if (it.size > 200) f.writeText(AppJson.encodeToString(docsSer, it)) } }
            .getOrElse { runCatching { AppJson.decodeFromString(docsSer, f.readText()) }.getOrDefault(emptyList()) }
    }

    // ------------------------------------------------------------------ job texts (loaded only when needed)

    private var jobTextCache: List<JobText>? = null
    private suspend fun jobTexts(): List<JobText> = jobTextCache ?: db.jobs().texts().also { jobTextCache = it }

    private suspend fun jobsMentioning(term: String, shortForm: String?): List<JobText> {
        val key = TermMiner.normalize(term)
        val sfRx = shortForm?.let { Regex("(?<![A-Za-z0-9-])" + Regex.escape(it) + "(?![A-Za-z0-9-])") }
        return jobTexts().filter { j ->
            " ${TermMiner.normalize(j.title + " " + j.description)} ".contains(" $key ") || (sfRx != null && sfRx.containsMatchIn(j.title + " " + j.description))
        }
    }

    // ------------------------------------------------------------------ verification: concepts

    private class Outcome(val term: TermEntity, val usedArxiv: Boolean)

    private suspend fun verifyConcept(c: TermMiner.Candidate, now: Long, previous: TermEntity?): Outcome? {
        // Several spellings may exist; verify the one the literature actually uses most.
        val histories = c.variants.take(2).mapNotNull { v -> ResearchSources.openAlexYears(v)?.let { v to it } }
        if (histories.isEmpty()) return null // source unreachable: retry next run
        val (q, perYear) = histories.maxBy { it.second.values.sum() }
        val today = java.time.LocalDate.now()
        val year = today.year
        // When it first appeared in the last 3 months of sources (drives the timeline).
        val spotted = c.docs.map { it.date }.filter { it in (now - 100 * day)..now }.minOrNull() ?: now
        fun base() = base(c, now, previous, spotted)
        fun rejected(reason: String, arxiv: Boolean) = Outcome(base().copy(status = TermStatus.REJECTED, reason = reason), arxiv)

        // 1. When did it emerge? OpenAlex counts every scholarly work (not just arXiv) per year. A term must have
        //    surged within the last 3 years with no real presence before; steady long-term use is rejected.
        val emerged = TermMiner.emergenceYear(perYear, year)
        val history = "OpenAlex works per year: " + (year - 5..year).joinToString(", ") { "$it: ${perYear[it] ?: 0}" }
        if (emerged == null) return rejected("Established or too little evidence — never surged above its own history. $history", false)
        if (emerged < year - 3) return rejected("Established — took off in $emerged. $history", false)

        // 2. Is it active in research right now? (arXiv computer-science papers, counted via OpenAlex)
        val r30 = ResearchSources.activityCount(q, 30, 0)
        if (r30 < 5) return rejected("Too little research activity — $r30 arXiv CS papers in the last 30 days. $history", true)
        val prior = ResearchSources.activityCount(q, 180, 31)

        // 3. Still growing? This year's pace vs last year (OpenAlex) or this month vs the 5 months before (arXiv).
        val thisYearPace = (perYear[year] ?: 0) * 365.0 / today.dayOfYear
        val lastYear = perYear[year - 1] ?: 0
        val growth = (r30 / 30.0 + 0.01) / (prior / 150.0 + 0.01)
        if (thisYearPace < lastYear && growth < 1.2) return rejected("Not growing any more — $history; arXiv CS papers: $r30 in 30 days vs $prior in the 150 days before", true)
        val isNew = emerged >= year - 1
        val total = perYear.values.sum()
        val firstSeen = java.time.LocalDate.of(emerged, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val counts = "Took off in $emerged ($history). arXiv CS papers: $r30 in the last 30 days vs $prior in the 150 days before"

        // Explanation strictly from sources: Wikipedia (if a page exists for exactly this term) or a paper/news sentence.
        val recentPapers = ResearchSources.recentPapersFor(q, 90, 25)
        val sourceDocs = recentPapers.map { TermMiner.Doc(it.url, it.title, it.abstract, it.url, "arXiv", "paper", it.published) } +
            c.docs.filter { it.type == "paper" || it.type == "news" }
        val wiki = ResearchSources.wikipediaFor(q, c.shortForm)
        val def = TermMiner.definition(q, c.shortForm, sourceDocs)
        val use = TermMiner.usage(q, c.shortForm, sourceDocs, exclude = def?.sentence)
        val what: Triple<String, String, String> = when {
            wiki != null -> Triple(SentenceSplitter.split(wiki.extract).take(2).joinToString(" "), "Wikipedia", wiki.url)
            def != null -> Triple(def.sentence, def.doc.source + ": " + def.doc.title.take(90), def.doc.url)
            else -> return rejected("No source sentence explains it yet — $counts", true)
        }

        val jobs = jobsMentioning(q, c.shortForm)
        val news = c.docs.filter { it.type == "news" }
        val repos = c.docs.filter { it.type == "repo" }
        val evidence = buildList {
            add(Evidence("Publication history of “$q” ($total works; took off in $emerged)", ResearchSources.openAlexUrl(q), "OpenAlex", null, "paper"))
            recentPapers.take(3).forEach { add(Evidence(it.title, it.url, "arXiv", it.published, "paper")) }
            news.take(3).forEach { add(Evidence(it.title, it.url, it.source, it.date, "news")) }
            repos.take(2).forEach { add(Evidence(it.title, it.url, it.source, it.date, "repo")) }
            jobs.take(3).forEach { add(Evidence("${it.title} · ${it.company}", it.url, it.source, it.postedAt, "job")) }
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
        return Outcome(
            base().copy(
                term = if (c.shortForm != null) "$q (${c.shortForm})" else q,
                status = if (isNew) TermStatus.NEW else TermStatus.RISING,
                reason = counts,
                what = what.first, whatSource = what.second, whatUrl = what.third,
                usage = use?.sentence, usageSource = use?.let { it.doc.source + ": " + it.doc.title.take(90) }, usageUrl = use?.doc?.url,
                simpleWhat = simpleWhat, simpleUsage = simpleUsage,
                papers30 = r30, papersPrior = prior, papersTotal = total, firstSeen = firstSeen,
                newsMentions = news.size, jobMentions = jobs.size,
                jobCompanies = jobs.map { it.company }.filter { it.isNotBlank() }.distinct().take(6).joinToString("|"),
                evidence = AppJson.encodeToString(evidenceSer, evidence), score = score,
            ),
            usedArxiv = true,
        )
    }

    private fun base(c: TermMiner.Candidate, now: Long, previous: TermEntity?, spotted: Long) = TermEntity(
        key = c.key, term = c.display, shortForm = c.shortForm, kind = "Concept", status = TermStatus.REJECTED, reason = "",
        what = null, whatSource = null, whatUrl = null, usage = null, usageSource = null, usageUrl = null,
        simpleWhat = null, simpleUsage = null, papers30 = 0, papersPrior = 0, papersTotal = 0, firstSeen = null,
        newsMentions = 0, jobMentions = 0, jobCompanies = "", repo = null, repoStars = null, evidence = "[]", score = 0.0,
        // "First spotted": the earliest mention in the last 3 months of sources, never later than a previous record.
        checkedAt = now, discoveredAt = minOf(previous?.discoveredAt ?: Long.MAX_VALUE, spotted),
    )

    // ------------------------------------------------------------------ verification: tools in job postings

    // Mixed-case product names (PyTorch, LangGraph, vLLM, Llama3); all-caps acronyms (API, AWS, SQL) are not tools.
    private val toolToken = Regex("(?<![A-Za-z0-9@/.])([A-Z][a-z]+[A-Z][A-Za-z0-9]*|[a-z]+[A-Z][A-Za-z0-9]*|[A-Za-z]*[a-z][A-Za-z]*[0-9][A-Za-z0-9]*)(?![A-Za-z0-9@/])")

    /** Mixed-case product names (PyTorch-style) that several different employers ask for, confirmed on GitHub + PyPI/npm. */
    private suspend fun verifyTools(now: Long, known: Map<String, TermEntity>, maxChecks: Int = 6): List<TermEntity> {
        val jobs = HeavyWork.lock.withLock { jobTexts() }
        if (jobs.isEmpty()) return emptyList()
        val companies = jobs.map { TermMiner.normalize(it.company) }.toSet()
        val byToken = HashMap<String, MutableList<JobText>>()
        for (j in jobs) toolToken.findAll(j.description).map { it.value }.toSet().forEach { byToken.getOrPut(it) { mutableListOf() } += j }
        val ranked = byToken.entries
            .map { (tok, js) -> Triple(tok, js, js.map { it.company }.distinct().size) }
            .filter { (tok, _, nCompanies) -> nCompanies >= 3 && tok.length in 3..24 && TermMiner.normalize(tok) !in companies }
            .sortedByDescending { it.third }
        val recentTotal = jobs.count { now - it.postedAt < 45 * day }.coerceAtLeast(1)
        val olderTotal = jobs.count { now - it.postedAt >= 45 * day }.coerceAtLeast(1)
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
            val recentShare = js.count { now - it.postedAt < 45 * day }.toDouble() / recentTotal
            val olderShare = js.count { now - it.postedAt >= 45 * day }.toDouble() / olderTotal
            val rising = js.count { now - it.postedAt < 45 * day } >= 3 && recentShare >= 1.5 * olderShare
            val released = pkg?.firstRelease ?: repo?.createdAt
            val isNew = released != null && now - released < 730 * day
            val companiesList = js.map { it.company }.distinct()
            val reason = "Asked for by $nCompanies employers in ${js.size} current postings" +
                (repo?.let { "; GitHub ${it.fullName}: ${it.stars} stars" } ?: "; no matching GitHub project") +
                (pkg?.let { "; confirmed by ${it.registry} package “${it.name}”, first released ${it.firstRelease?.let { t -> Text.timeAgo(t, now) } ?: "unknown"}" }
                    ?: if (repo != null) "; not confirmed by PyPI/npm" else "")
            val spotted = js.minOf { it.postedAt }.coerceIn(now - 90 * day, now)
            val baseEntity = TermEntity(
                key = key, term = tok, shortForm = null, kind = "Tool", status = TermStatus.REJECTED, reason = reason,
                what = null, whatSource = null, whatUrl = null, usage = null, usageSource = null, usageUrl = null,
                simpleWhat = null, simpleUsage = null, papers30 = 0, papersPrior = 0, papersTotal = 0, firstSeen = released,
                newsMentions = 0, jobMentions = js.size, jobCompanies = companiesList.take(6).joinToString("|"),
                repo = repo?.fullName, repoStars = repo?.stars, evidence = "[]", score = 0.0, checkedAt = now,
                discoveredAt = minOf(k?.discoveredAt ?: Long.MAX_VALUE, spotted),
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

    companion object {
        /** Bump whenever verification rules change. v2: OpenAlex emergence year + AI/ML-only arXiv counts.
         *  v3: terms with a real presence 5–10 years ago are established, not new. v4: most-used spelling.
         *  v5: 3-month history ("first spotted" dates). */
        const val RULES_VERSION = 6
    }

    fun evidenceOf(t: TermEntity): List<Evidence> = runCatching { AppJson.decodeFromString(evidenceSer, t.evidence) }.getOrDefault(emptyList())
}
