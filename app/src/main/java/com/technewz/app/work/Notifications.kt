package com.technewz.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.technewz.app.MainActivity
import com.technewz.app.R

object Notifications {
    const val CH_ALERTS = "alerts"
    const val CH_JOBS = "jobs"
    const val CH_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ALERTS, "Keyword alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "News that matches your alert keywords"
                },
                NotificationChannel(CH_JOBS, "New matching jobs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New jobs and internships that match your keywords"
                },
                NotificationChannel(CH_REMINDERS, "Application follow-ups", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders to follow up on applications"
                },
            )
        )
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openUrlIntent(context: Context, url: String?, requestCode: Int): PendingIntent {
        val intent = if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url))
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openAppIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    data class Line(val title: String, val source: String, val url: String)

    @SuppressLint("MissingPermission")
    fun newsAlert(context: Context, keyword: String, lines: List<Line>) {
        if (!canPost(context) || lines.isEmpty()) return
        val first = lines.first()
        val builder = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(0xFF4F46E5.toInt())
            .setAutoCancel(true)
        if (lines.size == 1) {
            builder.setContentTitle("“$keyword” · ${first.source}")
                .setContentText(first.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(first.title))
                .setContentIntent(openUrlIntent(context, first.url, first.url.hashCode()))
        } else {
            val style = NotificationCompat.InboxStyle()
            lines.take(5).forEach { style.addLine("${it.source}: ${it.title}") }
            builder.setContentTitle("${lines.size} new stories on “$keyword”")
                .setContentText(first.title)
                .setStyle(style)
                .setContentIntent(openAppIntent(context, "news", keyword.hashCode()))
        }
        NotificationManagerCompat.from(context).notify("kw:$keyword".hashCode(), builder.build())
    }

    @SuppressLint("MissingPermission")
    fun radarAlert(context: Context, terms: List<String>) {
        if (!canPost(context) || terms.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        terms.take(5).forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(0xFFA855F7.toInt())
            .setContentTitle(if (terms.size == 1) "New on Skills Radar: ${terms[0]}" else "${terms.size} new terms on Skills Radar")
            .setContentText(terms.joinToString(", "))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, "radar", 7002))
            .build()
        NotificationManagerCompat.from(context).notify(7002, n)
    }

    @SuppressLint("MissingPermission")
    fun jobAlert(context: Context, count: Int, sample: List<String>) {
        if (!canPost(context) || count == 0) return
        val style = NotificationCompat.InboxStyle()
        sample.take(5).forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(context, CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(0xFF10B981.toInt())
            .setContentTitle(if (count == 1) "New matching opportunity" else "$count new matching opportunities")
            .setContentText(sample.firstOrNull().orEmpty())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, "jobs", 7001))
            .build()
        NotificationManagerCompat.from(context).notify(7001, n)
    }

    @SuppressLint("MissingPermission")
    fun followUp(context: Context, jobId: String, title: String, company: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(0xFFF59E0B.toInt())
            .setContentTitle("Follow up with $company?")
            .setContentText("It's been a week since you applied for $title. A short, polite check-in can help.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("It's been a week since you applied for $title at $company. A short, polite check-in email can help your application stand out."))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, "tracker", jobId.hashCode()))
            .build()
        NotificationManagerCompat.from(context).notify(jobId.hashCode(), n)
    }
}
