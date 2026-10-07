package com.technewz.app

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.technewz.app.data.AppDatabase
import com.technewz.app.ui.AppRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scrolls the real feed continuously while a real news + jobs refresh runs over the internet, and times
 * every swipe (including the recompositions caused by data arriving). Android shows "isn't responding"
 * when the UI thread is blocked for 5 s; this asserts no swipe comes anywhere near that.  Run with LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScrollWhileRefreshingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun scrollingStaysResponsiveDuringRefresh() {
        assumeTrue(System.getenv("LIVE") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val c = AppContainer(app, db)
        // Nothing cached and never refreshed: opening the app starts a real background refresh immediately.
        runBlocking {
            c.settings.update {
                it.copy(onboarded = true, lastNewsRefresh = 0, lastJobsRefresh = 0, keywords = emptyList(),
                    lastRadarRefresh = System.currentTimeMillis(), radarVersion = com.technewz.app.data.RadarRepository.RULES_VERSION)
            }
        }
        rule.setContent { AppRoot(c, null) {} }
        rule.waitUntil(30_000) { rule.onAllNodes(hasContentDescription("sudo intro")).fetchSemanticsNodes().isEmpty() }

        val list = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val timings = mutableListOf<Long>()
        var sawSyncing = false
        val start = System.currentTimeMillis()
        // Keep scrolling until the refresh has filled the feed (or 4 minutes pass).
        while (System.currentTimeMillis() - start < 240_000) {
            sawSyncing = sawSyncing || c.syncing.value
            repeat(4) { i ->
                val t0 = System.nanoTime()
                rule.onAllNodes(list)[0].performTouchInput { if (i % 2 == 0) swipeUp() else swipeDown() }
                rule.waitForIdle()
                timings += (System.nanoTime() - t0) / 1_000_000
            }
            val stories = runBlocking { db.articles().all().size }
            if (stories > 40 && !c.syncing.value && timings.size >= 40) break
            Thread.sleep(250)
        }
        val stories = runBlocking { db.articles().all().size }
        val sorted = timings.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        println("swipes: ${timings.size}, refresh observed: $sawSyncing, stories loaded meanwhile: $stories")
        println("swipe time (ms) — median ${sorted[sorted.size / 2]}, p95 $p95, max ${sorted.last()}  (ANR threshold: 5000)")

        assertTrue("the refresh should have been running during the test", sawSyncing || stories > 0)
        assertTrue("a swipe blocked the UI for ${sorted.last()} ms", sorted.last() < 2_000)
    }
}
