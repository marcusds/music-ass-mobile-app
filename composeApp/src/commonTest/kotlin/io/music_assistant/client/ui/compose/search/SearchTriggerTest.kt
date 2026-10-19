package io.music_assistant.client.ui.compose.search

import io.music_assistant.client.ui.compose.search.SearchViewModel.Companion.searchTriggerAction
import io.music_assistant.client.ui.compose.search.SearchViewModel.SearchTriggerAction
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTriggerTest {
    @Test
    fun `typing searches from two characters`() {
        assertEquals(SearchTriggerAction.SEARCH, searchTriggerAction("ab", explicit = false))
        assertEquals(SearchTriggerAction.SEARCH, searchTriggerAction("beatles", explicit = false))
    }

    @Test
    fun `typing one character does not search`() {
        assertEquals(SearchTriggerAction.IGNORE, searchTriggerAction("a", explicit = false))
        assertEquals(SearchTriggerAction.IGNORE, searchTriggerAction(" a ", explicit = false))
    }

    @Test
    fun `the search key searches any query that is not blank`() {
        assertEquals(SearchTriggerAction.SEARCH, searchTriggerAction("a", explicit = true))
    }

    @Test
    fun `a blank query clears the results`() {
        assertEquals(SearchTriggerAction.CLEAR, searchTriggerAction("", explicit = false))
        assertEquals(SearchTriggerAction.CLEAR, searchTriggerAction("  ", explicit = true))
    }
}
