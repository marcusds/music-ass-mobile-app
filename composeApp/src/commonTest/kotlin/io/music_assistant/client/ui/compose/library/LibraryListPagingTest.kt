package io.music_assistant.client.ui.compose.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryListPagingTest {
    @Test
    fun `a reload fetches the rows that are loaded`() {
        assertEquals(150, refreshLimit(loaded = 150, pageSize = 50, max = 500))
    }

    @Test
    fun `a reload fetches at least one page`() {
        assertEquals(50, refreshLimit(loaded = 0, pageSize = 50, max = 500))
    }

    @Test
    fun `a reload fetches at most the cap`() {
        assertEquals(500, refreshLimit(loaded = 2000, pageSize = 50, max = 500))
    }
}
