package com.technewz.app.net

import com.technewz.app.data.Section

/**
 * Curated, reputable sources only. Every story in the app links back to one of these.
 * [maxItems] keeps high-volume feeds (e.g. arXiv) from drowning out everything else.
 */
data class Feed(
    val name: String,
    val url: String,
    val section: String,
    val defaultTopic: String,
    val maxItems: Int = 20,
)

object Feeds {
    val all = listOf(
        // ---- Tech, worldwide ----
        Feed("The Verge", "https://www.theverge.com/rss/index.xml", Section.TECH, "Tech"),
        Feed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", Section.TECH, "Tech"),
        Feed("TechCrunch", "https://techcrunch.com/feed/", Section.TECH, "Startups"),
        Feed("Wired", "https://www.wired.com/feed/rss", Section.TECH, "Tech"),
        Feed("Engadget", "https://www.engadget.com/rss.xml", Section.TECH, "Gadgets"),
        Feed("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml", Section.TECH, "Tech"),
        Feed("The Register", "https://www.theregister.com/headlines.atom", Section.TECH, "Software"),
        Feed("MIT Technology Review", "https://www.technologyreview.com/feed/", Section.TECH, "Tech"),
        Feed("Rest of World", "https://restofworld.org/feed/latest", Section.TECH, "Global"),
        Feed("The Hacker News", "https://feeds.feedburner.com/TheHackersNews", Section.TECH, "Security", 10),

        // ---- AI / ML / Data Science ----
        Feed("Google AI Blog", "https://blog.google/technology/ai/rss/", Section.AI, "Industry", 10),
        Feed("Google Research", "https://research.google/blog/rss/", Section.AI, "Research", 10),
        Feed("Google DeepMind", "https://deepmind.google/blog/rss.xml", Section.AI, "Research", 10),
        Feed("OpenAI", "https://openai.com/news/rss.xml", Section.AI, "Industry", 10),
        Feed("Hugging Face Blog", "https://huggingface.co/blog/feed.xml", Section.AI, "Tools", 10),
        Feed("NVIDIA Technical Blog", "https://developer.nvidia.com/blog/feed", Section.AI, "Tools", 8),
        Feed("AWS Machine Learning", "https://aws.amazon.com/blogs/machine-learning/feed/", Section.AI, "Tools", 8),
        Feed("MIT News — AI", "https://news.mit.edu/rss/topic/artificial-intelligence2", Section.AI, "Research", 10),
        Feed("Ars Technica AI", "https://arstechnica.com/ai/feed/", Section.AI, "Industry", 15),
        Feed("TechCrunch AI", "https://techcrunch.com/category/artificial-intelligence/feed/", Section.AI, "Industry", 15),
        Feed("The Decoder", "https://the-decoder.com/feed/", Section.AI, "Industry", 15),
        Feed("KDnuggets", "https://www.kdnuggets.com/feed", Section.AI, "Data Science", 10),
        Feed("Towards Data Science", "https://towardsdatascience.com/feed", Section.AI, "Data Science", 10),
        Feed("arXiv cs.LG", "https://rss.arxiv.org/rss/cs.LG", Section.AI, "Research", 6),
        Feed("arXiv cs.CL", "https://rss.arxiv.org/rss/cs.CL", Section.AI, "Research", 6),
    )

    val techTopics = listOf("All", "AI", "Security", "Startups", "Gadgets", "Software", "Policy", "Science", "Global")
    val aiTopics = listOf("All", "Research", "Industry", "Tools", "Data Science", "Policy")

    private val topicRules = listOf(
        "Security" to Regex("\\b(hack|breach|ransomware|vulnerab|malware|exploit|cyber|phishing|zero-day|CVE)", RegexOption.IGNORE_CASE),
        "AI" to Regex("\\b(AI|artificial intelligence|OpenAI|ChatGPT|Gemini|Claude|Anthropic|LLM|machine learning|neural)\\b", RegexOption.IGNORE_CASE),
        "Policy" to Regex("\\b(regulat|antitrust|lawsuit|court|FTC|EU |congress|senate|ban|law\\b|policy|government)", RegexOption.IGNORE_CASE),
        "Startups" to Regex("\\b(raises|funding|series [a-e]|seed round|valuation|startup|acquires|acquisition|IPO)\\b", RegexOption.IGNORE_CASE),
        "Gadgets" to Regex("\\b(iphone|pixel|galaxy|laptop|headphones|smartwatch|camera|tablet|console|review:)", RegexOption.IGNORE_CASE),
        "Science" to Regex("\\b(space|nasa|spacex|climate|quantum|physics|biology|rocket)", RegexOption.IGNORE_CASE),
    )

    private val aiTopicRules = listOf(
        "Data Science" to Regex("\\b(data scien|pandas|sql|analytics|visuali[sz]ation|statistic|dataset)", RegexOption.IGNORE_CASE),
        "Research" to Regex("\\b(paper|arxiv|benchmark|we propose|study|researchers)", RegexOption.IGNORE_CASE),
        "Policy" to Regex("\\b(regulat|AI act|safety|copyright|lawsuit|policy|government)", RegexOption.IGNORE_CASE),
        "Tools" to Regex("\\b(open[- ]source|library|framework|SDK|API|release[sd]?|fine-tun|deploy)", RegexOption.IGNORE_CASE),
    )

    fun guessTopic(section: String, default: String, title: String, text: String): String {
        val probe = "$title $title ${text.take(400)}"
        val rules = if (section == Section.AI) aiTopicRules else topicRules
        val allowed = if (section == Section.AI) aiTopics else techTopics
        val hit = rules.firstOrNull { it.second.containsMatchIn(probe) }?.first
        return hit ?: default.takeIf { it in allowed } ?: if (section == Section.AI) "Industry" else "Software"
    }
}
