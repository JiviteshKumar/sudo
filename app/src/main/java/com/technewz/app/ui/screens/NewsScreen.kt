package com.technewz.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.technewz.app.AppContainer
import com.technewz.app.data.ArticleEntity
import com.technewz.app.data.Profile
import com.technewz.app.data.Section
import com.technewz.app.data.SummaryKind
import com.technewz.app.data.TrendingEntity
import com.technewz.app.net.Feeds
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.BookmarkButton
import com.technewz.app.ui.components.ChipRow
import com.technewz.app.ui.components.EmptyState
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.components.LiveDot
import com.technewz.app.ui.components.Pill
import com.technewz.app.ui.components.SectionLabel
import com.technewz.app.ui.components.SkeletonCard
import com.technewz.app.ui.components.SourceLine
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.util.Browser
import com.technewz.app.util.Text
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

data class StoryCluster(val primary: ArticleEntity, val others: List<ArticleEntity>) {
    val all get() = listOf(primary) + others
    val latest get() = all.maxOf { it.publishedAt }
}

fun clusterArticles(articles: List<ArticleEntity>): List<StoryCluster> =
    articles.groupBy { it.clusterId }.values.map { group ->
        val primary = group.sortedWith(
            compareByDescending<ArticleEntity> { it.summaryKind == SummaryKind.AI }
                .thenByDescending { it.imageUrl != null }
                .thenBy { it.publishedAt }
        ).first()
        StoryCluster(primary, group.filter { it.sourceDomain != primary.sourceDomain }.distinctBy { it.sourceDomain })
    }.sortedByDescending { it.latest }

class NewsViewModel(private val c: AppContainer, val section: String) : ViewModel() {
    val topic = MutableStateFlow("All")

    private val clusters = c.db.articles().observeSection(section).map { clusterArticles(it) }

    val state: StateFlow<NewsUi> = combine(clusters, topic, c.settings.settings, c.settings.profile, c.refreshingNews) { cl, t, s, p, r ->
        val counts = cl.groupingBy { it.primary.topic }.eachCount()
        NewsUi(
            loaded = true,
            clusters = if (t == "All") cl else cl.filter { it.primary.topic == t },
            counts = counts,
            total = cl.size,
            lastRefresh = s.lastNewsRefresh,
            hasAi = s.hasAi,
            profile = p,
            refreshing = r,
            note = s.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NewsUi())

    val trending: StateFlow<List<TrendingEntity>> =
        c.db.trending().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            if (System.currentTimeMillis() - s.lastNewsRefresh > 10 * 60_000) c.refreshNews()
            if (section == Section.AI) runCatching { c.news.refreshTrendingIfStale() }
        }
    }

    fun refresh(onMessage: (String) -> Unit) = viewModelScope.launch {
        c.refreshNews()?.let(onMessage)
        if (section == Section.AI) runCatching { c.news.refreshTrendingIfStale(force = true) }
    }

    fun toggleBookmark(a: ArticleEntity) = viewModelScope.launch { c.news.setBookmarked(a.id, !a.bookmarked) }
    fun markRead(a: ArticleEntity) = viewModelScope.launch { c.news.markRead(a.id) }
}

