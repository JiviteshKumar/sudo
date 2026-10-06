package com.technewz.app.net

import com.technewz.app.util.Text
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class RawItem(
    val title: String,
    val link: String,
    val descriptionHtml: String,
    val published: Long?,
    val imageUrl: String?,
)

/** Minimal, forgiving RSS 2.0 / RDF / Atom parser. */
object RssParser {
    private val tags = Regex("<[^>]*>")

    fun parse(xml: String): List<RawItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml.trimStart(Char(0xFEFF), ' ', '\n', '\r', '\t')))

        val items = mutableListOf<RawItem>()
        var inItem = false
        var title = ""
        var link = ""
        var desc = ""
        var content = ""
        var date: String? = null
        var image: String? = null

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.lowercase()
                when (event) {
                    XmlPullParser.START_TAG -> when {
                        name == "item" || name == "entry" -> {
                            inItem = true
                            title = ""; link = ""; desc = ""; content = ""; date = null; image = null
                        }
                        !inItem -> Unit
                        name == "title" -> title = readText(parser)
                        name == "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            if (href != null) {
                                if (link.isEmpty() || rel == null || rel == "alternate") link = href
                            } else {
                                val t = readText(parser)
                                if (t.isNotBlank()) link = t
                            }
                        }
                        name == "guid" && link.isEmpty() -> {
                            val perma = parser.getAttributeValue(null, "isPermaLink")
                            val t = readText(parser)
                            if (perma != "false" && t.startsWith("http")) link = t
                        }
                        name == "description" || name == "summary" -> desc = readText(parser)
                        name == "content:encoded" || name == "content" -> {
                            if (parser.getAttributeValue(null, "url") == null) content = readText(parser)
                        }
                        name == "pubdate" || name == "published" || name == "dc:date" ||
                            (name == "updated" && date == null) -> date = readText(parser)
                        name == "media:content" || name == "media:thumbnail" -> {
                            val url = parser.getAttributeValue(null, "url")
                            val medium = parser.getAttributeValue(null, "medium")
                            val type = parser.getAttributeValue(null, "type")
                            if (url != null && image == null &&
                                (medium == null || medium == "image") && (type == null || type.startsWith("image"))
                            ) image = url
                        }
                        name == "enclosure" -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            val url = parser.getAttributeValue(null, "url")
                            if (url != null && type.startsWith("image") && image == null) image = url
                        }
                    }
                    XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                        inItem = false
                        val html = desc.ifBlank { content }
                        if (title.isNotBlank() && link.startsWith("http")) {
                            items += RawItem(
                                title = if (title.contains('<') || title.contains('&')) Text.htmlToText(title) else title.replace(Regex("\\s+"), " ").trim(),
                                link = link.trim(),
                                // Cheap length check (no HTML parser per item — feeds can carry thousands of items).
                                descriptionHtml = if (html.replace(tags, "").trim().length < 120 && content.isNotBlank()) content else html,
                                published = Text.parseDate(date),
                                imageUrl = image ?: Text.firstImage(content) ?: Text.firstImage(desc),
                            )
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Return what we managed to parse; one malformed entry shouldn't sink the feed.
        }
        return items
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(parser.text ?: "")
                XmlPullParser.START_TAG -> {
                    // XHTML content inside Atom: keep inner tags as text-ish
                    sb.append('<').append(parser.name).append('>')
                    depth++
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth > 0) sb.append("</").append(parser.name).append('>')
                }
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }
}
