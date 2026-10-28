package io.music_assistant.client.ui.compose.home.players

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.music_assistant.client.data.LocalPlayerStatus
import io.music_assistant.client.data.model.client.Player
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.PlayerDataFixtures
import io.music_assistant.client.data.model.client.QualityTier
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.client.items.Audiobook
import io.music_assistant.client.data.model.client.items.PodcastEpisode
import io.music_assistant.client.data.model.client.items.Track
import io.music_assistant.client.data.model.client.items.canBeFavorited
import io.music_assistant.client.data.model.client.presentationChapter
import io.music_assistant.client.data.model.client.qualityTier
import io.music_assistant.client.imageloader.ArtworkSizeClass
import io.music_assistant.client.imageloader.rememberArtworkRequest
import io.music_assistant.client.ui.alphaOn
import io.music_assistant.client.ui.compose.common.PlayerColors
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import io.music_assistant.client.ui.compose.common.icons.AlbumIcon
import io.music_assistant.client.ui.compose.common.icons.TrackIcon
import io.music_assistant.client.ui.compose.common.items.artistNavigation
import io.music_assistant.client.ui.compose.common.painters.rememberPlaceholderPainter
import io.music_assistant.client.ui.inactive
import io.music_assistant.client.ui.utils.fadingEdges
import kotlinx.coroutines.flow.Flow
import musicassistantclient.composeapp.generated.resources.Res
import musicassistantclient.composeapp.generated.resources.action_go_to_artist
import musicassistantclient.composeapp.generated.resources.action_go_to_track
import musicassistantclient.composeapp.generated.resources.cd_ambient_sound_on
import musicassistantclient.composeapp.generated.resources.cd_favorite
import musicassistantclient.composeapp.generated.resources.cd_lyrics
import musicassistantclient.composeapp.generated.resources.cd_playing
import musicassistantclient.composeapp.generated.resources.player_needs_setup
import musicassistantclient.composeapp.generated.resources.player_power_on
import musicassistantclient.composeapp.generated.resources.player_powered_off
import musicassistantclient.composeapp.generated.resources.player_standby
import musicassistantclient.composeapp.generated.resources.players_nothing
import musicassistantclient.composeapp.generated.resources.queue_cannot_play
import org.jetbrains.compose.resources.stringResource

