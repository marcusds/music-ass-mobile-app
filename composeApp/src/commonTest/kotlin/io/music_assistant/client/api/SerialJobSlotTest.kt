package io.music_assistant.client.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slot holds a transport's connect and reconnect loop. A replaced loop can still be
 * tearing down its manager after the cancel, so the next loop must not start before it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerialJobSlotTest {
    @Test
    fun theNewBlockStartsAfterTheOldTeardownEnds() = runTest {
        val slot = SerialJobSlot(slotScope())
        val events = mutableListOf<String>()
        slot.replace {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(TEARDOWN_MS)
                    events += "old closed at $currentTime"
                }
            }
        }
        runCurrent()

        slot.replace { events += "new started at $currentTime" }
        advanceUntilIdle()

        assertEquals(listOf("old closed at $TEARDOWN_MS", "new started at $TEARDOWN_MS"), events)
    }

    @Test
    fun twoBlocksNeverRunTogether() = runTest {
        val slot = SerialJobSlot(slotScope())
        var running = 0
        var maxRunning = 0
        repeat(5) {
            slot.replace {
                running++
                maxRunning = maxOf(maxRunning, running)
                try {
                    delay(TEARDOWN_MS)
                } finally {
                    withContext(NonCancellable) { delay(TEARDOWN_MS) }
                    running--
                }
            }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(1, maxRunning)
    }

    @Test
    fun aJobReplacedBeforeItStartedDoesNotLetTheNextOneSkipTheTeardown() = runTest {
        val slot = SerialJobSlot(slotScope())
        var startedAt = -1L
        slot.replace {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(TEARDOWN_MS) }
            }
        }
        runCurrent()

        slot.replace { error("replaced before it started") }
        slot.replace { startedAt = currentTime }
        advanceUntilIdle()

        assertEquals(TEARDOWN_MS, startedAt)
    }

    @Test
    fun aCancelBeforeTheNewJobStartsStillStopsTheOldOne() = runTest {
        val slot = SerialJobSlot(slotScope())
        var oldCancelled = false
        var newRan = false
        slot.replace {
            try {
                awaitCancellation()
            } finally {
                oldCancelled = true
            }
        }
        runCurrent()

        slot.replace { newRan = true }
        slot.cancel()
        advanceUntilIdle()

        assertTrue(oldCancelled)
        assertFalse(newRan)
    }

    @Test
    fun aFailedConditionKeepsTheCurrentJob() = runTest {
        val slot = SerialJobSlot(slotScope())
        val current = slot.replace { awaitCancellation() }
        runCurrent()

        assertNull(slot.replace(onlyIf = { false }) { error("must not run") })
        advanceUntilIdle()

        assertEquals(true, current?.isActive)
    }

    // backgroundScope work does not run under advanceUntilIdle once the test body is idle,
    // so the slot gets a foreground scope that the test cancels at the end.
    private fun TestScope.slotScope(): CoroutineScope =
        CoroutineScope(coroutineContext + SupervisorJob()).also { scope ->
            backgroundScope.launch {
                try {
                    awaitCancellation()
                } finally {
                    scope.cancel()
                }
            }
        }

    private companion object {
        const val TEARDOWN_MS = 100L
    }
}
