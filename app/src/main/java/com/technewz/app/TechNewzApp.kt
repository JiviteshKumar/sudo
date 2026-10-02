package com.technewz.app

import android.app.Application
import android.content.Context
import com.technewz.app.data.AiFeatures
import com.technewz.app.data.AppDatabase
import com.technewz.app.data.JobsRepository
import com.technewz.app.data.NewsRepository
import com.technewz.app.data.SettingsRepository
import com.technewz.app.widget.HeadlinesWidget
import com.technewz.app.work.Notifications
import com.technewz.app.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class AppContainer(private val context: Context, val db: AppDatabase = AppDatabase.create(context)) {
    val settings = SettingsRepository(context)
    val ai = AiFeatures(settings)
    val news = NewsRepository(context, db, settings, ai)
    val jobs = JobsRepository(context, db, settings)
    val radar = com.technewz.app.data.RadarRepository(context, db, settings, ai)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val refreshMutex = Mutex()
    private val _refreshingNews = MutableStateFlow(false)
    private val _refreshingJobs = MutableStateFlow(false)
    val refreshingNews: StateFlow<Boolean> = _refreshingNews
    val refreshingJobs: StateFlow<Boolean> = _refreshingJobs

    /** User-initiated refresh. Returns a short status message for the UI (or null if nothing to say). */
    suspend fun refreshNews(): String? {
        if (!refreshMutex.tryLock()) return null
        _refreshingNews.value = true
        return try {
            val r = news.refresh()
            runCatching { HeadlinesWidget.updateAll(context) }
            when {
                r.aiNote != null -> r.aiNote
                r.failedFeeds.size >= 6 -> "Some sources didn't respond — check your connection."
                r.newArticles > 0 -> "${r.newArticles} new stories"
                else -> "You're all caught up"
            }
        } catch (e: Exception) {
            "Couldn't refresh: ${e.message ?: "network error"}"
        } finally {
            _refreshingNews.value = false
            refreshMutex.unlock()
        }
    }

    private val _radarProgress = MutableStateFlow<String?>(null)
    /** Non-null while the Skills Radar is refreshing; holds a human-readable progress line. */
    val radarProgress: StateFlow<String?> = _radarProgress

    suspend fun refreshRadar(force: Boolean): String? {
        if (_radarProgress.value != null) return null
        _radarProgress.value = "Starting…"
        return try {
            val r = radar.refresh(force) { _radarProgress.value = it } ?: return null
            when {
                r.accepted.isNotEmpty() -> "New on the radar: " + r.accepted.take(3).joinToString(", ")
                else -> "Checked ${r.checked} candidate terms — nothing new passed verification"
            }
        } catch (e: Exception) {
            "Couldn't refresh the radar: ${e.message ?: "network error"}"
        } finally {
            _radarProgress.value = null
        }
    }

    suspend fun refreshJobs(force: Boolean): String? {
        if (_refreshingJobs.value) return null
        _refreshingJobs.value = true
        return try {
            val r = jobs.refresh(force) ?: return null
            if (r.total == 0 && r.failedSources.isNotEmpty()) "Couldn't reach job sources — check your connection."
            else if (r.newCount > 0) "${r.newCount} new opportunities" else "Jobs are up to date"
        } catch (e: Exception) {
            "Couldn't refresh jobs: ${e.message ?: "network error"}"
        } finally {
            _refreshingJobs.value = false
        }
    }
}

class TechNewzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch {
            RefreshWorker.schedule(this@TechNewzApp, container.settings.current().backgroundRefresh)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as TechNewzApp).container
