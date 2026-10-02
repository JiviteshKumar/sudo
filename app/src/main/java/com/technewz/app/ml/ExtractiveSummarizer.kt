package com.technewz.app.ml

import com.technewz.app.data.AppJson
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.tanh

/** Weights of the sentence-scoring network (features -> hidden tanh -> sigmoid), trained offline. */
@Serializable
data class SummarizerModel(
    val version: Int = 1,
    val mean: List<Double>,
    val std: List<Double>,
    val w1: List<List<Double>>, // hidden x features
    val b1: List<Double>,
    val w2: List<Double>, // hidden
    val b2: Double,
    /** Learned prior for article position (news puts key facts first); tuned on validation data. */
    val leadBlend: Double = 0.0,
    val trainedOn: String = "",
) {
    fun score(x: DoubleArray): Double {
        var out = b2
        for (h in b1.indices) {
            var z = b1[h]
            val row = w1[h]
            for (f in x.indices) z += row[f] * ((x[f] - mean[f]) / std[f])
            out += w2[h] * tanh(z)
        }
        return 1.0 / (1.0 + exp(-out))
    }

    companion object {
        fun fromJson(json: String): SummarizerModel = AppJson.decodeFromString(serializer(), json)
    }
}

/**
 * On-device extractive summarizer. It never writes new text: the summary is made only of the
 * publisher's own sentences, chosen by the trained model, so it cannot introduce false facts.
 */
class ExtractiveSummarizer(private val model: SummarizerModel?) {

    data class Result(val summary: String, val sentenceIndexes: List<Int>)

    /** Cleans raw article text into candidate sentences (drops captions, boilerplate, fragments). */
    fun candidates(text: String): List<String> =
        SentenceSplitter.split(text)
            .map { it.trim() }
            .filter { s ->
                val words = s.split(' ').count { it.isNotBlank() }
                words in 5..80 && s.any(Char::isLetter) && !s.endsWith(":") &&
                    !BOILER.containsMatchIn(s) && (s.last() in ".!?\"”’)")
            }
            .distinct()

    /**
     * Returns null when the text is too thin to summarise honestly (caller falls back to the
     * publisher's excerpt).
     */
    fun summarize(title: String, text: String, targetWords: Int = 50, maxWords: Int = 60): Result? {
        val sentences = candidates(text).take(60)
        if (sentences.size < 3 || sentences.sumOf { wc(it) } < 60) return null
        val scores = scoreAll(title, sentences)
        return select(sentences, scores, targetWords, maxWords)
    }

    fun scoreAll(title: String, sentences: List<String>): DoubleArray {
        val feats = Features.compute(title, sentences)
        val m = model
        return if (m != null) DoubleArray(sentences.size) { m.score(feats[it]) + m.leadBlend / (it + 1) }
        else DoubleArray(sentences.size) { 1.0 / (it + 1) } // lead fallback if model missing
    }

    companion object {
        private val BOILER = Regex(
            "subscribe|newsletter|sign up|click here|cookie|advertisement|all rights reserved|follow us on|" +
                "image:|photo:|credit:|getty images|reuters/|ap photo|this story has been updated|read more:|" +
                "images for download|creative commons|credit line|credit the image|©|copyright [0-9]{4}|terms of use|" +
                "is a contributing writer|is a senior (writer|reporter|editor) at|write to .*@|contact the author",
            RegexOption.IGNORE_CASE,
        )

        fun wc(s: String) = s.split(' ').count { it.isNotBlank() }

        private fun trigrams(s: String): Set<String> {
            val t = Features.tokens(s)
            return (0 until (t.size - 2).coerceAtLeast(0)).map { t[it] + " " + t[it + 1] + " " + t[it + 2] }.toSet()
        }

        /** Greedy selection by score with a word budget and trigram blocking (avoids repeating itself). */
        fun select(sentences: List<String>, scores: DoubleArray, targetWords: Int, maxWords: Int): Result? {
            val order = sentences.indices.sortedByDescending { scores[it] }
            val chosen = mutableListOf<Int>()
            val seen = HashSet<String>()
            var words = 0
            for (i in order) {
                if (words >= targetWords - 8 || chosen.size >= 3) break
                val w = wc(sentences[i])
                if (words + w > maxWords) continue
                val tri = trigrams(sentences[i])
                if (tri.any { it in seen }) continue
                chosen += i; seen += tri; words += w
            }
            if (chosen.isEmpty()) return null
            chosen.sort()
            return Result(chosen.joinToString(" ") { sentences[it] }, chosen)
        }
    }
}
