package io.music_assistant.client.utils

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val mainDispatcher: CoroutineDispatcher = Dispatchers.Main

/**
 * High-priority audio dispatcher for Android: one dedicated thread with THREAD_PRIORITY_AUDIO,
 * which runs the Sendspin scheduler loop and nothing else.
 *
 * That loop decodes each chunk right before it writes it to the AudioTrack: the decoder and the
 * sink handle have a single owner. A MediaCodec FLAC decode can block this thread for over
 * 100 ms (input retries plus the output wait in `AndroidDecoders`); the AudioTrack buffer in
 * `AudioTrackSink` is sized to play through such a stall. Networking, message parsing, the
 * jitter buffer, and commands run on normal-priority dispatchers.
 */
actual val audioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread({
        // Set Android process priority for this thread to AUDIO priority
        // THREAD_PRIORITY_AUDIO = -16 (higher priority than default 0)
        // This ensures audio streaming continues smoothly even when app is backgrounded
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        runnable.run()
    }, "AudioThread-${System.currentTimeMillis()}").apply {
        priority = Thread.MAX_PRIORITY
        setUncaughtExceptionHandler { thread, exception ->
            android.util.Log.e("AudioDispatcher", "Uncaught exception in $thread", exception)
        }
    }
}.asCoroutineDispatcher()
