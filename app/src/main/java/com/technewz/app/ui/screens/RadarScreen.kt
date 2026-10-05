package com.technewz.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.Evidence
import com.technewz.app.data.TermEntity
import com.technewz.app.data.TermStatus
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.ChipRow
import com.technewz.app.ui.components.EmptyState
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.components.Pill
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.util.Browser
import com.technewz.app.util.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RadarViewModel(private val c: AppContainer) : ViewModel() {
    val terms: StateFlow<List<TermEntity>?> = c.db.terms().observeVisible().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val progress: StateFlow<String?> = c.radarProgress
    val filter = MutableStateFlow("All")

    init {
        // Self-throttled (twice a day); first run happens automatically.
        viewModelScope.launch { c.refreshRadar(force = false) }
    }

    fun refresh(onMessage: (String) -> Unit) = viewModelScope.launch { c.refreshRadar(force = true)?.let(onMessage) }
    fun evidence(t: TermEntity): List<Evidence> = c.radar.evidenceOf(t)
}

private fun statusAccent(t: TermEntity): Accent = when {
    t.kind == "Tool" -> Accents.jobs
    t.status == TermStatus.NEW -> Accents.ai
    else -> Accents.tech
}

private fun growthLine(t: TermEntity): String? {
    if (t.kind == "Tool") return null
    val prior = t.papersPrior / 150.0
    val now = t.papers30 / 30.0
    val x = if (prior > 0) now / prior else null
    return "${t.papers30} AI/ML papers in 30 days" + (x?.takeIf { it >= 1.2 }?.let { " · ${"%.1f".format(it)}× faster" } ?: "")
}

