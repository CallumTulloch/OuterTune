package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMetadataCacheTest {
    @Test
    fun `album then artists partial successes retain both authoritative fields`() {
        val source = source()
        val album = MediaMetadata.Album("MPRE_album", "Album")
        val artists = listOf(MediaMetadata.Artist("UC_artist", "Artist"))

        val cache = PlaybackMetadataCacheEntry()
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = source.copy(album = album),
                    albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                ),
                nowMs = 1,
            )
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = source.copy(artists = artists),
                    artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                    artistIdentityResolutionComplete = true,
                ),
                nowMs = 2,
            )

        val combined = cache.asEnrichmentFor(source)
        assertEquals(album, combined.metadata.album)
        assertEquals(artists, combined.metadata.artists)
        assertTrue(combined.albumIsAuthoritative)
        assertTrue(combined.artistsAreAuthoritative)
    }

    @Test
    fun `artists then album partial successes retain both authoritative fields`() {
        val source = source()
        val album = MediaMetadata.Album("MPRE_album", "Album")
        val artists = listOf(MediaMetadata.Artist("UC_artist", "Artist"))

        val cache = PlaybackMetadataCacheEntry()
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = source.copy(artists = artists),
                    artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                    artistIdentityResolutionComplete = true,
                ),
                nowMs = 1,
            )
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = source.copy(album = album),
                    albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                ),
                nowMs = 2,
            )

        val applied = cache.applyTo(source)
        assertEquals(album, applied.album)
        assertEquals(artists, applied.artists)
        assertTrue(applied.artistCreditsResolved)
    }

    @Test
    fun `verified search id does not suppress a matching credits result`() {
        val source = source().copy(
            artists = listOf(MediaMetadata.Artist("UC_search", "Combined display")),
        )
        val official = listOf(MediaMetadata.Artist("UC_official", "Official artist"))
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(artists = official),
                artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                artistIdentityResolutionComplete = true,
            ),
            nowMs = 1,
        )

        val applied = cache.applyTo(source)

        assertEquals(official, applied.artists)
        assertTrue(applied.artistCreditsResolved)
    }

    @Test
    fun `cached values do not cross conflicting typed endpoint hints`() {
        val original = source()
        val cached = PlaybackMetadataCacheEntry()
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = original.copy(
                        album = MediaMetadata.Album("MPRE_album", "Album"),
                    ),
                    albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                ),
                nowMs = 1,
            )
            .merge(
                PlaybackMetadataEnrichment(
                    metadata = original.copy(
                        artists = listOf(MediaMetadata.Artist("UC_artist", "Artist")),
                    ),
                    artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                    artistIdentityResolutionComplete = true,
                ),
                nowMs = 1,
            )
        val conflicting = original.copy(
            metadataEndpointHints = original.metadataEndpointHints.copy(
                albumBrowseId = "MPRE_other",
                creditsBrowseId = "MPTC_other",
                artistBrowseIds = listOf("UC_other"),
            ),
        )

        val applied = cached.applyTo(conflicting)
        assertNull(applied.album)
        assertEquals(original.artists, applied.artists)
        assertTrue(cached.shouldResolveAlbum(conflicting, nowMs = 2))
        assertTrue(cached.shouldResolveArtists(conflicting, nowMs = 2))
    }

    @Test
    fun `album cache rejects a response whose id conflicts with its request hint`() {
        val source = source()
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(
                    album = MediaMetadata.Album("MPRE_returned_other", "Wrong album"),
                ),
                albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            ),
            nowMs = 1,
        )

        assertNull(cache.applyTo(source).album)
        assertTrue(cache.shouldResolveAlbum(source, nowMs = 2))
        assertFalse(cache.asEnrichmentFor(source).albumIsAuthoritative)
    }

    @Test
    fun `authoritative cache replaces an album that conflicts with the current hint`() {
        val source = source().copy(
            album = MediaMetadata.Album("MPRE_stale", "Stale album"),
        )
        val correctedAlbum = MediaMetadata.Album("MPRE_album", "Correct album")
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(album = correctedAlbum),
                albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            ),
            nowMs = 1,
        )

        assertTrue(source.needsAlbumMetadataResolution())
        assertEquals(correctedAlbum, cache.applyTo(source).album)
        assertTrue(cache.asEnrichmentFor(source).albumIsAuthoritative)
    }

    @Test
    fun `album mismatch schedules retry after a transient failure`() {
        val source = source().copy(
            album = MediaMetadata.Album("MPRE_stale", "Stale album"),
        )
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source,
                albumStatus = PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
            ),
            nowMs = 1_000,
        )

        assertFalse(cache.shouldResolveAlbum(source, 1_000 + METADATA_RETRY_BASE_MS - 1))
        assertEquals(1_000 + METADATA_RETRY_BASE_MS, cache.nextRetryAt(source))
    }

    @Test
    fun `absent album suppresses repeat lookup until ttl or a new hint`() {
        val source = source().copy(
            metadataEndpointHints = source().metadataEndpointHints.copy(albumBrowseId = null),
        )
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source,
                albumStatus = PlaybackMetadataResolutionStatus.ABSENT,
            ),
            nowMs = 100,
        )

        assertFalse(cache.shouldResolveAlbum(source, nowMs = 100 + METADATA_ABSENT_TTL_MS - 1))
        assertTrue(cache.shouldResolveAlbum(source, nowMs = 100 + METADATA_ABSENT_TTL_MS))
        assertTrue(
            cache.shouldResolveAlbum(
                source.copy(
                    metadataEndpointHints = source.metadataEndpointHints.copy(
                        albumBrowseId = "MPRE_new",
                    ),
                ),
                nowMs = 101,
            ),
        )
    }

    @Test
    fun `retryable artist identity preserves display and retries with bounded backoff`() {
        val source = source()
        val officialDisplay = listOf(MediaMetadata.Artist(id = null, name = "Official Artist"))
        val first = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(artists = officialDisplay),
                artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                artistIdentityResolutionComplete = false,
            ),
            nowMs = 1_000,
        )

        assertEquals(officialDisplay, first.applyTo(source).artists)
        assertFalse(first.shouldResolveArtists(source, nowMs = 1_000 + METADATA_RETRY_BASE_MS - 1))
        assertTrue(first.shouldResolveArtists(source, nowMs = 1_000 + METADATA_RETRY_BASE_MS))

        val afterAlbumSuccess = first.merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(
                    album = MediaMetadata.Album("MPRE_album", "Album"),
                ),
                albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            ),
            nowMs = 2_000,
        )
        assertEquals(officialDisplay, afterAlbumSuccess.applyTo(source).artists)
        assertFalse(afterAlbumSuccess.asEnrichmentFor(source).artistIdentityResolutionComplete)
    }

    @Test
    fun `cached credits restore missing provenance so incomplete identity can retry`() {
        val hintedSource = source()
        val officialDisplay = listOf(MediaMetadata.Artist(id = null, name = "Official Artist"))
        val cache = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = hintedSource.copy(artists = officialDisplay),
                artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                artistIdentityResolutionComplete = false,
            ),
            nowMs = 1_000,
        )
        val hintlessSource = hintedSource.copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(),
        )

        val applied = cache.applyTo(hintlessSource)

        assertEquals(officialDisplay, applied.artists)
        assertEquals("MPTC_video", applied.metadataEndpointHints.creditsBrowseId)
        assertEquals(listOf("UC_artist"), applied.metadataEndpointHints.artistBrowseIds)
        assertTrue(applied.needsArtistCreditResolution())
        assertTrue(cache.shouldResolveArtists(applied, 1_000 + METADATA_RETRY_BASE_MS))

        val queueAndPlayerApplied = cache.asEnrichmentFor(hintlessSource).applyTo(hintlessSource)
        assertEquals("MPTC_video", queueAndPlayerApplied.metadataEndpointHints.creditsBrowseId)
        assertEquals(listOf("UC_artist"), queueAndPlayerApplied.metadataEndpointHints.artistBrowseIds)
    }

    @Test
    fun `network reconnect unlocks only retryable outcomes`() {
        val source = source()
        val failed = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source,
                albumStatus = PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
                artistsStatus = PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
            ),
            nowMs = 10,
        )
        val unlocked = failed.retryFailuresNow(source)

        assertTrue(unlocked.shouldResolveAlbum(source, nowMs = 10))
        assertTrue(unlocked.shouldResolveArtists(source, nowMs = 10))

        val absent = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source,
                albumStatus = PlaybackMetadataResolutionStatus.ABSENT,
            ),
            nowMs = 10,
        ).retryFailuresNow(source)
        assertFalse(absent.shouldResolveAlbum(source, nowMs = 11))
    }

    @Test
    fun `absent credits after incomplete identity keeps display without immediate retry loop`() {
        val source = source()
        val verifiedDisplay = listOf(MediaMetadata.Artist(id = null, name = "Official Artist"))
        val retryAt = 1_000 + METADATA_RETRY_BASE_MS
        val incomplete = PlaybackMetadataCacheEntry().merge(
            PlaybackMetadataEnrichment(
                metadata = source.copy(artists = verifiedDisplay),
                artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
                artistIdentityResolutionComplete = false,
            ),
            nowMs = 1_000,
        )

        val absentAtRetry = incomplete.merge(
            PlaybackMetadataEnrichment(
                metadata = source,
                artistsStatus = PlaybackMetadataResolutionStatus.ABSENT,
            ),
            nowMs = retryAt,
        )

        assertEquals(verifiedDisplay, absentAtRetry.applyTo(source).artists)
        assertFalse(absentAtRetry.shouldResolveArtists(source, nowMs = retryAt))
        assertEquals(
            retryAt + METADATA_ABSENT_TTL_MS,
            absentAtRetry.nextRetryAt(source),
        )
    }

    private fun source() = MediaMetadata(
        id = "video",
        title = "Song",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Raw & Combined")),
        duration = 180,
        genre = null,
        metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
            albumBrowseId = "MPRE_album",
            artistBrowseIds = listOf("UC_artist"),
            creditsBrowseId = "MPTC_video",
        ),
    )
}
