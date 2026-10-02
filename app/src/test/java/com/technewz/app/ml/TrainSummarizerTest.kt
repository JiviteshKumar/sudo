package com.technewz.app.ml

import com.technewz.app.data.AppJson
import com.technewz.app.net.ArticleFetcher
import com.technewz.app.net.Feeds
import com.technewz.app.net.Http
import com.technewz.app.net.RssParser
import com.technewz.app.util.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds the datasets, trains the sentence scorer and writes:
 *  - app/src/main/assets/summarizer_model.json (shipped in the app)
 *  - ml/REPORT.md (data provenance, filters, benchmark vs baselines, sample outputs)
 *
 * Run: set TRAIN=1 then  gradlew :app:testDebugUnitTest --tests "*TrainSummarizerTest*" -i
 */
class TrainSummarizerTest {
    @Serializable
    data class Doc(val id: String, val source: String, val title: String, val text: String, val reference: String, val url: String = "")

    private val root = File("..").canonicalFile
    private val dataDir = File(root, "ml/data").apply { mkdirs() }
    private val summarizer = ExtractiveSummarizer(null)
    private val log = StringBuilder()
    private fun note(s: String) { println(s); log.appendLine(s) }

    // ------------------------------------------------------------------ data

    /** CNN stories with journalist-written highlights (CNN/DailyMail 3.0.0, train split, CNN portion only). */
    private suspend fun loadCnn(): List<Doc> {
        val cache = File(dataDir, "cnn.jsonl")
        if (cache.exists()) return cache.readLines().map { AppJson.decodeFromString(Doc.serializer(), it) }
        val docs = mutableListOf<Doc>()
        for (page in 0 until 60) {
            val offset = page * 1500
            val url = "https://datasets-server.huggingface.co/rows?dataset=abisee/cnn_dailymail&config=3.0.0&split=train&offset=$offset&length=100"
            var body: String? = null
            for (attempt in 0 until 4) {
                body = runCatching { Http.get(url) }.getOrNull()
                if (body != null) break
                delay(3000L * (attempt + 1))
            }
            val rows = body?.let { AppJson.parseToJsonElement(it).jsonObject["rows"]?.jsonArray } ?: continue
            for (r in rows) {
                val row = r.jsonObject["row"]!!.jsonObject
                val article = row["article"]!!.jsonPrimitive.content
                val head = article.take(200)
                if (!head.contains("(CNN)")) continue // CNN only: Daily Mail text carries layout artifacts
                val text = article.substringAfter("(CNN)").trimStart(' ', '-', '—').trim()
                val highlights = row["highlights"]!!.jsonPrimitive.content.split('\n').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
                if (highlights.isEmpty() || text.length < 500) continue
                docs += Doc(row["id"]!!.jsonPrimitive.content, "CNN", "", text, highlights.joinToString(". ") + ".")
            }
            println("cnn page $page -> ${docs.size}")
        }
        val unique = docs.distinctBy { it.id }.distinctBy { it.text.take(200) }
        cache.writeText(unique.joinToString("\n") { AppJson.encodeToString(it) })
        return unique
    }

