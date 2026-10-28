package io.music_assistant.client.services

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session writer republishes the playback state only when a host's extrapolation would be
 * wrong, and publishes only a window of a long queue.
 */
class MediaSessionWriteDedupTest {
    @Test
    fun `a steady playing tick is not republished however late it arrives`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000)
        val new = data(elapsedMs = 40_000, updateMs = 130_000)
        assertTrue(MediaNotificationData.areTooSimilarToUpdate(old, new))
    }

    @Test
    fun `whole second truncation does not count as drift`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000)
        val new = data(elapsedMs = 11_000, updateMs = 101_999)
        assertTrue(MediaNotificationData.areTooSimilarToUpdate(old, new))
    }

    @Test
    fun `a seek while playing is republished`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000)
        assertFalse(
            MediaNotificationData.areTooSimilarToUpdate(old, data(elapsedMs = 60_000, updateMs = 102_000)),
        )
        assertFalse(
            MediaNotificationData.areTooSimilarToUpdate(old, data(elapsedMs = 5_000, updateMs = 102_000)),
        )
    }

    @Test
    fun `a stalled playing position is republished`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000)
        val new = data(elapsedMs = 10_000, updateMs = 105_000)
        assertFalse(MediaNotificationData.areTooSimilarToUpdate(old, new))
    }

    @Test
    fun `a paused position that stands still is not republished`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000, isPlaying = false)
        val new = data(elapsedMs = 10_000, updateMs = 160_000, isPlaying = false)
        assertTrue(MediaNotificationData.areTooSimilarToUpdate(old, new))
    }

    @Test
    fun `a change outside the position is always republished`() {
        val old = data(elapsedMs = 10_000, updateMs = 100_000)
        val new = data(elapsedMs = 10_000, updateMs = 100_000, isPlaying = false)
        assertFalse(MediaNotificationData.areTooSimilarToUpdate(old, new))
    }

    @Test
    fun `a short queue is published whole`() {
        val queue = List(SESSION_QUEUE_WINDOW) { it }
        assertSame(queue, queue.sessionQueueWindow(150))
    }

    @Test
    fun `a long queue window always holds the current slot and some history`() {
        val queue = List(1_000) { it }
        (0 until queue.size).forEach { current ->
            val window = queue.sessionQueueWindow(current)
            assertEquals(SESSION_QUEUE_WINDOW, window.size)
            assertTrue(current in window, "current slot $current missing")
            assertTrue(
                window.first() <= (current - SESSION_QUEUE_HISTORY).coerceAtLeast(0),
                "history missing for $current",
            )
        }
    }

    @Test
    fun `the window moves in steps, not on every track`() {
        val queue = List(1_000) { it }
        assertEquals(queue.sessionQueueWindow(100), queue.sessionQueueWindow(101))
        assertEquals(0, queue.sessionQueueWindow(-1).first())
    }

    private fun data(elapsedMs: Long, updateMs: Long, isPlaying: Boolean = true) = MediaNotificationData(
        multiplePlayers = false,
        longItemId = 1L,
        activeQueueItemId = 1L,
        name = "Track",
        artist = "Artist",
        album = "Album",
        repeatMode = null,
        shuffleEnabled = null,
        isLongFormContent = false,
        isFavoritableTrack = false,
        isFavoritableStream = false,
        isFavorite = false,
        isPlaying = isPlaying,
        imageUrl = null,
        chapterName = null,
        elapsedTime = elapsedMs,
        elapsedUpdateTimeMs = updateMs,
        playerName = null,
        duration = 200_000L,
    )
}
