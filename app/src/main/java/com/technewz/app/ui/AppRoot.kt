package com.technewz.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.technewz.app.AppContainer
import com.technewz.app.data.AppSettings
import com.technewz.app.data.Section
import com.technewz.app.ui.screens.JobDetailScreen
import com.technewz.app.ui.screens.JobDetailViewModel
import com.technewz.app.ui.screens.JobsScreen
import com.technewz.app.ui.screens.JobsViewModel
import com.technewz.app.ui.screens.NewsScreen
import com.technewz.app.ui.screens.NewsViewModel
import com.technewz.app.ui.screens.OnboardingScreen
import com.technewz.app.ui.screens.ProfileScreen
import com.technewz.app.ui.screens.ProfileViewModel
import com.technewz.app.ui.screens.SavedScreen
import com.technewz.app.ui.screens.SavedViewModel
import com.technewz.app.ui.screens.SettingsScreen
import com.technewz.app.ui.screens.SettingsViewModel
import com.technewz.app.ui.screens.TrackerScreen
import com.technewz.app.ui.screens.TrackerViewModel
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.ui.theme.LocalExtra
import com.technewz.app.ui.theme.TechNewzTheme
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class Tab(val label: String, val icon: ImageVector, val accent: Accent)

private val tabs = listOf(
    Tab("Pulse", Icons.Rounded.Bolt, Accents.tech),
    Tab("AI & Data", Icons.Rounded.AutoAwesome, Accents.ai),
    Tab("Jobs", Icons.Rounded.WorkOutline, Accents.jobs),
    Tab("Tracker", Icons.Rounded.ViewKanban, Accents.tracker),
    Tab("Saved", Icons.Rounded.Bookmarks, Accents.saved),
)

private data class RootState(val theme: com.technewz.app.data.ThemeMode, val onboarded: Boolean)

@Composable
fun AppRoot(c: AppContainer, pendingRoute: String?, onRouteConsumed: () -> Unit) {
    // Only the two values the root needs: routine settings writes during a refresh (timestamps etc.)
    // must not redraw the whole app.
    val rootState by remember {
        c.settings.settings.map { RootState(it.theme, it.onboarded) }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val s = rootState
    TechNewzTheme(s?.theme ?: com.technewz.app.data.ThemeMode.SYSTEM) {
        // Keep status/navigation bar icons readable when the in-app theme differs from the system theme.
        val view = androidx.compose.ui.platform.LocalView.current
        var showIntro by rememberSaveable { mutableStateOf(true) }
        // The intro is always dark, so the system bar icons are light while it shows.
        val lightBars = !com.technewz.app.ui.theme.LocalExtra.current.isDark && !showIntro
        if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // The app loads underneath while the intro plays.
            if (s != null) AppNav(c, s, pendingRoute, onRouteConsumed)
            AnimatedVisibility(showIntro, enter = fadeIn(tween(0)), exit = fadeOut(tween(350))) {
                IntroScreen { showIntro = false }
            }
        }
    }
}

@Composable
private fun AppNav(c: AppContainer, s: RootState, pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val start = remember { if (s.onboarded) "home" else "onboarding" }

    NavHost(
        nav, startDestination = start,
        enterTransition = { slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(250)) },
        popExitTransition = { slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280)) },
    ) {
        composable("onboarding") {
            OnboardingScreen(c) { openProfile ->
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                if (openProfile) nav.navigate("profile")
            }
        }
        composable("home") {
            HomeScreen(
                c, pendingRoute, onRouteConsumed,
                onOpenJob = { id -> nav.navigate("job/" + URLEncoder.encode(id, "UTF-8")) },
                onOpenProfile = { nav.navigate("profile") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenRadar = { nav.navigate("radar") },
            )
        }
        composable("radar") {
            val vm = viewModel { com.technewz.app.ui.screens.RadarViewModel(c) }
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                com.technewz.app.ui.screens.RadarScreen(vm, onBack = { nav.popBackStack() }) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
        composable("job/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = URLDecoder.decode(entry.arguments?.getString("id").orEmpty(), "UTF-8")
            val vm = viewModel(key = "job-$id") { JobDetailViewModel(c, id) }
            JobDetailScreen(vm, onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate("profile") })
        }
        composable("profile") {
            val vm = viewModel { ProfileViewModel(c, context.applicationContext) }
            ProfileScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            val vm = viewModel { SettingsViewModel(c, context.applicationContext) }
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun HomeScreen(
    c: AppContainer,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRadar: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onMessage: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    LaunchedEffect(pendingRoute) {
        when (pendingRoute) {
            "news" -> tab = 0
            "jobs" -> tab = 2
            "tracker" -> tab = 3
            "radar" -> { tab = 1; onOpenRadar() }
        }
        if (pendingRoute != null) onRouteConsumed()
    }

    val techVm = viewModel(key = "news-tech") { NewsViewModel(c, Section.TECH) }
    val aiVm = viewModel(key = "news-ai") { NewsViewModel(c, Section.AI) }
    val jobsVm = viewModel { JobsViewModel(c) }
    val trackerVm = viewModel { TrackerViewModel(c) }
    val savedVm = viewModel { SavedViewModel(c) }
    val radarVm = viewModel { com.technewz.app.ui.screens.RadarViewModel(c) }
    val states = List(5) { rememberLazyListState() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(280)) { dir * it / 10 } + fadeIn(tween(280))) togetherWith fadeOut(tween(160))
            },
            label = "tabs",
        ) { t ->
            when (t) {
                0 -> NewsScreen(techVm, states[0], onOpenProfile, onOpenSettings, onMessage)
                1 -> NewsScreen(aiVm, states[1], onOpenProfile, onOpenSettings, onMessage, radarVm, onOpenRadar)
                2 -> JobsScreen(jobsVm, states[2], onOpenJob, onOpenProfile, onOpenSettings, onMessage)
                3 -> TrackerScreen(trackerVm, states[3], onOpenJob)
                else -> SavedScreen(savedVm, states[4])
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            SnackbarHost(snackbar, Modifier.padding(bottom = 84.dp)) { data ->
                Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.inverseSurface)
            }
        }
        // Soft fade so content scrolling under the floating bar stays legible.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.92f)))
            )
        )
        BottomBar(
            selected = tab,
            onSelect = { i ->
                if (i == tab) scope.launch { states[i].animateScrollToItem(0) } else tab = i
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Floating glass pill navigation with gradient selection that grows to show the label. */
@Composable
internal fun BottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val extra = LocalExtra.current
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (extra.isDark) 0.94f else 0.97f))
                .border(1.dp, extra.cardBorder, CircleShape)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
                Row(
                    Modifier
                        .clip(CircleShape)
                        .then(if (sel) Modifier.background(t.accent.horizontal) else Modifier)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .height(48.dp)
                        .padding(horizontal = if (sel) 16.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(22.dp))
                    AnimatedVisibility(sel, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Row {
                            Spacer(Modifier.width(7.dp))
                            Text(t.label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
