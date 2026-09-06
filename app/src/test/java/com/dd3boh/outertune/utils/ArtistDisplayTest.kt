package com.dd3boh.outertune.utils

import com.dd3boh.outertune.models.MediaMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistDisplayTest {
    @Test
    fun `a partial match does not remove the unresolved remainder`() {
        val credit = ArtistCredit("A & B / C", listOf(Artist("A", "UC-a", "LA-a")), ArtistCreditStatus.PARTIAL)
        assertEquals("A & B / C", artistDisplayText(credit, listOf("A"), language = "ja"))
        assertNull(metadata(credit).singleArtistTarget())
    }

    @Test
    fun `an unresolved byline never becomes an artist navigation target`() {
        val credit = ArtistCredit("A & B", emptyList(), ArtistCreditStatus.RAW)
        assertEquals("A & B", artistDisplayText(credit, emptyList(), language = "ja"))
        assertNull(metadata(credit).singleArtistTarget())
    }

    @Test
    fun `all four ID combinations keep both established names`() {
        for (aId in listOf(null, "UC-a")) {
            for (bId in listOf(null, "UC-b")) {
                val credit = ArtistCredit(
                    "A & B", listOf(Artist("A", aId, "LA-a"), Artist("B", bId, "LA-b")),
                    ArtistCreditStatus.COMPLETE,
                )
                assertEquals("A、B", artistDisplayText(credit, emptyList(), language = "ja"))
            }
        }
    }

    @Test
    fun `punctuation inside a confirmed group name is preserved`() {
        val credit = ArtistCredit(
            "Earth, Wind & Fire", listOf(Artist("Earth, Wind & Fire", null, "LA-group")),
            ArtistCreditStatus.COMPLETE,
        )
        assertEquals("Earth, Wind & Fire", artistDisplayText(credit, emptyList(), language = "ja"))
        assertEquals("LA-group", metadata(credit).singleArtistTarget())
    }

    @Test
    fun `a page name cannot replace an accepted track credit`() {
        val credit = ArtistCredit("A & B", listOf(Artist("A", null), Artist("B", "UC-b")), ArtistCreditStatus.COMPLETE)
        assertEquals("A、B", artistDisplayText(credit, listOf("B Official"), language = "ja"))
    }

    @Test
    fun `an information conflict preserves the complete original text`() {
        val credit = ArtistCredit("A & B", listOf(Artist("A", "UC-a")), ArtistCreditStatus.CONFLICT)
        assertEquals("A & B", artistDisplayText(credit, listOf("A"), language = "ja"))
        assertNull(metadata(credit).singleArtistTarget())
    }

    @Test
    fun `folder media keep the existing separator and names`() {
        assertEquals("Earth, Wind & Fire, B", artistDisplayText(
            null, listOf("Earth, Wind & Fire", "B"), preserveLegacy = true, language = "ja",
        ))
    }

    @Test
    fun `video bylines keep their existing display separator`() {
        val credit = ArtistCredit(
            "A & B", listOf(Artist("A", "UC-a"), Artist("B", "UC-b")),
            ArtistCreditStatus.COMPLETE, evidence = listOf("video-source:MUSIC_VIDEO_TYPE_OMV"),
        )
        assertEquals("A, B", artistDisplayText(credit, listOf("A", "B"), language = "ja"))
    }

    @Test
    fun `missing original and individual names have an explicit label`() {
        assertEquals("アーティスト不明", artistDisplayText(null, emptyList(), language = "ja"))
    }

    private fun metadata(credit: ArtistCredit) = MediaMetadata(
        id = "track", title = "Song", duration = 180, genre = null,
        artists = credit.artists.map { MediaMetadata.Artist(it.ref, it.name, onlineId = it.id) },
        artistCredit = credit,
    )
}
