package com.technewz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.AppSettings
import com.technewz.app.data.JobEntity
import com.technewz.app.data.Profile
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.ChipRow
import com.technewz.app.ui.components.CompanyAvatar
import com.technewz.app.ui.components.EmptyState
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.components.LiveDot
import com.technewz.app.ui.components.MatchRing
import com.technewz.app.ui.components.Pill
import com.technewz.app.ui.components.SkeletonCard
import com.technewz.app.ui.theme.Accents
import com.technewz.app.ui.theme.LocalExtra
import com.technewz.app.util.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

object JobFilter {
    const val INTERN = "Internships"
    const val FULL = "Full-time"
    const val REMOTE = "Remote"
    const val ONSITE = "On-site"
    const val NEAR = "Near me"
    const val DIRECT = "Direct from employer"
    val all = listOf(INTERN, FULL, REMOTE, ONSITE, NEAR, DIRECT)
}

data class JobsUi(
    val loaded: Boolean = false,
    val jobs: List<JobEntity> = emptyList(),
    val total: Int = 0,
    val filters: Set<String> = emptySet(),
    val query: String = "",
    val sortByMatch: Boolean = true,
    val refreshing: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val profile: Profile = Profile(),
    val trackedIds: Set<String> = emptySet(),
)

class JobsViewModel(private val c: AppContainer) : ViewModel() {
    private val filters = MutableStateFlow(setOf<String>())
    private val query = MutableStateFlow("")
    private val sortByMatch = MutableStateFlow(true)

    private data class Q(val f: Set<String>, val q: String, val m: Boolean)
    private val queryState = combine(filters, query, sortByMatch) { f, q, m -> Q(f, q, m) }