@Composable
fun CompactPlayerItem(
    modifier: Modifier,
    item: PlayerData,
    colors: PlayerColors,
    playerAction: (PlayerData, PlayerAction) -> Unit = { _, _ -> },
    onSelectPlayer: (() -> Unit)? = null,
    showAdditionalControls: Boolean = false,
    sendSpinState: LocalPlayerStatus?,
    // Server preference gate for chapter-based Next enablement and the chapter subtitle.
    chapterProgressEnabled: Boolean = true,
    livePositionFlow: Flow<Double>? = null,
) {
    val currentMedia = item.player.currentMedia
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    // Derived so the position tick recomposes this item only at chapter boundaries.
    val positionState = livePositionFlow
        ?.collectAsStateWithLifecycle(initialValue = item.queueInfo?.elapsedTime ?: 0.0)
    val chapterName by remember(item, chapterProgressEnabled, positionState) {
        derivedStateOf {
            item.takeIf { chapterProgressEnabled && !it.player.isPoweredOff }
                ?.presentationChapter(positionState?.value ?: item.queueInfo?.elapsedTime)
                ?.displayName
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // Album cover on the far left
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.dominant.alphaOn(currentMedia != null)),
                contentAlignment = Alignment.Center,
            ) {
                if (currentMedia != null) {
                    val placeholder = rememberPlaceholderPainter(
                        backgroundColor = colors.dominant,
                        iconColor = onPrimaryContainer,
                        icon = TrackIcon,
                    )
                    AsyncImage(
                        placeholder = placeholder,
                        fallback = placeholder,
                        error = placeholder,
                        model = rememberArtworkRequest(currentMedia.imageUrl, ArtworkSizeClass.COLOR_SOURCE),
                        contentDescription = currentMedia.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = AlbumIcon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = onPrimaryContainer.inactive(),
                    )
                }
            }

            // Track info
            val poweredOff = item.player.isPoweredOff
            val (trackName, trackContentDescription) = item.player.dormantLabel()
                ?.let { it to it }
                ?: trackNameAndContentDescription(currentMedia?.title)
            // Leading inset == fade width: at rest the left gradient covers only this empty pad
            // (first glyph crisp); the marquee scrolls the [pad][text] unit so text dissolves
            // toward the artwork when it overflows.
            val marqueeFade = 16.dp
            Column(
                modifier = Modifier
                    .clearAndSetSemantics {
                        contentDescription = trackContentDescription
                    },
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fadingEdges(marqueeFade)
                        .basicMarquee()
                        .padding(start = marqueeFade)
                        .alphaOn(poweredOff || currentMedia?.title != null),
                    text = trackName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Powered off: no subtitle line.
                if (!poweredOff) {
                    (chapterName ?: currentMedia?.subtitle)?.let {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fadingEdges(marqueeFade)
                                .basicMarquee()
                                .padding(start = marqueeFade),
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } ?: run {
                        if (item.queueInfo?.currentItem?.isPlayable == showAdditionalControls) {
                            Text(
                                modifier = Modifier.padding(horizontal = marqueeFade),
                                text = stringResource(Res.string.queue_cannot_play),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.inactive(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (item.player.isPoweredOff) {
            IconButton(
                modifier = Modifier.size(48.dp),
                onClick = { playerAction(item, PlayerAction.SetPower(true)) },
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = stringResource(Res.string.player_power_on),
                    tint = colors.controlTint,
                )
            }
        } else {
            PlayerControls(
                playerData = item,
                playerAction = playerAction,
                showAdditionalButtons = showAdditionalControls,
                mainButtonSize = 48.dp,
                showSkip = true,
                showSkipBack = onSelectPlayer != null,
                tint = colors.controlTint,
                chapterProgressEnabled = chapterProgressEnabled,
            )
        }

        if (onSelectPlayer != null) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                PlayerSelectionButton(
                    player = item,
                    controlTint = colors.controlTint,
                    sendSpinState = sendSpinState,
                    onSelectPlayer = onSelectPlayer,
                )
            }
        }
    }
}

@Composable
private fun trackNameAndContentDescription(title: String?): Pair<String, String> {
    val playingContentDescription = if (title != null) {
        stringResource(Res.string.cd_playing, title)
    } else {
        stringResource(Res.string.players_nothing)
    }

    return Pair(title ?: stringResource(Res.string.players_nothing), playingContentDescription)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerItem(
    modifier: Modifier,
    item: PlayerData,
    colors: PlayerColors,
    playerAction: (PlayerData, PlayerAction) -> Unit,
    // Null hides the heart: the user cannot edit the library.
    onFavoriteChange: ((AppMediaItem, Boolean?) -> Unit)?,
    onFavoriteStreamClick: (PlayerData) -> Unit = {},
    // See MainDataSource.canFavoriteCurrentlyPlaying: on-air song AND server support.
    canFavoriteStream: Boolean = false,
    livePositionFlow: Flow<Double>?,
    bufferedAheadSecFlow: Flow<Double>? = null,
    lyricsAvailable: Boolean = false,
    onLyricsClick: () -> Unit = {},
    // The quality pill and the speed pill only ask for their dialog; the dialogs themselves
    // live outside the pager so a page change cannot tear them down mid-gesture.
    onAudioChainClick: () -> Unit = {},
    onPlaybackSpeedClick: () -> Unit = {},
    // Null hides the ambient sound pill: the feature is unavailable for this queue.
    onAmbientSoundClick: (() -> Unit)? = null,
    // Server preference gate for the chapter-relative timeline.
    chapterProgressEnabled: Boolean = true,
    navigateToItem: (AppMediaItem) -> Unit = {},
    // The chooser for a multi-artist track lives in the dialog host, outside the pager.
    onChooseArtist: () -> Unit = {},
) {
    val currentMedia = item.player.currentMedia
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val controlTint = colors.controlTint

    // Do not add padding here - title/subtitle should be full width.
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = FULL_PLAYER_HORIZONTAL_PADDING)
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .heightIn(max = 500.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.dominant.alphaOn(currentMedia != null)),
            contentAlignment = Alignment.Center,
        ) {
            currentMedia?.imageUrl?.let {
                val placeholder =
                    rememberPlaceholderPainter(
                        backgroundColor = colors.dominant,
                        iconColor = onPrimaryContainer,
                        icon = currentMedia.defaultIcon,
                    )
                AsyncImage(
                    placeholder = placeholder,
                    fallback = placeholder,
                    error = placeholder,
                    model = rememberArtworkRequest(it, ArtworkSizeClass.COLOR_SOURCE),
                    contentDescription = currentMedia.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Icon(
                imageVector = currentMedia?.defaultIcon ?: AlbumIcon,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = onPrimaryContainer,
            )
        }

        // Track info
        val poweredOff = item.player.isPoweredOff
        val (trackName, trackContentDescription) = item.player.dormantLabel()
            ?.let { it to it }
            ?: trackNameAndContentDescription(currentMedia?.title)

        // Powered off: present the "no media" state — disabled slider, empty time labels.
        val duration = if (poweredOff) null else currentMedia?.duration?.takeIf { it > 0 }?.toFloat()

        // Live position from PlayerPositionTracker — single source of truth shared with
        // notification + Android Auto. Only PlayerTimeline and the derived chapter below read
        // it, so the position tick does not recompose the art, title and controls.
        val positionState = livePositionFlow
            ?.collectAsStateWithLifecycle(initialValue = item.queueInfo?.elapsedTime ?: 0.0)

        // Chapter presentation maps the slider/subtitle to the active chapter.
        // Domain positions, seeks, and tracker values remain absolute.
        // Derived so the position tick recomposes this item only at chapter boundaries.
        val currentChapter by remember(item, chapterProgressEnabled, poweredOff, positionState) {
            derivedStateOf {
                item.takeIf { chapterProgressEnabled && !poweredOff }
                    ?.presentationChapter(positionState?.value ?: item.queueInfo?.elapsedTime ?: 0.0)
            }
        }

        val titleTrack = (item.queueInfo?.currentItem?.track as? Track)?.takeIf { !poweredOff }
        // The title does the overflow's "Go to track".
        // Remembered: the position tick recomposes this item several times a second.
        val onTitleClick = remember(titleTrack, navigateToItem) {
            titleTrack?.let { track -> { navigateToItem(track) } }
        }
        val goToTrackLabel = stringResource(Res.string.action_go_to_track)
        // The artist line does the overflow's "Go to artist"; a chapter name in that line has no artist.
        val artists = titleTrack?.takeIf { currentChapter == null }?.artists.orEmpty()
        val onSubtitleClick = remember(artists, navigateToItem, onChooseArtist) {
            artistNavigation(artists, navigateToItem) { onChooseArtist() }
        }
        val goToArtistLabel = stringResource(Res.string.action_go_to_artist)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = trackContentDescription
                    // The cleared subtree hides the lines' own clicks, so expose them here.
                    onTitleClick?.let {
                        customActions = listOf(
                            CustomAccessibilityAction(goToTrackLabel) {
                                it()
                                true
                            },
                        )
                    }
                    onSubtitleClick?.let {
                        onClick(goToArtistLabel) {
                        it()
                    true
                    }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(onTitleClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .fadingEdges()
                    .basicMarquee()
                    .padding(horizontal = FULL_PLAYER_HORIZONTAL_PADDING)
                    .alphaOn(poweredOff || currentMedia?.title != null),
                text = trackName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                // Powered off: no subtitle line.
                poweredOff -> Unit
                item.queueInfo?.currentItem?.isPlayable == false -> {
                    Text(
                        text = stringResource(Res.string.queue_cannot_play),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.inactive(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                else -> {
                    // Always render the subtitle line so every player item keeps the same height,
                    // even when blank. But attach `basicMarquee()` ONLY when there's real text:
                    // marquee on a blank string builds a degenerate layer tree that overflows the
                    // RenderThread's native stack (SIGSEGV in HWUI prepareTree) — no-subtitle radios
                    // hit this. The empty Text still reserves one line; it just doesn't scroll.
                    // Chapter mode uses the active chapter name as the subtitle.
                    val subtitle = currentChapter?.displayName ?: currentMedia?.subtitle
                    Text(
                        modifier = Modifier.fillMaxWidth()
                            .then(onSubtitleClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                            .then(
                                if (subtitle.isNullOrBlank()) {
                                    Modifier
                                } else {
                                    Modifier
                                        .fadingEdges()
                                        .basicMarquee()
                                        .padding(horizontal = FULL_PLAYER_HORIZONTAL_PADDING)
                                },
                            )
                            .alphaOn(currentMedia?.title != null),
                        text = subtitle.orEmpty(), // TODO take from currentItem?
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Duration labels
        val currentQueueItem = item.queueInfo?.currentItem
        val tier = currentQueueItem?.qualityTier
        val isLq = tier == QualityTier.LQ

        // Variable speed: server-supported only for audiobooks/podcasts, and only
        // when the queue payload carries `playback_speed` (feature-detect gate).
        val playbackSpeed = item.queueInfo?.playbackSpeed
        val isSpokenContent = currentQueueItem?.track is Audiobook ||
                currentQueueItem?.track is PodcastEpisode
        val showSpeed = isSpokenContent && playbackSpeed != null && !poweredOff
        val onAmbientSound = onAmbientSoundClick
            ?.takeIf { item.queueInfo?.overlay?.enabled == true && !poweredOff }

        PlayerTimeline(
            item = item,
            positionState = positionState,
            bufferedAheadSecFlow = bufferedAheadSecFlow,
            currentChapter = currentChapter,
            duration = duration,
            controlTint = controlTint,
            playerAction = playerAction,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FULL_PLAYER_HORIZONTAL_PADDING),
            center = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSpeed) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.controlTint)
                                .clickable(onClick = onPlaybackSpeedClick)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${formatDecimal(snapSpeed(playbackSpeed), 2)}x",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (colors.controlTint.luminance() > 0.5f) {
                                    Color.Black
                                } else {
                                    Color.White
                                },
                            )
                        }
                    } else if ((tier != null && !poweredOff) || onAmbientSound == null) {
                        // A hidden badge stays as an invisible placeholder that keeps the
                        // slot height, unless the ambient pill holds the slot by itself.
                        Box(
                            modifier = Modifier
                                .alpha(if (tier != null && !poweredOff) 1f else 0f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isLq) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        colors.controlTint
                                    },
                                )
                                .clickable(enabled = tier != null, onClick = onAudioChainClick)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = (tier ?: QualityTier.LQ).name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isLq) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    if (colors.controlTint.luminance() > 0.5f) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    }
                                },
                            )
                        }
                    }
                    onAmbientSound?.let { AmbientSoundPill(tint = colors.controlTint, onClick = it) }
                }
            },
        )

        // Powered off: favorite + transport controls give way to a single power-on button,
        // sized to match the play/pause control.
        if (poweredOff) {
            IconButton(
                modifier = Modifier.size(60.dp),
                onClick = { playerAction(item, PlayerAction.SetPower(true)) },
            ) {
                Icon(
                    modifier = Modifier.size(48.dp),
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = stringResource(Res.string.player_power_on),
                    tint = controlTint,
                )
            }
            return@Column
        }

        // Favorite flag lives on the queue's current Track, not on the lightweight
        // `currentMedia`, so the heart reads from there.
        val currentTrack = item.queueInfo?.currentItem?.track as? AppMediaItem
        val favoriteSlot = 48.dp // Material IconButton size; mirrored by the trailing spacer.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FULL_PLAYER_HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canFavoriteStream) {
                // Radio favourite adds the on-air song to the library; the queue's `favorite`
                // flag is the station's, not the song's, so there is no "already favourited"
                // state and the heart always renders un-filled.
                IconButton(
                    modifier = Modifier.size(favoriteSlot),
                    onClick = { onFavoriteStreamClick(item) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(Res.string.cd_favorite),
                        tint = colors.controlTint,
                    )
                }
            } else if (currentTrack?.canBeFavorited == true && onFavoriteChange != null) {
                PlayerFavoriteButton(
                    item = currentTrack,
                    tint = colors.controlTint,
                    onFavoriteChange = onFavoriteChange,
                    modifier = Modifier.size(favoriteSlot),
                )
            } else {
                Spacer(Modifier.size(favoriteSlot)) // keep controls centered when heart is hidden
            }
            PlayerControls(
                playerData = item,
                playerAction = playerAction,
                mainButtonSize = 60.dp,
                tint = controlTint,
                chapterProgressEnabled = chapterProgressEnabled,
            )
            // Mirrors the heart slot: lyrics button when available, else a spacer
            // so the transport controls stay centered.
            if (lyricsAvailable) {
                IconButton(
                    modifier = Modifier.size(favoriteSlot),
                    onClick = onLyricsClick,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lyrics,
                        contentDescription = stringResource(Res.string.cd_lyrics),
                        tint = colors.controlTint,
                    )
                }
            } else {
                Spacer(Modifier.size(favoriteSlot))
            }
        }
    }
}

