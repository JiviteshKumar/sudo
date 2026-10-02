package com.technewz.app.ml

import com.technewz.app.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SummarizerTest {
    private val model = File("src/main/assets/summarizer_model.json").takeIf { it.exists() }?.readText()?.let { SummarizerModel.fromJson(it) }
    private val summarizer = ExtractiveSummarizer(model)

    private val article = """
        Google on Tuesday announced Gemini 4 Argon, a new AI model it says outperforms its previous systems on reasoning and coding tests.
        The company said the model will first be available to a small group of testers before a wider release through its API later this year.
        "This is our most capable model yet," Google DeepMind CEO Demis Hassabis said in a blog post.
        Sign up for our newsletter to get the latest AI news.
        Argon was trained on Google's own TPU chips, according to the company, which did not disclose the size of the model.
        It also scored 91% on an internal software engineering benchmark, up from 78% for the previous version.
        Rivals OpenAI and Anthropic have released similar models in recent months.
        Photo: Getty Images
    """.trimIndent()

    @Test fun splitterHandlesAbbreviationsAndDecimals() {
        val s = SentenceSplitter.split("Dr. Smith joined Acme Inc. in the U.S. last year. Revenue rose 3.5% to $1.2 billion. Was it enough? Yes.")
        assertEquals(listOf("Dr. Smith joined Acme Inc. in the U.S. last year.", "Revenue rose 3.5% to $1.2 billion.", "Was it enough?", "Yes."), s)
    }

    @Test fun summaryIsVerbatimAndWithinBudget() {
        val r = summarizer.summarize("Google announces Gemini 4 Argon", article)
        assertNotNull(r)
        val words = Text.wordCount(r!!.summary)
        assertTrue("words=$words", words in 20..60)
        // Every sentence in the summary must appear word-for-word in the source (nothing invented).
        SentenceSplitter.split(r.summary).forEach { assertTrue("not verbatim: $it", article.contains(it)) }
        // Boilerplate must never be chosen.
        assertTrue(!r.summary.contains("newsletter") && !r.summary.contains("Getty"))
        println("model=${model != null} -> ${r.summary}")
    }

    @Test fun thinTextFallsBack() {
        assertNull(summarizer.summarize("t", "Too short to summarise honestly. Really short."))
    }
}
