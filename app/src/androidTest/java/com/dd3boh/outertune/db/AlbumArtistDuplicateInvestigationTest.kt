package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.models.ArtistIdentity
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.PlaylistPanelVideoRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.NextPage
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in reproduction of an unresolved issue, not a regression requirement to keep duplicates.
 * Album labels and queue renderers are excerpts of captured responses. The production queue parser
 * and database paths are exercised; network orchestration and screen interaction are separate checks.
 */
class AlbumArtistDuplicateInvestigationTest {
    @Test
    fun currentAlbumSaveSeparatesTheSameIdlessCreditByRecording() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("investigateAlbumArtistDuplicate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = JSONObject(instrumentation.context.assets
            .open("album-artist-duplicate/special-album.json").bufferedReader().use { it.readText() })
        val albumInput = fixture.getJSONObject("album")
        val albumId = albumInput.getString("id")
        val artistName = fixture.getString("idlessPerformer")
        val onlineArtist = fixture.getJSONObject("verifiedOnlineArtist")
        val onlineId = onlineArtist.getString("id")
        val renderers = fixture.getJSONArray("queueRenderers")
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val queued = (0 until renderers.length()).map { index ->
            NextPage.fromPlaylistPanelVideoRenderer(json.decodeFromString<PlaylistPanelVideoRenderer>(
                renderers.getJSONObject(index).toString()), language = "ja")!!
        }
        val songIds = queued.map { it.id }
        val albumItem = AlbumItem(
            browseId = albumId,
            playlistId = albumInput.getString("playlistId"),
            title = albumInput.getString("title"),
            artists = emptyList(),
            thumbnail = "https://example.invalid/investigation-album.jpg",
            artistCredit = ArtistCredit(albumInput.getString("rawCredit"), emptyList(),
                ArtistCreditStatus.RAW, "album-header", "ja"),
        )
        val page = AlbumPage(albumItem, songIds.mapIndexed { index, id ->
            SongItem(id = id, title = queued[index].title,
                artists = emptyList(), album = Album(albumItem.title, albumId), duration = 238,
                thumbnail = albumItem.thumbnail,
                // The actual album track rows omit their byline. Queue/credits fill it later.
                artistCredit = ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "AlbumPage", "ja"))
        }, emptyList())
        val credits = queued.map { song ->
            assertEquals(albumId, song.album!!.id)
            song.artistCredit!!.also { credit ->
                assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
                assertEquals(listOf(artistName, onlineArtist.getString("name")), credit.artists.map { it.name })
                assertEquals(listOf(null, onlineId), credit.artists.map { it.id })
            }
        }
        val filename = "album-artist-duplicate-investigation.db"
        context.deleteDatabase(filename)
        var internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
        try {
            var database = MusicDatabase(internal)
            database.insert(page)
            assertEquals(0, database.artistsBySource(false).size)
            assertEquals(0, database.albumArtistIdsForAlbum(albumId).size)
            songIds.zip(credits).forEach { (id, credit) -> database.applyArtistCredit(id, credit) }
            val expectedRefs = songIds.map { ArtistIdentity.stableId(it, artistName) }
            assertNotEquals(expectedRefs[0], expectedRefs[1])
            assertEquals(listOf("LA56498d4e4b30c00f27a3c1001ec8067b",
                "LAa14505d06d383ca94876af698abc9b52"), expectedRefs)
            suspend fun assertObservedDuplicates() {
                val artists = database.artistsBySource(false)
                assertEquals(3, artists.size)
                assertEquals(expectedRefs.toSet(), artists.filter { it.name == artistName }.map { it.id }.toSet())
                artists.filter { it.name == artistName }.forEach {
                    assertNull(it.onlineArtistId)
                    assertFalse(it.isLocal)
                }
                assertEquals(1, artists.count { it.onlineArtistId == onlineId })
                songIds.zip(expectedRefs).forEach { (id, ref) ->
                    assertEquals(listOf(ref, onlineId), database.artistIdsForSong(id))
                }
                val visible = database.savedArtistsByCreateDateAsc().first()
                assertEquals(3, visible.size)
                assertEquals(listOf(1, 1), visible.filter { it.artist.name == artistName }.map { it.songCount })
                assertEquals(2, visible.single { it.artist.onlineArtistId == onlineId }.songCount)
            }
            // This is the production AlbumMenu "add all" operation, after visible rows resolve.
            songIds.forEach { database.toggleInLibrary(it, LocalDateTime.of(2026, 9, 13, 11, 0)) }
            assertObservedDuplicates()
            repeat(3) {
                database.insert(page)
                songIds.zip(credits).forEach { (id, credit) -> database.applyArtistCredit(id, credit) }
                songIds.forEach { database.toggleInLibrary(it, null) }
                assertEquals(0, database.savedArtistsByCreateDateAsc().first().size)
                songIds.forEach { database.toggleInLibrary(it, LocalDateTime.of(2026, 9, 13, 11, 0)) }
                assertObservedDuplicates()
            }
            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            database = MusicDatabase(internal)
            assertObservedDuplicates()
        } finally {
            internal.close()
            context.deleteDatabase(filename)
        }
    }
}
