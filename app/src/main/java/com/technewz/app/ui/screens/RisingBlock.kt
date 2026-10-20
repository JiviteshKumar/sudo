package com.technewz.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.technewz.app.data.Rising
import com.technewz.app.data.RisingRepoEntity
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.Pill
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.LocalExtra
import com.technewz.app.util.Text
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A listed repo plus what the app worked out for this user. */
data class RepoCard(val repo: RisingRepoEntity, val matched: List<String>, val isNew: Boolean)

data class RisingUi(
    val loaded: Boolean = false,
    val all: List<RepoCard> = emptyList(),
    val ai: List<RepoCard> = emptyList(),
    val forYou: List<RepoCard> = emptyList(),
    val hasSkills: Boolean = false,
    val lastCheck: Long = 0,
    val nextUpdate: LocalDate? = null,
    val checking: Boolean = false,
)

fun risingUi(listed: List<RisingRepoEntity>, userSkills: Set<String>, lastCheck: Long, lastScheduled: Long, checking: Boolean, now: Long = System.currentTimeMillis()): RisingUi {
    // "New" = added in the last 3 days, but not the very first fill (then everything would be new).
    val firstFill = listed.mapNotNull { it.firstFeaturedAt }.minOrNull()
    val cards = listed.map { r ->
        val fa = r.firstFeaturedAt
        RepoCard(r, Rising.matchedSkills(userSkills, r), fa != null && fa != firstFill && now - fa < 3 * 86_400_000L)
    }
    val all = cards.filter { it.repo.rankAll != null }.sortedBy { it.repo.rankAll }
    val ai = cards.filter { it.repo.rankAi != null }.sortedBy { it.repo.rankAi }
    return RisingUi(
        loaded = true, all = all, ai = ai,
        forYou = cards.filter { it.matched.isNotEmpty() }.sortedByDescending { it.repo.growthPerDay },
        hasSkills = userSkills.isNotEmpty(), lastCheck = lastCheck,
        nextUpdate = if (lastScheduled > 0) Rising.nextUpdate(lastScheduled, java.time.ZoneId.systemDefault()) else null,
        checking = checking,
    )
}

private fun nextLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        !d.isAfter(today) -> "next update this morning"
        d == today.plusDays(1) -> "next update tomorrow morning"
        else -> "next update " + d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " +
            d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " morning"
    }
}

@Composable
fun RisingBlock(ui: RisingUi, accent: Accent, onOpen: (RepoCard) -> Unit, onCheck: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    Column {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.RocketLaunch, null, tint = accent.start, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Rising on GitHub", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (ui.checking) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = accent.start)
            else IconButton(onClick = onCheck) { Icon(Icons.Rounded.Refresh, "Check GitHub now", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(
            listOfNotNull(
                "Repos gaining stars fastest right now",
                if (ui.lastCheck > 0) "updated ${Text.timeAgo(ui.lastCheck)}" else null,
                ui.nextUpdate?.let { nextLabel(it) },
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "AI/ML", "For you").forEach { f ->
                val sel = f == filter
                Pill(
                    f,
                    container = if (sel) accent.start else MaterialTheme.colorScheme.surfaceVariant,
                    content = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { filter = f },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Crossfade(filter, label = "rising") { f ->
            val list = when (f) { "AI/ML" -> ui.ai; "For you" -> ui.forYou; else -> ui.all }
            when {
                list.isNotEmpty() -> RepoRow(list, accent, onOpen)
                !ui.loaded || ui.checking || (ui.lastCheck == 0L && f == "All") -> RisingNote("Checking GitHub for rising repos…")
                f == "For you" && !ui.hasSkills -> RisingNote("Add your skills under Profile (top right) to see rising repos that match them.")
                f == "For you" -> RisingNote("None of the repos rising right now mention your skills. Check again after the next update.")
                else -> RisingNote("Couldn't reach GitHub yet — the list appears after the first successful check.")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RisingNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
fun RepoRow(list: List<RepoCard>, accent: Accent, onOpen: (RepoCard) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.repo.fullName }, contentType = { "repo" }) { card -> RepoMiniCard(card, list.indexOf(card), accent) { onOpen(card) } }
    }
}

@Composable
private fun RepoMiniCard(card: RepoCard, index: Int, accent: Accent, onClick: () -> Unit) {
    val r = card.repo
    AppCard(Modifier.width(270.dp).height(198.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    r.growthLabel.substringBefore(" · "), style = MaterialTheme.typography.labelMedium, color = accent.start,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (card.isNew) Pill("New", container = LocalExtra.current.successContainer, content = LocalExtra.current.success)
            }
            Spacer(Modifier.height(10.dp))
            Text(r.fullName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    listOf(Rising.human(r.stars), r.language).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (card.matched.isNotEmpty()) {
                    Text(
                        "Matches " + card.matched.take(2).joinToString(", "), style = MaterialTheme.typography.labelSmall,
                        color = LocalExtra.current.success, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RepoSheet(card: RepoCard, accent: Accent, onDismiss: () -> Unit, onOpenGitHub: (String) -> Unit) {
    val r = card.repo
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())
        ) {
            Text(r.fullName, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    r.language.takeIf { it.isNotBlank() },
                    "★ ${Rising.human(r.stars)} stars",
                    "${Rising.human(r.forks)} forks",
                    r.createdAt.takeIf { it > 0 }?.let { "created ${Text.timeAgo(it)}" },
                    r.pushedAt.takeIf { it > 0 }?.let { "last updated ${Text.timeAgo(it)}" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.start.copy(alpha = 0.10f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.RocketLaunch, null, tint = accent.start, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(r.growthLabel, style = MaterialTheme.typography.titleSmall)
                    Text("Source: ${r.growthSource}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SheetSection("What it is", r.description, "The author's own description on GitHub")
            r.readme?.let { SheetSection("What it does", it, "First paragraph of the README, quoted") }
            if (card.matched.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Matches your skills", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.matched.forEach { Pill(it, container = LocalExtra.current.successContainer, content = LocalExtra.current.success) }
                }
            }
            r.topics.split('|').filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { topics ->
                Spacer(Modifier.height(14.dp))
                Text("Topics", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.take(10).forEach { Pill(it) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Stars measure popularity, not quality or safety — read the code and license before you depend on it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            GradientButton("Open on GitHub", accent, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew) { onOpenGitHub(r.url) }
        }
    }
}

@Composable
private fun SheetSection(title: String, body: String, source: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
}
