package com.dd3boh.outertune.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.Artist as CreditArtist
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Album-scoped projections must not erase the per-recording credit needed to split later IDs.
 * The first test uses captured album/queue excerpts through the production parser and save path;
 * the other cases exercise actual Room views with deliberately distinct source identities.
 */
class AlbumArtistGroupingDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt = LocalDateTime.of(2026, 9, 13, 11, 0)
    private val personName = "翟锦彦"
    private val albumId = "MPRE-idless-group"

    private fun credit(name: String = personName, onlineId: String? = null) = ArtistCredit(
        name, listOf(CreditArtist(name, onlineId)), ArtistCreditStatus.COMPLETE, "queue", "ja",
    )

    private fun track(id: String, album: String? = albumId, name: String = personName,
        onlineId: String? = null) = MediaMetadata(
        id = id, title = "Recording $id", artists = emptyList(), duration = 180, genre = null,
        album = album?.let { MediaMetadata.Album(it, "Album $it") },
    ).withArtistCredit(credit(name, onlineId))

    private fun MusicDatabase.save(track: MediaMetadata) = insert(track) {
        it.copy(inLibrary = savedAt)
    }

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) } finally { internal.close() }
    }

    private suspend fun MusicDatabase.assertGroup(songIds: Collection<String>, name: String = personName): String {
        val group = savedArtistsByCreateDateAsc().first().single { it.artist.name == name }
        assertEquals(songIds.size, group.songCount)
        assertEquals(songIds.toSet(), artistSongsPreview(group.id, 99).first().map { it.id }.toSet())
        assertNull(group.artist.onlineArtistId)
        assertFalse(group.artist.isLocal)
        return group.id
    }

    @Test
    fun capturedSpecialAlbumGroupsBothRecordingsWithoutRewritingTheirIndividualCredits() = runBlocking {
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
            suspend fun assertGrouped() {
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
                assertEquals(2, visible.size)
                val grouped = visible.single { it.artist.name == artistName }
                assertEquals(2, grouped.songCount)
                assertNull(grouped.artist.onlineArtistId)
                assertFalse(grouped.artist.isLocal)
                assertTrue(grouped.id !in expectedRefs)
                assertEquals(2, visible.single { it.artist.onlineArtistId == onlineId }.songCount)
                assertEquals(listOf(grouped.id), database.searchArtists(artistName).first().map { it.id })
                assertEquals(songIds.toSet(), database.searchArtistSongs(artistName).first().map { it.id }.toSet())
                for (ref in expectedRefs + grouped.id) {
                    assertEquals(grouped.id, database.artist(ref).first()!!.id)
                    assertEquals(songIds.toSet(), database.artistSongs(ref, ArtistSongSortType.NAME, false)
                        .first().map { it.id }.toSet())
                }
                val projected = database.artistDisplayMappings().first()
                    .filter { it.sourceArtistId in expectedRefs }
                assertEquals(expectedRefs.toSet(), projected.map { it.sourceArtistId }.toSet())
                assertEquals(setOf(grouped.id), projected.map { it.canonicalArtistId }.toSet())
            }
            // This is the production AlbumMenu "add all" operation, after visible rows resolve.
            songIds.forEach { database.toggleInLibrary(it, LocalDateTime.of(2026, 9, 13, 11, 0)) }
            assertGrouped()
            repeat(3) {
                database.insert(page)
                songIds.zip(credits).forEach { (id, credit) -> database.applyArtistCredit(id, credit) }
                songIds.forEach { database.toggleInLibrary(it, null) }
                assertEquals(0, database.savedArtistsByCreateDateAsc().first().size)
                songIds.forEach { database.toggleInLibrary(it, LocalDateTime.of(2026, 9, 13, 11, 0)) }
                assertGrouped()
            }
            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            database = MusicDatabase(internal)
            assertGrouped()
        } finally {
            internal.close()
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun manyTracksShareOneStableGroupRegardlessOfInsertionOrderAndRepeatedAlbumSaves() = runBlocking {
        val songIds = (0 until 8).map { "group-order-$it" }
        val orders = listOf(songIds, songIds.reversed(), listOf(3, 7, 1, 6, 0, 4, 2, 5).map(songIds::get))
        val representatives = mutableSetOf<String>()
        for (order in orders) withDatabase { database ->
            order.forEachIndexed { index, id ->
                database.save(track(id))
                representatives += database.assertGroup(order.take(index + 1))
            }
            val sources = songIds.associateWith { ArtistIdentity.stableId(it, personName) }
            assertEquals(8, database.artistsBySource(false).size)
            repeat(2) {
                songIds.forEach { database.insert(track(it)) }
                representatives += database.assertGroup(songIds)
                songIds.forEach { id ->
                    assertEquals(listOf(sources.getValue(id)), database.artistIdsForSong(id))
                    assertEquals(sources.getValue(id), database.artistCredit(id).first()!!.artists.single().ref)
                }
            }
        }
        // The route must not change merely because a different recording was saved first.
        assertEquals(1, representatives.size)
    }

    @Test
    fun albumScopeSeparatesSameNamesElsewhereAndNeverSplitsOrGuessesRawBylines() = runBlocking {
        withDatabase { database ->
            database.save(track("album-a-1"))
            database.save(track("album-a-2"))
            val groupA = database.assertGroup(listOf("album-a-1", "album-a-2"))
            database.save(track("album-b-1", album = "MPRE-another-album"))
            database.save(track("album-b-2", album = "MPRE-another-album"))
            database.save(track("no-album-1", album = null))
            database.save(track("no-album-2", album = null))
            val localArtist = ArtistEntity("LA-folder-same-name", personName, isLocal = true)
            database.insert(localArtist)
            database.insert(SongEntity("LS-folder-same-name", "Folder recording", 180,
                isLocal = true, localPath = "/test/folder.wav", inLibrary = savedAt, albumId = albumId))
            database.insert(SongArtistMap("LS-folder-same-name", localArtist.id, 0))
            val visible = database.savedArtistsByCreateDateAsc().first()
            assertEquals(5, visible.size)
            assertEquals(listOf(1, 1, 1, 2, 2), visible.map { it.songCount }.sorted())
            assertEquals(2, database.artistSongsPreview(groupA, 99).first().size)
            assertEquals(localArtist.id, database.artist(localArtist.id).first()!!.id)
            assertTrue(database.artist(localArtist.id).first()!!.artist.isLocal)
            assertEquals(5, database.searchArtists(personName).first().size)
            assertEquals(7, database.searchArtistSongs(personName).first().size)

            // A raw combined header proves neither the number of people nor any album membership.
            val raw = ArtistCredit("$personName & Guest", emptyList(), ArtistCreditStatus.RAW, "album", "ja")
            database.applyAlbumArtistCredit(albumId, raw)
            assertTrue(database.albumArtistIdsForAlbum(albumId).isEmpty())
            assertTrue(database.artistAlbumsPreview(groupA, 99).first().isEmpty())
            assertEquals(5, database.savedArtistsByCreateDateAsc().first().size)
        }
    }

    @Test
    fun generatedAlbumIdsAndDifferentIndividualLabelsDoNotSupplyGroupingEvidence() = runBlocking {
        withDatabase { database ->
            database.save(track("title-only-a", album = "LB-title-only"))
            database.save(track("title-only-b", album = "LB-title-only"))
            assertEquals(2, database.savedArtistsByCreateDateAsc().first().size)
            database.save(track("individual-a", name = "Person"))
            database.save(track("individual-b", name = "Person"))
            database.save(track("punctuation-a", name = "Person & Guest"))
            database.save(track("punctuation-b", name = "Person & Guest"))
            assertEquals(4, database.savedArtistsByCreateDateAsc().first().size)
            val person = database.assertGroup(listOf("individual-a", "individual-b"), "Person")
            val punctuation = database.assertGroup(listOf("punctuation-a", "punctuation-b"), "Person & Guest")
            assertNotEquals(person, punctuation)
            assertEquals(6, database.artistsBySource(false).size)
        }
    }

    @Test
    fun discoveringThenChangingAlbumMembershipRefreshesGroupsWithoutRewritingTheCredit() = runBlocking {
        withDatabase { database ->
            val first = track("late-album-1", album = null)
            val second = track("late-album-2", album = null)
            database.save(first); database.save(second)
            val originalCredits = listOf(first, second).associate { it.id to database.artistCredit(it.id).first()!! }
            assertEquals(2, database.savedArtistsByCreateDateAsc().first().size)
            database.insert(first.copy(album = MediaMetadata.Album(albumId, "Late album")))
            database.insert(second.copy(album = MediaMetadata.Album(albumId, "Late album")))
            val joined = database.assertGroup(listOf(first.id, second.id))
            database.insert(first.copy(album = MediaMetadata.Album("MPRE-reassigned", "Corrected album")))
            val split = database.savedArtistsByCreateDateAsc().first()
            assertEquals(2, split.size)
            assertEquals(listOf(second.id), database.artistSongsPreview(joined, 99).first().map { it.id })
            assertEquals(listOf(first.id), database.artistSongsPreview(split.single { it.id != joined }.id, 99)
                .first().map { it.id })
            for ((id, original) in originalCredits) {
                assertEquals(original, database.artistCredit(id).first())
                assertEquals(listOf(original.artists.single().ref), database.artistIdsForSong(id))
            }
        }
    }

    @Test
    fun albumHeaderEvidenceControlsTheGroupAlbumListWithoutReplacingTrackCredits() = runBlocking {
        withDatabase { database ->
            val ids = listOf("header-1", "header-2")
            ids.forEach { database.save(track(it)) }
            val group = database.assertGroup(ids)
            val trackCredits = ids.associateWith { database.artistCredit(it).first()!! }
            assertNull(database.albumById(albumId)!!.artistCredit)
            assertTrue(database.albumArtistIdsForAlbum(albumId).isEmpty())
            // Before any header has been saved, the existing album list can use song participation.
            assertEquals(listOf(albumId), database.artistAlbumsPreview(group, 99).first().map { it.id })
            database.applyAlbumArtistCredit(albumId,
                ArtistCredit("Unresolved album header", emptyList(), ArtistCreditStatus.RAW, "album-header", "ja"))
            // An actual, unresolved header disables that fallback; it is not a confirmed person.
            assertTrue(database.artistAlbumsPreview(group, 99).first().isEmpty())
            assertTrue(database.albumArtistIdsForAlbum(albumId).isEmpty())
            database.applyAlbumArtistCredit(albumId, credit())
            val headerRef = ArtistIdentity.stableId("album:$albumId", personName)
            assertEquals(listOf(headerRef), database.albumArtistIdsForAlbum(albumId))
            assertEquals(3, database.artistsBySource(false).size)
            assertEquals(group, database.artist(headerRef).first()!!.id)
            assertEquals(listOf(albumId), database.artistAlbumsPreview(group, 99).first().map { it.id })
            assertEquals(group, database.assertGroup(ids))
            database.applyAlbumArtistCredit(albumId,
                ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "failed-refresh", "en"))
            assertEquals(listOf(headerRef), database.albumArtistIdsForAlbum(albumId))
            assertEquals(group, database.assertGroup(ids))
            ids.forEach { assertEquals(trackCredits.getValue(it), database.artistCredit(it).first()) }
        }
    }

    @Test
    fun laterOnlineIdsSplitOnlyTheConfirmedSourcesAndPreserveTheirOriginalRecordingRefs() = runBlocking {
        withDatabase { database ->
            val ids = listOf("late-online-a", "late-online-b", "late-online-c", "late-online-d")
            ids.forEach { database.save(track(it)) }
            val group = database.assertGroup(ids)
            val sources = ids.associateWith { database.artistCredit(it).first()!!.artists.single().ref!! }
            val onlineA = "UC" + "a".repeat(22)
            val onlineB = "UC" + "b".repeat(22)
            // An existing remote row must not cause a physical merge of the old per-recording row.
            database.save(track("already-confirmed", album = "MPRE-outside", onlineId = onlineA))
            database.applyArtistCredit(ids[0], credit(onlineId = onlineA))
            assertEquals(setOf(onlineA, group), database.savedArtistsByCreateDateAsc().first().map { it.id }.toSet())
            assertEquals(setOf(ids[0], "already-confirmed"), database.artistSongsPreview(onlineA, 99)
                .first().map { it.id }.toSet())
            assertEquals(ids.drop(1).toSet(), database.artistSongsPreview(group, 99).first().map { it.id }.toSet())
            database.applyArtistCredit(ids[1], credit(onlineId = onlineB))
            database.applyArtistCredit(ids[2], credit(onlineId = onlineA))
            assertEquals(setOf(onlineA, onlineB, group), database.savedArtistsByCreateDateAsc().first().map { it.id }.toSet())
            assertEquals(listOf(ids[3]), database.artistSongsPreview(group, 99).first().map { it.id })
            assertEquals(3, database.artistSongsPreview(onlineA, 99).first().size)
            assertEquals(listOf(ids[1]), database.artistSongsPreview(onlineB, 99).first().map { it.id })
            ids.forEach { id ->
                assertEquals(listOf(sources.getValue(id)), database.artistIdsForSong(id))
                assertEquals(sources.getValue(id), database.artistCredit(id).first()!!.artists.single().ref)
                assertNotNull(database.artistEntityByExactId(sources.getValue(id)))
            }
            assertNull(database.artistEntityByExactId(sources.getValue(ids[3]))!!.onlineArtistId)
            val mappings = database.artistDisplayMappings().first().associateBy { it.sourceArtistId }
            assertEquals(onlineA, mappings.getValue(sources.getValue(ids[0])).canonicalArtistId)
            assertEquals(onlineB, mappings.getValue(sources.getValue(ids[1])).canonicalArtistId)
            assertEquals(onlineA, mappings.getValue(sources.getValue(ids[2])).canonicalArtistId)
            assertEquals(group, mappings.getValue(sources.getValue(ids[3])).canonicalArtistId)
        }
    }

    @Test
    fun confirmedOnlineIdsRemainDistinctFromEachOtherAndFromSameNamedIdlessCredits() = runBlocking {
        withDatabase { database ->
            val onlineA = "UC" + "c".repeat(22)
            val onlineB = "UC" + "d".repeat(22)
            database.save(track("confirmed-a", onlineId = onlineA))
            database.save(track("confirmed-b", onlineId = onlineB))
            database.save(track("idless-a"))
            database.save(track("idless-b"))
            val visible = database.savedArtistsByCreateDateAsc().first()
            assertEquals(3, visible.size)
            assertEquals(2, visible.count { it.artist.onlineArtistId != null })
            assertEquals(2, visible.single { it.artist.onlineArtistId == null }.songCount)
            assertEquals(listOf("confirmed-a"), database.artistSongsPreview(onlineA, 99).first().map { it.id })
            assertEquals(listOf("confirmed-b"), database.artistSongsPreview(onlineB, 99).first().map { it.id })
            assertEquals(3, database.searchArtists(personName).first().size)
        }
    }

    @Test
    fun groupBookmarkSurvivesDeletingTheFirstRecordingAndDoesNotCreateAnExtraArtist() = runBlocking {
        withDatabase { database ->
            val ids = listOf("bookmark-first", "bookmark-second", "bookmark-third")
            ids.forEach { database.save(track(it)) }
            val group = database.assertGroup(ids)
            assertNull(database.artist(group).first()!!.artist.bookmarkedAt)
            database.toggleArtistBookmark(group)
            assertNotNull(database.artist(group).first()!!.artist.bookmarkedAt)
            val firstRef = database.artistIdsForSong(ids.first()).single()
            database.delete(database.songForArtistCredit(ids.first())!!)
            database.safeDeleteArtist(firstRef)
            assertEquals(group, database.assertGroup(ids.drop(1)))
            assertNotNull(database.artist(group).first()!!.artist.bookmarkedAt)
            assertEquals(1, database.savedArtistsByCreateDateAsc().first().size)
            database.toggleArtistBookmark(group)
            assertNull(database.artist(group).first()!!.artist.bookmarkedAt)
            assertEquals(1, database.savedArtistsByCreateDateAsc().first().size)
        }
    }

    @Test
    fun applicationBuilderResetsVersion26InsteadOfPretendingToMigrateArtistIdentities() = runBlocking {
        val filename = "album-artist-version26-reset.db"
        context.deleteDatabase(filename)
        try {
            context.getDatabasePath(filename).parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(filename), null).use { old ->
                old.execSQL("CREATE TABLE old_artist_group_marker (name TEXT NOT NULL)")
                old.execSQL("INSERT INTO old_artist_group_marker VALUES ('old data')")
                old.version = 26
            }
            var database = InternalDatabase.newTestInstance(context, filename)
            try {
                assertEquals(27, database.openHelper.readableDatabase.version)
                assertTrue(database.artistsBySource(false).isEmpty())
                database.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE name = 'old_artist_group_marker'",
                ).use { assertFalse(it.moveToFirst()) }
                database.save(track("after-reset"))
                val group = database.assertGroup(listOf("after-reset"))
                database.close()
                database = InternalDatabase.newTestInstance(context, filename)
                assertEquals(group, database.assertGroup(listOf("after-reset")))
            } finally { database.close() }
        } finally { context.deleteDatabase(filename) }
    }
}
