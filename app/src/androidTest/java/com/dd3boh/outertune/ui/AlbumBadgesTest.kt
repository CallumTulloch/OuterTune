package com.dd3boh.outertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.AlbumListItem
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real local album cards, with no DownloadUtil: rendering must not require download tracking. */
class AlbumBadgesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val savedAt = LocalDateTime.of(2026, 9, 27, 12, 0)

    @Test
    fun localListNeedsNeitherDatabaseNorDownloadsAndKeepsFavoriteBehavior() = withFixture { activity ->
        val album = mutableStateOf(localAlbum("local-list"))
        val showLiked = mutableStateOf(true)
        show(activity) {
            FixtureTheme {
                // Deliberately omit both LocalDatabase and LocalDownloadUtil. Their defaults throw.
                AlbumListItem(album = album.value, showLikedIcon = showLiked.value)
            }
        }
        assertTitle(activity, album.value.title)
        assertFavorite(activity, expected = true)

        onMain { showLiked.value = false }
        nextFrames(3)
        assertFavorite(activity, expected = false)

        onMain {
            showLiked.value = true
            album.value = localAlbum("another-local-list", liked = false)
        }
        nextFrames(3)
        assertTitle(activity, album.value.title)
        assertFavorite(activity, expected = false)

        onMain { album.value = album.value.copy(album = album.value.album.copy(bookmarkedAt = savedAt)) }
        nextFrames(3)
        assertFavorite(activity, expected = true)
    }

    @Test
    fun localGridDoesNotReadTracksAndKeepsFavoriteWhenAlbumIdentityChanges() = runBlocking {
        val trackQueries = AtomicInteger()
        val room = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, InternalDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    // This is the top-level query behind albumSongs(), not unrelated Room setup SQL.
                    if (sqlQuery.contains("count(song.dateDownload)", ignoreCase = true) &&
                        sqlQuery.contains("WHERE album.id =", ignoreCase = true)) {
                        trackQueries.incrementAndGet()
                    }
                }
            }, Executor { it.run() })
            .build()
        val database = MusicDatabase(room)
        val original = localAlbum("local-grid")
        try {
            database.awaitTransaction {
                insert(original.album)
                repeat(3) { index ->
                    val songId = "badge-local-song-$index"
                    insert(SongEntity(songId, "Track $index", isLocal = true,
                        localPath = "/fixture/$index.flac", inLibrary = savedAt))
                    insert(SongAlbumMap(songId, original.id, index))
                }
            }
            // Positive control: prove the callback observes the query that the UI must avoid.
            assertEquals(3, database.albumSongs(original.id).first().size)
            assertTrue("The Room query probe did not observe albumSongs", trackQueries.get() > 0)
            trackQueries.set(0)

            withFixture { activity ->
                val album = mutableStateOf(original)
                show(activity) {
                    CompositionLocalProvider(LocalDatabase provides database, LocalPlayerConnection provides null) {
                        FixtureTheme {
                            // The grid's thumbnail slot reads the DB local even with no player.
                            // No DownloadUtil is provided, so an accidental download collector fails immediately.
                            AlbumGridItem(album = album.value, coroutineScope = rememberCoroutineScope(),
                                showPlayButton = false)
                        }
                    }
                }
                assertTitle(activity, original.title)
                assertFavorite(activity, expected = true)
                assertEquals("Local grid loaded tracks for a download badge", 0, trackQueries.get())

                onMain { album.value = localAlbum("another-local-grid", liked = false) }
                nextFrames(3)
                assertTitle(activity, album.value.title)
                assertFavorite(activity, expected = false)
                assertEquals("Changing local albums started a track observer", 0, trackQueries.get())

                onMain { album.value = original }
                nextFrames(3)
                assertFavorite(activity, expected = true)
                assertEquals(0, trackQueries.get())
            }
        } finally {
            room.close()
        }
    }

    private fun localAlbum(id: String, liked: Boolean = true) = Album(
        album = AlbumEntity(id = id, title = id, songCount = 3, duration = 540,
            isLocal = true, bookmarkedAt = savedAt.takeIf { liked }),
        downloadCount = 0,
        artists = emptyList(),
    )

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = lightColorScheme(error = Color.Magenta)) {
            Column(Modifier.width(320.dp).background(Color.White)) { content() }
        }
    }

    private fun assertTitle(activity: SearchUiFixtureActivity, title: String) = onMain {
        val root = activity.fixtureView.getChildAt(0) as ViewRootForTest
        assertTrue("Album title did not render: $title", root.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false).any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true
            })
    }

    private fun assertFavorite(activity: SearchUiFixtureActivity, expected: Boolean) = onMain {
        // Favorite intentionally has no content description. Give its error tint a unique color
        // and inspect the rendered pixels instead of adding production-only test semantics.
        val bitmap = Bitmap.createBitmap(activity.fixtureView.width, activity.fixtureView.height, Bitmap.Config.ARGB_8888)
        try {
            activity.fixtureView.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val favoritePixels = pixels.count { pixel ->
                android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) < 70 &&
                    android.graphics.Color.blue(pixel) > 220
            }
            if (expected) assertTrue("Favorite icon did not render", favoritePixels > 8)
            else assertEquals("Unexpected favorite icon", 0, favoritePixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(activity: SearchUiFixtureActivity, content: @Composable () -> Unit) {
        onMain { activity.fixtureView.setContent(content) }
        nextFrames(3)
    }

    private fun nextFrames(count: Int) {
        val complete = CountDownLatch(1)
        onMain {
            var remaining = count
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) complete.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue("No Android frames reached the fixture", complete.await(5, TimeUnit.SECONDS))
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun withFixture(block: (SearchUiFixtureActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, SearchUiFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SearchUiFixtureActivity
        try {
            block(activity)
        } finally {
            onMain {
                activity.fixtureView.disposeComposition()
                activity.finish()
            }
        }
    }
}
