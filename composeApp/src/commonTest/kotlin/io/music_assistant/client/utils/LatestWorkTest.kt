package io.music_assistant.client.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LatestWorkTest {
    private fun TestScope.latestWork() = LatestWork(this, StandardTestDispatcher(testScheduler))

    @Test
    fun `a run delivers its result`() = runTest {
        val delivered = mutableListOf<String>()
        latestWork().run(compute = { "a" }, deliver = { delivered += it })

        advanceUntilIdle()

        assertEquals(listOf("a"), delivered)
    }

    @Test
    fun `a new run replaces a pending run`() = runTest {
        val delivered = mutableListOf<String>()
        val work = latestWork()
        work.run(compute = { "old" }, deliver = { delivered += it })
        work.run(compute = { "new" }, deliver = { delivered += it })

        advanceUntilIdle()

        assertEquals(listOf("new"), delivered)
    }

    @Test
    fun `a run replaced while it computes does not deliver`() = runTest {
        val delivered = mutableListOf<String>()
        val work = latestWork()
        work.run(
            compute = {
                // The next input arrives while the first result is still computing.
                work.run(compute = { "new" }, deliver = { delivered += it })
                "old"
            },
            deliver = { delivered += it },
        )

        advanceUntilIdle()

        assertEquals(listOf("new"), delivered)
    }

    @Test
    fun `cancel drops the pending result`() = runTest {
        val delivered = mutableListOf<String>()
        val work = latestWork()
        work.run(compute = { "a" }, deliver = { delivered += it })
        work.cancel()

        advanceUntilIdle()

        assertEquals(emptyList(), delivered)
    }
}
