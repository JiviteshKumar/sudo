package com.technewz.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.technewz.app.ui.theme.Accent
import com.technewz.app.ui.theme.Accents
import com.technewz.app.ui.theme.LocalExtra

/** Card surface used everywhere: soft border, no heavy shadow, press-to-shrink feedback. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(160), label = "press")
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(color)
            .border(1.dp, LocalExtra.current.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides androidx.compose.material3.contentColorFor(color),
            content = content,
        )
    }
}

@Composable
fun GradientText(text: String, accent: Accent, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = style.copy(brush = accent.horizontal))
}

@Composable
fun LiveDot(color: Color = LocalExtra.current.success, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(size * 2.2f)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = (1f - pulse) * 0.45f), radius = r * (0.45f + pulse * 0.55f))
        drawCircle(color, radius = r * 0.45f)
    }
}

@Composable
fun Favicon(domain: String, size: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = "https://www.google.com/s2/favicons?domain=$domain&sz=64",
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun SourceLine(domain: String, source: String, meta: String, modifier: Modifier = Modifier, verified: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Favicon(domain)
        Spacer(Modifier.width(8.dp))
        Text(source, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (verified) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Verified, contentDescription = "Trusted source", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
        Text("  ·  $meta", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** Horizontally scrolling filter chips with an animated gradient selection. */
@Composable
fun ChipRow(
    options: List<String>,
    selected: Set<String>,
    accent: Accent,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<String, Int> = emptyMap(),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it }) { opt ->
            val isSel = opt in selected
            val textColor by animateColorAsState(if (isSel) Color.White else MaterialTheme.colorScheme.onSurface, label = "c")
            Box(
                Modifier
                    .clip(CircleShape)
                    .then(
                        if (isSel) Modifier.background(accent.horizontal)
                        else Modifier.background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    .clickable { onToggle(opt) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                val c = counts[opt]
                Text(
                    if (c != null) "$opt  $c" else opt,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
fun BookmarkButton(bookmarked: Boolean, onToggle: () -> Unit) {
    val tint by animateColorAsState(
        if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "bm"
    )
    val scale by animateFloatAsState(if (bookmarked) 1.12f else 1f, tween(220, easing = FastOutSlowInEasing), label = "bms")
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        Icon(
            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
            tint = tint, modifier = Modifier.size(22.dp).scale(scale),
        )
    }
}

/** Circular match score ring. */
@Composable
fun MatchRing(score: Int?, size: Dp = 46.dp, stroke: Dp = 4.5.dp) {
    val extra = LocalExtra.current
    val target = (score ?: 0) / 100f
    val progress by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "ring")
    val color = when {
        score == null -> MaterialTheme.colorScheme.outline
        score >= 70 -> extra.success
        score >= 40 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = this.size.copy(this.size.width - sw, this.size.height - sw))
            drawArc(color, -90f, 360f * progress, false, style = Stroke(sw, cap = StrokeCap.Round), topLeft = Offset(sw / 2, sw / 2), size = this.size.copy(this.size.width - sw, this.size.height - sw))
        }
        Text(
            if (score == null) "–" else "$score",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun CompanyAvatar(name: String, size: Dp = 46.dp) {
    val accent = Accents.forName(name)
    val initials = name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(accent.brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).drawBehind {
            drawRect(
                Brush.linearGradient(
                    listOf(base, hi, base),
                    start = Offset(size.width * x - size.width, 0f),
                    end = Offset(size.width * x, size.height),
                )
            )
        }
    )
}

@Composable
fun SkeletonCard() {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Shimmer(Modifier.width(140.dp).height(14.dp))
            Spacer(Modifier.height(14.dp))
            Shimmer(Modifier.fillMaxWidth().height(20.dp))
            Spacer(Modifier.height(8.dp))
            Shimmer(Modifier.fillMaxWidth(0.7f).height(20.dp))
            Spacer(Modifier.height(14.dp))
            repeat(3) {
                Shimmer(Modifier.fillMaxWidth().height(12.dp))
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, accent: Accent, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(26.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Primary call-to-action with gradient fill. */
@Composable
fun GradientButton(
    text: String,
    accent: Accent,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "gb")
    Box(
        modifier
            .scale(scale)
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) accent.horizontal else Brush.horizontalGradient(listOf(Color.Gray, Color.Gray)))
            .clickable(interaction, androidx.compose.material3.ripple(color = Color.White), enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