    /** Tech articles from the app's own vetted feeds; reference = the publisher's own summary line (dek). */
    private suspend fun loadTech(): List<Doc> {
        val cache = File(dataDir, "tech.jsonl")
        val existing = if (cache.exists()) cache.readLines().map { AppJson.decodeFromString(Doc.serializer(), it) } else emptyList()
        val known = existing.map { it.url }.toHashSet()
        val gate = Semaphore(6)
        val fresh = Feeds.all.filter { !it.name.startsWith("arXiv") }.map { feed ->
            runCatching { RssParser.parse(Http.get(feed.url)).take(40) }.getOrDefault(emptyList()).map { item ->
                Triple(feed, item, Text.htmlToText(item.descriptionHtml)
                    .replace(Regex("The post .* appeared first on .*$"), "").trim())
            }
        }.flatten()
            .filter { (_, item, dek) ->
                val w = Text.wordCount(dek)
                item.link !in known && w in 12..70 && !Regex("(…|\\.\\.\\.|\\[…]|Read more|Continue reading)\\s*$").containsMatchIn(dek)
            }
        note("Tech candidates with a usable publisher summary: ${fresh.size}")
        val docs = runBlocking {
            fresh.map { (feed, item, dek) ->
                async {
                    gate.withPermit {
                        val page = ArticleFetcher.fetch(item.link) ?: return@withPermit null
                        if (Text.wordCount(page.text) < 150) return@withPermit null
                        Doc(Text.hash(item.link), feed.name, item.title, page.text, dek, item.link)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val all = (existing + docs).distinctBy { it.url }.distinctBy { it.text.take(200) }
        cache.writeText(all.joinToString("\n") { AppJson.encodeToString(it) })
        return all
    }

    // ------------------------------------------------------------------ labels & metrics

    data class Example(val doc: Doc, val sentences: List<String>, val features: Array<DoubleArray>, val oracle: Set<Int>)

    private fun oracle(sentences: List<String>, reference: String): Set<Int> {
        val chosen = mutableListOf<Int>()
        var best = 0.0
        while (chosen.size < 3) {
            var pick = -1
            for (i in sentences.indices) {
                if (i in chosen) continue
                val cand = (chosen + i).sorted().joinToString(" ") { sentences[it] }
                if (Text.wordCount(cand) > 70) continue
                val s = Rouge.f(cand, reference, 1) + Rouge.f(cand, reference, 2)
                if (s > best) { best = s; pick = i }
            }
            if (pick < 0) break
            chosen += pick
        }
        return chosen.toSet()
    }

    private fun prepare(docs: List<Doc>, label: String): List<Example> {
        var thin = 0; var unsupported = 0; var copiedLead = 0
        val out = docs.mapNotNull { d ->
            val sents = summarizer.candidates(d.text).take(60)
            if (sents.size < 3 || sents.sumOf { Text.wordCount(it) } < 60) { thin++; return@mapNotNull null }
            // Quality gate 1: the reference must actually be supported by the article text.
            if (Rouge.recall(d.text, d.reference, 1) < 0.35) { unsupported++; return@mapNotNull null }
            // Quality gate 2 (publisher deks): a dek that just repeats the first sentence is not an independent summary.
            if (d.source != "CNN" && Rouge.f(sents[0], d.reference, 2) > 0.6) { copiedLead++; return@mapNotNull null }
            Example(d, sents, Features.compute(d.title, sents), oracle(sents, d.reference))
        }
        note("$label: ${docs.size} docs -> ${out.size} kept (dropped: $thin too short/paywalled, $unsupported reference not supported by text, $copiedLead dek copies lead)")
        return out
    }

    // ------------------------------------------------------------------ model

    private class Mlp(val nIn: Int, val nH: Int, rnd: Random) {
        val w1 = Array(nH) { DoubleArray(nIn) { rnd.nextDouble(-1.0, 1.0) * sqrt(1.0 / nIn) } }
        val b1 = DoubleArray(nH)
        val w2 = DoubleArray(nH) { rnd.nextDouble(-1.0, 1.0) * sqrt(1.0 / nH) }
        var b2 = 0.0
        // Adam state
        val m1 = Array(nH) { DoubleArray(nIn) }; val v1 = Array(nH) { DoubleArray(nIn) }
        val mb1 = DoubleArray(nH); val vb1 = DoubleArray(nH)
        val m2 = DoubleArray(nH); val v2 = DoubleArray(nH)
        var mb2 = 0.0; var vb2 = 0.0; var t = 0

        fun forward(x: DoubleArray, h: DoubleArray): Double {
            var o = b2
            for (j in 0 until nH) {
                var z = b1[j]
                for (f in 0 until nIn) z += w1[j][f] * x[f]
                h[j] = kotlin.math.tanh(z)
                o += w2[j] * h[j]
            }
            return 1.0 / (1.0 + exp(-o))
        }

        fun trainBatch(xs: List<DoubleArray>, ys: List<Double>, ws: List<Double>, lr: Double, l2: Double) {
            val gW1 = Array(nH) { DoubleArray(nIn) }; val gB1 = DoubleArray(nH); val gW2 = DoubleArray(nH); var gB2 = 0.0
            val h = DoubleArray(nH)
            for (k in xs.indices) {
                val p = forward(xs[k], h)
                val d = (p - ys[k]) * ws[k]
                gB2 += d
                for (j in 0 until nH) {
                    gW2[j] += d * h[j]
                    val dh = d * w2[j] * (1 - h[j] * h[j])
                    gB1[j] += dh
                    for (f in 0 until nIn) gW1[j][f] += dh * xs[k][f]
                }
            }
            val n = xs.size.toDouble()
            t++
            fun adam(g: Double, m: Double, v: Double): Triple<Double, Double, Double> {
                val nm = 0.9 * m + 0.1 * g; val nv = 0.999 * v + 0.001 * g * g
                val mh = nm / (1 - Math.pow(0.9, t.toDouble())); val vh = nv / (1 - Math.pow(0.999, t.toDouble()))
                return Triple(lr * mh / (sqrt(vh) + 1e-8), nm, nv)
            }
            for (j in 0 until nH) {
                for (f in 0 until nIn) {
                    val (s, m, v) = adam(gW1[j][f] / n + l2 * w1[j][f], m1[j][f], v1[j][f]); w1[j][f] -= s; m1[j][f] = m; v1[j][f] = v
                }
                val (s1, m, v) = adam(gB1[j] / n, mb1[j], vb1[j]); b1[j] -= s1; mb1[j] = m; vb1[j] = v
                val (s2, mm, vv) = adam(gW2[j] / n + l2 * w2[j], m2[j], v2[j]); w2[j] -= s2; m2[j] = mm; v2[j] = vv
            }
            val (s, m, v) = adam(gB2 / n, mb2, vb2); b2 -= s; mb2 = m; vb2 = v
        }

        fun export(mean: DoubleArray, std: DoubleArray, trainedOn: String) = SummarizerModel(
            1, mean.toList(), std.toList(), w1.map { it.toList() }, b1.toList(), w2.toList(), b2, trainedOn = trainedOn,
        )
    }

    // ------------------------------------------------------------------ evaluation

    data class Scores(val r1: Double, val r2: Double, val rl: Double, val words: Double) {
        val avg get() = (r1 + r2 + rl) / 3
    }

    private fun evaluate(examples: List<Example>, scorer: (Example) -> DoubleArray): Scores {
        var r1 = 0.0; var r2 = 0.0; var rl = 0.0; var w = 0.0
        for (e in examples) {
            val res = ExtractiveSummarizer.select(e.sentences, scorer(e), 50, 60)
            val s = res?.summary.orEmpty()
            r1 += Rouge.f(s, e.doc.reference, 1); r2 += Rouge.f(s, e.doc.reference, 2); rl += Rouge.lcsF(s, e.doc.reference)
            w += Text.wordCount(s)
        }
        val n = examples.size.coerceAtLeast(1)
        return Scores(100 * r1 / n, 100 * r2 / n, 100 * rl / n, w / n)
    }

    private fun split(id: String): Int = (Text.hash(id).take(6).toInt(16) % 100)

    @Test
    fun train() = runBlocking {
        assumeTrue("Set TRAIN=1 to build datasets and train", System.getenv("TRAIN") == "1")
        val cnnDocs = loadCnn()
        val techDocs = loadTech()
        val cnn = prepare(cnnDocs, "CNN/DailyMail (CNN stories)")
        val tech = prepare(techDocs, "Tech feeds (publisher deks)")

        // Deterministic splits: CNN 80/10/10, tech 60/20/20 (tech is small, so bigger eval shares).
        val cnnTrain = cnn.filter { split(it.doc.id) < 80 }; val cnnVal = cnn.filter { split(it.doc.id) in 80..89 }; val cnnTest = cnn.filter { split(it.doc.id) >= 90 }
        val techTrain = tech.filter { split(it.doc.id) < 60 }; val techVal = tech.filter { split(it.doc.id) in 60..79 }; val techTest = tech.filter { split(it.doc.id) >= 80 }
        note("Splits — CNN train/val/test: ${cnnTrain.size}/${cnnVal.size}/${cnnTest.size}; Tech: ${techTrain.size}/${techVal.size}/${techTest.size}")

        // Training rows. Tech examples are up-weighted so the model adapts to this app's domain.
        val rows = mutableListOf<Triple<DoubleArray, Double, Double>>()
        for (e in cnnTrain) e.features.forEachIndexed { i, f -> rows += Triple(f, if (i in e.oracle) 1.0 else 0.0, 1.0) }
        for (e in techTrain) e.features.forEachIndexed { i, f -> rows += Triple(f, if (i in e.oracle) 1.0 else 0.0, 4.0) }
        val pos = rows.count { it.second == 1.0 }
        note("Training sentences: ${rows.size} ($pos positive)")
        val posWeight = (rows.size - pos).toDouble() / pos / 2

        val nF = Features.COUNT
        val mean = DoubleArray(nF) { f -> rows.sumOf { it.first[f] } / rows.size }
        val std = DoubleArray(nF) { f -> sqrt(rows.sumOf { (it.first[f] - mean[f]).let { d -> d * d } } / rows.size).coerceAtLeast(1e-6) }
        fun norm(x: DoubleArray) = DoubleArray(nF) { (x[it] - mean[it]) / std[it] }

        val rnd = Random(42)
        val net = Mlp(nF, 24, rnd)
        var best: SummarizerModel? = null
        var bestScore = -1.0
        val data = rows.map { Triple(norm(it.first), it.second, it.third * if (it.second == 1.0) posWeight else 1.0) }
        for (epoch in 1..24) {
            val shuffled = data.shuffled(rnd)
            shuffled.chunked(128).forEach { b -> net.trainBatch(b.map { it.first }, b.map { it.second }, b.map { it.third }, if (epoch <= 8) 0.002 else 0.0007, 1e-4) }
            val model = net.export(mean, std, "")
            val scorer = ExtractiveSummarizer(model)
            val v = (evaluate(cnnVal) { scorer.scoreAll(it.doc.title, it.sentences) }.avg + 2 * evaluate(techVal) { scorer.scoreAll(it.doc.title, it.sentences) }.avg) / 3
            println("epoch $epoch val=%.2f".format(v))
            if (v > bestScore) { bestScore = v; best = model }
        }
        val trainedOn = "CNN/DailyMail CNN stories: ${cnnTrain.size}; tech feeds: ${techTrain.size}; built ${LocalDate.now()}"
        // Tune the position prior on validation data only (never on test).
        fun valScore(m: SummarizerModel): Double { val sc = ExtractiveSummarizer(m); return (evaluate(cnnVal) { sc.scoreAll(it.doc.title, it.sentences) }.avg + 2 * evaluate(techVal) { sc.scoreAll(it.doc.title, it.sentences) }.avg) / 3 }
        val blend = listOf(0.0, 0.05, 0.1, 0.2, 0.3, 0.5, 0.8).maxBy { b -> valScore(best!!.copy(leadBlend = b)).also { v -> note("lead prior %.2f -> val %.2f".format(b, v)) } }
        val model = best!!.copy(trainedOn = trainedOn, leadBlend = blend)
        val ours = ExtractiveSummarizer(model)

        // Benchmark on held-out test sets.
        val methods = linkedMapOf<String, (Example) -> DoubleArray>(
            "Lead (first sentences)" to { e -> DoubleArray(e.sentences.size) { 1.0 / (it + 1) } },
            "TextRank" to { e -> Features.textRank(e.sentences.map { Features.contentTokens(it) }) },
            "**sudo model (ours)**" to { e -> ours.scoreAll(e.doc.title, e.sentences) },
            "Oracle (upper bound)" to { e -> DoubleArray(e.sentences.size) { if (it in e.oracle) 1.0 else 0.0 } },
        )
        val table = StringBuilder("| Method | Tech R-1 | Tech R-2 | Tech R-L | CNN R-1 | CNN R-2 | CNN R-L | Avg words |\n|---|---|---|---|---|---|---|---|\n")
        for ((name, fn) in methods) {
            val t = evaluate(techTest, fn); val c = evaluate(cnnTest, fn)
            table.append("| $name | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.0f |\n".format(t.r1, t.r2, t.rl, c.r1, c.r2, c.rl, t.words))
        }
        note("\n$table")

        // Feature importance (mean |first-layer weight| per input) for transparency.
        val importance = Features.names.indices.map { f -> Features.names[f] to model.w1.sumOf { kotlin.math.abs(it[f] * model.w2[model.w1.indexOf(it)]) } }
            .sortedByDescending { it.second }

        // Samples for human review (tech test set).
        val samples = StringBuilder()
        techTest.shuffled(Random(7)).take(6).forEach { e ->
            val s = ExtractiveSummarizer.select(e.sentences, ours.scoreAll(e.doc.title, e.sentences), 50, 60)?.summary
            samples.append("**${e.doc.title}** — ${e.doc.source}\n\n- Publisher summary: ${e.doc.reference}\n- Ours (${Text.wordCount(s.orEmpty())} words): $s\n\n")
        }

        val assets = File(root, "app/src/main/assets").apply { mkdirs() }
        File(assets, "summarizer_model.json").writeText(AppJson.encodeToString(model))
        File(root, "ml/REPORT.md").writeText(report(table.toString(), importance, samples.toString()))
        note("Model written to app/src/main/assets/summarizer_model.json")
    }

    private fun report(table: String, importance: List<Pair<String, Double>>, samples: String) = """
# On-device summarizer: training report

Generated ${LocalDate.now()} by `TrainSummarizerTest`.

## What the model does
For each sentence of an article it predicts how likely that sentence belongs in a summary. The app then picks
the top sentences (up to ~50 words, no repeated content) and shows them **verbatim, in original order**.
The model never writes new text, so it cannot introduce facts that the publisher did not write. It is a
20-feature → 24-unit neural network (≈ 530 weights) and runs in milliseconds on the phone, with no network
and no API.

## Training data (provenance)
1. **CNN/DailyMail 3.0.0** (abisee/cnn_dailymail on Hugging Face, train split): a standard research dataset of
   news articles paired with bullet-point highlights written by the publishers' journalists. Only CNN stories
   are used; Daily Mail text contains layout artifacts and was excluded.
2. **Tech articles from the app's own vetted feeds** (The Verge, Ars Technica, Wired, BBC, MIT Technology Review,
   Google/DeepMind/NVIDIA/AWS blogs, …). Article text comes from the publisher's page; the reference summary is
   the publisher's **own** summary line (dek) from its RSS feed. arXiv was excluded because its "summary" is the
   paper itself.

### Quality filters applied to every pair
- Feeds' summary lines that were truncated (“…”, “Read more”) or outside 12–70 words were dropped.
- Pages that were too short, paywalled or blocked (fewer than 3 clean sentences / 60 words) were dropped.
- Pairs whose reference is **not supported by the article text** (unigram recall < 35%) were dropped.
- Publisher lines that just repeat the article's first sentence were dropped (they aren't independent summaries).
- Duplicates by URL/ID and by identical opening text were removed.
- Captions, credits, newsletter/cookie boilerplate and fragments are removed before any sentence is scored,
  both in training and in the app.
- Train / validation / test splits are deterministic (hash of article ID), so test articles are never trained on.

## Data log
```
${log.toString().trim()}
```

## Benchmark (held-out test sets, ROUGE F1 ×100, 50-word budget)
$table
*Lead* = first sentences (a strong baseline for news). *Oracle* = best possible extractive choice given the
reference, i.e. the ceiling for any sentence-picking method.

## What the model pays attention to (feature importance)
${importance.take(10).joinToString("\n") { "- ${it.first}: %.2f".format(it.second) }}

## Sample outputs (tech test set, for human review)
$samples
""".trimIndent()
}

/** Minimal ROUGE implementation (lowercased word n-grams, no stemming). */
object Rouge {
    private fun toks(s: String) = Features.tokens(s)
    private fun ngrams(t: List<String>, n: Int): Map<String, Int> =
        (0..t.size - n).filter { it >= 0 }.map { t.subList(it, it + n).joinToString(" ") }.groupingBy { it }.eachCount()

    fun f(cand: String, ref: String, n: Int): Double {
        val c = ngrams(toks(cand), n); val r = ngrams(toks(ref), n)
        val overlap = c.entries.sumOf { (k, v) -> minOf(v, r[k] ?: 0) }.toDouble()
        val cs = c.values.sum(); val rs = r.values.sum()
        if (cs == 0 || rs == 0 || overlap == 0.0) return 0.0
        val p = overlap / cs; val rc = overlap / rs
        return 2 * p * rc / (p + rc)
    }

    fun recall(cand: String, ref: String, n: Int): Double {
        val c = ngrams(toks(cand), n); val r = ngrams(toks(ref), n)
        val rs = r.values.sum().takeIf { it > 0 } ?: return 0.0
        return r.entries.sumOf { (k, v) -> minOf(v, c[k] ?: 0) }.toDouble() / rs
    }

    fun lcsF(cand: String, ref: String): Double {
        val a = toks(cand); val b = toks(ref)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        val l = dp[a.size][b.size].toDouble()
        if (l == 0.0) return 0.0
        val p = l / a.size; val r = l / b.size
        return 2 * p * r / (p + r)
    }
}