/** Preview strip shown at the top of the AI & Data tab. */
@Composable
fun RadarBlock(vm: RadarViewModel, onOpenRadar: () -> Unit) {
    val terms by vm.terms.collectAsState()
    val progress by vm.progress.collectAsState()
    val accent = Accents.ai
    Column {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Radar, null, tint = accent.start, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Skills Radar", style = MaterialTheme.typography.titleLarge)
                Text(
                    progress ?: "New & rising terms and tools, verified",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenRadar) { Text("See all") }
        }
        Spacer(Modifier.height(10.dp))
        val list = terms.orEmpty()
        if (list.isEmpty()) {
            AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onOpenRadar, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (progress != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Radar, null, tint = accent.start)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (progress != null) "Scanning today's research, news, repos and job posts. Each term is checked against arXiv before it appears."
                        else "No verified new terms yet — open the radar to scan.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.take(8), key = { it.key }) { t ->
                    val a = statusAccent(t)
                    AppCard(Modifier.width(270.dp).height(178.dp), onClick = onOpenRadar) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(t)
                                Spacer(Modifier.weight(1f))
                                growthLine(t)?.let { Text(it.substringBefore(" ·"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    ?: Text("${t.jobMentions} job posts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(t.term, style = MaterialTheme.typography.titleMedium, color = a.start, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t.simpleWhat ?: t.what.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusPill(t: TermEntity) {
    val a = statusAccent(t)
    Box(Modifier.clip(CircleShape).background(a.horizontal).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(
            if (t.kind == "Tool") "${t.status} tool" else t.status.uppercase(),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(vm: RadarViewModel, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val terms by vm.terms.collectAsState()
    val progress by vm.progress.collectAsState()
    val filter by vm.filter.collectAsState()
    val list = terms.orEmpty().filter {
        when (filter) {
            "New" -> it.status == TermStatus.NEW
            "Rising" -> it.status == TermStatus.RISING
            "Concepts" -> it.kind == "Concept"
            "Tools" -> it.kind == "Tool"
            else -> true
        }
    }

    PullToRefreshBox(isRefreshing = progress != null, onRefresh = { vm.refresh(onMessage) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
            item("header") {
                Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        GradientText("Skills Radar", Accents.ai, MaterialTheme.typography.displaySmall)
                        Text(
                            "New and rising AI, ML & data-science terms and tools. Found in today's research, news, GitHub and job posts. Each must have taken off within the last 3 years (OpenAlex publication history), be active in AI/ML research now (arXiv) and be explained by a quoted source.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        progress?.let {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            item("chips") { ChipRow(listOf("All", "New", "Rising", "Concepts", "Tools"), setOf(filter), Accents.ai, { vm.filter.value = it }) }
            if (terms != null && list.isEmpty()) {
                item("empty") {
                    EmptyState(
                        Icons.Rounded.Radar,
                        if (progress != null) "Scanning…" else "Nothing verified yet",
                        "Terms only appear after they pass every check: defined in a real source, growing on arXiv (or new), and explainable from a source sentence.",
                        Accents.ai,
                    ) {
                        if (progress == null) GradientButton("Scan now", Accents.ai, icon = Icons.Rounded.Radar) { vm.refresh(onMessage) }
                    }
                }
            }
            items(list, key = { it.key }) { t -> TermCard(t, vm.evidence(t), Modifier.animateItem()) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermCard(t: TermEntity, evidence: List<Evidence>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()
    val a = statusAccent(t)
    var showSources by rememberSaveable(t.key) { mutableStateOf(false) }
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(t)
                Spacer(Modifier.width(8.dp))
                t.firstSeen?.let {
                    Text(
                        if (t.kind == "Tool") "first released " + Text.timeAgo(it)
                        else "took off in " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).year,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(t.term, style = MaterialTheme.typography.headlineSmall, color = a.start)

            Section("What it is")
            if (t.simpleWhat != null) {
                Text(t.simpleWhat, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "“${t.what}”", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text("“${t.what.orEmpty()}”", style = MaterialTheme.typography.bodyMedium)
            }
            SourceChip(t.whatSource, t.whatUrl, if (t.simpleWhat != null) "AI-simplified, checked against" else null) { Browser.open(context, it, toolbar) }

            if (!t.usage.isNullOrBlank()) {
                Section("Where it's used")
                Text(t.simpleUsage ?: (if (t.usageUrl != null) "“${t.usage}”" else t.usage), style = MaterialTheme.typography.bodyMedium)
                SourceChip(t.usageSource, t.usageUrl, null) { Browser.open(context, it, toolbar) }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                growthLine(t)?.let { Pill(it, icon = Icons.Rounded.Description, container = a.start.copy(alpha = 0.12f), content = a.start) }
                if (t.newsMentions > 0) Pill("${t.newsMentions} news stories", icon = Icons.Rounded.Article)
                if (t.jobMentions > 0) Pill("${t.jobMentions} job posts", icon = Icons.Rounded.WorkOutline)
                t.repoStars?.let { Pill("★ ${it / 1000}k on GitHub", icon = Icons.Rounded.Code) }
            }
            if (t.jobCompanies.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hiring for it: " + t.jobCompanies.split('|').joinToString(", "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showSources = !showSources }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Verified, null, tint = a.start, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Why it's on the radar · ${evidence.size} sources", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (showSources) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (showSources) {
                Text(t.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                evidence.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().clickable { Browser.open(context, e.url, toolbar) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(evidenceIcon(e.type), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                e.source + (e.date?.let { " · " + Text.timeAgo(it) } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun evidenceIcon(type: String): ImageVector = when (type) {
    "paper" -> Icons.Rounded.Description
    "news" -> Icons.Rounded.Article
    "repo" -> Icons.Rounded.Code
    "job" -> Icons.Rounded.WorkOutline
    else -> Icons.Rounded.AutoAwesome
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SourceChip(source: String?, url: String?, prefix: String?, onOpen: (String) -> Unit) {
    if (source == null) return
    Row(
        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
            .then(if (url != null) Modifier.clickable { onOpen(url) } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            listOfNotNull(prefix, source).joinToString(" "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (url != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
