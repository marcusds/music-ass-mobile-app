package io.music_assistant.client.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs at most one job for each key in [scope]. A new job for a key cancels the job that runs
 * for it and waits until that job ends, so the last job always writes last. Safe to call from
 * any thread.
 */
class LatestJobs<K>(private val scope: CoroutineScope) {
    private val jobs = MutableStateFlow<Map<K, Job>>(emptyMap())

    /** The keys that have a job in progress. */
    val activeKeys: Set<K> get() = jobs.value.keys

    /**
     * Launches [block] for [key] and cancels the earlier job for [key].
     *
     * @param key the key the job belongs to.
     * @param block the work; a later [launch] for the same key cancels it.
     */
    fun launch(key: K, block: suspend CoroutineScope.() -> Unit): Job {
        var previous: Job? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            block()
        }
        job.invokeOnCompletion {
            jobs.update { current -> if (current[key] === job) current - key else current }
        }
        previous = jobs.getAndUpdate { it + (key to job) }[key]
        previous?.cancel()
        job.start()
        return job
    }
}
