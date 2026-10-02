package com.technewz.app.data

import android.content.Context
import com.technewz.app.net.AiUnavailableException
import com.technewz.app.net.ArticleFetcher
import com.technewz.app.net.Feed
import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.RssParser
import com.technewz.app.net.TrendingSources
import com.technewz.app.util.Text
import com.technewz.app.work.Notifications
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

class NewsRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val ai: AiFeatures,
) {
    private val dao = db.articles()

    /** Our own trained sentence-scoring model, bundled in assets; runs fully on-device. */
    private val summarizer by lazy {
        val model = runCatching {
            context.assets.open("summarizer_model.json").bufferedReader().use { com.technewz.app.ml.SummarizerModel.fromJson(it.readText()) }
        }.getOrNull()
        com.technewz.app.ml.ExtractiveSummarizer(model)
    }

    data class RefreshResult(val newArticles: Int, val failedFeeds: List<String>, val aiNote: String?)

    suspend fun refresh(): RefreshResult = coroutineScope {
        val s = settings.current()
        val now = System.currentTimeMillis()

        // 1. Fetch every feed in parallel. A failing feed never blocks the others.
        val gate = Semaphore(8)
        val results = Feeds.all.map { feed ->
            async {
                gate.withPermit {
                    feed to runCatching { val xml = Http.get(feed.url); RssParser.parse(xml) to com.technewz.app.net.NewsFilter.siteLink(xml) }
                }
            }
        }.awaitAll()
        val failed = results.filter { it.second.isFailure || it.second.getOrNull()?.first.isNullOrEmpty() }.map { it.first.name }

        // 2. Build candidates, skipping anything we already have or that is too old.
        val known = dao.allIds().toHashSet()
        val candidates = LinkedHashMap<String, Pair<Feed, com.technewz.app.net.RawItem>>()
        for ((feed, res) in results) {
            val (items, siteLink) = res.getOrNull() ?: continue
            for (item in items.sortedByDescending { it.published ?: 0L }.take(feed.maxItems)) {
                // Undated, future-dated, off-site or commerce items are never shown as news.
                if (com.technewz.app.net.NewsFilter.reject(feed.url, siteLink, item, now) != null) continue
                // Same story may legitimately live in both Tech and AI tabs, so ids are per section.
                val id = Text.hash(feed.section + "|" + Text.canonicalUrl(item.link))
                if (id in known || id in candidates) continue
                candidates[id] = feed to item
            }
        }
        val fresh = candidates.entries.sortedByDescending { it.value.second.published }.take(80)

        // 3. Enrich: pull article text for better summaries + a lead image (bounded and parallel).
        val pageGate = Semaphore(6)
        val enriched = fresh.mapIndexed { index, (id, pair) ->
            async {
                val (feed, item) = pair
                val feedText = Text.cleanFeedText(item.descriptionHtml)
                val needPage = index < 40 && (feedText.length < 400 || item.imageUrl == null) && !feed.url.contains("arxiv")
                val page = if (needPage) pageGate.withPermit { ArticleFetcher.fetch(item.link) } else null
                val body = listOf(feedText, page?.text.orEmpty()).maxBy { it.length }
                val excerpt = body.take(2500)
                val onDevice = runCatching { summarizer.summarize(item.title, body.take(6000)) }.getOrNull()
                ArticleEntity(
                    id = id,
                    url = item.link,
                    title = item.title,
                    source = feed.name,
                    sourceDomain = Text.domainOf(item.link).ifBlank { Text.domainOf(feed.url) },
                    section = feed.section,
                    topic = Feeds.guessTopic(feed.section, feed.defaultTopic, item.title, excerpt),
                    imageUrl = item.imageUrl ?: page?.image,
                    publishedAt = item.published!!,
                    fetchedAt = now,
                    excerpt = excerpt,
                    summary = onDevice?.summary ?: Text.trimWords(feedText.ifBlank { excerpt }, 50).ifBlank { null },
                    summaryKind = if (onDevice != null) SummaryKind.ON_DEVICE else SummaryKind.EXCERPT,
                    whyItMatters = null,
                    clusterId = id,
                )
            }
        }.awaitAll()

        // 4. Cluster: same story from multiple outlets collapses into one card.
        val recent = dao.since(now - TimeUnit.HOURS.toMillis(48))
        data class Node(val clusterId: String, val section: String, val paper: Boolean, val title: String, val tokens: Set<String>)
        val pool = recent.map { Node(it.clusterId, it.section, it.source.startsWith("arXiv"), it.title, Text.titleTokens(it.title)) }.toMutableList()
        val clustered = enriched.sortedBy { it.publishedAt }.map { a ->
            val tokens = Text.titleTokens(a.title)
            val paper = a.source.startsWith("arXiv")
            val match = pool.firstOrNull { n ->
                n.section == a.section && if (paper || n.paper) n.title.equals(a.title, ignoreCase = true) else Text.sameStory(tokens, n.tokens)
            }
            val clusterId = match?.clusterId ?: a.id
            pool += Node(clusterId, a.section, paper, a.title, tokens)
            a.copy(clusterId = clusterId)
        }
        dao.insertAll(clustered)

        // 5. AI summaries (newest first, bounded per refresh to respect free-tier limits).
        var aiNote: String? = null
        if (s.hasAi && s.aiSummaries) {
            aiNote = runCatching { summarizePending() }.exceptionOrNull()?.let {
                if (it is AiUnavailableException) it.message else "AI summaries paused: ${it.message?.take(120)}"
            }
        }

        // 6. Keyword alerts (never on the very first sync, which would flood notifications).
        if (s.alertsEnabled && s.keywords.isNotEmpty() && s.lastNewsRefresh > 0) {
            sendAlerts(dao.byIds(clustered.map { it.id }), s.keywords)
        }

        dao.prune(now - TimeUnit.DAYS.toMillis(7))
        runCatching { refreshTrendingIfStale() }
        settings.update { it.copy(lastNewsRefresh = now, lastError = aiNote.orEmpty()) }
        RefreshResult(clustered.size, failed, aiNote)
    }

    private suspend fun summarizePending() {
        val profile = settings.currentProfile()
        val pending = dao.needingAiSummary(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(36), 20)
        val topics = mapOf(Section.TECH to Feeds.techTopics, Section.AI to Feeds.aiTopics)
        for (batch in pending.chunked(10)) {
            val results = ai.summarize(batch, profile.skills, topics)
            if (results.isEmpty()) continue
            dao.upsertAll(batch.mapNotNull { a ->
                val r = results[a.id] ?: return@mapNotNull null
                a.copy(summary = r.summary, whyItMatters = r.why, topic = r.topic ?: a.topic, summaryKind = SummaryKind.AI)
            })
        }
    }

    private suspend fun sendAlerts(articles: List<ArticleEntity>, keywords: List<String>) {
        val sentIds = mutableListOf<String>()
        for (kw in keywords) {
            val rx = Regex("(?<![\\p{L}\\d])" + Regex.escape(kw) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
            val hits = articles.filter { !it.alerted && it.id !in sentIds && (rx.containsMatchIn(it.title) || rx.containsMatchIn(it.summary.orEmpty())) }
                .distinctBy { it.clusterId }
            if (hits.isEmpty()) continue
            Notifications.newsAlert(context, kw, hits.map { Notifications.Line(it.title, it.source, it.url) })
            sentIds += hits.map { it.id }
        }
        if (sentIds.isNotEmpty()) dao.markAlerted(sentIds)
    }

    // ---------------- Trending (AI tab) ----------------
    suspend fun refreshTrendingIfStale(force: Boolean = false) = coroutineScope {
        val last = db.trending().lastFetched() ?: 0
        if (!force && System.currentTimeMillis() - last < TimeUnit.MINUTES.toMillis(60)) return@coroutineScope
        val now = System.currentTimeMillis()
        val jobs = listOf(
            "models" to async { runCatching { TrendingSources.hfModels() } },
            "papers" to async { runCatching { TrendingSources.hfPapers() } },
            "repos" to async { runCatching { TrendingSources.githubRepos() } },
        )
        for ((kind, deferred) in jobs) {
            val items = deferred.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            db.trending().clear(kind)
            db.trending().upsertAll(items.mapIndexed { i, it ->
                TrendingEntity(kind, it.id, i, it.title, it.subtitle, it.url, it.metric, now)
            })
        }
    }

    suspend fun setBookmarked(id: String, value: Boolean) = dao.setBookmarked(id, value)
    suspend fun markRead(id: String) = dao.markRead(id)
}
