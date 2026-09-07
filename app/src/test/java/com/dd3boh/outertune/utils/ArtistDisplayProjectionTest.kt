package com.dd3boh.outertune.utils

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistDisplayProjectionTest {
    private val onlineId = "UCrPe3hLA51968GwxHSZ1llw"
    private val artistRef = "LA-stored-person"
    private val credit = ArtistCredit(
        rawText = "ニルヴァーナ",
        artists = listOf(Artist("ニルヴァーナ", onlineId, artistRef)),
        status = ArtistCreditStatus.COMPLETE,
    )

    @After
    fun resetPublishedNames() {
        MetadataNames.publish(emptyMap())
    }

    @Test
    fun `projected artist text preserves credited name and navigation identity`() {
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "Nirvana"))
        val metadata = metadata()

        assertEquals("Nirvana", metadata.artistDisplayText())
        assertEquals(artistRef, metadata.singleArtistTarget())
        assertEquals("ニルヴァーナ", metadata.artists.single().name)
        assertEquals("ニルヴァーナ", metadata.artistCredit?.rawText)
        assertEquals(onlineId, metadata.artists.single().onlineId)
    }

    @Test
    fun `a local parent retains its own tags even when a child has an online identifier`() {
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "Nirvana"))
        val metadata = metadata().copy(isLocal = true)

        assertEquals("ニルヴァーナ", metadata.artistDisplayText())
        assertEquals(artistRef, metadata.singleArtistTarget())
    }

    @Test
    fun `name projection does not discard an unresolved collaborator`() {
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "Nirvana"))
        val metadata = metadata().copy(
            artistCredit = credit.copy(
                rawText = "ニルヴァーナ & 未確認の人物",
                status = ArtistCreditStatus.PARTIAL,
            ),
        )

        assertEquals("ニルヴァーナ & 未確認の人物", metadata.artistDisplayText())
        assertEquals(null, metadata.singleArtistTarget())
    }

    private fun metadata() = MediaMetadata(
        id = "ljUtuoFt-8c",
        title = "曲名",
        duration = 302,
        genre = null,
        artists = listOf(MediaMetadata.Artist(artistRef, "ニルヴァーナ", onlineId = onlineId)),
        artistCredit = credit,
    )
}
