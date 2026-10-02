package com.technewz.app.data

import com.technewz.app.net.Gemini
import com.technewz.app.net.str
import com.technewz.app.net.strList
import com.technewz.app.util.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * All AI prompts live here. Every prompt instructs the model to use ONLY the supplied text,
 * and every response is validated before it reaches the UI.
 */
class AiFeatures(private val settings: SettingsRepository) {

    private suspend fun client(): Gemini {
        val s = settings.current()
        return Gemini(s.geminiKey, s.geminiModel)
    }

    data class SummaryResult(val summary: String, val why: String?, val topic: String?)

    suspend fun summarize(
        articles: List<ArticleEntity>,
        skills: List<String>,
        topics: Map<String, List<String>>,
    ): Map<String, SummaryResult> {
        if (articles.isEmpty()) return emptyMap()
        val payload = JsonArray(articles.map { a ->
            JsonObject(
                mapOf(
                    "id" to kotlinx.serialization.json.JsonPrimitive(a.id),
                    "section" to kotlinx.serialization.json.JsonPrimitive(a.section),
                    "source" to kotlinx.serialization.json.JsonPrimitive(a.source),
                    "title" to kotlinx.serialization.json.JsonPrimitive(a.title),
                    "text" to kotlinx.serialization.json.JsonPrimitive(a.excerpt.take(2200)),
                )
            )
        })
        val skillLine = if (skills.isEmpty()) "" else """
            - "why": ONE sentence (max 20 words) on why this could matter to a reader skilled in: ${skills.take(25).joinToString(", ")}.
              Only if there is a genuine, concrete connection; otherwise "".""".trimIndent()
        val prompt = """
            You are a meticulous technology news editor. For each article below, using ONLY the supplied title and text:
            - "summary": a neutral, factual summary of 45 to 50 words. Do NOT add any fact, number, name, date, or claim that
              is not explicitly in the supplied text. No hype, no opinions, no speculation. If the text is thin, summarize only
              what is there (fewer words is fine). Never start with "This article".
            $skillLine
            - "topic": pick exactly one. For section "tech": ${topics[Section.TECH]!!.joinToString(", ")}.
              For section "ai": ${topics[Section.AI]!!.joinToString(", ")}.
            Return a JSON array of objects with keys "id", "summary", "why", "topic". Keep the ids exactly as given.

            ARTICLES:
            $payload
        """.trimIndent()

        val result = client().generateJson(prompt, temperature = 0.1)
        val arr = (result as? JsonArray) ?: result.jsonObject.values.firstOrNull { it is JsonArray }?.jsonArray ?: return emptyMap()
        val validIds = articles.associateBy { it.id }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")
            val article = validIds[id] ?: return@mapNotNull null
            val summary = o.str("summary").trim()
            if (Text.wordCount(summary) < 12) return@mapNotNull null
            val allowed = topics[article.section].orEmpty()
            id to SummaryResult(
                summary = Text.trimWords(summary, 55),
                why = o.str("why").trim().takeIf { it.length > 8 && skills.isNotEmpty() }?.let { Text.trimWords(it, 26) },
                topic = o.str("topic").takeIf { it in allowed && it != "All" },
            )
        }.toMap()
    }

    suspend fun parseResume(pdf: ByteArray, fileName: String): Profile {
        val prompt = """
            Extract the candidate's details from this resume. Use ONLY what is written in the document; leave a field as an
            empty string or empty list if it is not present. Do not guess or invent anything.
            Return JSON with keys:
            fullName, email, phone, location, headline (their current role or degree, short), linkedin (full URL), github (full URL),
            portfolio (personal website URL), summary (2 sentences max, written from the resume content),
            skills (array of concise technical skills), education (array like "B.Tech CSE, XYZ University, 2022–2026, CGPA 8.9"),
            experience (array like "ML Intern, Company — what they did (dates)"), projects (array, one line each).
        """.trimIndent()
        val o = client().generateJson(prompt, pdf = pdf, temperature = 0.0).jsonObject
        return Profile(
            fullName = o.str("fullName"), email = o.str("email"), phone = o.str("phone"), location = o.str("location"),
            headline = o.str("headline"), linkedin = o.str("linkedin"), github = o.str("github"),
            portfolio = o.str("portfolio"), summary = o.str("summary"),
            skills = o.strList("skills").distinct().take(60), education = o.strList("education"),
            experience = o.strList("experience"), projects = o.strList("projects"), resumeFileName = fileName,
        )
    }

    @Serializable
    data class Analysis(
        val score: Int = 0,
        val verdict: String = "",
        val strengths: List<String> = emptyList(),
        val gaps: List<String> = emptyList(),
        val tips: List<String> = emptyList(),
        val eligibility: String = "",
    )

    suspend fun analyzeMatch(profile: Profile, job: JobEntity): Analysis {
        val prompt = """
            You are an honest career coach. Compare the candidate to the job using ONLY the information given. Be candid and
            specific; do not flatter. Do not invent requirements that are not in the job text.
            Return JSON: {"score": 0-100 integer fit score, "verdict": one sentence,
            "strengths": up to 4 short bullets (what in the resume matches), "gaps": up to 5 short skill/experience gaps
            (just the skill name or a few words each), "tips": up to 3 concrete actions to improve the application,
            "eligibility": one short sentence on stated location/visa/degree/graduation-year constraints in the posting, or "" if none}.

            CANDIDATE:
            ${profileText(profile)}

            JOB: ${job.title} at ${job.company} (${job.location}, ${job.employmentType})
            ${job.description.take(6000)}
        """.trimIndent()
        val o = client().generateJson(prompt, temperature = 0.1).jsonObject
        return Analysis(
            score = (o["score"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 100),
            verdict = o.str("verdict"), strengths = o.strList("strengths").take(4), gaps = o.strList("gaps").take(5),
            tips = o.strList("tips").take(3), eligibility = o.str("eligibility"),
        )
    }

    @Serializable
    data class ApplyKit(val coverLetter: String = "", val answers: List<QA> = emptyList())

    @Serializable
    data class QA(val q: String, val a: String)

    suspend fun applyKit(profile: Profile, job: JobEntity): ApplyKit {
        val prompt = """
            Write application material for this candidate and job. STRICT RULES: use ONLY facts from the candidate profile —
            never invent experience, metrics, employers, degrees or skills. If the candidate lacks something the job wants,
            don't claim it; emphasise genuine transferable strengths and eagerness to learn instead. Plain, confident,
            human tone; no clichés like "I am writing to express"; no placeholders like [Company].
            Return JSON: {"coverLetter": a cover letter of 170-230 words addressed to the ${job.company} hiring team, signed with
            the candidate's name, "answers": [{"q": "Why are you interested in this role?", "a": 60-90 words},
            {"q": "Describe a relevant project or experience.", "a": 70-110 words},
            {"q": "What makes you a strong fit?", "a": 50-80 words}]}.

            CANDIDATE:
            ${profileText(profile)}

            JOB: ${job.title} at ${job.company} (${job.location})
            ${job.description.take(6000)}
        """.trimIndent()
        val o = client().generateJson(prompt, temperature = 0.5).jsonObject
        val answers = (o["answers"] as? JsonArray)?.mapNotNull { el ->
            val qa = el as? JsonObject ?: return@mapNotNull null
            QA(qa.str("q"), qa.str("a")).takeIf { it.a.isNotBlank() }
        }.orEmpty()
        return ApplyKit(o.str("coverLetter"), answers)
    }

    /**
     * Rewrites a term's verbatim definition/usage in plain English. The caller must run
     * TermMiner.grounded() on the result and discard it if it adds anything not in [sources].
     */
    suspend fun simplifyTerm(term: String, what: String, usage: String?, sources: List<String>): Pair<String, String?> {
        val prompt = """
            Explain the term "$term" to a student in plain, simple English, using ONLY facts stated in the SOURCES.
            Do not add any fact, example, name, number or claim that is not in the sources. If the sources do not say
            where it is used, return "" for "usage".
            Return JSON: {"what": one sentence, max 28 words, "usage": one sentence, max 28 words, or ""}.

            SOURCES:
            ${(listOf(what) + listOfNotNull(usage) + sources).joinToString(" --- ") { it.take(1500) }}
        """.trimIndent()
        val o = client().generateJson(prompt, temperature = 0.0).jsonObject
        return o.str("what") to o.str("usage").ifBlank { null }
    }

    suspend fun testKey(): String = client().ping()

    private fun profileText(p: Profile) = buildString {
        appendLine("Name: ${p.fullName}")
        if (p.headline.isNotBlank()) appendLine("Headline: ${p.headline}")
        if (p.location.isNotBlank()) appendLine("Location: ${p.location}")
        if (p.summary.isNotBlank()) appendLine("Summary: ${p.summary}")
        if (p.skills.isNotEmpty()) appendLine("Skills: ${p.skills.joinToString(", ")}")
        if (p.education.isNotEmpty()) appendLine("Education: ${p.education.joinToString(" | ")}")
        if (p.experience.isNotEmpty()) appendLine("Experience: ${p.experience.joinToString(" | ")}")
        if (p.projects.isNotEmpty()) appendLine("Projects: ${p.projects.joinToString(" | ")}")
        if (p.portfolio.isNotBlank()) appendLine("Portfolio: ${p.portfolio}")
        if (p.github.isNotBlank()) appendLine("GitHub: ${p.github}")
    }
}
