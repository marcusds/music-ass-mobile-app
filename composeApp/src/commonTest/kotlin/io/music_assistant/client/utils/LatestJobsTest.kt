package io.music_assistant.client.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A queue refresh pages the whole queue. Two quick queue events used to start two refreshes that
 * could end in the wrong order, so the shown queue went back to the older items.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestJobsTest {
    @Test
    fun `a new job for a key cancels the earlier one so a slow old result is never written`() =
        runTest {
            val jobs = LatestJobs<String>(backgroundScope)
            val written = mutableListOf<String>()
            val oldAnswer = CompletableDeferred<String>()

            val old = jobs.launch("kitchen") { written += oldAnswer.await() }
            settle()
            jobs.launch("kitchen") { written += "new" }
            oldAnswer.complete("old")
            settle()

            assertTrue(old.isCancelled)
            assertEquals(listOf("new"), written)
        }

    @Test
    fun `jobs for different keys run side by side`() = runTest {
        val jobs = LatestJobs<String>(backgroundScope)
        val written = mutableListOf<String>()

        jobs.launch("kitchen") {
            delay(100)
            written += "kitchen"
        }
        jobs.launch("office") {
            delay(100)
            written += "office"
        }
        settle()

        assertEquals(setOf("kitchen", "office"), written.toSet())
    }

    @Test
    fun `the new job starts only after the cancelled job has ended`() = runTest {
        val jobs = LatestJobs<String>(backgroundScope)
        val order = mutableListOf<String>()

        jobs.launch("kitchen") {
            try {
                delay(1_000)
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    order += "old ended"
                }
            }
        }
        runCurrent()
        jobs.launch("kitchen") { order += "new started" }
        settle()

        assertEquals(listOf("old ended", "new started"), order)
    }

    @Test
    fun `a burst keeps only the last job and a finished key is removed`() = runTest {
        val jobs = LatestJobs<String>(backgroundScope)
        val written = mutableListOf<Int>()

        repeat(5) { i ->
            jobs.launch("kitchen") {
                delay(250)
                written += i
            }
        }
        assertEquals(setOf("kitchen"), jobs.activeKeys)
        settle()

        assertEquals(listOf(4), written)
        assertTrue(jobs.activeKeys.isEmpty())
    }

    // backgroundScope stands in for the app scope. advanceUntilIdle does not run its work, so the
    // tests move the virtual clock instead.
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }
}
