package io.music_assistant.client.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListMapChangedTest {
    private data class Row(val id: Int, val favorite: Boolean)

    private val rows = listOf(Row(1, false), Row(2, false), Row(3, false))

    @Test
    fun `no changed element gives null`() {
        assertNull(rows.mapChangedOrNull { it })
    }

    @Test
    fun `an equal but new instance counts as a change`() {
        assertEquals(rows, rows.mapChangedOrNull { if (it.id == 2) it.copy() else it })
    }

    @Test
    fun `changed elements are replaced in place and order is kept`() {
        val result = rows.mapChangedOrNull { if (it.id == 2) it.copy(favorite = true) else it }

        assertEquals(listOf(Row(1, false), Row(2, true), Row(3, false)), result)
    }

    @Test
    fun `an empty list gives null`() {
        assertNull(emptyList<Row>().mapChangedOrNull { it.copy(favorite = true) })
    }
}
