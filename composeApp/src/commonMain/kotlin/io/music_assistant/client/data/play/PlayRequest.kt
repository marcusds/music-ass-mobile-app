package io.music_assistant.client.data.play

import io.music_assistant.client.api.Request
import io.music_assistant.client.data.model.client.QueueOption
import io.music_assistant.client.data.model.server.endlessMixPlaylistUri
import io.music_assistant.client.data.model.server.supportsEndlessMixPlaylist

/** One `player_queues/play_media` request. All fields together are its identity. */
data class PlayRequest(
    val queueOrPlayerId: String,
    val media: List<String>,
    val option: QueueOption,
    /** Play an endless mix (similar music) seeded by each item of [media] instead of the items. */
    val endlessMixMode: Boolean = false,
    /** Server-side "start playback from this item id within [media]" (play-from-here). */
    val startItem: String? = null,
    /** `shuffle=true`: the server shuffles the media before it plays. Only `play` and `replace` honor it. */
    val shuffle: Boolean = false,
    /** `start_from_beginning`: the server ignores the saved resume position of a podcast episode. */
    val startFromBeginning: Boolean = false,
)

/**
 * The wire request for [schemaVersion]. A server with the `radio_playlist` provider gets each seed
 * as its endless mix playlist URI. An older server, or one whose schema is not known yet, gets
 * `radio_mode`, which every server accepts: a newer one translates it to the same playlists.
 */
internal fun PlayRequest.toPlayMediaRequest(schemaVersion: Int?): Request {
    val asPlaylists = endlessMixMode && supportsEndlessMixPlaylist(schemaVersion)
    return Request.Library.play(
        media = if (asPlaylists) media.map(::endlessMixPlaylistUri) else media,
        queueOrPlayerId = queueOrPlayerId,
        option = option,
        endlessMixMode = endlessMixMode && !asPlaylists,
        startItem = startItem,
        shuffle = shuffle,
        startFromBeginning = startFromBeginning,
    )
}
