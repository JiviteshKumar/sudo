package com.technewz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps background crunching from competing with the UI on a phone:
 * - [dispatcher] uses at most two CPU cores, leaving the rest for scrolling and rendering;
 * - [lock] makes the memory-heavy phases of the news, jobs and radar refreshes run one at a time,
 *   so their peaks never stack (stacked peaks are what ran phones out of memory).
 */
object HeavyWork {
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher = Dispatchers.Default.limitedParallelism(2)
    val lock = Mutex()
}