data class NewsUi(
    val loaded: Boolean = false,
    val clusters: List<StoryCluster> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val lastRefresh: Long = 0,
    val hasAi: Boolean = false,
    val profile: Profile = Profile(),
    val refreshing: Boolean = false,
    val note: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    vm: NewsViewModel,
    listState: LazyListState,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessage: (String) -> Unit,
    radarVm: RadarViewModel? = null,
    onOpenRadar: () -> Unit = {},
) {
    val ui by vm.state.collectAsState()
    val topic by vm.topic.collectAsState()
    val trending by vm.trending.collectAsState()
    val context = LocalContext.current
    val isAi = vm.section == Section.AI
    val accent = if (isAi) Accents.ai else Accents.tech
    var sheetCluster by remember { mutableStateOf<StoryCluster?>(null) }
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()

    fun open(a: ArticleEntity) {
        vm.markRead(a)
        Browser.open(context, a.url, toolbar)
    }

    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = { vm.refresh(onMessage) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                NewsHeader(
                    title = if (isAi) "AI & Data" else "Tech Pulse",
                    accent = accent,
                    profile = ui.profile,
                    lastRefresh = ui.lastRefresh,
                    total = ui.total,
                    refreshing = ui.refreshing,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings,
                )
            }
            item(key = "chips") {
                ChipRow(
                    options = if (isAi) Feeds.aiTopics else Feeds.techTopics,
                    selected = setOf(topic),
                    accent = accent,
                    counts = ui.counts,
                    onToggle = { vm.topic.value = it },
                )
            }

            if (isAi && radarVm != null && topic == "All") {
                item(key = "radar") { RadarBlock(radarVm, onOpenRadar) }
            }
            if (isAi && trending.isNotEmpty() && topic == "All") {
                item(key = "trending") { TrendingBlock(trending, accent) { Browser.open(context, it, toolbar) } }
                item(key = "latest-label") { SectionLabel("Latest from the field") }
            }

            when {
                !ui.loaded || (ui.total == 0 && ui.refreshing) -> items(4, key = { "sk$it" }) { SkeletonCard() }
                ui.clusters.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.Newspaper,
                        if (ui.total == 0) "Fetching the latest" else "Nothing in $topic yet",
                        if (ui.total == 0) "Pull down to refresh. Stories come only from vetted outlets, so it can take a moment."
                        else "New stories arrive every 15 minutes.",
                        accent,
                    )
                }
                else -> {
                    val list = ui.clusters
                    val heroIndex = list.take(3).indexOfFirst { it.primary.imageUrl != null }.takeIf { it >= 0 && topic == "All" }
                    if (heroIndex != null) {
                        val hero = list[heroIndex]
                        item(key = "hero-" + hero.primary.id) {
                            HeroCard(
                                hero, accent,
                                onClick = { open(hero.primary) },
                                onBookmark = { vm.toggleBookmark(hero.primary) },
                                onShare = { Browser.share(context, hero.primary.title, hero.primary.url) },
                                onCluster = { sheetCluster = hero },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    itemsIndexed(list.filterIndexed { i, _ -> i != heroIndex }, key = { _, c -> c.primary.id }) { _, cl ->
                        NewsCard(
                            cl, accent,
                            onClick = { open(cl.primary) },
                            onBookmark = { vm.toggleBookmark(cl.primary) },
                            onShare = { Browser.share(context, cl.primary.title, cl.primary.url) },
                            onCluster = { sheetCluster = cl },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    sheetCluster?.let { cl ->
        CoverageSheet(cl, onDismiss = { sheetCluster = null }, onOpen = { open(it) })
    }
}

@Composable
fun NewsHeader(
    title: String,
    accent: Accent,
    profile: Profile,
    lastRefresh: Long,
    total: Int,
    refreshing: Boolean,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Burning the midnight oil"
    }
    Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (profile.firstName.isNotBlank()) "$greeting, ${profile.firstName}" else greeting,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProfileButton(profile, accent, onOpenProfile)
        }
        GradientText(title, accent, MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(4.dp))
            val status = when {
                refreshing -> "Refreshing…"
                lastRefresh == 0L -> "Connecting to sources"
                else -> "Updated ${Text.timeAgo(lastRefresh)}"
            }
            Text(
                "$status  ·  $total stories  ·  auto-refresh 15 min",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun ProfileButton(profile: Profile, accent: Accent, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp).size(40.dp).clip(CircleShape).background(accent.brush).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val initials = profile.fullName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        if (initials.isNotBlank()) Text(initials, color = Color.White, style = MaterialTheme.typography.labelLarge)
        else Icon(Icons.Rounded.Person, "Profile", tint = Color.White)
    }
}

@Composable
private fun AiHint(accent: Accent, onOpenSettings: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onOpenSettings, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Turn on AI summaries", style = MaterialTheme.typography.titleSmall)
                Text(
                    "You're seeing publisher excerpts. Add a free Gemini key for 50-word summaries & “why it matters”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun NewsCard(
    cl: StoryCluster,
    accent: Accent,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    onCluster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = cl.primary
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceLine(a.sourceDomain, a.source, Text.timeAgo(a.publishedAt), Modifier.weight(1f))
                BookmarkButton(a.bookmarked, onBookmark)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(end = 8.dp)) {
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (a.read) 0.6f else 1f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (a.imageUrl != null) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = a.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
            SummaryBlock(a, accent)
            CardFooter(cl, accent, onShare, onCluster)
        }
    }
}

@Composable
private fun SummaryBlock(a: ArticleEntity, accent: Accent) {
    if (!a.summary.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            a.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    if (!a.whyItMatters.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .padding(end = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(accent.start.copy(alpha = 0.12f), accent.end.copy(alpha = 0.06f))))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = accent.start, modifier = Modifier.size(15.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(a.whyItMatters, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CardFooter(cl: StoryCluster, accent: Accent, onShare: () -> Unit, onCluster: () -> Unit) {
    val a = cl.primary
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(a.topic)
        if (cl.others.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Pill(
                "+${cl.others.size} outlet${if (cl.others.size > 1) "s" else ""}",
                icon = Icons.Rounded.Layers,
                container = accent.start.copy(alpha = 0.12f),
                content = accent.start,
                onClick = onCluster,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when (a.summaryKind) {
                SummaryKind.AI -> "AI summary"
                SummaryKind.ON_DEVICE -> "Key sentences · on-device"
                else -> "Excerpt"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Share, "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
fun HeroCard(
    cl: StoryCluster,
    accent: Accent,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    onCluster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = cl.primary
    AppCard(modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onClick, shape = RoundedCornerShape(28.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                AsyncImage(
                    model = a.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))
                    )
                )
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).background(accent.horizontal).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.TrendingUp, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("TOP STORY", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text(
                        "${a.source}  ·  ${Text.timeAgo(a.publishedAt)}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceLine(a.sourceDomain, a.source, a.topic, Modifier.weight(1f))
                    BookmarkButton(a.bookmarked, onBookmark)
                }
                SummaryBlock(a, accent)
                CardFooter(cl, accent, onShare = onShare, onCluster = onCluster)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverageSheet(cl: StoryCluster, onDismiss: () -> Unit, onOpen: (ArticleEntity) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Full coverage", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${cl.all.size} outlets reported this story — independent coverage is a good sign it's accurate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            cl.all.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SourceLine(a.sourceDomain, a.source, Text.timeAgo(a.publishedAt))
                        Spacer(Modifier.height(4.dp))
                        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------- Trending (AI tab) ----------------
@Composable
private fun TrendingBlock(items: List<TrendingEntity>, accent: Accent, onOpen: (String) -> Unit) {
    val kinds = listOf("models" to "Models", "papers" to "Papers", "repos" to "Repos").filter { k -> items.any { it.kind == k.first } }
    if (kinds.isEmpty()) return
    var kind by rememberSaveable { mutableStateOf(kinds.first().first) }
    Column {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TrendingUp, null, tint = accent.start, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Trending now", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)
            ) {
                kinds.forEach { (k, label) ->
                    val sel = k == kind
                    Box(
                        Modifier.clip(CircleShape)
                            .then(if (sel) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                            .clickable { kind = k }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Crossfade(kind, label = "trend") { k ->
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items.filter { it.kind == k }, key = { it.kind + it.id }) { t ->
                    AppCard(Modifier.width(250.dp).height(148.dp), onClick = { onOpen(t.url) }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(accent.brush),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${t.rank + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                                Spacer(Modifier.width(8.dp))
                                Text(t.metric, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

