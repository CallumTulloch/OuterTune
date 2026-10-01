package com.dd3boh.outertune.utils

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real Room link mappings and the shared display objects; no application library or network use. */
class ChannelArtistNamesProjectionTest {
    @Test
    fun japaneseAndEnglishPerformerNamesApplyOnlyWhileChannelIsLinkedAndUnlinkRestoresUploaderSearch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        val onlineId = "UCPCiIrrrNJOKvi_5vr3G6PA"
        val uploaderName = "投稿チャンネル・元の表記"
        val japanesePerformer = "選択した人物の日本語名"
        val englishPerformer = "Selected Performer Original"
        try {
            val credit = ArtistCredit(
                rawText = uploaderName,
                artists = listOf(Artist(uploaderName, null, isChannel = true, sourceChannelId = onlineId)),
                status = ArtistCreditStatus.COMPLETE,
                source = "video-channel",
                language = "ja",
                evidence = listOf("video-source:MUSIC_VIDEO_TYPE_OMV", "channel-source:fixture"),
            )
            val input = MediaMetadata("channel-language-fixture", "Fixture song", emptyList(), 180, genre = null)
                .withArtistCredit(credit)
            database.insert(input) { it.copy(inLibrary = LocalDateTime.of(2026, 10, 1, 12, 0)) }
            val sourceId = database.artistIdsForSong(input.id).single()
            val sourceArtist = database.artistById(sourceId)!!.copy(thumbnailUrl = "uploader-image")
            database.update(sourceArtist)
            val stored = database.song(input.id).first()!!
            val metadata = stored.toMediaMetadata()
            val rawCredit = stored.artistCredit!!
            val rawEntity = stored.song
            val playbackEntity = metadata.toSongEntity()
            val performerTarget = OriginalNameTarget(OriginalNameKind.ARTIST, onlineId)
            val sourceTarget = OriginalNameTarget(OriginalNameKind.ARTIST, sourceId)
            val aliases = mapOf(
                performerTarget to listOf(japanesePerformer, englishPerformer),
                sourceTarget to listOf("Incorrect source translation"),
            )

            suspend fun publishMappings() {
                val mappings = database.artistDisplayMappings().first()
                withContext(Dispatchers.Main) { ArtistDisplayProjection.publish(mappings) }
            }

            suspend fun assertDisplay(name: String, destination: String, linked: Boolean) {
                withContext(Dispatchers.Main) {
                    assertEquals(name, sourceArtist.displayName)
                    assertEquals(name, stored.artistDisplayText())
                    assertEquals(name, metadata.artistDisplayText())
                    assertEquals(name, rawCredit.artists.single().displayName)
                    assertEquals(name, metadata.displayArtists().single().name)
                    assertEquals(destination, metadata.singleArtistTarget())
                    assertEquals(destination, sourceArtist.artistNavigationId)
                    assertEquals(if (linked) "person-image" else "uploader-image", sourceArtist.displayThumbnailUrl)
                    assertEquals(linked, metadata.matchesMetadataQuery(japanesePerformer))
                    assertEquals(linked, metadata.matchesMetadataQuery(englishPerformer))
                    assertEquals(!linked, metadata.matchesMetadataQuery(uploaderName))
                    assertFalse(metadata.matchesMetadataQuery("Incorrect source translation"))
                    assertEquals(playbackEntity, metadata.toSongEntity())
                    assertEquals(sourceId, metadata.artists.single().id)
                    assertEquals(uploaderName, metadata.artists.single().name)
                    assertTrue(metadata.artists.single().isChannel)
                    assertEquals(onlineId, metadata.artists.single().sourceChannelId)
                    assertNull(metadata.artists.single().onlineId)
                    assertEquals(rawCredit, metadata.artistCredit)
                }
                assertEquals(rawEntity, database.songForArtistCredit(input.id))
                assertEquals(rawCredit, database.artistCredit(input.id).first())
                assertEquals(sourceArtist, database.artistById(sourceId))
                assertEquals(listOf(sourceId), database.artistIdsForSong(input.id))
            }

            // A channel ID equal to the chosen performer's ID is still only uploader provenance.
            publishMappings()
            withContext(Dispatchers.Main) {
                MetadataNames.publish(mapOf(
                    performerTarget to englishPerformer,
                    sourceTarget to "Incorrect source translation",
                ), aliases)
            }
            assertDisplay(uploaderName, sourceId, linked = false)

            val choice = LocalArtistLink(sourceId, onlineId, "Chosen provider text", "person-image", "names-choice")
            database.setLocalArtistLink(choice)
            publishMappings()
            assertDisplay(englishPerformer, onlineId, linked = true)

            withContext(Dispatchers.Main) {
                MetadataNames.publish(mapOf(performerTarget to japanesePerformer), aliases)
            }
            assertDisplay(japanesePerformer, onlineId, linked = true)
            withContext(Dispatchers.Main) {
                MetadataNames.publish(mapOf(performerTarget to englishPerformer), aliases)
            }
            assertDisplay(englishPerformer, onlineId, linked = true)

            assertTrue(database.removeLocalArtistLink(sourceId, choice.revision))
            publishMappings()
            // Cached Japanese/English performer names remain published after unlink.
            assertDisplay(uploaderName, sourceId, linked = false)
            assertNull(database.artistByOnlineId(onlineId))
            assertTrue(database.localArtistLinkSources().first().isEmpty())
        } finally {
            withContext(Dispatchers.Main) {
                ArtistDisplayProjection.publish(emptyList())
                MetadataNames.publish(emptyMap())
            }
            internal.close()
        }
    }
}
