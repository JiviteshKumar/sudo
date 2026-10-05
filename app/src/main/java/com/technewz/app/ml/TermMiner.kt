package com.technewz.app.ml

import kotlin.math.ln

/**
 * Finds candidate emerging terms in a live corpus. Nothing here is a list of terms: acronyms and
 * their meanings are learned from how authors define them in text, and novelty is measured
 * against an older baseline corpus.
 */
object TermMiner {

    @kotlinx.serialization.Serializable
    data class Doc(
        val id: String,
        val title: String,
        val text: String,
        val url: String,
        val source: String,
        val type: String, // paper | news | repo | job
        val date: Long,
        val company: String = "",
    )

    data class Candidate(
        val key: String,          // normalised long form (or phrase)
        val longForm: String,     // most common surface form
        val shortForm: String?,   // acronym, when the text defines one
        val docs: List<Doc>,
        val baselineDf: Int,
        val score: Double,
        /** Every spelling seen for this term (e.g. "RL with verifiable rewards" / "reinforcement learning with verifiable rewards"). */
        val variants: List<String> = listOf(longForm),
    ) {
        val types: Set<String> get() = docs.map { it.type }.toSet()
        val display: String get() = if (shortForm != null) "$longForm ($shortForm)" else longForm
    }

    // ---------------------------------------------------------------- abbreviation definitions
    // Schwartz & Hearst (2003), "A simple algorithm for identifying abbreviation definitions in biomedical text".

    private val parenthetical = Regex("\\(([^()]{2,12})\\)")

    fun isShortForm(sf: String): Boolean {
        if (sf.length !in 2..10 || sf.contains(' ')) return false
        if (!sf[0].isLetter()) return false
        if (!sf.all { it.isLetterOrDigit() || it == '-' }) return false
        return sf.count { it.isUpperCase() } >= 2
    }

