package io.music_assistant.client.api

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the one job that may run at a time, such as a transport's connect or reconnect loop.
 * Safe to call from any thread.
 *
 * [replace] cancels the current job at once, and the new block starts only after the old job
 * has finished. Two blocks never run together, even when the old one still has teardown to do
 * after its cancellation, and even when two threads call [replace] at the same moment.
 */
internal class SerialJobSlot(private val scope: CoroutineScope) {
    private val lock = SynchronizedObject()
    private var current: Job? = null

    // Every replaced job that has not ended yet. A job cancelled before it started never
    // waits for anything, so a new job joins all of these, not only the one it replaces.
    private val unfinished = mutableListOf<Job>()

    /**
     * Replaces the current job with one that runs [block].
     *
     * @param onlyIf checked under the slot's lock. When it returns false, nothing is replaced
     *        and this returns null.
     */
    fun replace(onlyIf: () -> Boolean = { true }, block: suspend CoroutineScope.() -> Unit): Job? =
        synchronized(lock) {
            if (!onlyIf()) return@synchronized null
            // Cancel now, not in the new job: a cancel() that arrives before the new job
            // starts must still stop the previous one.
            current?.cancel()
            unfinished.removeAll { it.isCompleted }
            val ahead = unfinished.toList()
            scope.launch {
                withContext(NonCancellable) { ahead.joinAll() }
                ensureActive()
                block()
            }.also {
                current = it
                unfinished += it
            }
        }

    /** Cancels the current job. Its teardown can still be running when this returns. */
    fun cancel() {
        synchronized(lock) {
            current?.cancel()
            current = null
        }
    }
}
