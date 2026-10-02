package com.technewz.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.technewz.app.MainActivity
import com.technewz.app.container
import com.technewz.app.data.ArticleEntity
import com.technewz.app.data.Section
import com.technewz.app.util.Text as T

class HeadlinesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = context.container.db.articles()
        val items = (dao.latest(Section.TECH, 40) + dao.latest(Section.AI, 20))
            .sortedByDescending { it.publishedAt }
            .distinctBy { it.clusterId }
            .take(8)
        provideContent { GlanceTheme { Content(items) } }
    }

    @Composable
    private fun Content(items: List<ArticleEntity>) {
        val bg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF12141C))
        val fg = ColorProvider(day = Color(0xFF0E1220), night = Color(0xFFECEEF5))
        val muted = ColorProvider(day = Color(0xFF5B6275), night = Color(0xFF9AA1B5))
        val accent = ColorProvider(day = Color(0xFF4F46E5), night = Color(0xFF8B87FF))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            ) {
                Text("sudo", style = TextStyle(color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(6.dp))
                Text("· latest", style = TextStyle(color = muted, fontSize = 13.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (items.isEmpty()) {
                Text("Open the app to load headlines", style = TextStyle(color = muted, fontSize = 13.sp))
            } else {
                LazyColumn {
                    items(items, itemId = { it.id.hashCode().toLong() }) { a ->
                        Column(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(
                                actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)))
                            )
                        ) {
                            Text(a.title, maxLines = 2, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                            Text("${a.source} · ${T.timeAgo(a.publishedAt)}", style = TextStyle(color = muted, fontSize = 11.sp))
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) = HeadlinesWidget().updateAll(context)
    }
}

class HeadlinesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeadlinesWidget()
}
