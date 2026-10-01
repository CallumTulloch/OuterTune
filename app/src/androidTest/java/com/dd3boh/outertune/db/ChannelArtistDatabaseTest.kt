package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.ArtistItem
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Room checks for channel provenance, reversible links, and saved track relationships. */
class ChannelArtistDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt = LocalDateTime.of(2026, 10, 1, 12, 0)
    private val channelId = "UCPCiIrrrNJOKvi_5vr3G6PA"
    private val alternateId = "UCbrWU0y_rLsEOYgaTX5Y74A"

    private fun channelCredit(name: String = "投稿チャンネル", sourceId: String? = channelId) = ArtistCredit(
        rawText = name,
        artists = listOf(Artist(name, null, isChannel = true, sourceChannelId = sourceId)),
        status = ArtistCreditStatus.COMPLETE,
        source = "video-channel",
        language = "ja",
        evidence = listOf("video-source:MUSIC_VIDEO_TYPE_OMV", "channel-source:fixture"),
    )

    private fun artistCredit(name: String = "明示された人物", onlineId: String = channelId) = ArtistCredit(
        name, listOf(Artist(name, onlineId)), ArtistCreditStatus.COMPLETE, "track-credits", "ja",
        listOf("structured-byline:fixture"),
    )

    private fun metadata(id: String, credit: ArtistCredit) = MediaMetadata(
        id = id, title = "Track $id", artists = emptyList(), duration = 180, genre = null,
        album = MediaMetadata.Album("MPRE-channel-fixture", "Album"),
    ).withArtistCredit(credit)

    private fun save(database: MusicDatabase, id: String, credit: ArtistCredit = channelCredit()) {
        database.insert(metadata(id, credit)) { it.copy(inLibrary = savedAt, liked = true, likedDate = savedAt) }
    }

    private fun link(sourceId: String, target: String = channelId, revision: String = UUID.randomUUID().toString()) =
        LocalArtistLink(sourceId, target, "選択したオンライン名", "chosen-image", revision)

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) } finally { internal.close() }
    }

    @Test
    fun channelIdReusesSourceAcrossVideosAndRenameWithoutBecomingAnOnlineArtist() = runBlocking {
        withDatabase { database ->
            save(database, "channel-video-one")
            save(database, "channel-video-two", channelCredit("変更後のチャンネル名"))
            val sourceId = database.artistIdsForSong("channel-video-one").single()
            assertTrue(sourceId.startsWith("CS"))
            assertEquals(listOf(sourceId), database.artistIdsForSong("channel-video-two"))
            val source = database.artistById(sourceId)!!
            assertTrue(source.isChannel)
            assertFalse(source.isLocal)
            assertNull(source.onlineArtistId)
            assertEquals(channelId, source.sourceChannelId)
            assertNull(source.albumGroupId)
            assertNull(database.artistByOnlineId(channelId))
            assertEquals(2, database.artist(sourceId).first()!!.songCount)
            val savedCredit = database.artistCredit("channel-video-two").first()!!.artists.single()
            assertEquals(sourceId, savedCredit.ref)
            assertTrue(savedCredit.isChannel)
            assertEquals(channelId, savedCredit.sourceChannelId)
            assertNull(savedCredit.id)
            assertEquals("変更後のチャンネル名", savedCredit.name)
            val restored = database.song("channel-video-two").first()!!.toMediaMetadata().artists.single()
            assertTrue(restored.isChannel)
            assertEquals(channelId, restored.sourceChannelId)
            assertNull(restored.onlineId)
        }
    }

    @Test
    fun sameDisplayNameDoesNotJoinDifferentChannelsOrIdlessVideos() = runBlocking {
        withDatabase { database ->
            save(database, "channel-one", channelCredit("Same name", channelId))
            save(database, "channel-two", channelCredit("Same name", alternateId))
            save(database, "channel-idless-one", channelCredit("Same name", null))
            save(database, "channel-idless-two", channelCredit("Same name", null))
            val sources = listOf("channel-one", "channel-two", "channel-idless-one", "channel-idless-two")
                .map { database.artistIdsForSong(it).single() }
            assertEquals(4, sources.distinct().size)
            assertEquals(sources.toSet(), database.artists(ArtistFilter.LIBRARY, ArtistSortType.NAME, false)
                .first().map { it.id }.toSet())
            assertTrue(sources.all { database.artistById(it)!!.albumGroupId == null })
        }
    }

    @Test
    fun folderAndConfirmedArtistWithSameNameAndChannelIdRemainIndependent() = runBlocking {
        withDatabase { database ->
            val name = "Same name"
            val folder = database.resolveAndInsertArtist(null, name, true)
            save(database, "channel-source", channelCredit(name))
            save(database, "confirmed-source", artistCredit(name))
            val sourceId = database.artistIdsForSong("channel-source").single()
            val confirmedId = database.artistIdsForSong("confirmed-source").single()
            assertEquals(3, setOf(folder.id, sourceId, confirmedId).size)
            assertEquals(confirmedId, database.artistByOnlineId(channelId)!!.id)
            assertEquals(channelId, database.artistById(confirmedId)!!.onlineArtistId)
            assertEquals(sourceId, database.artistDisplayById(sourceId)!!.id)
            assertTrue(database.artistById(sourceId)!!.isChannel)
            assertFalse(database.artistById(confirmedId)!!.isChannel)
            assertTrue(database.localArtistLinks().first().isEmpty())
        }
    }

    @Test
    fun linkChangeAndUnlinkProjectSongsAndImagesWhilePreservingChannelAndTrackSnapshots() = runBlocking {
        withDatabase { database ->
            save(database, "channel-linked-one")
            save(database, "channel-linked-two")
            val sourceId = database.artistIdsForSong("channel-linked-one").single()
            val source = database.artistById(sourceId)!!.copy(thumbnailUrl = "uploader-image", bookmarkedAt = savedAt)
            database.update(source)
            database.update(database.songForArtistCredit("channel-linked-two")!!.copy(dateDownload = savedAt))
            val snapshots = listOf("channel-linked-one", "channel-linked-two")
                .associateWith { database.songForArtistCredit(it)!! }
            val remote = ArtistEntity(channelId, "保存済みオンライン名", thumbnailUrl = "online-image", onlineId = channelId)
            database.insert(remote)
            save(database, "confirmed-linked-track", artistCredit())

            fun assertRawPreserved() {
                assertEquals(source, database.artistById(sourceId))
                snapshots.forEach { (id, snapshot) ->
                    assertEquals(snapshot, database.songForArtistCredit(id))
                    assertEquals(listOf(sourceId), database.artistIdsForSong(id))
                }
            }

            val choice = link(sourceId)
            database.setLocalArtistLink(choice)
            assertEquals(channelId, database.artistDisplayById(sourceId)!!.id)
            assertEquals("online-image", database.artist(sourceId).first()!!.thumbnailUrl)
            assertEquals(3, database.artist(channelId).first()!!.songCount)
            assertEquals(setOf("channel-linked-one", "channel-linked-two", "confirmed-linked-track"),
                database.artistSongs(channelId, ArtistSongSortType.NAME, false).first().map { it.id }.toSet())
            val managed = database.localArtistLinkSources(channelId).first().single()
            assertEquals(sourceId, managed.localArtist.id)
            assertEquals(2, managed.localArtist.songCount)
            assertTrue(managed.folders.isEmpty())
            assertRawPreserved()

            val changed = link(sourceId, alternateId).copy(thumbnailUrl = null)
            database.setLocalArtistLink(changed)
            assertFalse(database.removeLocalArtistLink(sourceId, choice.revision))
            assertEquals(alternateId, database.artistDisplayById(sourceId)!!.id)
            // Missing online artwork cannot expose the uploader's image as a performer image.
            assertNull(database.artist(sourceId).first()!!.thumbnailUrl)
            assertEquals(2, database.artist(alternateId).first()!!.songCount)
            assertEquals(1, database.artist(channelId).first()!!.songCount)
            assertRawPreserved()

            assertTrue(database.removeLocalArtistLink(sourceId, changed.revision))
            assertEquals(sourceId, database.artistDisplayById(sourceId)!!.id)
            assertEquals(source.name, database.artist(sourceId).first()!!.title)
            assertEquals("uploader-image", database.artist(sourceId).first()!!.thumbnailUrl)
            assertTrue(database.localArtistLinkSources().first().isEmpty())
            assertRawPreserved()
        }
    }

    @Test
    fun unlinkedChannelRemainsVisibleWhenBookmarkedAndDownloadedAndRejectsPerformerProfileWrites() = runBlocking {
        withDatabase { database ->
            save(database, "channel-filter-track")
            val sourceId = database.artistIdsForSong("channel-filter-track").single()
            val source = database.artistById(sourceId)!!.copy(bookmarkedAt = savedAt, thumbnailUrl = "uploader-image")
            database.update(source)
            database.update(database.songForArtistCredit("channel-filter-track")!!.copy(dateDownload = savedAt))
            assertEquals(listOf(sourceId), database.artists(ArtistFilter.LIKED, ArtistSortType.NAME, false).first().map { it.id })
            assertEquals(listOf(sourceId), database.artists(ArtistFilter.DOWNLOADED, ArtistSortType.NAME, false).first().map { it.id })
            assertEquals(0, database.saveArtistProfile(ArtistItem(channelId, "Performer", "performer-image",
                shuffleEndpoint = null, radioEndpoint = null)))
            assertEquals(source, database.artistById(sourceId))
        }
    }

    @Test
    fun savedAndDownloadedChannelMembershipCountsOverlapOnceAndExcludesViewedOnlyTracksAcrossLinkAndUnlink() = runBlocking {
        withDatabase { database ->
            val savedOnly = "channel-membership-saved"
            val downloadedOnly = "channel-membership-downloaded"
            val both = "channel-membership-both"
            val viewedOnly = "channel-membership-viewed"
            val allIds = listOf(savedOnly, downloadedOnly, both, viewedOnly)
            val expectedIds = setOf(savedOnly, downloadedOnly, both)
            allIds.forEach { id ->
                database.insert(metadata(id, channelCredit())) { song ->
                    song.copy(
                        inLibrary = savedAt.takeIf { id == savedOnly || id == both },
                        dateDownload = savedAt.takeIf { id == downloadedOnly || id == both },
                    )
                }
            }
            val sourceId = database.artistIdsForSong(savedOnly).single()
            val sourceSnapshot = database.artistById(sourceId)!!
            val trackSnapshots = allIds.associateWith { database.songForArtistCredit(it)!! }
            assertTrue(allIds.all { database.artistIdsForSong(it) == listOf(sourceId) })

            suspend fun assertMembership(displayId: String) {
                val source = database.linkableArtistSource(sourceId).first()!!
                assertEquals(sourceId, source.localArtist.id)
                assertEquals(3, source.localArtist.songCount)
                assertEquals(2, source.localArtist.downloadCount)
                assertEquals(3, source.songs.size)
                assertEquals(expectedIds, source.songs.map { it.id }.toSet())
                assertEquals(3, source.songs.map { it.id }.distinct().size)
                assertTrue(source.folders.isEmpty())

                // The same membership rule feeds source management and the visible artist page.
                val displayed = database.artist(sourceId).first()!!
                assertEquals(displayId, displayed.id)
                assertEquals(3, displayed.songCount)
                assertEquals(2, displayed.downloadCount)
                for (sort in ArtistSongSortType.entries) {
                    val tracks = database.artistSongs(displayId, sort, false).first()
                    assertEquals(3, tracks.size)
                    assertEquals(expectedIds, tracks.map { it.id }.toSet())
                }
                for ((filter, count, downloads) in listOf(
                    Triple(ArtistFilter.LIBRARY, 2, 1),
                    Triple(ArtistFilter.DOWNLOADED, 2, 2),
                    Triple(ArtistFilter.ALL, 3, 2),
                )) {
                    val artists = database.artists(filter, ArtistSortType.NAME, false).first()
                    assertEquals(listOf(displayId), artists.map { it.id })
                    assertEquals(count, artists.single().songCount)
                    assertEquals(downloads, artists.single().downloadCount)
                }
                assertEquals(sourceSnapshot, database.artistById(sourceId))
                trackSnapshots.forEach { (id, snapshot) ->
                    assertEquals(snapshot, database.songForArtistCredit(id))
                    assertEquals(listOf(sourceId), database.artistIdsForSong(id))
                }
            }

            assertTrue(database.localArtistLinkSources().first().isEmpty())
            assertMembership(sourceId)
            val choice = link(sourceId)
            database.setLocalArtistLink(choice)
            assertMembership(channelId)
            for (target in listOf<String?>(null, channelId)) {
                val managed = database.localArtistLinkSources(target).first().single()
                assertEquals(sourceId, managed.localArtist.id)
                assertEquals(3, managed.localArtist.songCount)
                assertEquals(2, managed.localArtist.downloadCount)
                assertEquals(3, managed.songs.size)
                assertEquals(expectedIds, managed.songs.map { it.id }.toSet())
            }
            assertTrue(database.localArtistLinkSources(alternateId).first().isEmpty())

            assertTrue(database.removeLocalArtistLink(sourceId, choice.revision))
            assertTrue(database.localArtistLinkSources().first().isEmpty())
            assertMembership(sourceId)
            assertNull(database.artistDisplayById(channelId))
            assertNull(database.artistByOnlineId(channelId))
        }
    }

    @Test
    fun explicitTrackCreditReplacesChannelFallbackWithoutInheritingItsManualChoice() = runBlocking {
        withDatabase { database ->
            save(database, "channel-upgrade")
            save(database, "channel-still-fallback")
            val sourceId = database.artistIdsForSong("channel-upgrade").single()
            val choice = link(sourceId, alternateId)
            database.setLocalArtistLink(choice)
            val source = database.artistById(sourceId)!!
            val before = database.songForArtistCredit("channel-upgrade")!!
            // Matching names do not establish that the uploader and performer are one identity.
            database.applyArtistCredit("channel-upgrade", artistCredit(source.name, channelId))
            val confirmed = database.artistIdsForSong("channel-upgrade").single()
            assertNotEquals(sourceId, confirmed)
            assertEquals(channelId, database.artistById(confirmed)!!.onlineArtistId)
            assertFalse(database.artistCredit("channel-upgrade").first()!!.artists.single().isChannel)
            val after = database.songForArtistCredit("channel-upgrade")!!
            assertEquals(before, after.copy(artistCreditJson = before.artistCreditJson))
            assertEquals(listOf(sourceId), database.artistIdsForSong("channel-still-fallback"))
            assertEquals(source, database.artistById(sourceId))
            assertEquals(choice, database.localArtistLinkById(sourceId))
            assertEquals(channelId, database.artistDisplayById(confirmed)!!.id)
            assertEquals(alternateId, database.artistDisplayById(sourceId)!!.id)

            // Subsequent uploader-only replies cannot downgrade the explicit track credit.
            val confirmedSnapshot = database.songForArtistCredit("channel-upgrade")!!
            val confirmedCredit = database.artistCredit("channel-upgrade").first()!!
            database.applyArtistCredit("channel-upgrade", channelCredit("別の投稿者表記"))
            val retained = database.songForArtistCredit("channel-upgrade")!!
            assertEquals(confirmedSnapshot, retained.copy(artistCreditJson = confirmedSnapshot.artistCreditJson))
            val retainedCredit = database.artistCredit("channel-upgrade").first()!!
            assertEquals(confirmedCredit.artists, retainedCredit.artists)
            assertEquals(confirmedCredit.rawText, retainedCredit.rawText)
            assertEquals(confirmedCredit.status, retainedCredit.status)
            assertEquals(listOf(confirmed), database.artistIdsForSong("channel-upgrade"))
        }
    }

    @Test
    fun pendingAndThinLookupsKeepSavedChannelMetadataAndRelationships() = runBlocking {
        withDatabase { database ->
            save(database, "channel-lookup")
            val sourceId = database.artistIdsForSong("channel-lookup").single()
            val saved = database.songForArtistCredit("channel-lookup")!!
            val source = database.artistById(sourceId)!!
            val thinReplies = listOf(
                ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "context-refresh", "ja"),
                ArtistCredit(source.name, emptyList(), ArtistCreditStatus.RAW, "playlist", "ja"),
            )
            for (thin in thinReplies) {
                database.applyArtistCredit("channel-lookup", thin)
                assertEquals(saved, database.songForArtistCredit("channel-lookup"))
                assertEquals(listOf(sourceId), database.artistIdsForSong("channel-lookup"))
                database.insert(metadata("channel-lookup", thin))
                assertEquals(saved, database.songForArtistCredit("channel-lookup"))
                assertEquals(source, database.artistById(sourceId))
                assertEquals(listOf(sourceId), database.artistIdsForSong("channel-lookup"))
            }
        }
    }

    @Test
    fun channelProvenanceManualChoiceAndRawCreditsSurviveDiskReopen() = runBlocking {
        val filename = "channel-artist-${UUID.randomUUID()}.db"
        var source: ArtistEntity? = null
        var snapshot: com.dd3boh.outertune.db.entities.SongEntity? = null
        var choice: LocalArtistLink? = null
        try {
            InternalDatabase.newTestInstance(context, filename).let { database ->
                try {
                    save(database, "channel-reopen")
                    source = database.artistById(database.artistIdsForSong("channel-reopen").single())!!
                    snapshot = database.songForArtistCredit("channel-reopen")!!
                    choice = link(source!!.id)
                    database.setLocalArtistLink(choice!!)
                } finally { database.close() }
            }
            InternalDatabase.newTestInstance(context, filename).let { database ->
                try {
                    assertEquals(source, database.artistById(source!!.id))
                    assertEquals(snapshot, database.songForArtistCredit("channel-reopen"))
                    assertEquals(choice, database.localArtistLinkById(source!!.id))
                    assertEquals(channelId, database.artistDisplayById(source!!.id)!!.id)
                    assertEquals("chosen-image", database.artist(source!!.id).first()!!.thumbnailUrl)
                    assertEquals(1, database.localArtistLinkSources(channelId).first().single().localArtist.songCount)
                    assertTrue(database.removeLocalArtistLink(source!!.id, choice!!.revision))
                    assertEquals(source!!.id, database.artistDisplayById(source!!.id)!!.id)
                    assertEquals(channelId, database.artistCredit("channel-reopen").first()!!.artists.single().sourceChannelId)
                } finally { database.close() }
            }
        } finally { context.deleteDatabase(filename) }
    }
}
