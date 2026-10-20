package io.music_assistant.client.utils

/**
 * Returns this list with each element passed through [transform], or null when [transform] returns
 * every element unchanged (the same instance). A null result lets a caller skip the list update
 * and any work that follows it.
 */
inline fun <T> List<T>.mapChangedOrNull(transform: (T) -> T): List<T>? {
    var changed = false
    val mapped = map { element -> transform(element).also { if (it !== element) changed = true } }
    return mapped.takeIf { changed }
}
