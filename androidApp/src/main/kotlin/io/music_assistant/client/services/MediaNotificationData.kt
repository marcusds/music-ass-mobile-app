package io.music_assistant.client.services

import android.os.SystemClock
import io.music_assistant.client.data.model.client.MediaType
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.RepeatMode
import io.music_assistant.client.data.model.client.ResolvedChapter
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.client.items.Audiobook
import io.music_assistant.client.data.model.client.items.canBeFavorited
import io.music_assistant.client.data.model.client.items.isLongFormSpokenContent
import kotlin.math.abs

// How far the position may drift from the host's extrapolation before we treat it as a
// real position change (a seek, a stall, a server correction). Positions are truncated to
// whole seconds, so a steady tick already shows up to 1s of drift; the extra 500ms absorbs
// jitter in the position anchor. debounce(200ms) bounds write churn.
private const val ELAPSED_TIME_DRIFT_TOLERANCE_MS = 1_500

data class MediaNotificationData(
    val multiplePlayers: Boolean,
    val longItemId: Long?,
    // Session queue row to highlight: the active chapter row when chapter mode applies, else the slot.
    val activeQueueItemId: Long?,
    val name: String?,
    val artist: String?,
    val album: String?,
    val repeatMode: RepeatMode?,
    val shuffleEnabled: Boolean?,
    // Audiobook / podcast episode: notification swaps shuffle & repeat for seek controls.
    val isLongFormContent: Boolean,
    // Current item is a favoritable track: gates the favorite toggle action
    // (see sessionActions for the render-order priority).
    val isFavoritableTrack: Boolean,
    // Current item is a radio stream with a real song on air: same slot competition
    // as isFavoritableTrack, but the action always adds (see getFavoriteIcon).
    val isFavoritableStream: Boolean,
    val isFavorite: Boolean,
    val isPlaying: Boolean,
    val imageUrl: String?,
    // Active audiobook chapter; elapsed/duration are chapter-relative and the artist line shows its name.
    val chapterName: String?,
    val elapsedTime: Long?,
    // SystemClock.elapsedRealtime() captured when [elapsedTime] was sampled.
    // Paired with elapsedTime when writing PlaybackStateCompat so Android's
    // extrapolation re-anchors at the correct wall clock — not 200ms later
    // when the debounced collector finally calls setState.
    val elapsedUpdateTimeMs: Long?,
    val playerName: String?,
    val duration: Long?,
) {
    companion object {
        /**
         * Builds the snapshot pushed to MediaSession. [effectiveElapsedSec]
         * is the freshly-extrapolated position at write-time (computed by the
         * data layer from anchor + elapsed wall clock). We use it directly
         * instead of `playerData.queueInfo.elapsedTime` because the latter is
         * only refreshed on `QueueAdded/UpdatedEvent` — not on the more
         * frequent `QueueTimeUpdatedEvent` — and would freeze the AA /
         * notification progress bar at a stale anchor on pause.
         *
         * [currentChapter] maps this snapshot's position/duration to chapter space;
         * the domain remains absolute.
         */
        fun from(
            playerData: PlayerData,
            multiplePlayers: Boolean,
            effectiveElapsedSec: Double?,
            currentChapter: ResolvedChapter? = null,
            // `library.write`: without it neither favorite slot is offered.
            canEditLibrary: Boolean,
            // Real on-air stream song AND the connected server can resolve it — see
            // MainDataSource.canFavoriteCurrentlyPlaying, the single source for this.
            isFavoritableStream: Boolean = false,
        ) = run {
            val currentTrack = playerData.queueInfo?.currentItem?.track as? AppMediaItem
            val streamFavorite = canEditLibrary && isFavoritableStream
            val longItemId = playerData.player.currentMedia?.queueItemId?.let(::sessionQueueItemId)
            MediaNotificationData(
            multiplePlayers = multiplePlayers,
            longItemId = longItemId,
            activeQueueItemId = playerData.queueInfo?.currentItem?.let { slot ->
                (slot.track as? Audiobook)?.chapters?.indexOf(currentChapter?.chapter)
                    ?.takeIf { it >= 0 }
                    ?.let { sessionChapterItemId(slot.id, it) }
            } ?: longItemId,
            name = playerData.player.currentMedia?.title,
            artist = playerData.player.currentMedia?.artist,
            album = playerData.player.currentMedia?.album,
            repeatMode = playerData.queueInfo?.repeatMode
                ?.takeIf { playerData.queueInfo?.isDynamicPlaylist != true },
            shuffleEnabled = playerData.queueInfo?.shuffleEnabled
                ?.takeIf { playerData.queueInfo?.isDynamicPlaylist != true },
            isLongFormContent = playerData.queueInfo?.currentItem?.track.isLongFormSpokenContent,
            isFavoritableTrack = canEditLibrary && currentTrack
                ?.let { it.mediaType == MediaType.TRACK && it.canBeFavorited } == true,
            isFavoritableStream = streamFavorite,
            // The station's own favorite flag would show a filled heart for an already-
            // favorited station even though nothing has been favorited for the on-air song,
            // so the stream case always renders un-filled.
            isFavorite = currentTrack?.favorite == true && !streamFavorite,
            isPlaying = playerData.player.isPlaying,
            imageUrl = playerData.player.currentMedia?.imageUrl,
            chapterName = currentChapter?.displayName,
            elapsedTime = effectiveElapsedSec
                ?.let { currentChapter?.relativeSec(it) ?: it }
                ?.toLong()?.let { it * 1000 },
            elapsedUpdateTimeMs = effectiveElapsedSec?.let { SystemClock.elapsedRealtime() },
            playerName = playerData.player.nameAndSuffix.takeIf { !playerData.isLocal },
            duration = (currentChapter?.duration ?: playerData.player.currentMedia?.duration)
                ?.toLong()?.let { it * 1000 },
            )
        }

        /**
         * True when [new] would publish the same session state as [old]. The position counts as
         * unchanged while it stays on the line that a host extrapolates from [old]: a playing
         * item advances with the wall clock, a paused one stands still. Comparing raw positions
         * would let every re-emission of a playing item through once a second has passed.
         */
        fun areTooSimilarToUpdate(old: MediaNotificationData, new: MediaNotificationData): Boolean {
            if (old.copy(elapsedTime = null, elapsedUpdateTimeMs = null) !=
                new.copy(elapsedTime = null, elapsedUpdateTimeMs = null)
            ) {
                return false
            }
            val oldElapsed = old.elapsedTime ?: return new.elapsedTime == null
            val newElapsed = new.elapsedTime ?: return false
            val wallClockAdvance = (new.elapsedUpdateTimeMs ?: 0L) - (old.elapsedUpdateTimeMs ?: 0L)
            val expectedAdvance = wallClockAdvance.takeIf { old.isPlaying } ?: 0L
            val drift = (newElapsed - oldElapsed) - expectedAdvance
            return abs(drift) <= ELAPSED_TIME_DRIFT_TOLERANCE_MS
        }
    }
}
