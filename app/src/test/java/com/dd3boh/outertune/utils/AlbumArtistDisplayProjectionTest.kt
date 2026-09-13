package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
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

class AlbumArtistDisplayProjectionTest {
    private val name = "翟锦彦"
    private val firstRef = "LA-track-one-credit"
    private val secondRef = "LA-track-two-credit"
    private val group = "AG-album-one-person"
    private val otherGroup = "AG-album-two-person"
    private val onlineId = "UC" + "a".repeat(22)

    private fun mapping(ref: String, target: String = group) = ArtistDisplayMapping(ref, target, name, null)

    private fun song(ref: String, id: String = "track-one") = Song(
        SongEntity(id = id, title = "It Will Fit Me Just As Well", duration = 180, localPath = null,
            albumId = "MPRE-album-one", albumName = "特别版", artistCreditJson = credit(ref).toStoredJson()),
        listOf(ArtistEntity(ref, name)),
    )

    private fun credit(ref: String) = ArtistCredit(
        name, listOf(Artist(name, null, ref)), ArtistCreditStatus.COMPLETE, "track-credits", "zh",
    )

    @After fun reset() {
        ArtistDisplayProjection.publish(emptyList())
        MetadataNames.publish(emptyMap())
    }

    @Test fun `album track and player use one internal identity without borrowing remote names or images`() {
        val song = song(firstRef)
        val metadata = song.toMediaMetadata()
        val albumRef = "LA-album-credit"
        val album = Album(AlbumEntity("MPRE-album-one", title = "特别版", songCount = 2, duration = 360,
            artistCreditJson = credit(albumRef).toStoredJson()), 0, listOf(ArtistEntity(albumRef, name)))
        val saved = metadata.toSongEntity()
        ArtistDisplayProjection.publish(listOf(mapping(firstRef), mapping(secondRef), mapping(albumRef)))
        val invalidRemoteTarget = OriginalNameTarget(OriginalNameKind.ARTIST, group)
        MetadataNames.publish(mapOf(invalidRemoteTarget to "Unrelated online name"),
            mapOf(invalidRemoteTarget to listOf("Unrelated online alias")))

        assertEquals(name, song.artistDisplayText())
        assertEquals(name, album.artistDisplayText())
        assertEquals(name, metadata.artistDisplayText())
        assertEquals(group, metadata.singleArtistTarget())
        assertEquals(group, song.artists.single().artistNavigationId)
        assertEquals(group, album.artists.artistDisplayTargets().single().id)
        assertNull(metadata.displayArtists().single().onlineIdentity)
        assertNull(metadata.displayArtists().single().thumbnailUrl)
        assertTrue(metadata.matchesMetadataQuery(name))
        assertFalse(metadata.matchesMetadataQuery("Unrelated online"))
        assertEquals(firstRef, metadata.artists.single().id)
        assertNull(metadata.artists.single().onlineId)
        assertEquals(credit(firstRef), metadata.artistCredit)
        val savedAfter = metadata.toSongEntity()
        assertEquals(saved.copy(inLibrary = savedAfter.inLibrary), savedAfter)
    }

    @Test fun `provisional groups collapse shared refs but keep different albums and online identities separate`() {
        ArtistDisplayProjection.publish(listOf(mapping(firstRef), mapping(secondRef), mapping("LA-other-album", otherGroup)))
        val artists = listOf(
            MediaMetadata.Artist(firstRef, name), MediaMetadata.Artist(secondRef, name),
            MediaMetadata.Artist(group, name), MediaMetadata.Artist("LA-other-album", name),
            MediaMetadata.Artist(onlineId, name, onlineId = onlineId),
            MediaMetadata.Artist("LA-unscoped", name),
        )
        assertEquals(listOf(group, otherGroup, onlineId, "LA-unscoped"), artists.artistDisplayTargets().map { it.id })
        assertEquals(6, artists.size)
    }

    @Test fun `later online resolution moves only the resolved reference without rewriting an existing queue tag`() {
        val first = song(firstRef).toMediaMetadata()
        val second = song(secondRef, "track-two").toMediaMetadata()
        ArtistDisplayProjection.publish(listOf(mapping(firstRef), mapping(secondRef)))
        assertEquals(group, first.singleArtistTarget())
        assertEquals(group, second.singleArtistTarget())

        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "Confirmed artist"))
        ArtistDisplayProjection.publish(listOf(mapping(firstRef, onlineId), mapping(secondRef)))
        assertEquals(onlineId, first.singleArtistTarget())
        assertEquals("Confirmed artist", first.artistDisplayText())
        assertTrue(first.matchesMetadataQuery("Confirmed artist"))
        assertEquals(group, second.singleArtistTarget())
        assertEquals(name, second.artistDisplayText())
        assertFalse(second.matchesMetadataQuery("Confirmed artist"))
        assertEquals(firstRef, first.artists.single().id)
        assertNull(first.artists.single().onlineId)
        assertEquals(credit(firstRef), first.artistCredit)

        ArtistDisplayProjection.publish(emptyList())
        assertEquals(firstRef, first.singleArtistTarget())
        assertEquals(name, first.artistDisplayText())
    }

    @Test fun `an incomplete byline keeps its unresolved people after the known ref joins a group`() {
        ArtistDisplayProjection.publish(listOf(mapping(firstRef)))
        val metadata = song(firstRef).toMediaMetadata().copy(artistCredit = credit(firstRef).copy(
            rawText = "$name & 未确认", status = ArtistCreditStatus.PARTIAL,
        ))
        assertEquals("$name & 未确认", metadata.artistDisplayText())
        assertNull(metadata.singleArtistTarget())
        assertEquals(group, metadata.displayArtists().single().id)
    }
}
