/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.imageLoader
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import kotlin.math.min

class CoilBitmapLoader @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(coilCoroutine),
    private val data: LocalArtworkPath = LocalArtworkPath(null),
) : Fetcher, BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: drawPlaceholder(context)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future {
            try {
                // local images
                val result = if (uri.toString().startsWith("/storage/")) {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(LocalArtworkPath(uri.toString()))
                            .allowHardware(false)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                } else {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .build()
                    )
                }
                if (result is ErrorResult) {
                    reportException(ExecutionException(result.throwable))
                    return@future drawPlaceholder(context)
                }

                result.image!!.toBitmap()
            } catch (e: Exception) {
                reportException(ExecutionException(e))
                return@future drawPlaceholder(context)
            }
        }

    override suspend fun fetch(): FetchResult? = withContext(coilCoroutine) {
        try {
            if (data.path?.startsWith("/storage/") == true) {
                val decoded = try {
                    extractEmbeddedArtwork(data.path)?.let { art ->
                        decodeLocalArtwork(art, data.x, data.y)
                    }
                } catch (e: Exception) {
                    null
                } ?: drawPlaceholder(context).let { placeholder ->
                    resizeLocalArtwork(
                        placeholder,
                        localArtworkDecodePlan(placeholder.width, placeholder.height, data.x, data.y),
                        placeholder.width,
                        placeholder.height,
                    )
                }

                ImageFetchResult(
                    image = decoded.bitmap.asImage(),
                    isSampled = decoded.isSampled,
                    dataSource = DataSource.DISK
                )
            } else {
                null
            }
        } catch (e: Exception) {
            reportException(e)
            ImageFetchResult(
                image = drawPlaceholder(context).asImage(),
                isSampled = false,
                dataSource = DataSource.MEMORY
            )
        }
    }

    companion object {
        // TODO: re eval dimens after a few months
        /**
         * Draw a centered square app icon with the maximum possible size while maintaining aspect ratio.
         *
         * @param context
         * @param x Desired final x dimension
         * @param y Desired final y dimension
         * @param size Percentage size of valid draw frame. Must be a value between 0.0 and 1.0. For example, 0.8
         *      means that inner frame should be 80% of the size of the final frame, and centered within that frame.
         */
        fun drawPlaceholder(context: Context, x: Int = 2000, y: Int = 2000, size: Float = 0.8f): Bitmap {
            val padding = size.coerceIn(0f, 1f)
            val innerRecWidth = x * padding
            val innerRecHeight = y * padding

            val squareLength = min(innerRecWidth, innerRecHeight).toInt()
            val squareLeft = ((x - squareLength) / 2)
            val squareTop = ((y - squareLength) / 2)

            val drawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.placeholder_icon)
            val bitmap = Bitmap.createBitmap(x, y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawable?.setBounds(squareLeft, squareTop, squareLeft + squareLength, squareTop + squareLength)
            drawable?.draw(canvas)
            return bitmap
        }
    }

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<LocalArtworkPath> {
        override fun create(data: LocalArtworkPath, options: Options, imageLoader: ImageLoader): Fetcher? {
            return CoilBitmapLoader(context, data = data)
        }
    }
}

internal data class LocalArtworkDecodePlan(val width: Int, val height: Int, val sampleSize: Int)

/** Retain the existing output dimensions without decoding more source pixels than they need. */
internal fun localArtworkDecodePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): LocalArtworkDecodePlan {
    require(sourceWidth > 0 && sourceHeight > 0)
    if (requestedWidth <= 0 || requestedHeight <= 0) {
        return LocalArtworkDecodePlan(sourceWidth, sourceHeight, 1)
    }

    val width: Int
    val height: Int
    if (sourceWidth == sourceHeight) {
        // Keep the existing behavior for explicitly requested rectangular frames too.
        width = requestedWidth
        height = requestedHeight
    } else {
        val scale = minOf(requestedWidth.toFloat() / sourceWidth, requestedHeight.toFloat() / sourceHeight)
        width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        height = (sourceHeight * scale).toInt().coerceAtLeast(1)
    }

    val maximumSample = minOf(sourceWidth / width, sourceHeight / height).coerceAtLeast(1)
    var sampleSize = 1
    while (sampleSize <= maximumSample / 2) sampleSize *= 2
    return LocalArtworkDecodePlan(width, height, sampleSize)
}

private data class LocalArtworkBitmap(val bitmap: Bitmap, val isSampled: Boolean)

private fun decodeLocalArtwork(art: ByteArray, width: Int, height: Int): LocalArtworkBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val plan = localArtworkDecodePlan(bounds.outWidth, bounds.outHeight, width, height)
    val options = BitmapFactory.Options().apply { inSampleSize = plan.sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options) ?: return null
    return resizeLocalArtwork(bitmap, plan, bounds.outWidth, bounds.outHeight)
}

private fun resizeLocalArtwork(
    bitmap: Bitmap,
    plan: LocalArtworkDecodePlan,
    sourceWidth: Int,
    sourceHeight: Int,
): LocalArtworkBitmap {
    val resized = try {
        bitmap.scale(plan.width, plan.height)
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
    // This bitmap was decoded for this fetch and has not entered Coil's shared cache yet.
    if (resized !== bitmap) bitmap.recycle()
    return LocalArtworkBitmap(
        bitmap = resized,
        isSampled = plan.width < sourceWidth || plan.height < sourceHeight,
    )
}

/** Read the artwork bytes and release the native retriever before decoding the image. */
internal fun extractEmbeddedArtwork(
    path: String,
    retriever: MediaMetadataRetriever = MediaMetadataRetriever(),
): ByteArray? = try {
    retriever.setDataSource(path)
    retriever.embeddedPicture
} finally {
    // release(), unlike close(), is available on all supported Android versions.
    try {
        retriever.release()
    } catch (e: Exception) {
        // Cleanup must not discard artwork already read or replace the read failure.
        reportException(e)
    }
}

class LocalArtworkPathKeyer : Keyer<LocalArtworkPath> {
    override fun key(
        data: LocalArtworkPath,
        options: Options
    ): String? {
        return data.path + ";" + data.x + ";" + data.y
    }

}

data class LocalArtworkPath(val path: String?, val x: Int = -1, val y: Int = -1)
