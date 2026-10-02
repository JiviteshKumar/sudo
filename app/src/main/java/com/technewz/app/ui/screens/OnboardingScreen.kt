package com.technewz.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.technewz.app.AppContainer
import com.technewz.app.ui.components.GradientButton
import com.technewz.app.ui.components.GradientText
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.util.Browser
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(c: AppContainer, onFinish: (openProfile: Boolean) -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf(listOf("OpenAI", "Google", "Internship")) }
    val toolbar = MaterialTheme.colorScheme.surface.toArgb()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun finish(openProfile: Boolean) {
        scope.launch {
            c.settings.update { it.copy(onboarded = true, geminiKey = key.trim().ifBlank { it.geminiKey }, keywords = keywords) }
            // Never let the permission prompt block finishing onboarding.
            if (Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { onFinish(openProfile) }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = true) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                when (page) {
                    0 -> {
                        PromptLogo()
                        Spacer(Modifier.height(28.dp))
                        GradientText("Tech, distilled.", Accents.tech, MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "What's happening in tech, AI and data — verified sources, 50-word summaries, refreshed every 15 minutes. Plus jobs that actually fit you.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(28.dp))
                        Feature(Icons.Rounded.Verified, Accents.tech, "Only trusted sources", "Major outlets & official lab blogs. Every story links to the original.")
                        Feature(Icons.Rounded.AutoAwesome, Accents.ai, "AI & Data section", "Research, releases, trending models, papers and repos.")
                        Feature(Icons.Rounded.WorkOutline, Accents.jobs, "Jobs & internships", "Remote and near you, scored against your resume.")
                        Feature(Icons.Rounded.RocketLaunch, Accents.tracker, "Assisted apply", "Tailored cover letter, one-tap copy, built-in tracker.")
                    }
                    1 -> {
                        IconBadge(Icons.Rounded.AutoAwesome, Accents.ai)
                        Spacer(Modifier.height(22.dp))
                        GradientText("Switch on AI", Accents.ai, MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Summaries are made on your phone by our own model, using only the publisher's sentences. A free Google Gemini key adds resume reading, match analysis and cover letters.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key (optional)") },
                            singleLine = true, shape = RoundedCornerShape(16.dp), visualTransformation = PasswordVisualTransformation(),
                        )
                        TextButton(onClick = { Browser.open(context, "https://aistudio.google.com/apikey", toolbar) }) { Text("Get a free key in 30 seconds →") }
                    }
                    else -> {
                        IconBadge(Icons.Rounded.Bolt, Accents.tech)
                        Spacer(Modifier.height(22.dp))
                        GradientText("Never miss it", Accents.tech, MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Get a notification when a headline or a new job mentions something you care about.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        KeywordEditor(
                            keywords,
                            suggestions = listOf("Gemini", "Claude", "NVIDIA", "Apple", "LLM", "Cybersecurity", "Startup funding", "Data Science", "Remote"),
                        ) { keywords = it }
                    }
                }
            }
        }
        // ---- Footer: dots + CTA ----
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(3) { i ->
                    val w by animateDpAsState(if (pager.currentPage == i) 26.dp else 8.dp, label = "dot")
                    Box(
                        Modifier.padding(3.dp).height(8.dp).width(w).clip(CircleShape)
                            .background(if (pager.currentPage == i) Accents.tech.horizontal else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)))
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (pager.currentPage < 2) {
                GradientButton("Continue", Accents.tech, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.ArrowForward) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            } else {
                GradientButton("Add my resume", Accents.jobs, Modifier.fillMaxWidth(), icon = Icons.Rounded.WorkOutline) { finish(openProfile = true) }
                TextButton(onClick = { finish(openProfile = false) }, modifier = Modifier.fillMaxWidth()) { Text("Skip — start reading") }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, accent: Accent, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, accent: Accent) {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

/** Animated brand mark: a terminal prompt ">_" whose cursor blinks, next to the "sudo" wordmark. */
@Composable
private fun PromptLogo() {
    val t = rememberInfiniteTransition(label = "logo")
    val blink by t.animateFloat(1f, 0f, infiniteRepeatable(tween(1060, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Restart), label = "blink")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(Accents.tech.brush), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val chevron = Path().apply {
                    moveTo(w * 0.12f, h * 0.22f); lineTo(w * 0.45f, h * 0.5f); lineTo(w * 0.12f, h * 0.78f)
                }
                drawPath(chevron, Color.White, style = stroke)
                // Cursor: visible for the first half of each cycle, like a real terminal.
                if (blink > 0.5f) drawLine(Color(0xFF67E8F9), Offset(w * 0.58f, h * 0.8f), Offset(w * 0.92f, h * 0.8f), 6.dp.toPx(), StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            "sudo",
            style = MaterialTheme.typography.displayMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