    val state: StateFlow<JobsUi> = combine(
        c.db.jobs().observeAll(), queryState, c.settings.settings, c.settings.profile,
        combine(c.refreshingJobs, c.db.applications().observeAll()) { r, apps -> r to apps.map { it.jobId }.toSet() },
    ) { jobs, q, s, p, rt ->
        val near = nearTerms(s, p)
        val text = q.q.trim().lowercase(Locale.ROOT)
        val filtered = jobs.asSequence()
            .filter { it.scamFlags.split('|').count { f -> f.isNotBlank() } < 2 }
            .filter { j ->
                (JobFilter.INTERN !in q.f || j.isInternship) &&
                    (JobFilter.FULL !in q.f || j.employmentType == "Full-time") &&
                    (JobFilter.REMOTE !in q.f || j.isRemote) &&
                    (JobFilter.ONSITE !in q.f || !j.isRemote) &&
                    (JobFilter.DIRECT !in q.f || j.directFromEmployer) &&
                    (JobFilter.NEAR !in q.f || near.any { t -> j.location.contains(t, ignoreCase = true) })
            }
            .filter { j ->
                text.isEmpty() || j.title.lowercase().contains(text) || j.company.lowercase().contains(text) ||
                    j.location.lowercase().contains(text) || j.matchedSkills.lowercase().contains(text)
            }
            .toList()
        val sorted = if (q.m) filtered.sortedWith(compareByDescending<JobEntity> { it.matchScore ?: -1 }.thenByDescending { it.postedAt })
        else filtered.sortedByDescending { it.postedAt }
        JobsUi(true, sorted.take(300), jobs.size, q.f, q.q, q.m, rt.first, s, p, rt.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JobsUi())

    init {
        viewModelScope.launch { c.refreshJobs(force = false) }
    }

    fun toggle(f: String) {
        filters.value = filters.value.toMutableSet().apply {
            if (!add(f)) remove(f)
            if (f == JobFilter.REMOTE) remove(JobFilter.ONSITE)
            if (f == JobFilter.ONSITE) remove(JobFilter.REMOTE)
            if (f == JobFilter.INTERN) remove(JobFilter.FULL)
            if (f == JobFilter.FULL) remove(JobFilter.INTERN)
        }
    }
    fun setQuery(q: String) { query.value = q }
    fun toggleSort() { sortByMatch.value = !sortByMatch.value }
    fun refresh(onMessage: (String) -> Unit) = viewModelScope.launch { c.refreshJobs(force = true)?.let(onMessage) }

    companion object {
        fun nearTerms(s: AppSettings, p: Profile): List<String> {
            val country = runCatching { Locale("", s.countryCode.uppercase()).getDisplayCountry(Locale.ENGLISH) }.getOrNull()
            return listOfNotNull(
                s.city.takeIf { it.isNotBlank() },
                p.location.substringBefore(',').trim().takeIf { it.length > 2 },
                country?.takeIf { it.isNotBlank() && it.length > 2 },
                if (s.countryCode.equals("in", true)) "India" else null,
                if (s.countryCode.equals("us", true)) "United States" else null,
                if (s.countryCode.equals("gb", true)) "United Kingdom" else null,
            ).distinct()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    vm: JobsViewModel,
    listState: LazyListState,
    onOpenJob: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ui by vm.state.collectAsState()
    val accent = Accents.jobs

    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.refresh(onMessage) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Opportunities for you",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpenSettings) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.LocationOn, "Location settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProfileButton(ui.profile, accent, onOpenProfile)
                    }
                    GradientText("Jobs & Internships", accent, MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot()
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${ui.jobs.size} of ${ui.total} roles  ·  " +
                                if (ui.settings.lastJobsRefresh > 0) "synced ${Text.timeAgo(ui.settings.lastJobsRefresh)}" else "syncing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search role, company, skill, city") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (ui.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                }
            }
            item(key = "chips") {
                ChipRow(JobFilter.all, ui.filters, accent, vm::toggle)
            }
            item(key = "sort") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            ui.profile.skills.isEmpty() -> "Add your resume to see match scores"
                            ui.sortByMatch -> "Sorted by skill match"
                            else -> "Sorted by newest"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.profile.skills.isEmpty()) {
                        TextButton(onClick = onOpenProfile) { Text("Add resume") }
                    } else {
                        TextButton(onClick = vm::toggleSort) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (ui.sortByMatch) "Newest" else "Best match")
                        }
                    }
                }
            }
            if (JobFilter.NEAR in ui.filters && ui.settings.city.isBlank()) {
                item(key = "nocity") {
                    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onOpenSettings, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            "Set your city in Settings to see on-site roles near you. Add a free Adzuna key there for the widest local coverage.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            when {
                !ui.loaded || (ui.total == 0 && ui.refreshing) -> items(4, key = { "sk$it" }) { SkeletonCard() }
                ui.jobs.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.WorkOutline,
                        if (ui.total == 0) "Finding opportunities" else "No matches for these filters",
                        if (ui.total == 0) "Pull down to sync. Jobs come from employer career pages and trusted job APIs."
                        else "Try removing a filter or searching for something broader.",
                        accent,
                    )
                }
                else -> items(ui.jobs, key = { it.id }) { job ->
                    JobCard(job, tracked = job.id in ui.trackedIds, hasProfile = ui.profile.skills.isNotEmpty(), onClick = { onOpenJob(job.id) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

/** Employer boards keep evergreen roles open for months; say "live" rather than implying the role is stale. */
fun postedLabel(job: JobEntity): String {
    val old = System.currentTimeMillis() - job.postedAt > 60L * 24 * 3600 * 1000
    return if (job.directFromEmployer && old) "live on careers page" else "posted ${Text.timeAgo(job.postedAt)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobCard(job: JobEntity, tracked: Boolean, hasProfile: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = LocalExtra.current
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CompanyAvatar(job.company)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.company, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        if (job.directFromEmployer) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Verified, "Direct from employer", tint = extra.success, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (hasProfile) {
                    Spacer(Modifier.width(8.dp))
                    MatchRing(job.matchScore)
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    job.location.take(42) + if (job.location.length > 42) "…" else "",
                    icon = if (job.isRemote) Icons.Rounded.Public else Icons.Rounded.LocationOn,
                )
                if (job.isInternship) Pill("Internship", icon = Icons.Rounded.School, container = Accents.ai.start.copy(alpha = 0.13f), content = Accents.ai.start)
                else Pill(job.employmentType)
                job.salary?.let { Pill(it, icon = Icons.Rounded.Payments, container = extra.successContainer, content = extra.success) }
                if (job.scamFlags.isNotBlank()) Pill("Check carefully", icon = Icons.Rounded.Warning, container = extra.warningContainer, content = extra.warning)
                if (tracked) Pill("In tracker", container = Accents.tracker.start.copy(alpha = 0.14f), content = Accents.tracker.start)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (job.directFromEmployer) extra.success else MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(6.dp))
                Text(
                    "via ${job.source}  ·  " + postedLabel(job),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val missing = job.missingSkills.split('|').filter { it.isNotBlank() }
                if (hasProfile && missing.isNotEmpty()) {
                    Text(
                        "${missing.size} skill gap${if (missing.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
