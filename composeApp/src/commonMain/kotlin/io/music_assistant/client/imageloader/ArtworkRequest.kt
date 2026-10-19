package io.music_assistant.client.imageloader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.music_assistant.client.utils.disableHardwareBitmaps

/**
 * Pixel size every artwork is decoded at on color surfaces. Comfortably above kmpalette's internal
 * ~112x112 quantization area, so the extracted palette is identical regardless of how large the
 * artwork is drawn — and large enough that the hero now-playing art stays crisp.
 */
const val ARTWORK_DECODE_SIZE = 512

private fun artworkCacheKey(url: String) = "artwork@$ARTWORK_DECODE_SIZE:$url"

/**
 * How an artwork `AsyncImage` is decoded.
 */
enum class ArtworkSizeClass {
    /**
     * Artwork whose colors are also extracted (now-playing art, item detail header). Decoded once
     * at [ARTWORK_DECODE_SIZE] as a software bitmap and shared with the palette extractor.
     */
    COLOR_SOURCE,

    /**
     * List and grid thumbnails. Decoded at the size of the composable, with hardware bitmaps
     * allowed. Uses the same memory-cache entry as a plain url model.
     */
    THUMBNAIL,
}

/**
 * Canonical Coil request driving BOTH artwork display and on-device color extraction. A single
 * fixed decode size plus a shared memory-cache key means Coil decodes the bitmap exactly once and
 * serves it to the AsyncImage and the palette extractor alike (no second decode). The fixed size
 * makes the palette deterministic — independent of the widget's on-screen size and of which
 * surface loaded first. Software bitmaps are forced so the extractor can read pixels.
 */
fun artworkImageRequest(context: PlatformContext, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(ARTWORK_DECODE_SIZE)
        .disableHardwareBitmaps()
        .memoryCacheKey(artworkCacheKey(url))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .build()

/**
 * Coil request for a list or grid thumbnail. The size comes from the AsyncImage constraints, so a
 * small row holds a small bitmap, and the platform default bitmap config applies.
 */
fun thumbnailImageRequest(context: PlatformContext, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .build()

/**
 * Compose entry point for displaying artwork. Use [ArtworkSizeClass.COLOR_SOURCE] only where the
 * same url feeds color extraction; every other surface uses [ArtworkSizeClass.THUMBNAIL]. Returns
 * null for a null [url] (AsyncImage then shows its placeholder/fallback). Remembered per url so
 * the request isn't rebuilt each recomposition.
 */
@Composable
fun rememberArtworkRequest(url: String?, sizeClass: ArtworkSizeClass): ImageRequest? {
    val context = LocalPlatformContext.current
    return remember(url, sizeClass, context) {
        url?.let {
            when (sizeClass) {
                ArtworkSizeClass.COLOR_SOURCE -> artworkImageRequest(context, it)
                ArtworkSizeClass.THUMBNAIL -> thumbnailImageRequest(context, it)
            }
        }
    }
}
