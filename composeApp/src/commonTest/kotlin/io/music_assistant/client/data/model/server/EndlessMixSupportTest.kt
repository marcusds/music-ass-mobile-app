package io.music_assistant.client.data.model.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the endless mix playlist gate. The server commit that added the `radio_playlist`
 * provider left `API_SCHEMA_VERSION` at 34, so 34 must stay on `radio_mode`.
 */
class EndlessMixSupportTest {
    @Test
    fun unknownSchemaIsUnsupported() {
        assertFalse(supportsEndlessMixPlaylist(null))
    }

    @Test
    fun serverTwoNineAndTheUnbumpedSchemaAreUnsupported() {
        assertFalse(supportsEndlessMixPlaylist(31))
        assertFalse(supportsEndlessMixPlaylist(34))
    }

    @Test
    fun schemaAtOrAboveThresholdIsSupported() {
        assertTrue(supportsEndlessMixPlaylist(35))
        assertTrue(supportsEndlessMixPlaylist(LOCAL_SCHEMA_VERSION))
        assertTrue(supportsEndlessMixPlaylist(SERVER_2_11_SCHEMA))
    }

    @Test
    fun seedUriIsWrappedRaw() {
        assertEquals(
            "radio_playlist://playlist/spotify://track/abc",
            endlessMixPlaylistUri("spotify://track/abc"),
        )
        assertEquals(
            "radio_playlist://playlist/library://artist/12",
            endlessMixPlaylistUri("library://artist/12"),
        )
    }

    @Test
    fun anEndlessMixUriIsNotWrappedTwice() {
        val uri = "radio_playlist://playlist/library://album/3"
        assertEquals(uri, endlessMixPlaylistUri(uri))
    }
}
