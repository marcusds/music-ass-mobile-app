package io.music_assistant.client.services

import android.content.Context
import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.SuccessResult
import io.music_assistant.client.imageloader.artworkImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Loads now-playing artwork with the canonical artwork request, so the session bitmap is
 * decoded at the bounded artwork size and shares its decoded-memory entry with the in-app
 * player artwork, instead of decoding the original image again for every track.
 */
internal suspend fun loadCoilBitmap(
    context: Context,
    imageLoader: ImageLoader,
    url: String,
): Bitmap? = (
    (imageLoader.execute(artworkImageRequest(context, url)) as? SuccessResult)
        ?.image as? BitmapImage
    )?.bitmap

/**
 * Pairs each upstream [MediaNotificationData] emission with the freshest
 * available bitmap. Bitmap loading runs on its own flow keyed by `imageUrl`;
 * a URL change cancels any in-flight fetch (via `mapLatest`) and starts a
 * new one. Slow or failing artwork never blocks downstream emissions — the
 * upstream data lands immediately paired with the last-known bitmap (or
 * `null` until the first one resolves), and a second emission replaces it
 * once the new bitmap arrives. Crucially the fetch is cancelled *only* on a
 * URL change, so frequent non-artwork updates (position ticks, play/pause)
 * never restart or starve a slow load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<MediaNotificationData>.withAsyncBitmap(
    scope: CoroutineScope,
    loadBitmap: suspend (String) -> Bitmap?,
): Flow<Pair<MediaNotificationData, Bitmap?>> {
    val bitmap = map { it.imageUrl }
        .distinctUntilChanged()
        .mapLatest { url -> url?.let { loadBitmap(it) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(), null)
    return combine(this, bitmap) { data, bmp -> data to bmp }
}
