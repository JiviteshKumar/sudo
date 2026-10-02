package com.technewz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.AppStatus
import com.technewz.app.data.ApplicationEntity
import com.technewz.app.data.ArticleEntity
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.ChipRow
import com.technewz.app.ui.components.CompanyAvatar
import com.technewz.app.ui.components.EmptyState
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.util.Browser
import com.technewz.app.util.Text
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class TrackerViewModel(private val c: AppContainer) : ViewModel() {
    val apps: StateFlow<List<ApplicationEntity>?> =
        c.db.applications().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setStatus(a: ApplicationEntity, s: String) = viewModelScope.launch { c.jobs.updateApplication(a, s) }
    fun saveNotes(a: ApplicationEntity, notes: String) = viewModelScope.launch { c.jobs.saveNotes(a, notes) }
    fun remove(a: ApplicationEntity) = viewModelScope.launch { c.jobs.removeApplication(a.jobId) }
}

private fun statusAccent(s: String): Accent = when (s) {
    AppStatus.SAVED -> Accents.saved
    AppStatus.APPLIED -> Accents.tech
    AppStatus.INTERVIEW -> Accents.ai
    AppStatus.OFFER -> Accents.jobs
    else -> Accent(Color(0xFF94A3B8), Color(0xFF64748B))
}

@Composable
fun TrackerScreen(vm: TrackerViewModel, listState: LazyListState, onOpenJob: (String) -> Unit) {
    val apps by vm.apps.collectAsState()
    val list = apps.orEmpty()
    var filter by rememberSaveable { mutableStateOf("All") }
    var editing by remember { mutableStateOf<ApplicationEntity?>(null) }
    val counts = list.groupingBy { it.status }.eachCount()

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item("header") {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 20.dp)) {
                Text("Your pipeline", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientText("Tracker", Accents.tracker, MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(AppStatus.APPLIED, AppStatus.INTERVIEW, AppStatus.OFFER).forEach { s ->
                        val acc = statusAccent(s)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(acc.start.copy(alpha = 0.16f), acc.end.copy(alpha = 0.08f))))
                                .padding(14.dp)
                        ) {
                            Text("${counts[s] ?: 0}", style = MaterialTheme.typography.headlineLarge, color = acc.start)
                            Text(if (s == AppStatus.INTERVIEW) "Interviews" else if (s == AppStatus.OFFER) "Offers" else "Applied", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        item("chips") {
            ChipRow(listOf("All") + AppStatus.all, setOf(filter), Accents.tracker, { filter = it }, counts = counts + ("All" to list.size))
        }
        val shown = if (filter == "All") list else list.filter { it.status == filter }
        if (apps != null && shown.isEmpty()) {
            item("empty") {
                EmptyState(
                    Icons.Rounded.ViewKanban,
                    if (list.isEmpty()) "Track every application" else "Nothing here yet",
                    "Save jobs or tap Apply on any listing — they'll show up here with follow-up reminders.",
                    Accents.tracker,
                )
            }
        }
        items(shown, key = { it.jobId }) { a ->
            ApplicationCard(a, onOpen = { onOpenJob(a.jobId) }, onStatus = { vm.setStatus(a, it) }, onNotes = { editing = a }, onRemove = { vm.remove(a) }, modifier = Modifier.animateItem())
        }
    }

    editing?.let { a ->
        var notes by remember(a.jobId) { mutableStateOf(a.notes) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Notes · ${a.company}") },
            text = {
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().height(180.dp), placeholder = { Text("Recruiter name, interview date, prep notes…") })
            },
            confirmButton = { TextButton(onClick = { vm.saveNotes(a, notes); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApplicationCard(
    a: ApplicationEntity,
    onOpen: () -> Unit,
    onStatus: (String) -> Unit,
    onNotes: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val acc = statusAccent(a.status)
    var menu by remember { mutableStateOf(false) }
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onOpen) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompanyAvatar(a.company, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${a.company} · ${a.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    Box(
                        Modifier.clip(CircleShape).background(acc.horizontal).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(a.status, color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(menu, { menu = false }) {
                        AppStatus.all.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { onStatus(s); menu = false }) }
                        DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, onClick = { onRemove(); menu = false })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val line = buildList {
                a.appliedAt?.let { add("Applied ${df.format(Date(it))}") } ?: add("Saved ${Text.timeAgo(a.createdAt)}")
                if (a.status == AppStatus.APPLIED) a.followUpAt?.let { add(if (it > System.currentTimeMillis()) "follow up ${df.format(Date(it))}" else "time to follow up") }
            }.joinToString("  ·  ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.status == AppStatus.APPLIED && a.followUpAt != null) {
                    Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onNotes, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.EditNote, "Notes", Modifier.size(20.dp)) }
                IconButton(onClick = { Browser.open(context, a.url, toolbar) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open posting", Modifier.size(18.dp))
                }
            }
            if (a.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(a.notes, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- Saved articles ----------------
class SavedViewModel(private val c: AppContainer) : ViewModel() {
    val saved: StateFlow<List<ArticleEntity>?> = c.db.articles().observeBookmarked().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun unsave(a: ArticleEntity) = viewModelScope.launch { c.news.setBookmarked(a.id, false) }
    fun read(a: ArticleEntity) = viewModelScope.launch { c.news.markRead(a.id) }
}

@Composable
fun SavedScreen(vm: SavedViewModel, listState: LazyListState) {
    val saved by vm.saved.collectAsState()
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item("header") {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 6.dp)) {
                Text("Read later · works offline", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradientText("Saved", Accents.saved, MaterialTheme.typography.displayMedium)
            }
        }
        val list = saved.orEmpty()
        if (saved != null && list.isEmpty()) {
            item("empty") {
                EmptyState(Icons.Rounded.Bookmarks, "No saved stories", "Tap the bookmark on any story to keep it here. Saved stories never expire.", Accents.saved)
            }
        }
        items(list, key = { it.id }) { a ->
            NewsCard(
                StoryCluster(a, emptyList()), if (a.section == "ai") Accents.ai else Accents.tech,
                onClick = { vm.read(a); Browser.open(context, a.url, toolbar) },
                onBookmark = { vm.unsave(a) },
                onShare = { Browser.share(context, a.title, a.url) },
                onCluster = {},
                modifier = Modifier.animateItem(),
            )
        }
    }
}
