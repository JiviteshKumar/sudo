package com.technewz.app.ml

/** Rule-based English sentence splitter tuned for news text (abbreviations, initials, decimals, quotes). */
object SentenceSplitter {
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "inc", "ltd", "co", "corp", "llc", "plc",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "no", "fig", "approx", "dept", "gov", "gen", "rep", "sen", "lt", "col", "sgt", "capt", "mt", "ft", "al", "est",
        "e.g", "i.e", "u.s", "u.k", "u.n", "e.u", "a.m", "p.m", "ph.d", "d.c",
    )

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        // Paragraph breaks are always sentence boundaries.
        for (para in text.split(Regex("\\n+"))) {
            val p = para.replace(Regex("\\s+"), " ").trim()
            if (p.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < p.length) {
                val c = p[i]
                if (c == '.' || c == '!' || c == '?') {
                    var end = i + 1
                    while (end < p.length && p[end] in "\"'”’)]") end++
                    val nextIsSpace = end < p.length && p[end] == ' '
                    val nextStart = if (nextIsSpace && end + 1 < p.length) p[end + 1] else null
                    val startsNew = nextStart != null && (nextStart.isUpperCase() || nextStart.isDigit() || nextStart in "\"“‘'(")
                    if ((end >= p.length || (nextIsSpace && startsNew)) && !(c == '.' && isAbbreviation(p, start, i))) {
                        out += p.substring(start, end).trim()
                        start = end
                    }
                    i = end
                    continue
                }
                i++
            }
            if (start < p.length) p.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    private fun isAbbreviation(p: String, sentenceStart: Int, dot: Int): Boolean {
        var s = dot - 1
        while (s >= sentenceStart && !p[s].isWhitespace() && p[s] != '(' && p[s] != '"') s--
        val token = p.substring(s + 1, dot).lowercase()
        if (token.isEmpty()) return false
        if (token in abbreviations) return true
        if (token.length == 1 && token[0].isLetter()) return true // initials: "J. Smith"
        if (token.contains('.') && token.length <= 6) return true // "U.S" style
        return false
    }
}
