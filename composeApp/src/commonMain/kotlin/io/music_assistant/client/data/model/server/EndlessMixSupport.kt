package io.music_assistant.client.data.model.server

/**
 * First server API schema with the `radio_playlist` provider: an endless mix is the dynamic
 * playlist `radio_playlist://playlist/<seed uri>`, and `play_media(radio_mode=true)` is
 * deprecated. The commit that added the provider (server 2.10 development) left
 * `API_SCHEMA_VERSION` at 34, so 35 is the first version that guarantees it. Server 2.9 and
 * older (schema 31 and lower) know only `radio_mode`.
 */
const val ENDLESS_MIX_PLAYLIST_MIN_SCHEMA = 35

private const val ENDLESS_MIX_PLAYLIST_PREFIX = "radio_playlist://"

fun supportsEndlessMixPlaylist(schemaVersion: Int?): Boolean =
    schemaVersion != null && schemaVersion >= ENDLESS_MIX_PLAYLIST_MIN_SCHEMA

/** The endless mix playlist URI for a seed item URI. A URI that already is one stays unchanged. */
fun endlessMixPlaylistUri(seedUri: String): String =
    seedUri.takeIf { it.startsWith(ENDLESS_MIX_PLAYLIST_PREFIX) }
        ?: "${ENDLESS_MIX_PLAYLIST_PREFIX}playlist/$seedUri"
