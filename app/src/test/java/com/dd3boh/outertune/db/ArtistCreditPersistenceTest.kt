package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.artistCreditFromJson
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.toStoredJson
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import org.junit.Assert.*
import org.junit.Test

/** Persistence/transport contracts; Room transaction and SQL behavior use the device DB tests. */
class ArtistCreditPersistenceTest {
    @Test
    fun `unidentified same names in different songs do not become the same person`() {
        assertEquals(ArtistIdentity.stableId("one", "Ａ　Ｂ"), ArtistIdentity.stableId("one", "a b"))
        assertNotEquals(ArtistIdentity.stableId("one", "A"), ArtistIdentity.stableId("two", "A"))
    }

    @Test
    fun `late online id does not replace the original internal reference`() {
        val partial = ArtistIdentity.withStableRefs("song",
            credit("A & B", listOf(Artist("A", null)), ArtistCreditStatus.PARTIAL))
        val complete = ArtistIdentity.withStableRefs("song",
            credit("A & B", listOf(Artist("A", "UC-a", "UC-a"), Artist("B", "UC-b"))), partial)
        assertEquals(partial.artists.single().ref, complete.artists.first().ref)
        assertEquals("UC-a", complete.artists.first().id)
        assertEquals("UC-b", complete.artists.last().ref)
    }

    @Test
    fun `raw input never supplies an adopted phantom artist`() {
        val raw = credit("A、B", listOf(Artist("A、B", null)), ArtistCreditStatus.RAW)
        val result = ArtistIdentity.withStableRefs("song", raw)
        assertEquals("A、B", result.rawText)
        assertTrue(result.artists.isEmpty())
    }

    @Test
    fun `raw label language evidence and stable references survive JSON storage`() {
        val original = ArtistCredit("翟锦彦、8082Audio", listOf(Artist("翟锦彦", null, "LA-reference")),
            ArtistCreditStatus.PARTIAL, "album-track", "ja", listOf("structured\ncredit", "quote:\"name\""))
        assertEquals(original, artistCreditFromJson(original.toStoredJson()))
        assertNull(artistCreditFromJson("{broken"))
    }

    @Test
    fun `page-name refresh does not change a saved song's credited spelling`() {
        val ref = ArtistIdentity.stableId("song", "A")
        val adopted = credit("A & B", listOf(Artist("A", "UC-a", ref)), ArtistCreditStatus.PARTIAL)
        val stored = SongEntity("song", "Title", localPath = null, artistCreditJson = adopted.toStoredJson())
        val metadata = Song(stored, listOf(ArtistEntity(ref, "Page title", onlineId = "UC-a"))).toMediaMetadata()
        assertEquals("A", metadata.artists.single().name)
        assertEquals(ref, metadata.artists.single().id)
        assertEquals("UC-a", metadata.artists.single().onlineId)
        assertEquals("A & B", metadata.artistCredit!!.rawText)
    }

    @Test
    fun `readback can recover a canonical reference from an older credited reference`() {
        val adopted = credit("Spelling", listOf(Artist("Spelling", "UC-a", "UC-a")))
        val stored = SongEntity("song", "Title", localPath = null, artistCreditJson = adopted.toStoredJson())
        val metadata = Song(stored, listOf(ArtistEntity("LA-canonical", "Page title", onlineId = "UC-a"))).toMediaMetadata()
        assertEquals("LA-canonical", metadata.artists.single().id)
        assertEquals("LA-canonical", metadata.artistCredit!!.artists.single().ref)
        assertEquals("Spelling", metadata.artists.single().name)
    }

    @Test
    fun `applying a credit preserves media and album properties`() {
        val original = MediaMetadata("song", "Title", emptyList(), duration = 123, genre = null,
            album = MediaMetadata.Album("MPRE-album", "Album"), liked = true, shuffleIndex = 4)
        val updated = original.withArtistCredit(credit("A & B", listOf(Artist("A", null)), ArtistCreditStatus.PARTIAL))
        assertEquals(original, updated.copy(artists = original.artists, artistCredit = original.artistCredit))
        assertEquals(ArtistIdentity.stableId("song", "A"), updated.artists.single().id)
        assertNull(updated.artists.single().onlineId)
    }

    private fun credit(raw: String, artists: List<Artist>, status: ArtistCreditStatus = ArtistCreditStatus.COMPLETE) =
        ArtistCredit(raw, artists, status, "fixture", "ja", listOf("structured-byline:fixture"))
}
