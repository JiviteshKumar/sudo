package com.technewz.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents

private const val TOTAL_MS = 2400f

/**
 * Launch intro (~2.4 s, tap to skip): the ">_" prompt pops in, "sudo" types itself out, the tagline and the
 * three things the app does slide in, and a progress bar sweeps across while the app loads underneath.
 */
@Composable
fun IntroScreen(frozenAtMs: Float? = null, onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (frozenAtMs != null) return@LaunchedEffect // screenshot tests freeze a single frame
        clock.animateTo(TOTAL_MS, tween(TOTAL_MS.toInt(), easing = LinearEasing))
        onDone()
    }
    val t = frozenAtMs ?: clock.value
    fun progress(startMs: Float, durMs: Float) = FastOutSlowInEasing.transform(((t - startMs) / durMs).coerceIn(0f, 1f))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07080D))
            .semantics { contentDescription = "sudo intro" }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        // Two slowly drifting glows behind everything.
        Canvas(Modifier.fillMaxSize()) {
            val drift = t / TOTAL_MS
            drawCircle(
                Brush.radialGradient(listOf(Color(0x664F46E5), Color.Transparent), center = Offset(size.width * (0.2f + 0.15f * drift), size.height * 0.3f), radius = size.width * 0.8f),
                radius = size.width * 0.8f, center = Offset(size.width * (0.2f + 0.15f * drift), size.height * 0.3f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0x5506B6D4), Color.Transparent), center = Offset(size.width * (0.85f - 0.15f * drift), size.height * 0.75f), radius = size.width * 0.7f),
                radius = size.width * 0.7f, center = Offset(size.width * (0.85f - 0.15f * drift), size.height * 0.75f),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            // 1. prompt tile pops in (0–420 ms) with a slight overshoot
            val pop = progress(0f, 420f)
            val overshoot = if (pop < 1f) 0.7f + 0.38f * pop else 1f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(84.dp).graphicsLayer { scaleX = overshoot; scaleY = overshoot; alpha = pop }
                        .clip(RoundedCornerShape(26.dp)).background(Accents.tech.brush),
                    contentAlignment = Alignment.Center,
                ) {
                    val cursorOn = ((t / 260f).toInt() % 2 == 0)
                    Canvas(Modifier.size(48.dp)) {
                        val w = size.width
                        val h = size.height
                        val chevron = Path().apply { moveTo(w * 0.12f, h * 0.22f); lineTo(w * 0.45f, h * 0.5f); lineTo(w * 0.12f, h * 0.78f) }
                        drawPath(chevron, Color.White, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (cursorOn) drawLine(Color(0xFF67E8F9), Offset(w * 0.58f, h * 0.8f), Offset(w * 0.92f, h * 0.8f), 6.dp.toPx(), StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(16.dp))
                // 2. "sudo" types itself out (350–850 ms)
                val typed = "sudo".take(((t - 350f) / 125f).toInt().coerceIn(0, 4))
                Text(
                    typed.padEnd(4, ' '),
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 52.sp),
                )
            }

            Spacer(Modifier.height(22.dp))
            // 3. tagline (850–1150 ms)
            val tag = progress(850f, 300f)
            Text(
                "Tech, distilled.",
                style = MaterialTheme.typography.headlineLarge.copy(brush = Accents.tech.horizontal),
                modifier = Modifier.graphicsLayer { alpha = tag; translationY = (1 - tag) * 24f },
            )

            Spacer(Modifier.height(30.dp))
            // 4. what the app does, staggered (1100 / 1250 / 1400 ms)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Feature(Icons.Rounded.Bolt, "Live tech & AI news, verified", Accents.tech, progress(1100f, 320f))
                Feature(Icons.Rounded.Radar, "Skills Radar: what's emerging", Accents.ai, progress(1250f, 320f))
                Feature(Icons.Rounded.WorkOutline, "Jobs & internships that fit you", Accents.jobs, progress(1400f, 320f))
            }
        }

        // 5. progress bar sweeps across while the app loads underneath
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp)) {
            Box(Modifier.width(160.dp).height(4.dp).clip(CircleShape).background(Color(0x22FFFFFF))) {
                Box(Modifier.fillMaxWidth((t / TOTAL_MS).coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Accents.tech.horizontal))
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String, accent: Accent, p: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer { alpha = p; translationY = (1 - p) * 32f },
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color(0xFFE6E8F0), style = MaterialTheme.typography.titleMedium)
    }
}
