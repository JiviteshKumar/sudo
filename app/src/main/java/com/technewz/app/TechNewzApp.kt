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
import kotlinx.coroutines.sync.withLock

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
    private val _syncing = MutableStateFlow(false)
    /** True while a silent catch-up refresh runs (no spinner; just a small status line). */
    val syncing: StateFlow<Boolean> = _syncing
    private val jobsMutex = Mutex()

    /**
     * Refreshes news. [userInitiated] shows the pull-to-refresh spinner; otherwise it runs silently.
     * Returns a short status message for the UI (or null if nothing to say).
     */
    suspend fun refreshNews(userInitiated: Boolean = true): String? {
        if (!refreshMutex.tryLock()) return if (userInitiated) "Already updating in the background" else null
        if (userInitiated) _refreshingNews.value = true else _syncing.value = true
        return try {
            val r = com.technewz.app.data.HeavyWork.lock.withLock { news.refresh() }
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
            _syncing.value = false
            refreshMutex.unlock()
        }
    }

    private val _radarProgress = MutableStateFlow<String?>(null)
    /** Non-null while the Skills Radar is refreshing; holds a human-readable progress line. */
    val radarProgress: StateFlow<String?> = _radarProgress
    private val _radarNote = MutableStateFlow<String?>(null)
    /** Last scan's caveat (e.g. a source's daily limit was reached), shown on the radar until the next scan. */
    val radarNote: StateFlow<String?> = _radarNote

    suspend fun refreshRadar(force: Boolean): String? {
        if (_radarProgress.value != null) return null
        _radarProgress.value = "Starting…"
        return try {
            val r = radar.refresh(force) { _radarProgress.value = it } ?: return null
            _radarNote.value = if (r.paused) "Verification paused — research sources were busy or at their daily limit; it continues on the next scan" else null
            when {
                r.paused -> "Radar paused: research sources were busy or at their free daily limit. It retries automatically; a free OpenAlex key in Settings helps."
                r.accepted.isNotEmpty() -> "New on the radar: " + r.accepted.take(3).joinToString(", ")
                else -> "Checked ${r.checked} candidate terms — nothing new passed verification"
            }
        } catch (e: Exception) {
            "Couldn't refresh the radar: ${e.message ?: "network error"}"
        } finally {
            _radarProgress.value = null
        }
    }

    suspend fun refreshJobs(force: Boolean, userInitiated: Boolean = true): String? {
        if (!jobsMutex.tryLock()) return null
        if (userInitiated) _refreshingJobs.value = true
        return try {
            val r = com.technewz.app.data.HeavyWork.lock.withLock { jobs.refresh(force) } ?: return null
            if (r.total == 0 && r.failedSources.isNotEmpty()) "Couldn't reach job sources — check your connection."
            else if (r.newCount > 0) "${r.newCount} new opportunities" else "Jobs are up to date"
        } catch (e: Exception) {
            "Couldn't refresh jobs: ${e.message ?: "network error"}"
        } finally {
            _refreshingJobs.value = false
            jobsMutex.unlock()
        }
    }
}

class TechNewzApp : Application(), coil.ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    /** Images get a modest memory budget (default is up to 25% of the heap) plus a disk cache. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(64L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch {
            RefreshWorker.schedule(this@TechNewzApp, container.settings.current().backgroundRefresh)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as TechNewzApp).container

/** Saves the last crash so it can be copied from Settings and diagnosed, then lets Android handle it. */
object CrashLog {
    private fun file(c: Context) = java.io.File(c.filesDir, "last_crash.txt")

    fun install(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val rt = Runtime.getRuntime()
                file(c).writeText(
                    listOf(
                        "sudo crash · ${java.util.Date()}",
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
                        "heap: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB · thread: ${thread.name}",
                        "",
                        e.stackTraceToString().take(20_000),
                    ).joinToString(System.lineSeparator())
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
