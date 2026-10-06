package com.technewz.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.technewz.app.TechNewzApp
import com.technewz.app.data.HeavyWork
import kotlinx.coroutines.sync.withLock
import com.technewz.app.widget.HeadlinesWidget
import java.util.concurrent.TimeUnit

/** Runs every 15 minutes (Android's minimum periodic interval) while there is a network connection. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as TechNewzApp).container
        // One heavy job at a time, so memory peaks never stack (the radar takes the lock itself).
        val news = runCatching { HeavyWork.lock.withLock { c.news.refresh() } }
        runCatching { HeavyWork.lock.withLock { c.jobs.refresh(force = false) } }
        runCatching { c.radar.refresh(force = false) } // self-throttles to twice a day
        runCatching { HeadlinesWidget.updateAll(applicationContext) }
        return if (news.isSuccess || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "periodic-refresh"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.success()
        val c = (applicationContext as TechNewzApp).container
        val app = c.db.applications().get(jobId) ?: return Result.success()
        if (app.status == com.technewz.app.data.AppStatus.APPLIED) {
            Notifications.followUp(applicationContext, jobId, app.title, app.company)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_JOB = "job"
        private fun name(jobId: String) = "followup-$jobId"

        fun schedule(context: Context, jobId: String, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<FollowUpWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_JOB to jobId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(jobId), ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(jobId))
        }
    }
}
