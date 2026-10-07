package com.technewz.app.data

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps background crunching from competing with the UI on a phone:
 * - [dispatcher] runs on two dedicated threads at Android's *background* priority, so the scheduler always
 *   prefers the UI thread — scrolling stays smooth while feeds, jobs or the radar are being processed;
 * - [lock] makes the memory-heavy phases of the news, jobs and radar refreshes run one at a time,
 *   so their peaks never stack (stacked peaks are what ran phones out of memory).
 */
object HeavyWork {
    private val count = AtomicInteger()

    val dispatcher = Executors.newFixedThreadPool(2) { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "sudo-heavy-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val lock = Mutex()
}
