package com.technewz.app.ml

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Sentence features shared by training (JVM tests) and on-device inference, so the model sees
 * exactly the same inputs in both places.
 */
object Features {
    const val COUNT = 20

    val names = listOf(
        "relPosition", "isFirst", "isSecond", "invPosition", "length", "tooShort", "tooLong", "titleOverlap",
        "centroidSim", "textRank", "numberRatio", "capitalRatio", "hasQuote", "danglingStart", "isQuestion",
        "stopwordRatio", "attribution", "boilerplate", "docLength", "leadSimilarity",
    )

    private val stopwords = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "be",
        "been", "being", "it", "its", "this", "that", "these", "those", "at", "by", "from", "as", "has", "have", "had",
        "will", "would", "can", "could", "should", "may", "might", "not", "no", "so", "if", "than", "then", "there",
        "their", "they", "them", "he", "she", "his", "her", "we", "our", "you", "your", "i", "me", "my", "who", "which",
        "what", "when", "where", "how", "also", "about", "into", "over", "after", "more", "most", "some", "such", "said",
        "says", "just", "all", "any", "do", "does", "did", "up", "out", "new", "one", "two",
    )
    val stopwordSet: Set<String> get() = stopwords

    private val dangling = Regex(
        "^(he|she|it|they|this|that|these|those|his|her|their|its|but|however|meanwhile|also|still|and|so|yet|instead|" +
            "then|there|here|which|such|both|another|other|others)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val attribution = Regex("\\b(said|says|told|according to|announced|stated|reported)\\b", RegexOption.IGNORE_CASE)
    private val boilerplate = Regex(
        "subscribe|newsletter|sign up|click here|cookie|advertisement|all rights reserved|follow us|read more|" +
            "related:|image credit|photo by|getty images|this article|our journalism|support us|terms of service|" +
            "log in|privacy policy|share this",
        RegexOption.IGNORE_CASE,
    )

    fun tokens(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9$%. ]"), " ").split(' ')
            .map { it.trim('.') }.filter { it.isNotEmpty() }

    fun contentTokens(s: String): List<String> = tokens(s).filter { it !in stopwords && it.length > 1 }

    /** One feature vector per sentence. [sentences] must be the cleaned sentence list for the document. */
    fun compute(title: String, sentences: List<String>): Array<DoubleArray> {
        val n = sentences.size
        val toks = sentences.map { tokens(it) }
        val content = sentences.map { contentTokens(it) }
        val titleSet = contentTokens(title).toSet()
        val docTf = HashMap<String, Int>()
        content.forEach { c -> c.forEach { docTf[it] = (docTf[it] ?: 0) + 1 } }
        val docNorm = sqrt(docTf.values.sumOf { it.toDouble() * it })
        val rank = textRank(content)
        val leadSet = content.firstOrNull()?.toSet() ?: emptySet()

        return Array(n) { i ->
            val words = sentences[i].split(' ').filter { it.isNotBlank() }
            val len = words.size.coerceAtLeast(1)
            val c = content[i]
            val cSet = c.toSet()
            val tf = c.groupingBy { it }.eachCount()
            val dot = tf.entries.sumOf { (w, k) -> k.toDouble() * (docTf[w] ?: 0) }
            val norm = sqrt(tf.values.sumOf { it.toDouble() * it })
            val centroid = if (norm == 0.0 || docNorm == 0.0) 0.0 else dot / (norm * docNorm)
            val nums = toks[i].count { t -> t.any(Char::isDigit) }
            val caps = words.drop(1).count { w -> w.firstOrNull()?.isUpperCase() == true }
            val stops = toks[i].count { it in stopwords }
            doubleArrayOf(
                if (n > 1) i.toDouble() / (n - 1) else 0.0,
                if (i == 0) 1.0 else 0.0,
                if (i == 1) 1.0 else 0.0,
                1.0 / (i + 1),
                len / 30.0,
                if (len < 8) 1.0 else 0.0,
                if (len > 45) 1.0 else 0.0,
                if (titleSet.isEmpty()) 0.0 else cSet.count { it in titleSet }.toDouble() / titleSet.size,
                centroid,
                rank[i] * n,
                nums.toDouble() / len,
                caps.toDouble() / len,
                if (sentences[i].contains('"') || sentences[i].contains('“')) 1.0 else 0.0,
                if (dangling.containsMatchIn(sentences[i])) 1.0 else 0.0,
                if (sentences[i].trimEnd().endsWith("?")) 1.0 else 0.0,
                stops.toDouble() / toks[i].size.coerceAtLeast(1),
                if (attribution.containsMatchIn(sentences[i])) 1.0 else 0.0,
                if (boilerplate.containsMatchIn(sentences[i])) 1.0 else 0.0,
                ln(n.toDouble() + 1) / 4.0,
                if (i == 0 || leadSet.isEmpty()) 1.0 else cSet.count { it in leadSet }.toDouble() / (cSet.size + leadSet.size).coerceAtLeast(1) * 2,
            )
        }
    }

    /** Classic TextRank over word-overlap similarity. Returns scores summing to 1. */
    fun textRank(content: List<List<String>>, iterations: Int = 30, d: Double = 0.85): DoubleArray {
        val n = content.size
        if (n == 0) return DoubleArray(0)
        val sets = content.map { it.toSet() }
        val w = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = sets[i]
            val b = sets[j]
            if (a.size < 2 || b.size < 2) continue
            val overlap = a.count { it in b }
            if (overlap == 0) continue
            val sim = overlap / (ln(a.size.toDouble()) + ln(b.size.toDouble()))
            w[i][j] = sim; w[j][i] = sim
        }
        val out = DoubleArray(n) { w[it].sum() }
        var score = DoubleArray(n) { 1.0 / n }
        repeat(iterations) {
            val next = DoubleArray(n) { (1 - d) / n }
            for (j in 0 until n) {
                if (out[j] == 0.0) continue
                for (i in 0 until n) if (w[j][i] > 0) next[i] += d * score[j] * w[j][i] / out[j]
            }
            score = next
        }
        val total = score.sum().takeIf { it > 0 } ?: 1.0
        return DoubleArray(n) { score[it] / total }
    }
}
