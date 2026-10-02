package com.technewz.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.AiFeatures
import com.technewz.app.data.AppJson
import com.technewz.app.data.AppStatus
import com.technewz.app.data.ApplicationEntity
import com.technewz.app.data.JobEntity
import com.technewz.app.data.Profile
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.CompanyAvatar
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.MatchRing
import com.technewz.app.ui.components.Pill
import com.technewz.app.ui.theme.Accents
import com.technewz.app.ui.theme.LocalExtra
import com.technewz.app.util.Browser
import com.technewz.app.util.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.URLEncoder

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class JobDetailViewModel(private val c: AppContainer, val jobId: String) : ViewModel() {
    val job: StateFlow<JobEntity?> = c.db.jobs().observe(jobId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val app: StateFlow<ApplicationEntity?> = c.db.applications().observe(jobId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val profile: StateFlow<Profile> = c.settings.profile.stateIn(viewModelScope, SharingStarted.Eagerly, Profile())
    val hasAi: StateFlow<Boolean> = MutableStateFlow(false).also { f -> viewModelScope.launch { c.settings.settings.collect { f.value = it.hasAi } } }

    val analysis = MutableStateFlow<Load<AiFeatures.Analysis>>(Load.Idle)
    val kit = MutableStateFlow<Load<AiFeatures.ApplyKit>>(Load.Idle)
    val coverLetter = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val j = c.db.jobs().get(jobId)
            j?.aiAnalysis?.let { json ->
                runCatching { AppJson.decodeFromString<AiFeatures.Analysis>(json) }.getOrNull()?.let { analysis.value = Load.Done(it) }
            }
            c.db.applications().get(jobId)?.coverLetter?.let { coverLetter.value = it }
        }
    }

    fun analyze() = viewModelScope.launch {
        val j = job.value ?: return@launch
        analysis.value = Load.Loading
        analysis.value = runCatching { c.ai.analyzeMatch(c.settings.currentProfile(), j) }.fold(
            onSuccess = { a -> c.db.jobs().setAnalysis(jobId, AppJson.encodeToString(a)); Load.Done(a) },
            onFailure = { Load.Failed(it.message ?: "AI request failed") },
        )
    }

    fun generateKit() = viewModelScope.launch {
        val j = job.value ?: return@launch
        kit.value = Load.Loading
        kit.value = runCatching { c.ai.applyKit(c.settings.currentProfile(), j) }.fold(
            onSuccess = { k -> if (k.coverLetter.isNotBlank()) coverLetter.value = k.coverLetter; Load.Done(k) },
            onFailure = { Load.Failed(it.message ?: "AI request failed") },
        )
    }

    fun setStatus(status: String) = viewModelScope.launch {
        val j = job.value ?: return@launch
        c.jobs.setStatus(j, status)
        if (status == AppStatus.APPLIED && coverLetter.value.isNotBlank()) c.jobs.saveCoverLetter(jobId, coverLetter.value)
    }

    fun untrack() = viewModelScope.launch { c.jobs.removeApplication(jobId) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobDetailScreen(vm: JobDetailViewModel, onBack: () -> Unit, onOpenProfile: () -> Unit) {
    val job by vm.job.collectAsState()
    val app by vm.app.collectAsState()
    val profile by vm.profile.collectAsState()
    val hasAi by vm.hasAi.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val context = LocalContext.current
    val extra = LocalExtra.current
    val accent = Accents.jobs
    var showApply by rememberSaveable { mutableStateOf(false) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    var askSubmitted by rememberSaveable { mutableStateOf(false) }
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()

    // When the user comes back from the employer's site, ask whether they submitted.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                askSubmitted = true
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val j = job
    if (j == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("This listing is no longer available.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val matched = j.matchedSkills.split('|').filter { it.isNotBlank() }
    val missing = j.missingSkills.split('|').filter { it.isNotBlank() }
    val flags = j.scamFlags.split('|').filter { it.isNotBlank() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- Hero header ----
            Box(
                Modifier.fillMaxWidth().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Accents.forName(j.company).start.copy(alpha = 0.22f), MaterialTheme.colorScheme.background)
                    )
                )
            ) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { Browser.share(context, "${j.title} at ${j.company}", j.url) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { Browser.open(context, j.url, toolbar) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open posting") }
                    }
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        CompanyAvatar(j.company, 64.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(j.title, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(j.company, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (j.directFromEmployer) {
                                Spacer(Modifier.width(8.dp))
                                Pill("Direct from employer", icon = Icons.Rounded.Verified, container = extra.successContainer, content = extra.success)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(j.location, icon = if (j.isRemote) Icons.Rounded.Public else Icons.Rounded.LocationOn)
                            Pill(j.employmentType, icon = if (j.isInternship) Icons.Rounded.School else null)
                            j.salary?.let { Pill(it, icon = Icons.Rounded.Payments, container = extra.successContainer, content = extra.success) }
                            Pill(postedLabel(j).replaceFirstChar { it.uppercase() })
                            Pill("via ${j.source}")
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (flags.isNotEmpty()) {
                    AppCard(Modifier.fillMaxWidth(), color = extra.warningContainer) {
                        Row(Modifier.padding(16.dp)) {
                            Icon(Icons.Rounded.Warning, null, tint = extra.warning)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Be careful: ${flags.joinToString(", ").lowercase()}. Legitimate employers never ask you to pay or to move to a messaging app.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // ---- Match card ----
                AppCard(Modifier.fillMaxWidth().animateContentSize()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shownScore = (analysis as? Load.Done)?.value?.score ?: j.matchScore
                            MatchRing(if (profile.skills.isEmpty()) null else shownScore, size = 64.dp, stroke = 6.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Your match", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    when {
                                        profile.skills.isEmpty() -> "Add your resume to see how you fit."
                                        analysis is Load.Done -> "AI fit score based on your full resume"
                                        matched.isEmpty() && missing.isEmpty() -> "This posting doesn't list specific skills."
                                        else -> "You have ${matched.size} of ${matched.size + missing.size} skills this role mentions"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (profile.skills.isEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onOpenProfile) { Text("Add resume & skills") }
                        }
                        if (matched.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("YOU HAVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                matched.forEach { Pill(it, icon = Icons.Rounded.Check, container = extra.successContainer, content = extra.success) }
                            }
                        }
                        if (missing.isNotEmpty() && profile.skills.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("SKILL GAPS · TAP TO LEARN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                missing.forEach { skill ->
                                    Box(
                                        Modifier.clip(CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                            .clickable { Browser.open(context, learnUrl(skill), toolbar) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) { Text(skill, style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }

                        // AI deep analysis
                        Spacer(Modifier.height(16.dp))
                        when (val a = analysis) {
                            is Load.Done -> AnalysisView(a.value)
                            is Load.Failed -> Text(a.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else -> Unit
                        }
                        if (analysis !is Load.Done && profile.skills.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            GradientButton(
                                if (hasAi) "Deep match analysis" else "Add Gemini key for AI analysis",
                                Accents.ai,
                                Modifier.fillMaxWidth(),
                                icon = Icons.Rounded.AutoAwesome,
                                enabled = hasAi,
                                loading = analysis is Load.Loading,
                                onClick = { vm.analyze() },
                            )
                        }
                    }
                }

                // ---- Description ----
                var expanded by rememberSaveable { mutableStateOf(false) }
                AppCard(Modifier.fillMaxWidth().animateContentSize()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("About the role", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        val desc = j.description.ifBlank { "The full description is on the employer's page." }
                        Text(
                            if (expanded || desc.length < 900) desc else desc.take(900).trimEnd() + "…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (desc.length >= 900) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Show less" else "Read full description")
                                Icon(Icons.Rounded.ExpandMore, null)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(110.dp))
            }
        }

        // ---- Sticky action bar ----
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusButton(app, onSet = vm::setStatus, onRemove = vm::untrack)
            Spacer(Modifier.width(12.dp))
            GradientButton(
                if (app?.status == AppStatus.APPLIED) "Open application" else "Apply",
                accent,
                Modifier.weight(1f),
                icon = Icons.Rounded.RocketLaunch,
                onClick = { showApply = true },
            )
        }
    }

    if (showApply) {
        ApplySheet(
            vm = vm,
            job = j,
            profile = profile,
            hasAi = hasAi,
            onDismiss = { showApply = false },
            onOpenProfile = { showApply = false; onOpenProfile() },
            onGo = {
                showApply = false
                awaitingReturn = true
                Browser.open(context, j.url, toolbar)
            },
        )
    }

    if (askSubmitted) {
        AlertDialog(
            onDismissRequest = { askSubmitted = false },
            title = { Text("Did you submit it?") },
            text = { Text("If you applied for ${j.title} at ${j.company}, we'll add it to your tracker and remind you to follow up in a week.") },
            confirmButton = {
                TextButton(onClick = { vm.setStatus(AppStatus.APPLIED); askSubmitted = false }) { Text("Yes, I applied") }
            },
            dismissButton = {
                TextButton(onClick = { if (app == null) vm.setStatus(AppStatus.SAVED); askSubmitted = false }) { Text("Not yet") }
            },
        )
    }
}

fun learnUrl(skill: String): String =
    "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$skill full course for beginners", "UTF-8")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisView(a: AiFeatures.Analysis) {
    val extra = LocalExtra.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Accents.ai.start.copy(alpha = 0.08f)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Accents.ai.start, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("AI analysis", style = MaterialTheme.typography.labelLarge, color = Accents.ai.start)
        }
        Spacer(Modifier.height(6.dp))
        Text(a.verdict, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        if (a.eligibility.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Eligibility: ${a.eligibility}", style = MaterialTheme.typography.bodySmall, color = extra.warning)
        }
        Bullets("Strengths", a.strengths)
        Bullets("Gaps", a.gaps)
        Bullets("To improve your chances", a.tips)
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach {
        Row(Modifier.padding(top = 4.dp)) {
            Text("•  ", style = MaterialTheme.typography.bodySmall)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusButton(app: ApplicationEntity?, onSet: (String) -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(54.dp).clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .clickable { if (app == null) onSet(AppStatus.SAVED) else open = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (app == null) Icons.Rounded.BookmarkAdd else Icons.Rounded.Check, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(app?.status ?: "Save", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AppStatus.all.forEach { s ->
                DropdownMenuItem(text = { Text(s) }, onClick = { onSet(s); open = false })
            }
            DropdownMenuItem(text = { Text("Remove from tracker", color = MaterialTheme.colorScheme.error) }, onClick = { onRemove(); open = false })
        }
    }
}

// ------------------------------------------------------------------
// Assisted apply: everything prepared, the user stays in control.
// ------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ApplySheet(
    vm: JobDetailViewModel,
    job: JobEntity,
    profile: Profile,
    hasAi: Boolean,
    onDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
    onGo: () -> Unit,
) {
    val context = LocalContext.current
    val kit by vm.kit.collectAsState()
    val letter by vm.coverLetter.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)
        ) {
            Text("Assisted apply", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Everything you need, ready to paste. You review and submit on ${job.company}'s page — nothing is sent without you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Step(1, "Your details", "Tap any field to copy it")
            if (profile.isEmpty) {
                TextButton(onClick = onOpenProfile) { Text("Add your resume to fill these in") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Name" to profile.fullName, "Email" to profile.email, "Phone" to profile.phone,
                        "Location" to profile.location, "LinkedIn" to profile.linkedin, "GitHub" to profile.github,
                        "Portfolio" to profile.portfolio,
                    ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                        CopyChip(label, value) { Browser.copy(context, label, value) }
                    }
                }
            }

            Step(2, "Cover letter & answers", if (hasAi) "Tailored to this role from your real resume" else "Add a Gemini key in Settings to generate")
            when (val k = kit) {
                is Load.Failed -> Text(k.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            if (letter.isNotBlank()) {
                OutlinedTextField(
                    value = letter, onValueChange = { vm.coverLetter.value = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { Browser.copy(context, "Cover letter", letter) }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy letter")
                    }
                    TextButton(onClick = { vm.generateKit() }, enabled = hasAi && kit !is Load.Loading) { Text("Regenerate") }
                }
            }
            (kit as? Load.Done)?.value?.answers?.forEach { qa ->
                AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { Browser.copy(context, "Answer", qa.a) }, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(qa.q, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(qa.a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (letter.isBlank()) {
                GradientButton(
                    "Generate cover letter", Accents.ai, Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoAwesome,
                    enabled = hasAi && !profile.isEmpty, loading = kit is Load.Loading, onClick = { vm.generateKit() },
                )
            }

            Step(3, "Resume", if (profile.resumeFileName.isNotBlank()) profile.resumeFileName else "No resume added yet")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Use the form's “Upload resume” button and pick your PDF from Files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
            }
            val resumeFile = File(context.filesDir, "resume/resume.pdf")
            if (resumeFile.exists()) {
                TextButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", resumeFile)
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share resume"))
                }) { Text("Share resume PDF (for email applications)") }
            }

            Step(4, "Submit on the employer's site", "We'll ask when you're back so your tracker stays accurate")
            Spacer(Modifier.height(4.dp))
            GradientButton("Open application page", Accents.jobs, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = onGo)
            AnimatedVisibility(visible = !job.directFromEmployer) {
                Text(
                    "This listing is via ${job.source}; it will redirect you to the employer's application.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, subtitle: String) {
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Accents.jobs.brush), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CopyChip(label: String, value: String, onClick: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onClick(); copied = true }.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(12.dp), tint = if (copied) LocalExtra.current.success else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value.take(36) + if (value.length > 36) "…" else "", style = MaterialTheme.typography.labelLarge)
    }
}
