package io.music_assistant.client.ui.compose.home.players

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.music_assistant.client.data.model.client.MediaType
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.ResolvedChapter
import io.music_assistant.client.data.model.client.items.Audiobook
import io.music_assistant.client.data.model.client.toAbsoluteSeekSeconds
import io.music_assistant.client.ui.compose.common.CenteredThreeSlotRow
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import io.music_assistant.client.ui.inactive
import io.music_assistant.client.utils.formatDuration
import kotlinx.coroutines.flow.Flow
import kotlin.time.DurationUnit

private const val SEEK_STICK_EPSILON_SECONDS = 0.5f

// Buffered-ahead band uses the slider's own control tint, one alpha, no health tint. Sits just
// above the inactive track (0.4) so the buffered region reads a touch more opaque than "not yet
// buffered" without introducing a second color.
private const val BUFFER_TRACK_ALPHA = 0.45f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTimeline(
    item: PlayerData,
    positionState: State<Double>?,
    bufferedAheadSecFlow: Flow<Double>?,
    currentChapter: ResolvedChapter?,
    duration: Float?,
    controlTint: Color,
    playerAction: (PlayerData, PlayerAction) -> Unit,
    center: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentMedia = item.player.currentMedia
    val poweredOff = item.player.isPoweredOff

    val displayPosition = positionState?.value?.toFloat()
        ?: item.queueInfo?.elapsedTime?.toFloat()
        ?: 0f

    // Chapter presentation maps the slider to the active chapter.
    // Domain positions, seeks, and tracker values remain absolute.
    val timelineDuration = currentChapter?.duration?.toFloat() ?: duration
    val timelinePosition =
        currentChapter?.relativeSec(displayPosition.toDouble())?.toFloat() ?: displayPosition
    // A radio stream cannot seek, not even in a song whose length it reports.
    val seekable = timelineDuration != null && currentMedia?.mediaType != MediaType.RADIO

    // Buffered-ahead seconds (local player only; null → 0 for remote/preview).
    val bufferedAheadSec = bufferedAheadSecFlow
        ?.collectAsStateWithLifecycle(initialValue = 0.0)?.value?.toFloat() ?: 0f

    // Hold the released absolute seek until the tracker publishes its frozen anchor.
    // Drag values are timeline-relative; convert the latch for display so it reconciles
    // even when the seek lands in another chapter.
    var userDragPosition by remember { mutableStateOf<Float?>(null) }
    var releasedSeekPosition by remember { mutableStateOf<Float?>(null) }
    // Latch the chapter at drag start so boundary crossings cannot shift the thumb.
    // It outlives the gesture: the released latch is an absolute position in THIS
    // chapter, and [currentChapter] may already have moved on to the next one.
    var dragChapter by remember { mutableStateOf<ResolvedChapter?>(null) }

    // A queue-item change mid-drag (track end, Next from another controller)
    // invalidates the gesture: a release would seek the NEW item to the OLD
    // item's coordinates. Cancel the drag and drop the released-seek latch
    // (mirrors the web frontend clearing its pending seek on item change).
    LaunchedEffect(item.queueInfo?.currentItem?.id) {
        userDragPosition = null
        releasedSeekPosition = null
        dragChapter = null
    }

    LaunchedEffect(displayPosition, releasedSeekPosition) {
        val released = releasedSeekPosition ?: return@LaunchedEffect
        if (kotlin.math.abs(displayPosition - released) < SEEK_STICK_EPSILON_SECONDS) {
            releasedSeekPosition = null
            dragChapter = null
        }
    }

    val sliderPosition = when {
        poweredOff -> 0f
        else ->
            userDragPosition
            ?: releasedSeekPosition?.let { released ->
                dragChapter?.relativeSec(released.toDouble())?.toFloat() ?: released
            }
            ?: timelinePosition
    }

    val progressSliderColors = SliderDefaults.colors().copy(
        thumbColor = controlTint,
        activeTrackColor = controlTint,
        inactiveTrackColor = controlTint.inactive(),
    )
    Column(modifier = modifier) {
        Slider(
            value = sliderPosition,
            valueRange = timelineDuration?.let { 0f..it } ?: 0f..1f,
            enabled = seekable,
            onValueChange = {
                if (userDragPosition == null) dragChapter = currentChapter
                userDragPosition = it  // Track drag position locally
            },
            onValueChangeFinished = {
                userDragPosition?.let { seekPos ->
                    // Convert the latched chapter-relative value to an absolute,
                    // whole-second target to match server/tracker state.
                    val seekSeconds = dragChapter.toAbsoluteSeekSeconds(seekPos.toDouble())
                    releasedSeekPosition = seekSeconds.toFloat()
                    playerAction(item, PlayerAction.SeekTo(seekSeconds))
                    userDragPosition = null  // Clear drag state
                }
            },
            modifier = Modifier.fillMaxWidth(),
            thumb = {
                sliderPosition.takeIf { seekable }?.let {
                    SliderDefaults.Thumb(
                        interactionSource = remember { MutableInteractionSource() },
                        thumbSize = DpSize(16.dp, 16.dp),
                        colors = progressSliderColors,
                    )
                }
            },
            track = { sliderState ->
                val audiobook = item.queueInfo?.currentItem?.track as? Audiobook
                val chapters = audiobook?.chapters
                // Square inside corners leave a full or empty track, and the buffered band,
                // with a square outer end; the clip keeps both ends round.
                Box(modifier = Modifier.clip(CircleShape)) {
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        colors = progressSliderColors,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp,
                        drawStopIndicator = null,
                        enabled = currentMedia != null && !item.player.isAnnouncing && !poweredOff,
                        modifier = Modifier.height(8.dp),
                    )
                    // Buffered-ahead highlight — the region between the real playhead and
                    // the buffered point, at an alpha between the inactive (0.4) and active
                    // (1.0) track. Local player with audio buffered ahead; shown while paused
                    // too (pause keeps the buffer — only a genuine stop zeroes bufferedAhead).
                    if (item.isLocal &&
                        timelineDuration != null && timelineDuration > 0f && bufferedAheadSec > 0f
                    ) {
                        val playheadFraction =
                            (timelinePosition / timelineDuration).coerceIn(0f, 1f)
                        val bufferedFraction =
                            ((timelinePosition + bufferedAheadSec) / timelineDuration)
                                .coerceIn(0f, 1f)
                        // Same hue as the slider itself, just a touch more opaque than the
                        // inactive track — a subtle "already buffered" band, no health tint.
                        val bufferedColor = controlTint.copy(alpha = BUFFER_TRACK_ALPHA)
                        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                            val startX = playheadFraction * size.width
                            val endX = bufferedFraction * size.width
                            if (endX > startX) {
                                drawRect(
                                    color = bufferedColor,
                                    topLeft = Offset(startX, 0f),
                                    size = Size(endX - startX, size.height),
                                )
                            }
                        }
                    }
                    // Chapter ticks use absolute starts, so show them only on the full-book timeline.
                    if (currentChapter == null &&
                        !chapters.isNullOrEmpty() && duration != null && duration > 0f
                    ) {
                        val tickColor =
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                            chapters.drop(1).forEach { chapter ->
                                val fraction =
                                    (chapter.start.toFloat() / duration).coerceIn(0f, 1f)
                                val x = fraction * size.width
                                drawLine(
                                    color = tickColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 2.dp.toPx(),
                                )
                            }
                        }
                    }
                }
            },
        )

        CenteredThreeSlotRow(
            modifier = Modifier.fillMaxWidth(),
            start = {
                Text(
                    text = sliderPosition.takeIf { currentMedia != null }
                        .formatDuration(DurationUnit.SECONDS)
                        .takeIf { timelineDuration != null } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            center = center,
            end = {
                Text(
                    text = currentMedia.takeUnless { poweredOff }
                        ?.let { timelineDuration?.formatDuration(DurationUnit.SECONDS) ?: "\u221E" }
                        ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
