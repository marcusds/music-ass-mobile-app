package io.music_assistant.client.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a computation on [dispatcher] and gives its result to a callback in [scope]. A new [run]
 * cancels the previous one, and so does [cancel]. Only the result of the latest run is delivered.
 */
class LatestWork(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    fun <T> run(compute: () -> T, deliver: (T) -> Unit) {
        job?.cancel()
        job = scope.launch {
            // withContext checks for cancellation when it returns, so a replaced run never delivers.
            val result = withContext(dispatcher) { compute() }
            deliver(result)
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
