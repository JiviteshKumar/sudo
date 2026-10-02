package com.technewz.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technewz.app.AppContainer
import com.technewz.app.data.Profile
import com.technewz.app.ui.components.AppCard
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.components.SectionLabel
import com.technewz.app.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileViewModel(private val c: AppContainer, private val appContext: android.content.Context) : ViewModel() {
    val draft = MutableStateFlow(Profile())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val hasAi = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            draft.value = c.settings.currentProfile()
            hasAi.value = c.settings.current().hasAi
        }
    }

    fun update(transform: (Profile) -> Profile) { draft.value = transform(draft.value) }

    fun importResume(uri: Uri) = viewModelScope.launch {
        busy.value = true
        message.value = null
        try {
            val (bytes, name) = withContext(Dispatchers.IO) {
                val resolver = appContext.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "resume.pdf"
                val data = resolver.openInputStream(uri)!!.use { it.readBytes() }
                require(data.size < 8_000_000) { "That file is larger than 8 MB." }
                val dir = File(appContext.filesDir, "resume").apply { mkdirs() }
                File(dir, "resume.pdf").writeBytes(data)
                data to name
            }
            draft.value = draft.value.copy(resumeFileName = name)
            if (c.settings.current().hasAi) {
                val parsed = c.ai.parseResume(bytes, name)
                draft.value = merge(draft.value, parsed)
                message.value = "Filled in from your resume — please review before saving."
            } else {
                message.value = "Resume saved. Add a Gemini key in Settings to auto-fill, or fill in the fields below."
            }
        } catch (e: Exception) {
            message.value = "Couldn't read the resume: ${e.message}"
        } finally {
            busy.value = false
        }
    }

    private fun merge(old: Profile, new: Profile) = Profile(
        fullName = new.fullName.ifBlank { old.fullName }, email = new.email.ifBlank { old.email },
        phone = new.phone.ifBlank { old.phone }, location = new.location.ifBlank { old.location },
        headline = new.headline.ifBlank { old.headline }, linkedin = new.linkedin.ifBlank { old.linkedin },
        github = new.github.ifBlank { old.github }, portfolio = new.portfolio.ifBlank { old.portfolio },
        summary = new.summary.ifBlank { old.summary },
        skills = (new.skills + old.skills).distinctBy { it.lowercase() },
        education = new.education.ifEmpty { old.education }, experience = new.experience.ifEmpty { old.experience },
        projects = new.projects.ifEmpty { old.projects }, resumeFileName = new.resumeFileName.ifBlank { old.resumeFileName },
    )

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true
        c.settings.saveProfile(draft.value.copy(skills = draft.value.skills.map { it.trim() }.filter { it.isNotEmpty() }))
        c.jobs.rescore()
        busy.value = false
        onDone()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val p by vm.draft.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val hasAi by vm.hasAi.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importResume) }
    val accent = Accents.ai

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                GradientText("Your profile", accent, MaterialTheme.typography.displaySmall)
                Text(
                    "Used for match scores, “why it matters” and assisted apply. Stored only on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                AppCard(Modifier.fillMaxWidth(), onClick = { if (!busy) picker.launch(arrayOf("application/pdf")) }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
                            Icon(if (p.resumeFileName.isBlank()) Icons.Rounded.UploadFile else Icons.Rounded.Description, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (p.resumeFileName.isBlank()) "Upload your resume" else p.resumeFileName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (busy) "Reading your resume…" else if (hasAi) "PDF · we'll fill in everything below with AI" else "PDF · tap to replace",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else if (hasAi) Icon(Icons.Rounded.AutoAwesome, null, tint = accent.start)
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel("Contact")
            FieldGroup {
                Field("Full name", p.fullName) { v -> vm.update { it.copy(fullName = v) } }
                Field("Email", p.email, KeyboardType.Email) { v -> vm.update { it.copy(email = v) } }
                Field("Phone", p.phone, KeyboardType.Phone) { v -> vm.update { it.copy(phone = v) } }
                Field("City, Country", p.location) { v -> vm.update { it.copy(location = v) } }
                Field("Headline (e.g. CS undergrad · ML enthusiast)", p.headline) { v -> vm.update { it.copy(headline = v) } }
            }
            SectionLabel("Links & portfolio")
            FieldGroup {
                Field("Portfolio website", p.portfolio, KeyboardType.Uri) { v -> vm.update { it.copy(portfolio = v) } }
                Field("LinkedIn URL", p.linkedin, KeyboardType.Uri) { v -> vm.update { it.copy(linkedin = v) } }
                Field("GitHub URL", p.github, KeyboardType.Uri) { v -> vm.update { it.copy(github = v) } }
            }
            SectionLabel("Skills")
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.skills.forEach { s ->
                        Row(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { vm.update { it.copy(skills = it.skills - s) } }
                                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Close, "Remove", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                var newSkill by remember { mutableStateOf("") }
                val add = {
                    val items = newSkill.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (items.isNotEmpty()) vm.update { it.copy(skills = (it.skills + items).distinctBy { s -> s.lowercase() }) }
                    newSkill = ""
                }
                OutlinedTextField(
                    newSkill, { newSkill = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Add skills, comma separated") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    trailingIcon = { IconButton(onClick = add) { Icon(Icons.Rounded.Add, "Add") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
            }
            SectionLabel("Background (one per line)")
            FieldGroup {
                Field("Summary", p.summary, lines = 3) { v -> vm.update { it.copy(summary = v) } }
                Field("Education", p.education.joinToString("\n"), lines = 3) { v -> vm.update { it.copy(education = v.lines()) } }
                Field("Experience", p.experience.joinToString("\n"), lines = 4) { v -> vm.update { it.copy(experience = v.lines()) } }
                Field("Projects", p.projects.joinToString("\n"), lines = 4) { v -> vm.update { it.copy(projects = v.lines()) } }
            }
            Spacer(Modifier.height(110.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .navigationBarsPadding().padding(16.dp)
        ) {
            GradientButton("Save profile", accent, Modifier.fillMaxWidth(), loading = busy) {
                vm.update { it.copy(education = it.education.filter(String::isNotBlank), experience = it.experience.filter(String::isNotBlank), projects = it.projects.filter(String::isNotBlank)) }
                vm.save(onBack)
            }
        }
    }
}

@Composable
fun FieldGroup(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
