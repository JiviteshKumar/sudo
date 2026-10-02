@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.technewz.app.net

import com.technewz.app.data.AppJson
import com.technewz.app.util.Text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromStream
import java.net.URLEncoder
import java.util.Locale

/** A job as fetched, before local scoring. */
data class RawJob(
    val source: String,
    val externalId: String,
    val title: String,
    val company: String,
    val location: String,
    val isRemote: Boolean,
    val employmentType: String,
    val url: String,
    val description: String,
    val postedAt: Long?,
    val salary: String?,
    val directFromEmployer: Boolean,
)

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonObject.s(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it != "null" }.orEmpty()
private fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
private fun JsonObject.l(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object JobSources {

    // Strong signals are always tech; weak signals count only when the title isn't clearly a non-tech role.
    private val strongTech = Regex(
        "engineer|engineering|developer|software|programmer|scientist|\\bdata\\b|machine learning|\\bml\\b|devops|\\bsre\\b|" +
            "front[- ]?end|back[- ]?end|full[- ]?stack|\\bandroid\\b|\\bios\\b",
        RegexOption.IGNORE_CASE,
    )
    private val weakTech = Regex(
        "\\bai\\b|research|security|analyst|cloud|platform|infrastructure|architect|\\bqa\\b|\\btest|mobile|" +
            "\\b(intern|interns|internship|internships)\\b|technical",
        RegexOption.IGNORE_CASE,
    )
    private val nonTech = Regex(
        "\\b(sales|account executive|account manager|accounting|accountant|recruit\\w*|talent|marketing|legal|counsel|" +
            "paralegal|finance|financial|payroll|tax|people|human resources|hr|assistant|customer success|customer support|" +
            "business development|partnerships|communications|policy|facilities|workplace|office|events|content|copywriter|" +
            "chef|cook|driver|warehouse|retail|store|cashier|nurse|teacher|associate)\\b",
        RegexOption.IGNORE_CASE,
    )
    // Clearly commercial/support roles are excluded unless the title is itself an engineering/science role.
    private val hardNonTech = Regex(
        "\\b(sales|account executive|account manager|recruit\\w*|marketing|legal|counsel|customer success)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val coreTech = Regex("engineer|developer|scientist|programmer", RegexOption.IGNORE_CASE)

    fun isTechRole(title: String): Boolean {
        if (hardNonTech.containsMatchIn(title) && !coreTech.containsMatchIn(title)) return false
        return strongTech.containsMatchIn(title) || (weakTech.containsMatchIn(title) && !nonTech.containsMatchIn(title))
    }

    private val boardNames = mapOf(
        "openai" to "OpenAI", "scaleai" to "Scale AI", "gitlab" to "GitLab", "mongodb" to "MongoDB",
        "databricks" to "Databricks", "anthropic" to "Anthropic", "cloudflare" to "Cloudflare",
    )
    private fun displayName(board: String) = boardNames[board] ?: board.replaceFirstChar { it.uppercase() }

    private val remoteWords = Regex("remote|anywhere|work from home|wfh|distributed", RegexOption.IGNORE_CASE)

    private fun typeLabel(raw: String, title: String): String {
        val t = raw.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(intern|interns|internship|internships|trainee|apprentice|apprenticeship|co-op)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(title) || Regex("\\bintern").containsMatchIn(t) -> "Internship"
            "part" in t -> "Part-time"
            "contract" in t || "freelance" in t || "temporary" in t -> "Contract"
            "full" in t || "permanent" in t -> "Full-time"
            else -> "Full-time"
        }
    }

    // ---------- Remotive (remote only) ----------
    suspend fun remotive(): List<RawJob> = listOf("software-dev", "data", "devops").flatMap { cat ->
        val json = AppJson.parseToJsonElement(Http.get("https://remotive.com/api/remote-jobs?category=$cat&limit=150"))
        json.obj()?.get("jobs").arr().orEmpty().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val title = o.s("title")
            if (!isTechRole(title)) return@mapNotNull null
            RawJob(
                source = "Remotive", externalId = o.s("id"), title = title, company = o.s("company_name"),
                location = "Remote · " + o.s("candidate_required_location").ifBlank { "Worldwide" },
                isRemote = true, employmentType = typeLabel(o.s("job_type"), title), url = o.s("url"),
                description = Text.htmlToReadable(o.s("description")), postedAt = Text.parseDate(o.s("publication_date")),
                salary = o.s("salary").ifBlank { null }, directFromEmployer = false,
            )
        }
    }

    // ---------- Arbeitnow ----------
    suspend fun arbeitnow(): List<RawJob> = (1..2).flatMap { page ->
        val json = AppJson.parseToJsonElement(Http.get("https://www.arbeitnow.com/api/job-board-api?page=$page"))
        json.obj()?.get("data").arr().orEmpty().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val title = o.s("title")
            if (!isTechRole(title)) return@mapNotNull null
            val remote = o.b("remote")
            val types = o["job_types"].arr()?.joinToString(" ") { it.jsonPrimitive.content }.orEmpty()
            RawJob(
                source = "Arbeitnow", externalId = o.s("slug"), title = title, company = o.s("company_name"),
                location = (if (remote) "Remote · " else "") + o.s("location"), isRemote = remote,
                employmentType = typeLabel(types, title), url = o.s("url"),
                description = Text.htmlToReadable(o.s("description")), postedAt = o.l("created_at")?.times(1000),
                salary = null, directFromEmployer = false,
            )
        }
    }

    // ---------- Remote OK (attribution: shown as source on every card) ----------
    suspend fun remoteOk(): List<RawJob> {
        val json = AppJson.parseToJsonElement(Http.get("https://remoteok.com/api"))
        return json.arr().orEmpty().drop(1).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val title = o.s("position")
            if (title.isBlank() || !isTechRole(title)) return@mapNotNull null
            val min = o.l("salary_min") ?: 0
            val max = o.l("salary_max") ?: 0
            RawJob(
                source = "Remote OK", externalId = o.s("id"), title = title, company = o.s("company"),
                location = "Remote · " + o.s("location").ifBlank { "Worldwide" }, isRemote = true,
                employmentType = typeLabel("", title), url = o.s("url").ifBlank { o.s("apply_url") },
                description = Text.htmlToReadable(o.s("description")),
                postedAt = o.l("epoch")?.times(1000) ?: Text.parseDate(o.s("date")),
                salary = if (min > 0 && max > 0) "$${min / 1000}k–$${max / 1000}k" else null,
                directFromEmployer = false,
            )
        }
    }

    // ---------- The Muse ----------
    suspend fun theMuse(): List<RawJob> {
        val cats = listOf("Software Engineering", "Data and Analytics", "Data Science")
        val catQuery = cats.joinToString("&") { "category=${enc(it)}" }
        val urls = listOf(
            "https://www.themuse.com/api/public/jobs?$catQuery&page=0&descending=true",
            "https://www.themuse.com/api/public/jobs?$catQuery&page=1&descending=true",
            "https://www.themuse.com/api/public/jobs?$catQuery&level=Internship&page=0&descending=true",
        )
        return urls.flatMap { url ->
            val json = AppJson.parseToJsonElement(Http.get(url))
            json.obj()?.get("results").arr().orEmpty().mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                val title = o.s("name")
                if (!isTechRole(title)) return@mapNotNull null
                val locs = o["locations"].arr()?.mapNotNull { it.obj()?.s("name") }.orEmpty()
                val levels = o["levels"].arr()?.mapNotNull { it.obj()?.s("name") }.orEmpty().joinToString(" ")
                val remote = locs.any { remoteWords.containsMatchIn(it) || it.contains("Flexible", true) }
                RawJob(
                    source = "The Muse", externalId = o.s("id"), title = title,
                    company = o["company"].obj()?.s("name").orEmpty(),
                    location = locs.joinToString(" · ").ifBlank { "Not specified" }, isRemote = remote,
                    employmentType = typeLabel(levels, title),
                    url = o["refs"].obj()?.s("landing_page").orEmpty(),
                    description = Text.htmlToReadable(o.s("contents")), postedAt = Text.parseDate(o.s("publication_date")),
                    salary = null, directFromEmployer = false,
                )
            }
        }
    }

    // ---------- Adzuna (optional free key; best source for on-site jobs near you) ----------
    val adzunaCountries = setOf("gb", "us", "at", "au", "be", "br", "ca", "ch", "de", "es", "fr", "in", "it", "mx", "nl", "nz", "pl", "sg", "za")

    suspend fun adzuna(appId: String, appKey: String, country: String, city: String): List<RawJob> {
        if (appId.isBlank() || appKey.isBlank() || country !in adzunaCountries) return emptyList()
        val where = if (city.isNotBlank()) "&where=${enc(city)}" else ""
        val queries = listOf(
            "what_or=${enc("software developer engineer data scientist analyst machine learning")}",
            "what_or=${enc("intern internship trainee")}",
        )
        return queries.flatMap { q ->
            val url = "https://api.adzuna.com/v1/api/jobs/$country/search/1?app_id=${enc(appId)}&app_key=${enc(appKey)}" +
                "&results_per_page=50&max_days_old=30&sort_by=date&$q$where&content-type=application/json"
            val json = AppJson.parseToJsonElement(Http.get(url))
            json.obj()?.get("results").arr().orEmpty().mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                val title = Text.htmlToText(o.s("title"))
                if (!isTechRole(title)) return@mapNotNull null
                val loc = o["location"].obj()?.s("display_name").orEmpty()
                val desc = Text.htmlToText(o.s("description"))
                val min = o.l("salary_min") ?: 0
                val max = o.l("salary_max") ?: 0
                RawJob(
                    source = "Adzuna", externalId = o.s("id"), title = title,
                    company = o["company"].obj()?.s("display_name").orEmpty(),
                    location = loc.ifBlank { "Not specified" },
                    isRemote = remoteWords.containsMatchIn(title + " " + loc),
                    employmentType = typeLabel(o.s("contract_time") + " " + o.s("contract_type"), title),
                    url = o.s("redirect_url"), description = desc, postedAt = Text.parseDate(o.s("created")),
                    salary = if (min > 0) (if (max > min) "${min.compact()}–${max.compact()}" else min.compact()) else null,
                    directFromEmployer = false,
                )
            }
        }
    }

    private fun Long.compact(): String = if (this >= 1000) "${this / 1000}k" else toString()

    // ---------- Company career boards (direct from the employer) ----------
    // These responses can exceed 10 MB, so they are decoded straight from the network stream into
    // small typed models instead of building a full JSON tree in memory.
    @Serializable private data class GhLoc(val name: String = "")
    @Serializable private data class GhJob(
        val id: Long = 0, val title: String = "", val absolute_url: String = "", val location: GhLoc? = null,
        val updated_at: String = "", val first_published: String? = null, val content: String = "", val company_name: String? = null,
    )
    @Serializable private data class GhResp(val jobs: List<GhJob> = emptyList())

    suspend fun greenhouse(board: String): List<RawJob> {
        val resp = Http.stream("https://boards-api.greenhouse.io/v1/boards/$board/jobs?content=true") {
            AppJson.decodeFromStream(GhResp.serializer(), it)
        }
        val companyName = displayName(board)
        return resp.jobs.asSequence().filter { isTechRole(it.title) }.take(120).map { o ->
            val loc = o.location?.name.orEmpty()
            RawJob(
                source = "Greenhouse", externalId = "$board-${o.id}", title = o.title,
                company = o.company_name?.ifBlank { null } ?: companyName, location = loc.ifBlank { "Not specified" },
                isRemote = remoteWords.containsMatchIn(loc), employmentType = typeLabel("", o.title),
                url = o.absolute_url, description = Text.htmlToReadable(o.content),
                postedAt = Text.parseDate(o.first_published?.ifBlank { null } ?: o.updated_at),
                salary = null, directFromEmployer = true,
            )
        }.toList()
    }

    @Serializable private data class LeverCats(val location: String? = null, val commitment: String? = null)
    @Serializable private data class LeverList(val text: String = "", val content: String = "")
    @Serializable private data class LeverJob(
        val id: String = "", val text: String = "", val hostedUrl: String = "", val applyUrl: String = "",
        val categories: LeverCats? = null, val createdAt: Long? = null, val descriptionPlain: String = "",
        val additionalPlain: String = "", val lists: List<LeverList> = emptyList(), val workplaceType: String? = null,
    )

    suspend fun lever(company: String): List<RawJob> {
        val jobs = Http.stream("https://api.lever.co/v0/postings/$company?mode=json") {
            AppJson.decodeFromStream(ListSerializer(LeverJob.serializer()), it)
        }
        val companyName = displayName(company)
        return jobs.asSequence().filter { isTechRole(it.text) }.take(120).map { o ->
            val loc = o.categories?.location.orEmpty()
            val lists = o.lists.joinToString("\n\n") { it.text + "\n" + Text.htmlToReadable(it.content) }
            RawJob(
                source = "Lever", externalId = "$company-${o.id}", title = o.text, company = companyName,
                location = loc.ifBlank { "Not specified" },
                isRemote = o.workplaceType == "remote" || remoteWords.containsMatchIn(loc),
                employmentType = typeLabel(o.categories?.commitment.orEmpty(), o.text),
                url = o.hostedUrl.ifBlank { o.applyUrl },
                description = (o.descriptionPlain + "\n\n" + lists + "\n\n" + o.additionalPlain).trim(),
                postedAt = o.createdAt, salary = null, directFromEmployer = true,
            )
        }.toList()
    }

    @Serializable private data class AshbyComp(val compensationTierSummary: String? = null)
    @Serializable private data class AshbyJob(
        val id: String = "", val title: String = "", val location: String = "", val isRemote: Boolean = false,
        val workplaceType: String? = null, val descriptionPlain: String = "", val publishedAt: String = "",
        val employmentType: String = "", val jobUrl: String = "", val applyUrl: String = "", val isListed: Boolean = true,
        val compensation: AshbyComp? = null,
    )
    @Serializable private data class AshbyResp(val jobs: List<AshbyJob> = emptyList())

    suspend fun ashby(org: String): List<RawJob> {
        val resp = Http.stream("https://api.ashbyhq.com/posting-api/job-board/$org?includeCompensation=true") {
            AppJson.decodeFromStream(AshbyResp.serializer(), it)
        }
        val companyName = displayName(org)
        return resp.jobs.asSequence().filter { it.isListed && isTechRole(it.title) }.take(120).map { o ->
            val remote = o.isRemote || o.workplaceType.equals("Remote", true)
            RawJob(
                source = "Ashby", externalId = "$org-${o.id}", title = o.title, company = companyName,
                location = (if (remote && !o.location.contains("remote", true)) "Remote · " else "") + o.location.ifBlank { "Not specified" },
                isRemote = remote, employmentType = typeLabel(o.employmentType, o.title),
                url = o.jobUrl.ifBlank { o.applyUrl }, description = o.descriptionPlain,
                postedAt = Text.parseDate(o.publishedAt),
                salary = o.compensation?.compensationTierSummary?.ifBlank { null }, directFromEmployer = true,
            )
        }.toList()
    }

}

