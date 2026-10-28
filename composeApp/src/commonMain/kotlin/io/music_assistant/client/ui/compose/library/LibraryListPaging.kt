package io.music_assistant.client.ui.compose.library

/**
 * The row count of a background reload: the rows that are [loaded] already, at least one
 * [pageSize] and at most [max], because the server sends a list in one message.
 */
internal fun refreshLimit(loaded: Int, pageSize: Int, max: Int): Int =
    loaded.coerceAtMost(max).coerceAtLeast(pageSize)
