package com.technewz.app.data

import android.content.Context
import com.technewz.app.net.JobSources
import com.technewz.app.net.RawJob
import com.technewz.app.util.ScamFilter
import com.technewz.app.util.Skills
import com.technewz.app.work.FollowUpWorker
import com.technewz.app.work.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

class JobsRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val dao = db.jobs()
    private val apps = db.applications()

    data class RefreshResult(val total: Int, val newCount: Int, val failedSources: List<String>)

    /** Jobs change slowly and the APIs are free/public, so we sync them at most hourly unless forced. */
    suspend fun refresh(force: Boolean = false): RefreshResult? = withContext(HeavyWork.dispatcher) {
        val s = settings.current()
        val now = System.currentTimeMillis()
        if (!force && now - s.lastJobsRefresh < TimeUnit.MINUTES.toMillis(55)) return@withContext null

        val sources = buildList<Pair<String, suspend () -> List<RawJob>>> {
            add("Remotive" to { JobSources.remotive() })
            add("Remote OK" to { JobSources.remoteOk() })
            add("The Muse" to { JobSources.theMuse() })
            add("Arbeitnow" to { JobSources.arbeitnow() })
            if (s.adzunaAppId.isNotBlank()) add("Adzuna" to { JobSources.adzuna(s.adzunaAppId, s.adzunaAppKey, s.countryCode, s.city) })
            boards(s.greenhouseBoards).forEach { b -> add("Greenhouse/$b" to { JobSources.greenhouse(b) }) }
            boards(s.leverBoards).forEach { b -> add("Lever/$b" to { JobSources.lever(b) }) }
            boards(s.ashbyBoards).forEach { b -> add("Ashby/$b" to { JobSources.ashby(b) }) }
        }
        val gate = Semaphore(6)
        val results = sources.map { (name, fetch) ->
            async { gate.withPermit { name to runCatching { fetch() } } }
        }.awaitAll()
        val failed = results.filter { it.second.isFailure }.map { it.first }

        val cutoff = now - TimeUnit.DAYS.toMillis(45)
        val raw = results.flatMap { it.second.getOrNull().orEmpty() }
            .filter { it.url.startsWith("http") && it.title.isNotBlank() && it.company.isNotBlank() }
            // Aggregator listings go stale; a role still on the employer's own board is live regardless of its first-posted date.
            .filter { it.directFromEmployer || (it.postedAt ?: now) >= cutoff }
            // Prefer the employer's own posting when the same role appears on an aggregator.
            .sortedByDescending { it.directFromEmployer }
            .distinctBy { normKey(it.title, it.company, it.location) }

        val existing = dao.all().associateBy { it.id }
        val userSkills = Skills.normalize(settings.currentProfile().skills)
        val entities = raw.map { r ->
            val id = "${r.source}:${r.externalId}"
            val old = existing[id]
            toEntity(r, id, now, userSkills).copy(aiAnalysis = old?.aiAnalysis, alerted = old?.alerted ?: false)
        }
        dao.upsertAll(entities)
        // Remove listings that have expired or disappeared from their source for 3+ days.
        dao.prune(cutoff, now - TimeUnit.DAYS.toMillis(3))

        val newOnes = entities.filter { it.id !in existing }
        if (s.alertsEnabled && s.keywords.isNotEmpty() && s.lastJobsRefresh > 0) {
            val rxs = s.keywords.map { Regex("(?<![\\p{L}\\d])" + Regex.escape(it) + "(?![\\p{L}\\d])", RegexOption.IGNORE_CASE) }
            val hits = newOnes.filter { j -> !j.alerted && j.scamFlags.isEmpty() && rxs.any { it.containsMatchIn(j.title) || it.containsMatchIn(j.company) } }
            if (hits.isNotEmpty()) {
                Notifications.jobAlert(context, hits.size, hits.map { "${it.title} · ${it.company}" })
                dao.markAlerted(hits.map { it.id })
            }
        }
        settings.update { it.copy(lastJobsRefresh = now) }
        RefreshResult(entities.size, newOnes.size, failed)
    }

    /** Re-score all jobs after the profile changes. */
    suspend fun rescore() = withContext(HeavyWork.dispatcher) {
        val userSkills = Skills.normalize(settings.currentProfile().skills)
        dao.upsertAll(dao.all().map { j ->
            val m = Skills.match(userSkills, j.title + "\n" + j.description)
            j.copy(matchScore = m.score, matchedSkills = m.matched.joinToString("|"), missingSkills = m.missing.joinToString("|"))
        })
    }

    private fun toEntity(r: RawJob, id: String, now: Long, userSkills: Set<String>): JobEntity {
        val text = r.title + "\n" + r.description
        val m = Skills.match(userSkills, text)
        return JobEntity(
            id = id, title = r.title.trim(), company = r.company.trim(), location = r.location.trim(),
            isRemote = r.isRemote, employmentType = r.employmentType, isInternship = r.employmentType == "Internship",
            url = r.url, source = r.source, directFromEmployer = r.directFromEmployer,
            description = r.description.take(12000), postedAt = r.postedAt ?: now, fetchedAt = now, salary = r.salary,
            matchScore = m.score, matchedSkills = m.matched.joinToString("|"), missingSkills = m.missing.joinToString("|"),
            scamFlags = ScamFilter.flags(text).joinToString("|"),
        )
    }

    private fun boards(csv: String) = csv.split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun normKey(title: String, company: String, location: String) =
        (title + "|" + company + "|" + location.take(20)).lowercase().replace(Regex("[^a-z0-9|]"), "")

    // ---------------- Tracker ----------------
    suspend fun setStatus(job: JobEntity, status: String) {
        val now = System.currentTimeMillis()
        val current = apps.get(job.id)
        val base = current ?: ApplicationEntity(
            jobId = job.id, title = job.title, company = job.company, location = job.location, url = job.url,
            source = job.source, status = status, createdAt = now, updatedAt = now,
        )
        updateApplication(base, status)
    }

    suspend fun updateApplication(app: ApplicationEntity, status: String) {
        val now = System.currentTimeMillis()
        var updated = app.copy(status = status, updatedAt = now)
        if (status == AppStatus.APPLIED && app.appliedAt == null) {
            val followUp = now + TimeUnit.DAYS.toMillis(7)
            updated = updated.copy(appliedAt = now, followUpAt = followUp)
            FollowUpWorker.schedule(context, app.jobId, followUp - now)
        }
        if (status != AppStatus.APPLIED) FollowUpWorker.cancel(context, app.jobId)
        apps.upsert(updated)
    }

    suspend fun saveNotes(app: ApplicationEntity, notes: String) = apps.upsert(app.copy(notes = notes, updatedAt = System.currentTimeMillis()))
    suspend fun saveCoverLetter(jobId: String, text: String) {
        apps.get(jobId)?.let { apps.upsert(it.copy(coverLetter = text)) }
    }
    suspend fun removeApplication(jobId: String) {
        FollowUpWorker.cancel(context, jobId)
        apps.delete(jobId)
    }
}