object TrendingSources {
    data class Item(val id: String, val title: String, val subtitle: String, val url: String, val metric: String)

    suspend fun hfModels(): List<Item> {
        val raw = runCatching { Http.get("https://huggingface.co/api/models?sort=trendingScore&direction=-1&limit=15") }
            .getOrElse { Http.get("https://huggingface.co/api/models?sort=likes7d&direction=-1&limit=15") }
        return AppJson.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val id = o.s("id").ifBlank { o.s("modelId") }
            if (id.isBlank()) return@mapNotNull null
            val likes = o.l("likes") ?: 0
            val downloads = o.l("downloads") ?: 0
            Item(
                id = id, title = id.substringAfter('/'), subtitle = listOf(id.substringBefore('/'), o.s("pipeline_tag"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                url = "https://huggingface.co/$id", metric = "♥ ${likes.human()}" + if (downloads > 0) "  ↓ ${downloads.human()}" else "",
            )
        }
    }

    suspend fun hfPapers(): List<Item> {
        val raw = Http.get("https://huggingface.co/api/daily_papers?limit=15")
        return AppJson.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val paper = o["paper"]?.jsonObject ?: return@mapNotNull null
            val id = paper.s("id")
            if (id.isBlank()) return@mapNotNull null
            Item(
                id = id, title = paper.s("title").ifBlank { o.s("title") }.replace(Regex("\\s+"), " "),
                subtitle = "arXiv $id", url = "https://huggingface.co/papers/$id",
                metric = "▲ ${paper.l("upvotes") ?: 0}",
            )
        }.sortedByDescending { it.metric.removePrefix("▲ ").toIntOrNull() ?: 0 }
    }

