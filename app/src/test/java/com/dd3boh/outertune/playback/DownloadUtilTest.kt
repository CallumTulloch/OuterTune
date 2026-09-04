package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `queue album replaces a stale album only when it matches the typed hint`() {
        val hinted = original.copy(
            album = MediaMetadata.Album("MPRE_stale", "Stale"),
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = resolved.album!!.id,
            ),
        )

        assertEquals(resolved.album, mergeResolvedMetadata(hinted, resolved).album)
        assertEquals(
            hinted.album,
            mergeResolvedMetadata(
                hinted,
                resolved.copy(album = MediaMetadata.Album("MPRE_other", "Other")),
            ).album,
        )
    }

    @Test
    fun `generated online artist id is not treated as a resolved browse id`() {
        val merged = mergeResolvedMetadata(
            original.copy(
                artists = listOf(MediaMetadata.Artist(id = "LAgvxYyqZN", name = "椎名林檎")),
            ),
            resolved,
        )

        assertEquals("UCbrWU0y_rLsEOYgaTX5Y74A", merged.artists.single().id)
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
