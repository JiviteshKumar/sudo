package com.technewz.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.AppSettings
import com.technewz.app.data.ThemeMode
import com.technewz.app.net.Feeds
import com.technewz.app.net.JobSources
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.components.SectionLabel
import com.technewz.app.ui.theme.Accents
import com.technewz.app.util.Browser
import com.technewz.app.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsViewModel(private val c: AppContainer, private val appContext: Context) : ViewModel() {
    val draft = MutableStateFlow(AppSettings())
    val testResult = MutableStateFlow<String?>(null)
    val testing = MutableStateFlow(false)
    private var original = AppSettings()
    private var loaded = false

    init {
        viewModelScope.launch { c.settings.current().let { original = it; draft.value = it; loaded = true } }
    }

    fun update(t: (AppSettings) -> AppSettings) { draft.value = t(draft.value) }

    fun save() {
        if (!loaded) return // never overwrite real settings with the placeholder draft
        val d = draft.value
        c.appScope.launch {
            c.settings.update { cur ->
                cur.copy(
                    geminiKey = d.geminiKey, geminiModel = d.geminiModel, adzunaAppId = d.adzunaAppId, adzunaAppKey = d.adzunaAppKey,
                    openAlexKey = d.openAlexKey,
                    city = d.city, countryCode = d.countryCode, keywords = d.keywords, alertsEnabled = d.alertsEnabled,
                    backgroundRefresh = d.backgroundRefresh, theme = d.theme, aiSummaries = d.aiSummaries, greenhouseBoards = d.greenhouseBoards,
                    leverBoards = d.leverBoards, ashbyBoards = d.ashbyBoards,
                )
            }
            if (d.backgroundRefresh != original.backgroundRefresh) RefreshWorker.schedule(appContext, d.backgroundRefresh)
            val sourcesChanged = d.city != original.city || d.countryCode != original.countryCode || d.adzunaAppId != original.adzunaAppId ||
                d.adzunaAppKey != original.adzunaAppKey || d.greenhouseBoards != original.greenhouseBoards ||
                d.leverBoards != original.leverBoards || d.ashbyBoards != original.ashbyBoards
            if (sourcesChanged) c.refreshJobs(force = true)
            if (d.geminiKey != original.geminiKey && d.geminiKey.isNotBlank()) c.refreshNews()
            original = d
        }
    }

    fun testKey() = viewModelScope.launch {
        save()
        testing.value = true
        testResult.value = null
        c.settings.update { it.copy(geminiKey = draft.value.geminiKey, geminiModel = draft.value.geminiModel) }
        testResult.value = runCatching { "✓ Connected · model ${c.ai.testKey()}" }.getOrElse { "✗ ${it.message?.take(160)}" }
        testing.value = false
    }

    @SuppressLint("MissingPermission")
    fun locate(onDone: (String?) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val lm = appContext.getSystemService(LocationManager::class.java)
                val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                    ?: return@runCatching null
                @Suppress("DEPRECATION")
                val addr = Geocoder(appContext, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                    ?: return@runCatching null
                (addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "") to (addr.countryCode ?: "")
            }.getOrNull()
        }
        if (result != null && result.first.isNotBlank()) {
            update { it.copy(city = result.first, countryCode = result.second.lowercase()) }
            onDone(null)
        } else onDone("Couldn't get your location — type your city instead.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.draft.collectAsState()
    val test by vm.testResult.collectAsState()
    val testing by vm.testing.collectAsState()
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()
    var locMsg by remember { mutableStateOf<String?>(null) }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.locate { locMsg = it } else locMsg = "Location permission denied — type your city instead."
    }
    DisposableEffect(Unit) { onDispose { vm.save() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
        GradientText("Settings", Accents.tech, MaterialTheme.typography.displaySmall, Modifier.padding(horizontal = 20.dp))
        Text("Changes save automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(16.dp))

        // ---- AI ----
        SectionLabel("AI (Google Gemini)")
        FieldGroup {
            var show by remember { mutableStateOf(false) }
            OutlinedTextField(
                s.geminiKey, { v -> vm.update { it.copy(geminiKey = v.trim()) } }, Modifier.fillMaxWidth(),
                label = { Text("Gemini API key") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key") } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Browser.open(context, "https://aistudio.google.com/apikey", toolbar) }) { Text("Get a free key") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::testKey, enabled = s.geminiKey.isNotBlank() && !testing) { Text(if (testing) "Testing…" else "Test key") }
            }
            test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) Accents.jobs.start else MaterialTheme.colorScheme.error) }
            Field("Model", s.geminiModel) { v -> vm.update { it.copy(geminiModel = v) } }
            ToggleRow(
                "Rewrite summaries with Gemini",
                "Off: our on-device model picks the article's key sentences — no API, nothing invented. On: Gemini rewrites them and adds “why it matters”.",
                s.aiSummaries,
            ) { v -> vm.update { it.copy(aiSummaries = v) } }
            Hint("Gemini powers resume reading, match analysis and cover letters (and rewritten summaries if enabled). The key stays on this device and is sent only to Google. If the model is retired, the app automatically picks the newest Flash model.")
        }

        // ---- Research sources ----
        SectionLabel("Skills Radar verification")
        FieldGroup {
            Hint("The radar checks every term against OpenAlex (when it took off) and arXiv (is it active now). Without a key, OpenAlex shares a small free daily budget per network — on some mobile networks it can run out, and the radar then pauses until the next day. A free personal key avoids that.")
            Field("OpenAlex API key (optional, free)", s.openAlexKey) { v -> vm.update { it.copy(openAlexKey = v.trim()) } }
            TextButton(onClick = { Browser.open(context, "https://help.openalex.org/api/authentication/", toolbar) }) { Text("How to get a free OpenAlex key") }
        }

        // ---- Location ----
        SectionLabel("Location (for on-site roles)")
        FieldGroup {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("City", s.city) { v -> vm.update { it.copy(city = v) } } }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(96.dp)) { Field("Country", s.countryCode.uppercase()) { v -> vm.update { it.copy(countryCode = v.take(2).lowercase()) } } }
            }
            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.locate { locMsg = it }
                else locPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Use my current location")
            }
            locMsg?.let { Hint(it) }
        }

        // ---- Job sources ----
        SectionLabel("Job sources")
        FieldGroup {
            Hint("Always on: Remotive, Remote OK, The Muse, Arbeitnow and the company career boards below (direct from employer).")
            Text("Adzuna — best for local on-site jobs", style = MaterialTheme.typography.titleSmall)
            Field("Adzuna App ID", s.adzunaAppId) { v -> vm.update { it.copy(adzunaAppId = v) } }
            Field("Adzuna App Key", s.adzunaAppKey) { v -> vm.update { it.copy(adzunaAppKey = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Browser.open(context, "https://developer.adzuna.com/signup", toolbar) }) { Text("Get free Adzuna keys") }
                if (s.countryCode.isNotBlank() && s.countryCode !in JobSources.adzunaCountries) {
                    Text("Adzuna doesn't cover ${s.countryCode.uppercase()} yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Text("Company career boards", style = MaterialTheme.typography.titleSmall)
            Hint("Board names from the company's careers URL, comma separated — e.g. boards.greenhouse.io/<name>, jobs.lever.co/<name>, jobs.ashbyhq.com/<name>.")
            Field("Greenhouse", s.greenhouseBoards, lines = 2) { v -> vm.update { it.copy(greenhouseBoards = v) } }
            Field("Lever", s.leverBoards) { v -> vm.update { it.copy(leverBoards = v) } }
            Field("Ashby", s.ashbyBoards) { v -> vm.update { it.copy(ashbyBoards = v) } }
        }

        // ---- Alerts ----
        SectionLabel("Keyword alerts")
        FieldGroup {
            ToggleRow("Notify me about new matches", "News and jobs whose headline mentions a keyword", s.alertsEnabled) { v -> vm.update { it.copy(alertsEnabled = v) } }
            KeywordEditor(s.keywords) { kws -> vm.update { it.copy(keywords = kws) } }
        }

        // ---- General ----
        SectionLabel("General")
        FieldGroup {
            ToggleRow("Background refresh", "Every 15 min when online (Android may delay this to save battery)", s.backgroundRefresh) { v -> vm.update { it.copy(backgroundRefresh = v) } }
            if (!com.technewz.app.util.Battery.isUnrestricted(context)) {
                TextButton(onClick = { com.technewz.app.util.Battery.requestUnrestricted(context) }) { Text("Allow running in the background (recommended)") }
            }
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
                ThemeMode.entries.forEach { m ->
                    val sel = s.theme == m
                    Box(
                        Modifier.weight(1f).clip(CircleShape)
                            .then(if (sel) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                            .clickable { vm.update { it.copy(theme = m) }; vm.save() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }

        // ---- Diagnostics ----
        var crash by remember { mutableStateOf(com.technewz.app.CrashLog.read(context)) }
        crash?.let { report ->
            SectionLabel("Diagnostics")
            FieldGroup {
                Text("The app crashed last time", style = MaterialTheme.typography.titleSmall)
                Hint(report.lineSequence().take(4).joinToString(System.lineSeparator()))
                Row {
                    TextButton(onClick = { Browser.copy(context, "Crash report", report) }) { Text("Copy crash report") }
                    TextButton(onClick = { com.technewz.app.CrashLog.clear(context); crash = null }) { Text("Clear") }
                }
            }
        }

        // ---- Transparency ----
        SectionLabel("Where your news comes from")
        FieldGroup {
            AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp)) {
                    Hint("Only these established outlets and official lab blogs are used. Every card links to the original article; AI summaries are generated strictly from the article text and are labelled.")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Feeds.all.forEach { f ->
                            com.technewz.app.ui.components.Pill(f.name)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordEditor(keywords: List<String>, suggestions: List<String> = emptyList(), onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keywords.forEach { k ->
            Row(
                Modifier.clip(CircleShape).background(Accents.tech.horizontal).clickable { onChange(keywords - k) }
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Close, "Remove", Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        suggestions.filter { it !in keywords }.forEach { k ->
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onChange(keywords + k) }
                    .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(k, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    var text by remember { mutableStateOf("") }
    val add = {
        val k = text.trim()
        if (k.isNotEmpty() && keywords.none { it.equals(k, true) }) onChange(keywords + k)
        text = ""
    }
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth().padding(top = 4.dp),
        placeholder = { Text("Add keyword, e.g. Gemini, NVIDIA, internship") }, singleLine = true, shape = RoundedCornerShape(16.dp),
        trailingIcon = { IconButton(onClick = add) { Icon(Icons.Rounded.Add, "Add") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
    )
}