    suspend fun githubRepos(): List<Item> {
        val since = java.time.LocalDate.now().minusDays(30).toString()
        val topics = listOf("llm", "machine-learning")
        return topics.flatMap { t ->
            val raw = Http.get(
                "https://api.github.com/search/repositories?q=${enc("topic:$t created:>$since")}&sort=stars&order=desc&per_page=10",
                mapOf("Accept" to "application/vnd.github+json"),
            )
            AppJson.parseToJsonElement(raw).jsonObject["items"]?.jsonArray.orEmptyList().mapNotNull { el ->
                val o = el.jsonObject
                (o.l("stargazers_count") ?: 0) to Item(
                    id = o.s("full_name"), title = o.s("full_name"),
                    subtitle = listOf(o.s("language"), o.s("description").take(90)).filter { it.isNotBlank() }.joinToString(" · "),
                    url = o.s("html_url"), metric = "★ ${(o.l("stargazers_count") ?: 0).human()}",
                )
            }
        }.distinctBy { it.second.id }.sortedByDescending { it.first }.map { it.second }.take(15)
    }

    private fun JsonArray?.orEmptyList(): List<JsonElement> = this ?: emptyList()

    private fun Long.human(): String = when {
        this >= 1_000_000 -> String.format(Locale.US, "%.1fM", this / 1_000_000.0)
        this >= 1_000 -> String.format(Locale.US, "%.1fk", this / 1_000.0)
        else -> toString()
    }
}
