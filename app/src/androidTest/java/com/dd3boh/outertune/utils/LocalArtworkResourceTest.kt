package com.dd3boh.outertune.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.ImageFetchResult
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class LocalArtworkResourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun successfulReadPreservesBytesAndReleasesRetriever() {
        val artwork = byteArrayOf(0, 1, 127, -128, -1)
        withRetriever(RecordingRetriever(artwork = artwork)) { retriever ->
            assertArrayEquals(artwork, extractEmbeddedArtwork(TEST_PATH, retriever))
            assertEquals(listOf("source:$TEST_PATH", "picture", "release"), retriever.events)
        }
    }

    @Test
    fun missingArtworkReleasesRetriever() {
        withRetriever(RecordingRetriever()) { retriever ->
            assertNull(extractEmbeddedArtwork(TEST_PATH, retriever))
            assertEquals(listOf("source:$TEST_PATH", "picture", "release"), retriever.events)
        }
    }

    @Test
    fun dataSourceFailureReleasesRetrieverWithoutReadingPicture() {
        val failure = IllegalArgumentException("Synthetic source failure")
        withRetriever(RecordingRetriever(sourceFailure = failure)) { retriever ->
            assertSame(failure, runCatching { extractEmbeddedArtwork(TEST_PATH, retriever) }.exceptionOrNull())
            assertEquals(listOf("source:$TEST_PATH", "release"), retriever.events)
        }
    }

    @Test
    fun pictureReadFailureReleasesRetriever() {
        val failure = IllegalStateException("Synthetic artwork failure")
        withRetriever(RecordingRetriever(pictureFailure = failure)) { retriever ->
            assertSame(failure, runCatching { extractEmbeddedArtwork(TEST_PATH, retriever) }.exceptionOrNull())
            assertEquals(listOf("source:$TEST_PATH", "picture", "release"), retriever.events)
        }
    }

    @Test
    fun releaseFailureDoesNotDiscardSuccessfulArtwork() {
        val artwork = byteArrayOf(0, 1, 127, -128, -1)
        withRetriever(
            RecordingRetriever(artwork = artwork, releaseFailure = IOException("Synthetic release failure")),
        ) { retriever ->
            assertArrayEquals(artwork, extractEmbeddedArtwork(TEST_PATH, retriever))
            assertEquals(listOf("source:$TEST_PATH", "picture", "release"), retriever.events)
        }
    }

    @Test
    fun releaseFailureDoesNotReplaceOriginalReadFailure() {
        val failure = IllegalStateException("Synthetic artwork failure")
        withRetriever(
            RecordingRetriever(
                pictureFailure = failure,
                releaseFailure = IOException("Synthetic release failure"),
            ),
        ) { retriever ->
            assertSame(failure, runCatching { extractEmbeddedArtwork(TEST_PATH, retriever) }.exceptionOrNull())
            assertEquals(listOf("source:$TEST_PATH", "picture", "release"), retriever.events)
        }
    }

    @Test
    fun nativeFetchPreservesOriginalDimensionsAndEveryPixel() = runBlocking {
        withArtworkFile { file ->
            val result = CoilBitmapLoader(context, data = LocalArtworkPath(file.absolutePath)).fetch()
            assertTrue(result is ImageFetchResult)
            assertFalse((result as ImageFetchResult).isSampled)
            val bitmap = result.image.toBitmap()
            try {
                assertOriginalArtwork(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun nativeFetchRetainsExplicitSizeAndAspectRatio() = runBlocking {
        withArtworkFile { file ->
            // Square and wide frames, a source smaller than its display, and an unspecified axis.
            val cases = listOf(
                intArrayOf(48, 48, 48, 32),
                intArrayOf(300, 100, 150, 100),
                intArrayOf(2400, 2400, 2400, 1600),
                intArrayOf(0, 48, ARTWORK_WIDTH, ARTWORK_HEIGHT),
            )
            for ((requestedWidth, requestedHeight, expectedWidth, expectedHeight) in cases) {
                val result = CoilBitmapLoader(
                    context, data = LocalArtworkPath(file.absolutePath, requestedWidth, requestedHeight),
                ).fetch()
                assertTrue(result is ImageFetchResult)
                val fetched = result as ImageFetchResult
                val bitmap = fetched.image.toBitmap()
                try {
                    assertEquals(expectedWidth, bitmap.width)
                    assertEquals(expectedHeight, bitmap.height)
                    assertEquals(expectedWidth < ARTWORK_WIDTH || expectedHeight < ARTWORK_HEIGHT, fetched.isSampled)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun samplingBoundsLargeDecodesWithoutUndershootingDisplayPixels() {
        val landscape = localArtworkDecodePlan(12000, 8000, 96, 96)
        assertEquals(LocalArtworkDecodePlan(96, 64, 64), landscape)
        assertTrue(12000 / landscape.sampleSize >= landscape.width)
        assertTrue(8000 / landscape.sampleSize >= landscape.height)
        assertTrue((12000L / landscape.sampleSize) * (8000 / landscape.sampleSize) < 24_000)
        val portrait = localArtworkDecodePlan(8000, 12000, 96, 96)
        assertEquals(LocalArtworkDecodePlan(64, 96, 64), portrait)
        assertEquals(LocalArtworkDecodePlan(1080, 720, 2), localArtworkDecodePlan(3000, 2000, 1080, 1080))
        assertEquals(LocalArtworkDecodePlan(96, 48, 1), localArtworkDecodePlan(32, 16, 96, 96))
        assertEquals(LocalArtworkDecodePlan(32, 16, 1), localArtworkDecodePlan(32, 16, -1, -1))
        assertEquals(LocalArtworkDecodePlan(32, 16, 1), localArtworkDecodePlan(32, 16, 48, 0))
        // Extremely thin artwork must still produce a valid bitmap, without losing its only column.
        assertEquals(LocalArtworkDecodePlan(1, 48, 1), localArtworkDecodePlan(1, 8000, 48, 48))
    }

    @Test
    fun imageLoaderCacheKeepsSmallLargeAndOriginalArtworkSeparateInBothOrders() = runBlocking {
        withArtworkFile { file ->
            for (sizes in listOf(listOf(48, 600, -1), listOf(-1, 600, 48))) {
                val loader = ImageLoader.Builder(context)
                    .components {
                        add(CoilBitmapLoader.Factory(context))
                        add(LocalArtworkPathKeyer())
                    }
                    .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false)
                    .build()
                try {
                    // Keep all three entries live, then check their actual memory-cache results.
                    repeat(2) { pass ->
                        for (size in sizes) {
                            val request = ImageRequest.Builder(context)
                                .data(LocalArtworkPath(file.absolutePath, size, size))
                                .size(if (size > 0) Size(size, size) else Size.ORIGINAL)
                                .scale(Scale.FIT)
                                .precision(Precision.EXACT)
                                .build()
                            val result = loader.execute(request)
                            assertTrue("Artwork request failed: $result", result is SuccessResult)
                            val loaded = result as SuccessResult
                            assertEquals(if (pass == 0) DataSource.DISK else DataSource.MEMORY_CACHE, loaded.dataSource)
                            val bitmap = loaded.image.toBitmap()
                            if (size < 0) {
                                assertOriginalArtwork(bitmap)
                            } else {
                                assertEquals(size, bitmap.width)
                                assertEquals(size * ARTWORK_HEIGHT / ARTWORK_WIDTH, bitmap.height)
                            }
                        }
                    }
                } finally {
                    loader.memoryCache?.clear()
                    loader.shutdown()
                }
            }
        }
    }

    @Test
    fun corruptEmbeddedArtworkReturnsPlaceholderAtRequestedSize() = runBlocking {
        withArtworkFile { file ->
            // Keep the MP3/APIC container valid while making its embedded PNG undecodable.
            val bytes = file.readBytes()
            val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
            val artworkStart = (0..bytes.size - pngSignature.size).first { offset ->
                pngSignature.indices.all { bytes[offset + it] == pngSignature[it] }
            }
            bytes[artworkStart] = 0
            file.writeBytes(bytes)
            assertEquals(0, requireNotNull(extractEmbeddedArtwork(file.absolutePath))[0].toInt())
            val result = CoilBitmapLoader(context, data = LocalArtworkPath(file.absolutePath, 48, 48)).fetch()
            assertTrue(result is ImageFetchResult)
            val bitmap = (result as ImageFetchResult).image.toBitmap()
            try {
                assertEquals(48, bitmap.width)
                assertEquals(48, bitmap.height)
                assertTrue(result.isSampled)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun cacheKeysKeepOriginalAndExplicitArtworkSizesSeparate() {
        val keyer = LocalArtworkPathKeyer()
        val options = Options(context)
        val original = keyer.key(LocalArtworkPath(TEST_PATH), options)
        val thumbnail = keyer.key(LocalArtworkPath(TEST_PATH, 48, 48), options)
        assertEquals("$TEST_PATH;-1;-1", original)
        assertEquals("$TEST_PATH;48;48", thumbnail)
        assertNotEquals(original, thumbnail)
    }

    private fun withRetriever(retriever: RecordingRetriever, block: (RecordingRetriever) -> Unit) {
        try {
            block(retriever)
        } finally {
            // Even a regression that omits release must not leak this test's native object.
            retriever.disposeIfNeeded()
        }
    }

    private fun assertOriginalArtwork(bitmap: Bitmap) {
        assertEquals(ARTWORK_WIDTH, bitmap.width)
        assertEquals(ARTWORK_HEIGHT, bitmap.height)
        val actual = IntArray(ARTWORK_WIDTH * ARTWORK_HEIGHT)
        bitmap.getPixels(actual, 0, ARTWORK_WIDTH, 0, 0, ARTWORK_WIDTH, ARTWORK_HEIGHT)
        val expected = IntArray(actual.size) { index ->
            if ((index % ARTWORK_WIDTH + index / ARTWORK_WIDTH) % 2 == 0) Color.RED else Color.BLUE
        }
        assertArrayEquals(expected, actual)
    }

    private suspend fun withArtworkFile(block: suspend (File) -> Unit) {
        val directory = requireNotNull(context.externalCacheDir)
        val file = File.createTempFile("local-artwork-", ".mp3", directory)
        try {
            assertTrue("Fixture must use the local artwork /storage/ path", file.absolutePath.startsWith("/storage/"))
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("local-artwork/checkerboard.mp3").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            block(file)
        } finally {
            file.delete()
        }
    }

    /** Overrides only the failure points; the Android native retriever is still allocated and released. */
    private class RecordingRetriever(
        private val artwork: ByteArray? = null,
        private val sourceFailure: RuntimeException? = null,
        private val pictureFailure: RuntimeException? = null,
        private val releaseFailure: IOException? = null,
    ) : MediaMetadataRetriever() {
        val events = mutableListOf<String>()
        private var nativeReleased = false

        override fun setDataSource(path: String) {
            events += "source:$path"
            sourceFailure?.let { throw it }
        }

        override fun getEmbeddedPicture(): ByteArray? {
            events += "picture"
            pictureFailure?.let { throw it }
            return artwork
        }

        override fun release() {
            events += "release"
            super.release()
            nativeReleased = true
            releaseFailure?.let { throw it }
        }

        fun disposeIfNeeded() {
            if (!nativeReleased) {
                super.release()
                nativeReleased = true
            }
        }
    }

    private companion object {
        const val TEST_PATH = "/storage/emulated/0/synthetic-artwork.mp3"
        const val ARTWORK_WIDTH = 1200
        const val ARTWORK_HEIGHT = 800
    }
}
