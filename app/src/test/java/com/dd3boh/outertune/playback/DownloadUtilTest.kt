package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DownloadUtilTest {
    private val original = MediaMetadata(
        id = "4tlUwgtgdZA",
        title = "丸ノ内サディスティック",
        artists = listOf(MediaMetadata.Artist(id = null, name = "椎名林檎")),
        duration = 214,
        thumbnailUrl = "search-thumbnail",
        album = null,
        genre = null,
    )
    private val resolved = original.copy(
        title = "different queue title",
        artists = listOf(MediaMetadata.Artist(id = "UCbrWU0y_rLsEOYgaTX5Y74A", name = "椎名林檎")),
        thumbnailUrl = "queue-thumbnail",
        album = MediaMetadata.Album(
            id = "MPREb_Oo0wRyxtDXp",
            title = "無罪モラトリアム",
        ),
    )

    @Test
    fun `queue metadata fills missing album without replacing search presentation`() {
        val merged = mergeResolvedMetadata(original, resolved)

        assertEquals(resolved.album, merged.album)
        assertEquals("UCbrWU0y_rLsEOYgaTX5Y74A", merged.artists.single().id)
        assertEquals(original.title, merged.title)
        assertEquals(original.thumbnailUrl, merged.thumbnailUrl)
    }

    @Test
    fun `missing queue album does not invent an album`() {
        val merged = mergeResolvedMetadata(original, resolved.copy(album = null))

        assertNull(merged.album)
    }

    @Test
    fun `complete resolved names replace raw download byline consistently and fill the album`() {
        val raw = ArtistCredit("A、B", emptyList(), ArtistCreditStatus.RAW, "search", "ja")
        val complete = raw.copy(status = ArtistCreditStatus.COMPLETE,
            artists = listOf(Artist("A", null, "ref-a"), Artist("B", "UC-b", "ref-b")))
        val merged = mergeResolvedMetadata(original.withArtistCredit(raw), resolved.withArtistCredit(complete))

        assertEquals(ArtistCreditStatus.COMPLETE, merged.artistCredit!!.status)
        assertEquals("A、B", merged.artistCredit!!.rawText)
        assertEquals(listOf("A", "B"), merged.artists.map { it.name })
        assertEquals(merged.artistCredit!!.artists.map { it.ref }, merged.artists.map { it.id })
        assertEquals(merged.artistCredit!!.artists.map { it.id }, merged.artists.map { it.onlineId })
        assertEquals(resolved.album, merged.album)
        assertEquals(original.title, merged.title)
    }

    @Test
    fun `thin response cannot regress saved names or discard the existing album`() {
        val complete = ArtistCredit("A、B", listOf(Artist("A", "UC-a", "ref-a"), Artist("B", null, "ref-b")),
            ArtistCreditStatus.COMPLETE, "credits", "ja")
        val saved = original.copy(album = MediaMetadata.Album("saved-album", "Saved album"))
            .withArtistCredit(complete)
        val thin = resolved.withArtistCredit(ArtistCredit("A", emptyList(), ArtistCreditStatus.RAW, "queue", "ja"))
        val merged = mergeResolvedMetadata(saved, thin)

        assertEquals(saved.artistCredit!!.artists, merged.artistCredit!!.artists)
        assertEquals(saved.artists, merged.artists)
        assertEquals(saved.artistCredit!!.status, merged.artistCredit!!.status)
        assertEquals(saved.album, merged.album)
    }

    @Test
    fun `late online ids enrich download metadata while internal artist refs remain stable`() {
        val before = ArtistCredit("A、B", listOf(Artist("A", null, "ref-a"), Artist("B", null, "ref-b")),
            ArtistCreditStatus.COMPLETE, "credits", "ja")
        val incoming = before.copy(artists = listOf(Artist("A", "UC-a", "new-ref"), Artist("B", null)))
        val merged = mergeResolvedMetadata(original.withArtistCredit(before), resolved.withArtistCredit(incoming))

        assertEquals(listOf("ref-a", "ref-b"), merged.artists.map { it.id })
        assertEquals(listOf("UC-a", null), merged.artists.map { it.onlineId })
        assertEquals(merged.artists.map { it.onlineId }, merged.artistCredit!!.artists.map { it.id })
    }

    @Test
    fun `conflicting resolved credits keep adopted download display and record the conflict`() {
        val before = ArtistCredit("A、B", listOf(Artist("A", "UC-a", "ref-a"), Artist("B", null, "ref-b")),
            ArtistCreditStatus.COMPLETE, "credits", "ja")
        val incoming = before.copy(artists = listOf(Artist("A", "UC-other")))
        val merged = mergeResolvedMetadata(original.withArtistCredit(before), resolved.withArtistCredit(incoming))

        assertEquals(before.artists, merged.artistCredit!!.artists)
        assertEquals(ArtistCreditStatus.COMPLETE, merged.artistCredit!!.status)
        assertTrue(merged.artistCredit!!.evidence.any { it.startsWith("conflict:") })
        assertEquals(listOf("A", "B"), merged.artists.map { it.name })
    }

    @Test
    fun `unrelated responses and local file metadata are not adopted as online credits`() {
        assertEquals(original, mergeResolvedMetadata(original, resolved.copy(id = "another-video")))
        val local = original.copy(isLocal = true,
            artists = listOf(MediaMetadata.Artist("local-ref", "Local tag", isLocal = true)))
        assertEquals(local, mergeResolvedMetadata(local, resolved))
    }

    @Test
    fun `completed download is persisted with its update time`() {
        val updateTimeMs = 1_700_000_000_000L

        assertEquals(
            Instant.ofEpochMilli(updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime(),
            completedAtForDownloadState(Download.STATE_COMPLETED, updateTimeMs),
        )
    }

    @Test
    fun `queued or active download is not persisted as completed`() {
        assertNull(completedAtForDownloadState(Download.STATE_QUEUED, 1L))
        assertNull(completedAtForDownloadState(Download.STATE_DOWNLOADING, 1L))
    }

    @Test
    fun `clear downloads retains ids still present in extra import directories`() {
        val idsToClear = downloadIdsToClear(
            indexedMediaIds = setOf("internal", "also-extra"),
            cacheMediaIds = setOf("orphan-cache"),
            databaseDownloadIds = setOf("stale-db", "also-extra"),
            deletedMainMediaIds = setOf("main", "also-extra"),
            remainingCustomIds = setOf("also-extra", "extra-only"),
        )

        assertEquals(setOf("internal", "orphan-cache", "stale-db", "main"), idsToClear)
    }
}