private val FULL_PLAYER_HORIZONTAL_PADDING = 16.dp

private val previewPoweredOffColors = PlayerColors(dominant = Color.DarkGray, controlTint = Color.White)

/**
 * The line that replaces the track title while the player renders nothing, or null while it
 * plays. [Player.isPoweredOff] covers both a switched-off device and one the server cannot
 * reach any more, so the two are told apart here to word the label correctly.
 */
@Composable
private fun Player.dormantLabel(): String? = when {
    needsSetup -> stringResource(Res.string.player_needs_setup)
    !isAvailable -> stringResource(Res.string.player_standby)
    isPoweredOff -> stringResource(Res.string.player_powered_off)
    else -> null
}

@Preview
@Composable
private fun CompactPlayerItemPoweredOffPreview() {
    MaterialTheme {
        CompactPlayerItem(
            modifier = Modifier,
            item = PlayerDataFixtures.playerData(canPower = true, isPowered = false),
            colors = previewPoweredOffColors,
            onSelectPlayer = {},
            sendSpinState = null,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun FullPlayerItemPoweredOffPreview() {
    MaterialTheme {
        FullPlayerItem(
            modifier = Modifier,
            item = PlayerDataFixtures.playerData(canPower = true, isPowered = false),
            colors = previewPoweredOffColors,
            playerAction = { _, _ -> },
            onFavoriteChange = null,
            livePositionFlow = null,
        )
    }
}

/** Shows that an ambient sound plays with the queue. Opens the ambient sound dialog. */
@Composable
private fun AmbientSoundPill(tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Waves,
            contentDescription = stringResource(Res.string.cd_ambient_sound_on),
            // As tall as one line of the text badges, at every font scale.
            modifier = Modifier.size(
                with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() },
            ),
            tint = if (tint.luminance() > 0.5f) Color.Black else Color.White,
        )
    }
}