    /** Returns (shortForm, longForm) pairs defined in [text] as "long form (SF)". */
    fun abbreviations(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in parenthetical.findAll(text)) {
            val sf = m.groupValues[1].trim()
            if (!isShortForm(sf)) continue
            val before = text.substring(0, m.range.first).trimEnd()
            val sentenceStart = maxOf(before.lastIndexOf(". "), before.lastIndexOf("; "), before.lastIndexOf(": "), before.lastIndexOf('('))
            val window = before.substring(sentenceStart + 1).trim()
            val words = window.split(' ').filter { it.isNotBlank() }
            val maxWords = minOf(sf.length + 5, sf.length * 2)
            val candidate = words.takeLast(maxWords).joinToString(" ")
            val lf = bestLongForm(sf, candidate) ?: continue
            val cleaned = lf.trim().trim(',', ';', ':', '"', '“', '”', '\'').trim()
            val lfWords = cleaned.split(' ').filter { it.isNotBlank() }
            if (lfWords.size > maxWords || cleaned.length <= sf.length) continue
            if (cleaned.contains(sf)) continue
            // Must read like a term: at least two letters-words or one hyphenated compound.
            if (lfWords.size < 2 && !cleaned.contains('-')) continue
            if (lfWords.first().lowercase() in Features.stopwordSet) continue
            out += sf to cleaned
        }
        return out
    }

    private fun bestLongForm(sf: String, lf: String): String? {
        var s = sf.length - 1
        var l = lf.length - 1
        while (s >= 0) {
            val c = sf[s].lowercaseChar()
            if (!c.isLetterOrDigit()) { s--; continue }
            while (l >= 0 && (lf[l].lowercaseChar() != c || (s == 0 && l > 0 && lf[l - 1].isLetterOrDigit()))) l--
            if (l < 0) return null
            l--; s--
        }
        val start = lf.lastIndexOf(' ', l) + 1
        return lf.substring(start)
    }

    fun normalize(term: String): String =
        term.lowercase().replace(Regex("[-‐–_/]"), " ").replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
            .split(' ').joinToString(" ") { w -> if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }

    // ---------------------------------------------------------------- candidates

    /**
     * Ranks terms that are frequent in [recent] but rare in [baseline].
     * Acronym definitions come from any document; title phrases (2–3 words) come from document titles.
     */
    /** Inverted index so phrase lookups only scan documents that contain every word of the phrase. */
    private class Index(texts: List<String>) {
        val padded = texts.map { " $it " }
        private val postings = HashMap<String, MutableList<Int>>()

        init {
            texts.forEachIndexed { i, t -> t.split(' ').toSet().forEach { w -> if (w.isNotEmpty()) postings.getOrPut(w) { mutableListOf() } += i } }
        }

        fun containing(phrase: String): List<Int> {
            val words = phrase.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val lists = words.map { postings[it] ?: return emptyList() }.sortedBy { it.size }
            var acc: Collection<Int> = lists.first()
            for (l in lists.drop(1)) {
                val s = l.toHashSet()
                acc = acc.filter { it in s }
            }
            return acc.filter { padded[it].contains(" $phrase ") }
        }
    }

    fun candidates(recent: List<Doc>, baseline: List<Doc>, minDocs: Int = 3, minLift: Double = 3.0): List<Candidate> {
        val recentFieldN = recent.count { it.type != "job" }
        val baseIdx = Index(baseline.map { normalize(it.title + " " + it.text) })
        val baseCache = HashMap<String, Int>()
        fun baselineDf(key: String): Int = baseCache.getOrPut(key) { baseIdx.containing(key).size }
        val recentIdx = Index(recent.map { normalize(it.title + " " + it.text) })
        fun rawIndex(docs: List<Doc>) = HashMap<String, MutableSet<Int>>().also { m ->
            docs.forEachIndexed { i, d -> Regex("[A-Za-z0-9-]+").findAll(d.title + " " + d.text).forEach { m.getOrPut(it.value) { mutableSetOf() } += i } }
        }
        val rawTokens = rawIndex(recent)
        val baseRaw = rawIndex(baseline)

        // 1. acronym-defined terms
        val byKey = HashMap<String, MutableList<Pair<Doc, Pair<String, String>>>>()
        for (d in recent) {
            for ((sf, lf) in abbreviations(d.title + ". " + d.text).distinctBy { normalize(it.second) }) {
                byKey.getOrPut(normalize(lf)) { mutableListOf() } += d to (sf to lf)
            }
        }
        // Count every doc that mentions the long form or the acronym, not only those that define it.
        val out = mutableListOf<Candidate>()
        for ((key, defs) in byKey) {
            val sf = defs.groupingBy { it.second.first }.eachCount().maxBy { it.value }.key
            val lf = defs.groupingBy { it.second.second }.eachCount().maxBy { it.value }.key
            val ids = recentIdx.containing(key).toHashSet().apply { addAll(rawTokens[sf].orEmpty()) }
            val docs = ids.map { recent[it] }
            if (docs.map { it.id }.distinct().size < minDocs) continue
            // Field terms must appear in research, news or repos, not only in job ads (HR boilerplate like EEO).
            if (docs.count { it.type != "job" } < 2) continue
            // Count the baseline exactly like the recent corpus: long form OR acronym.
            val base = baseIdx.containing(key).toHashSet().apply { addAll(baseRaw[sf].orEmpty()) }.size
            val sc = score(docs, base, recentFieldN, baseline.size, minLift) ?: continue
            out += Candidate(key, lf, sf, docs.distinctBy { it.id }, base, sc)
        }

        // Same acronym with near-identical spellings ("RL with verifiable rewards" vs "Reinforcement Learning
        // with Verifiable Rewards") is one term: keep the best-supported spelling and merge the evidence.
        val merged = out.groupBy { it.shortForm?.removeSuffix("s")?.lowercase() }.flatMap { (_, group) ->
            val remaining = group.sortedByDescending { it.docs.size }.toMutableList()
            val result = mutableListOf<Candidate>()
            while (remaining.isNotEmpty()) {
                val head = remaining.removeAt(0)
                val headTokens = head.key.split(' ').toSet()
                val same = remaining.filter { o ->
                    val t = o.key.split(' ').toSet()
                    t.intersect(headTokens).size.toDouble() / minOf(t.size, headTokens.size) >= 0.5
                }
                remaining.removeAll(same)
                result += head.copy(
                    docs = (head.docs + same.flatMap { it.docs }).distinctBy { it.id },
                    score = head.score + same.sumOf { it.score } * 0.5,
                    variants = (head.variants + same.flatMap { it.variants }).distinctBy { normalize(it) },
                )
            }
            result
        }
        out.clear(); out.addAll(merged)

        // 2. multi-word title phrases (terms that are not abbreviated)
        val phraseDocs = HashMap<String, MutableSet<Doc>>()
        val surface = HashMap<String, MutableMap<String, Int>>()
        for (d in recent) {
            val toks = d.title.split(Regex("[\\s:;,.!?()\\[\\]\"“”]+")).filter { it.isNotBlank() }
            for (n in 2..3) for (i in 0..toks.size - n) {
                val gram = toks.subList(i, i + n)
                val lower = gram.map { it.lowercase() }
                if (lower.first() in Features.stopwordSet || lower.last() in Features.stopwordSet) continue
                if (lower.any { w -> w.length < 3 || w.all(Char::isDigit) }) continue
                val key = normalize(gram.joinToString(" "))
                if (key.split(' ').size < 2) continue
                phraseDocs.getOrPut(key) { mutableSetOf() } += d
                surface.getOrPut(key) { mutableMapOf() }.merge(gram.joinToString(" "), 1, Int::plus)
            }
        }
        val acronymKeys = out.map { it.key }.toSet()
        for ((key, docs) in phraseDocs) {
            if (docs.size < minDocs + 1 || key in acronymKeys) continue
            if (docs.count { it.type != "job" } < minDocs) continue
            if (acronymKeys.any { it.contains(key) || key.contains(it) }) continue
            val base = baselineDf(key)
            // Prefer the lower-case surface form unless the phrase is consistently capitalised.
            val form = surface[key]!!.maxBy { it.value }.key
            val sc = score(docs.toList(), base, recentFieldN, baseline.size, minLift) ?: continue
            out += Candidate(key, form, null, docs.toList(), base, sc * 0.8)
        }
        return out.sortedByDescending { it.score }
    }

    /**
     * How much more common the term is now than in the baseline (rates normalised by corpus size),
     * weighted by how many distinct documents and kinds of source mention it.
     */
    private fun score(docs: List<Doc>, baselineDf: Int, recentFieldN: Int, baselineN: Int, minLift: Double): Double? {
        // Only research/news/repo mentions count towards novelty; job ads are a different kind of text.
        val n = docs.filter { it.type != "job" }.map { it.id }.distinct().size
        val types = docs.map { it.type }.distinct().size
        val lift = (n.toDouble() / recentFieldN.coerceAtLeast(1)) / ((baselineDf + 0.5) / baselineN.coerceAtLeast(1))
        if (lift < minLift) return null // not meaningfully more common than 6–18 months ago
        return ln(1.0 + n) * ln(1.0 + lift) * (1 + 0.25 * (types - 1))
    }

    // ---------------------------------------------------------------- when did a term emerge?

    /**
     * The year a term took off: the first recent year whose publication count is at least 5x its own
     * historical level (median of the years 5–10 years ago) and at least [minCount]. Returns null when the
     * term never surged, i.e. it has been in steady use for years (established) or has too little evidence.
     * Measuring against the term's own history handles phrases that had an older, unrelated meaning.
     *
     * A term that already had a real presence 5–10 years ago (more than [maxPrior] works a year, or more than
     * 2% of today's volume) is an established idea that is merely booming (e.g. differential privacy, world
     * models), not a new one, so it returns null too.
     */
    fun emergenceYear(perYear: Map<Int, Int>, currentYear: Int, minCount: Int = 10, maxPrior: Int = 30): Int? {
        val history = (currentYear - 10..currentYear - 5).map { perYear[it] ?: 0 }.sorted()
        val baseline = (history[2] + history[3]) / 2.0
        val current = maxOf(perYear[currentYear] ?: 0, perYear[currentYear - 1] ?: 0)
        if (baseline > maxPrior || baseline > 0.02 * current) return null
        val threshold = maxOf(minCount.toDouble(), 5 * baseline)
        return (currentYear - 10..currentYear).firstOrNull { (perYear[it] ?: 0) >= threshold }
    }

    // ---------------------------------------------------------------- explanation sentences

    private val definitionVerbs = "(is|are|refers to|denotes|describes|means|has emerged as|have emerged as|has become|is defined as|aims to|allows|enables|lets)"
    private val selfReference = Regex("\\b(we|our|this paper|this work|in this paper)\\b", RegexOption.IGNORE_CASE)

    private val definitional = listOf(
        Regex("\\b(is|are|refers to|denotes|describes|means)\\s+(a|an|the)\\b", RegexOption.IGNORE_CASE) to 3.0,
        Regex("\\b(a|an)\\s+(new |novel |emerging |simple |general )?(paradigm|framework|method|approach|technique|architecture|protocol|strategy|algorithm|standard|family|class|form)\\b", RegexOption.IGNORE_CASE) to 2.0,
        Regex("\\b(which|that)\\s+(\\w+\\s+){0,3}(uses|leverages|combines|replaces|allows|lets|enables|stores|retrieves|trains|learns)\\b", RegexOption.IGNORE_CASE) to 1.5,
        Regex("\\bwe (propose|introduce|present)\\b", RegexOption.IGNORE_CASE) to 0.8,
    )
    private val application = Regex(
        "\\b(used (in|for|to|by)|applied (to|in)|applications?|deployed|enables?|use cases?|tasks such as|settings such as|domains such as|in practice|real-world|industry|production)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val results = Regex("\\b(outperforms?|achieves?|state-of-the-art|improves? .* by|\\d+(\\.\\d+)?%)", RegexOption.IGNORE_CASE)

    data class Pick(val sentence: String, val doc: Doc)

    private fun mentions(sentence: String, longForm: String, shortForm: String?): Boolean {
        val n = " ${normalize(sentence)} "
        if (n.contains(" ${normalize(longForm)} ")) return true
        return shortForm != null && Regex("(?<![A-Za-z0-9-])" + Regex.escape(shortForm) + "(?![A-Za-z0-9-])").containsMatchIn(sentence)
    }

    /**
     * Best verbatim sentence that says what the term is. Strongly prefers sentences where the term is the
     * subject ("X is ...", "X, a ..., ..."), not a paper describing its own system.
     */
    fun definition(longForm: String, shortForm: String?, docs: List<Doc>): Pick? {
        val names = listOfNotNull(Regex.escape(longForm), shortForm?.let { Regex.escape(it) }).joinToString("|")
        val subject = Regex("^(the |a |an )?($names)( \\(($names)\\))?,? $definitionVerbs\\b", RegexOption.IGNORE_CASE)
        val appositive = Regex("^(the |a |an )?($names)( \\(($names)\\))?, (a|an|the) ", RegexOption.IGNORE_CASE)
        return bestSentence(longForm, shortForm, docs) { s ->
            var sc = 0.0
            if (subject.containsMatchIn(s)) sc += 5.0
            if (appositive.containsMatchIn(s)) sc += 3.0
            sc += definitional.sumOf { (rx, w) -> if (rx.containsMatchIn(s)) w * 0.5 else 0.0 }
            if (sc == 0.0) return@bestSentence null
            if (selfReference.containsMatchIn(s)) sc -= 2.0
            if (results.containsMatchIn(s)) sc -= 2.0
            sc
        }?.takeIf { p -> subject.containsMatchIn(p.sentence) || appositive.containsMatchIn(p.sentence) || !selfReference.containsMatchIn(p.sentence) }
    }

    /** Best verbatim sentence that says where/how the term is used. */
    fun usage(longForm: String, shortForm: String?, docs: List<Doc>, exclude: String?): Pick? =
        bestSentence(longForm, shortForm, docs) { s ->
            if (s == exclude || !application.containsMatchIn(s)) return@bestSentence null
            if (selfReference.containsMatchIn(s)) return@bestSentence null
            // Usage should describe where it's applied, not report a benchmark number.
            if (Regex("\\d").findAll(s).count() > 2 || results.containsMatchIn(s)) return@bestSentence null
            var sc = 2.0
            if (selfReference.containsMatchIn(s)) sc -= 1.0
            sc
        }

    private fun bestSentence(longForm: String, shortForm: String?, docs: List<Doc>, scorer: (String) -> Double?): Pick? {
        var best: Pick? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (d in docs) {
            for (s in SentenceSplitter.split(d.text)) {
                val words = s.split(' ').count { it.isNotBlank() }
                if (words !in 8..45 || !mentions(s, longForm, shortForm)) continue
                if (Regex("^(however|but|moreover|furthermore|in contrast|unlike)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) continue
                val base = scorer(s) ?: continue
                // Shorter, simpler sentences explain better; papers that merely cite the term are fine sources.
                val sc = base - words / 30.0
                if (sc > bestScore) { bestScore = sc; best = Pick(s.trim(), d) }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- grounding check for AI-simplified text

    /**
     * True when every sentence of [generated] is supported by [sources]: most content words must
     * appear in the sources, and every number and capitalised name must appear verbatim.
     */
    fun grounded(generated: String, sources: List<String>, minSupport: Double = 0.7): Boolean {
        if (generated.isBlank()) return false
        val srcText = sources.joinToString(" ")
        val srcTokens = Features.contentTokens(srcText).map { stem(it) }.toHashSet()
        for (sentence in SentenceSplitter.split(generated)) {
            val toks = Features.contentTokens(sentence).map { stem(it) }
            if (toks.isEmpty()) continue
            val support = toks.count { it in srcTokens }.toDouble() / toks.size
            if (support < minSupport) return false
            val numbers = Regex("\\d+(\\.\\d+)?").findAll(sentence).map { it.value }
            if (numbers.any { !srcText.contains(it) }) return false
            val names = sentence.split(' ').drop(1).map { it.trim(',', '.', ';', ':', '(', ')', '"') }
                .filter { it.length > 1 && it[0].isUpperCase() }
            if (names.any { !srcText.contains(it) }) return false
        }
        return true
    }

    private fun stem(w: String) = w.removeSuffix("ing").removeSuffix("es").removeSuffix("s").removeSuffix("ed")
}
