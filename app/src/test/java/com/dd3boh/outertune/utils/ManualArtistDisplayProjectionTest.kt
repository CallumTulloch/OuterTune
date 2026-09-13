package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ManualArtistDisplayProjectionTest {
    private val localId = "LA-local-source"
    private val onlineId = "UCabcdefghijklmnopqrstuv"
    private val otherId = "UCABCDEFGHIJKLMNOPQRSTUV"
    private val sourceName = "元のファイル表記"
    private val sourceArtist = ArtistEntity(localId, sourceName, thumbnailUrl = "/local/artist.jpg", isLocal = true)
    private val credit = ArtistCredit(sourceName, listOf(Artist(sourceName, null, localId)),
        ArtistCreditStatus.COMPLETE, "file-tags", "ja")

    @After fun reset() {
        ArtistDisplayProjection.publish(emptyList())
        MetadataNames.publish(emptyMap())
    }

    private fun mapping(id: String = onlineId, name: String = "選択したオンライン名", image: String? = "online-image") =
        ArtistDisplayMapping(localId, id, name, image)

    private fun song() = Song(SongEntity(
        id = "LS-original", title = "元の曲名", duration = 180, isLocal = true,
        localPath = "/local/original.wav", artistCreditJson = credit.toStoredJson(),
    ), listOf(sourceArtist))

    @Test fun `folder song album player and navigation share the canonical name while all source values survive`() {
        val song = song()
        val metadata = song.toMediaMetadata()
        val savedBefore = metadata.toSongEntity()
        val albumEntity = AlbumEntity("AL-local", title = "元のアルバム", songCount = 1,
            duration = 180, isLocal = true, artistCreditJson = credit.toStoredJson())
        val album = Album(albumEntity, 0, listOf(sourceArtist))
        val albumWithSongs = AlbumWithSongs(albumEntity, listOf(sourceArtist), listOf(song), 0)
        assertEquals(sourceName, song.artistDisplayText())
        ArtistDisplayProjection.publish(listOf(mapping()))
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "言語設定で選ばれた名前"))

        assertEquals("言語設定で選ばれた名前", song.artistDisplayText())
        assertEquals(song.artistDisplayText(), album.artistDisplayText())
        assertEquals(song.artistDisplayText(), albumWithSongs.artistDisplayText())
        assertEquals(song.artistDisplayText(), metadata.artistDisplayText())
        assertEquals(onlineId, metadata.singleArtistTarget())
        assertEquals(onlineId, sourceArtist.artistNavigationId)
        assertEquals("online-image", sourceArtist.displayThumbnailUrl)
        assertEquals(onlineId, metadata.displayArtists().single().id)
        assertEquals(sourceName, sourceArtist.name)
        assertEquals(localId, metadata.artists.single().id)
        assertEquals(sourceName, metadata.artists.single().name)
        assertEquals(credit, metadata.artistCredit)
        val savedAfter = metadata.toSongEntity()
        assertEquals(savedBefore.copy(inLibrary = savedAfter.inLibrary), savedAfter)
        assertEquals("/local/original.wav", metadata.localPath)
        assertEquals("元のアルバム", album.album.title)
        assertEquals(localId, album.artists.single().id)
    }

    @Test fun `unlink restores tags and relink replaces both destination and searchable online names`() {
        val metadata = song().toMediaMetadata()
        ArtistDisplayProjection.publish(listOf(mapping(name = "First online")))
        assertTrue(metadata.matchesMetadataQuery("First online"))
        assertFalse(metadata.matchesMetadataQuery(sourceName))
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "第一の名前"),
            mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to listOf("First online", "第一の名前")))
        assertTrue(metadata.matchesMetadataQuery("第一"))
        assertTrue(metadata.matchesMetadataQuery("First online"))
        assertFalse(metadata.matchesMetadataQuery(sourceName))

        ArtistDisplayProjection.publish(listOf(mapping(otherId, "Second online")))
        assertEquals(otherId, metadata.singleArtistTarget())
        assertEquals("Second online", metadata.artistDisplayText())
        assertFalse(metadata.matchesMetadataQuery("First online"))
        assertFalse(metadata.matchesMetadataQuery("第一"))
        assertTrue(metadata.matchesMetadataQuery("Second online"))

        ArtistDisplayProjection.publish(emptyList())
        assertEquals(localId, metadata.singleArtistTarget())
        assertEquals(sourceName, metadata.artistDisplayText())
        assertTrue(metadata.matchesMetadataQuery(sourceName))
        assertFalse(metadata.matchesMetadataQuery("Second online"))
        assertEquals("/local/artist.jpg", sourceArtist.displayThumbnailUrl)
    }

    @Test fun `two manually linked source tags display one identity without removing either source credit`() {
        val secondId = "LA-second-local-source"
        val metadata = song().toMediaMetadata().copy(artists = listOf(
            MediaMetadata.Artist(localId, sourceName, isLocal = true),
            MediaMetadata.Artist(secondId, "別のタグ表記", isLocal = true),
        ))
        ArtistDisplayProjection.publish(listOf(mapping(), mapping().copy(sourceArtistId = secondId)))
        assertEquals(1, metadata.displayArtists().size)
        assertEquals(onlineId, metadata.singleArtistTarget())
        assertEquals("選択したオンライン名", metadata.artistDisplayText())
        assertEquals(listOf(localId, secondId), metadata.artists.map { it.id })
        assertEquals(credit, metadata.artistCredit)
        assertFalse(metadata.matchesMetadataQuery("別のタグ表記"))
    }

    @Test fun `manual link also deduplicates its known online counterpart but never guesses from an equal name`() {
        ArtistDisplayProjection.publish(listOf(mapping()))
        val artists = listOf(
            MediaMetadata.Artist("LA-remote-reference", "Remote raw", onlineId = onlineId),
            MediaMetadata.Artist(localId, sourceName, isLocal = true),
            MediaMetadata.Artist("LA-unlinked", "選択したオンライン名", isLocal = true),
        )
        val displayed = artists.artistDisplayTargets()
        assertEquals(listOf(onlineId, "LA-unlinked"), displayed.map { it.id })
        assertEquals(3, artists.size)
    }

    @Test fun `unlinked local and incomplete online credits retain their established display and navigation rules`() {
        val target = OriginalNameTarget(OriginalNameKind.ARTIST, onlineId)
        MetadataNames.publish(mapOf(target to "Online translated"))
        val incidental = song().toMediaMetadata().copy(artists = listOf(
            MediaMetadata.Artist(localId, sourceName, onlineId = onlineId),
        ))
        assertEquals(sourceName, incidental.artistDisplayText())
        assertEquals(sourceName, incidental.displayArtists().single().name)
        assertEquals(localId, incidental.singleArtistTarget())

        val partial = incidental.copy(isLocal = false, artistCredit = credit.copy(
            rawText = "Known & Unknown", status = ArtistCreditStatus.PARTIAL,
        ))
        assertEquals("Known & Unknown", partial.artistDisplayText())
        assertNull(partial.singleArtistTarget())
        val regularOnline = listOf(MediaMetadata.Artist("LA-online", "Raw", onlineId = onlineId))
        assertEquals("LA-online", regularOnline.artistDisplayTargets().single().id)
        assertEquals("Online translated", regularOnline.artistDisplayTargets().single().name)
    }

    @Test fun `missing canonical artwork does not expose a different source portrait and identity changes publish even with equal names`() {
        val before = ArtistDisplayProjection.updates.value
        ArtistDisplayProjection.publish(listOf(mapping(image = null)))
        assertNull(sourceArtist.displayThumbnailUrl)
        assertNull(sourceArtist.displayArtistTarget().thumbnailUrl)
        val linked = ArtistDisplayProjection.updates.value
        assertTrue(linked > before)
        ArtistDisplayProjection.publish(listOf(mapping(image = null)))
        assertEquals(linked, ArtistDisplayProjection.updates.value)
        ArtistDisplayProjection.publish(listOf(mapping(otherId, image = null)))
        assertTrue(ArtistDisplayProjection.updates.value > linked)
        assertEquals(otherId, sourceArtist.artistNavigationId)
    }
}
