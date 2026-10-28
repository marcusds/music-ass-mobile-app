package io.music_assistant.client.data.play

import io.music_assistant.client.api.Request
import io.music_assistant.client.data.model.client.QueueOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayRequestTest {
    @Test
    fun `an endless mix on a 2_10 or newer server plays the endless mix playlist`() {
        val args = endlessMix("library://track/7").toPlayMediaRequest(schemaVersion = 65).args()

        assertEquals(listOf("radio_playlist://playlist/library://track/7"), args.media())
        assertFalse(args.radioMode())
        assertEquals("replace", args["option"]?.jsonPrimitive?.content)
        assertEquals("queue", args["queue_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an endless mix on a 2_9 server uses radio_mode`() {
        val args = endlessMix("library://album/3").toPlayMediaRequest(schemaVersion = 31).args()

        assertEquals(listOf("library://album/3"), args.media())
        assertTrue(args.radioMode())
    }

    @Test
    fun `an endless mix without a known schema uses radio_mode which every server accepts`() {
        val args = endlessMix("library://artist/1").toPlayMediaRequest(schemaVersion = null).args()

        assertEquals(listOf("library://artist/1"), args.media())
        assertTrue(args.radioMode())
    }

    @Test
    fun `every seed becomes its own endless mix`() {
        val request = PlayRequest(
            queueOrPlayerId = "queue",
            media = listOf("library://track/1", "spotify://playlist/x"),
            option = QueueOption.REPLACE,
            endlessMixMode = true,
        )

        assertEquals(
            listOf(
                "radio_playlist://playlist/library://track/1",
                "radio_playlist://playlist/spotify://playlist/x",
            ),
            request.toPlayMediaRequest(schemaVersion = 96).args().media(),
        )
    }

    @Test
    fun `a plain play is unchanged on every server`() {
        val request = PlayRequest(
            queueOrPlayerId = "queue",
            media = listOf("library://album/3"),
            option = QueueOption.PLAY,
            startItem = "42",
        )

        listOf(null, 31, 65, 96).forEach { schema ->
            val args = request.toPlayMediaRequest(schema).args()
            assertEquals(listOf("library://album/3"), args.media())
            assertFalse(args.radioMode())
            assertEquals("42", args["start_item"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `no start item is sent when none is set`() {
        assertNull(endlessMix("library://track/7").toPlayMediaRequest(65).args()["start_item"])
    }

    @Test
    fun `shuffle and play from beginning reach the wire`() {
        val request = PlayRequest(
            queueOrPlayerId = "queue",
            media = listOf("library://playlist/2"),
            option = QueueOption.REPLACE,
            shuffle = true,
            startFromBeginning = true,
        )

        val args = request.toPlayMediaRequest(schemaVersion = 96).args()

        assertTrue(args["shuffle"]?.jsonPrimitive?.boolean == true)
        assertTrue(args["start_from_beginning"]?.jsonPrimitive?.boolean == true)
        assertFalse(args.radioMode())
    }

    private fun endlessMix(uri: String) = PlayRequest(
        queueOrPlayerId = "queue",
        media = listOf(uri),
        option = QueueOption.REPLACE,
        endlessMixMode = true,
    )

    private fun Request.args(): JsonObject = args ?: JsonObject(emptyMap())

    private fun JsonObject.media(): List<String> =
        get("media")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.radioMode(): Boolean = get("radio_mode")?.jsonPrimitive?.boolean ?: false
}
