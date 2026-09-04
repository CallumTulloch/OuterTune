package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.NavigationEndpoint
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.SongMetadataEndpointHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMetadataEnricherTest {
    @Test
    fun `unlinked credit run remains one opaque display artist`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(Run(text = "翟锦彦、8082Audio", navigationEndpoint = null)),
            verifiedArtistNames = mapOf(
                "UChWKQRswWTLRXp98zmgHtdQ" to "8082Audio",
            ),
        )

        assertEquals(1, artists.size)
        assertEquals("翟锦彦、8082Audio", artists.single().name)
        assertNull(artists.single().id)
    }

    @Test
    fun `candidate id is attached only to an exactly matching credit name`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(Run(text = "8082Audio", navigationEndpoint = null)),
            verifiedArtistNames = mapOf(
                "UChWKQRswWTLRXp98zmgHtdQ" to "8082Audio",
            ),
        )

        assertEquals("UChWKQRswWTLRXp98zmgHtdQ", artists.single().id)
        assertEquals("8082Audio", artists.single().name)
    }

    @Test
    fun `ambiguous exact candidate names remain non-navigable`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(Run(text = "Same Name", navigationEndpoint = null)),
            verifiedArtistNames = mapOf(
                "UC_first" to "Same Name",
                "UC_second" to "Same Name",
            ),
        )

        assertEquals("Same Name", artists.single().name)
        assertNull(artists.single().id)
    }

    @Test
    fun `linked credit runs are the only explicit artist boundaries`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(
                Run(
                    text = "First",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC_first"),
                    ),
                ),
                Run(
                    text = "Second",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC_second"),
                    ),
                ),
            ),
            verifiedArtistNames = emptyMap(),
        )

        assertEquals(listOf("First", "Second"), artists.map(MediaMetadata.Artist::name))
        assertEquals(listOf("UC_first", "UC_second"), artists.map(MediaMetadata.Artist::id))
    }

    @Test
    fun `unlinked separator among linked runs stays opaque`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(
                Run(
                    text = "First",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC_first"),
                    ),
                ),
                Run(text = " & ", navigationEndpoint = null),
                Run(
                    text = "Second",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC_second"),
                    ),
                ),
            ),
            verifiedArtistNames = emptyMap(),
        )

        assertEquals(listOf("First & Second"), artists.map(MediaMetadata.Artist::name))
        assertNull(artists.single().id)
    }

    @Test
    fun `mixed linked and unlinked credit runs fall back to one opaque display`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(
                Run(
                    text = "First",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC_first"),
                    ),
                ),
                Run(text = " & ", navigationEndpoint = null),
                Run(text = "Second", navigationEndpoint = null),
            ),
            verifiedArtistNames = emptyMap(),
        )

        assertEquals(listOf("First & Second"), artists.map(MediaMetadata.Artist::name))
        assertNull(artists.single().id)
    }

    @Test
    fun `punctuation inside an official display name is never split`() {
        listOf("Earth, Wind & Fire", "AC/DC", "水曜日のカンパネラ・詩羽").forEach { name ->
            val artists = primaryArtistsFromCredits(
                runs = listOf(Run(text = name, navigationEndpoint = null)),
                verifiedArtistNames = emptyMap(),
            )

            assertEquals(listOf(name), artists.map(MediaMetadata.Artist::name))
        }
    }

    @Test
    fun `multiple unlinked runs remain one opaque display artist`() {
        val artists = primaryArtistsFromCredits(
            runs = listOf(
                Run(text = "Earth", navigationEndpoint = null),
                Run(text = ", ", navigationEndpoint = null),
                Run(text = "Wind", navigationEndpoint = null),
                Run(text = " & ", navigationEndpoint = null),
                Run(text = "Fire", navigationEndpoint = null),
            ),
            verifiedArtistNames = emptyMap(),
        )

        assertEquals(listOf("Earth, Wind & Fire"), artists.map(MediaMetadata.Artist::name))
        assertNull(artists.single().id)
    }

    @Test
    fun `song endpoint hints survive media metadata conversion without artist association`() {
        val metadata = SongItem(
            id = "video",
            title = "Song",
            artists = emptyList(),
            duration = 180,
            thumbnail = "https://example.com/cover.jpg",
            metadataEndpointHints = SongMetadataEndpointHints(
                album = BrowseEndpoint(browseId = "MPRE_album"),
                artistCandidates = listOf(
                    BrowseEndpoint(browseId = "UC_artist"),
                    BrowseEndpoint(browseId = "UC_artist"),
                ),
                credits = BrowseEndpoint(browseId = "MPTCvideo"),
            ),
        ).toMediaMetadata()

        assertEquals("MPRE_album", metadata.metadataEndpointHints.albumBrowseId)
        assertEquals(listOf("UC_artist"), metadata.metadataEndpointHints.artistBrowseIds)
        assertEquals("MPTCvideo", metadata.metadataEndpointHints.creditsBrowseId)
        assertTrue(metadata.artists.isEmpty())
    }

    @Test
    fun `resolved fields overlay a newer source without replacing user state`() {
        val resolution = PlaybackMetadataEnrichment(
            metadata = metadata(artistId = null, album = MediaMetadata.Album("album", "Album"))
                .copy(artists = listOf(MediaMetadata.Artist(id = null, name = "Resolved"))),
            albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
        )
        val newerSource = metadata(artistId = null, album = null).copy(
            title = "Newer title",
            liked = true,
            setVideoId = "playlist-entry",
        )

        val applied = resolution.applyTo(newerSource)

        assertEquals("Newer title", applied.title)
        assertTrue(applied.liked)
        assertEquals("playlist-entry", applied.setVideoId)
        assertEquals("album", applied.album?.id)
        assertEquals(listOf("Resolved"), applied.artists.map(MediaMetadata.Artist::name))
        assertTrue(applied.artistCreditsResolved)
    }

    @Test
    fun `running request only covers artist hints it actually received`() {
        val unresolved = metadata(artistId = null, album = null)
        val albumOnlyRequest = unresolved.copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(),
        )
        val creditsRequest = unresolved.copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                artistBrowseIds = listOf("UC_candidate"),
                creditsBrowseId = "MPTCvideo",
            ),
        )

        assertFalse(albumOnlyRequest.coversMetadataEnrichmentRequest(creditsRequest))
        assertTrue(creditsRequest.coversMetadataEnrichmentRequest(creditsRequest))
        assertFalse(
            creditsRequest.copy(album = MediaMetadata.Album("album", "Album"))
                .coversMetadataEnrichmentRequest(creditsRequest),
        )
        assertFalse(
            albumOnlyRequest.coversMetadataEnrichmentRequest(
                creditsRequest.copy(
                    metadataEndpointHints = creditsRequest.metadataEndpointHints.copy(
                        albumBrowseId = "MPRE_album",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `running album request distinguishes conflicting hints even with a stale album`() {
        val request = metadata(
            artistId = null,
            album = MediaMetadata.Album("MPRE_stale", "Stale"),
        ).copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_expected",
            ),
        )
        val conflicting = request.copy(
            metadataEndpointHints = request.metadataEndpointHints.copy(
                albumBrowseId = "MPRE_other",
            ),
        )

        assertTrue(request.needsAlbumMetadataResolution())
        assertTrue(request.coversAlbumMetadataRequest(request))
        assertFalse(request.coversAlbumMetadataRequest(conflicting))
    }

    @Test
    fun `stale album remains compatible with the album named by its authoritative hint`() {
        val source = metadata(
            artistId = null,
            album = MediaMetadata.Album("MPRE_stale", "Stale"),
        ).copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_expected",
            ),
        )

        assertTrue(
            source.isCompatibleWithResolvedAlbum(
                MediaMetadata.Album("MPRE_expected", "Expected"),
            ),
        )
        assertFalse(
            source.isCompatibleWithResolvedAlbum(
                MediaMetadata.Album("MPRE_other", "Other"),
            ),
        )
    }

    @Test
    fun `cached fields do not cross conflicting endpoint hints`() {
        val cachedMetadata = metadata(
            artistId = "UC_old",
            album = MediaMetadata.Album("MPRE_old", "Old album"),
        ).copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_old",
                artistBrowseIds = listOf("UC_old"),
                creditsBrowseId = "MPTC_old",
            ),
        )
        val resolution = PlaybackMetadataEnrichment(
            metadata = cachedMetadata,
            albumStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
        )
        val newSource = metadata(artistId = null, album = null).copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_new",
                artistBrowseIds = listOf("UC_new"),
                creditsBrowseId = "MPTC_new",
            ),
        )

        val applied = resolution.applyTo(newSource)

        assertNull(applied.album)
        assertNull(applied.artists.single().id)
    }

    @Test
    fun `source album is not propagated as an authoritative lookup`() {
        val resolution = PlaybackMetadataEnrichment(
            metadata = metadata(
                artistId = null,
                album = MediaMetadata.Album("MPRE_source", "Source album"),
            ),
            albumStatus = PlaybackMetadataResolutionStatus.NOT_REQUESTED,
        )

        assertNull(resolution.applyTo(metadata(artistId = null, album = null)).album)
    }

    @Test
    fun `resolved display artist never downgrades a newer verified artist identity`() {
        val hints = MediaMetadata.MetadataEndpointHints(
            artistBrowseIds = listOf("UCbrWU0y_rLsEOYgaTX5Y74A"),
            creditsBrowseId = "MPTCTSZhKssbW2g",
        )
        val resolution = PlaybackMetadataEnrichment(
            metadata = metadata(artistId = null, album = null).copy(
                artists = listOf(MediaMetadata.Artist(id = null, name = "Credits display")),
                metadataEndpointHints = hints,
            ),
            artistsStatus = PlaybackMetadataResolutionStatus.RESOLVED,
            artistIdentityResolutionComplete = false,
        )
        val verified = MediaMetadata.Artist(
            id = "UCbrWU0y_rLsEOYgaTX5Y74A",
            name = "Verified artist",
        )
        val newerSource = metadata(artistId = verified.id, album = null).copy(
            artists = listOf(verified),
            metadataEndpointHints = hints,
            artistCreditsResolved = true,
        )

        val applied = resolution.applyTo(newerSource)
        assertEquals(listOf(verified), applied.artists)
        assertTrue(applied.artistCreditsResolved)
    }

    @Test
    fun `only missing fields with a credits endpoint require enrichment`() {
        val complete = metadata(
            artistId = "UCbrWU0y_rLsEOYgaTX5Y74A",
            album = MediaMetadata.Album("MPREalbum", "Album"),
        )
        val missingAlbum = complete.copy(album = null)
        val unresolvedArtist = complete.copy(
            artists = listOf(MediaMetadata.Artist(id = null, name = "翟锦彦、8082Audio")),
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                creditsBrowseId = "MPTCTSZhKssbW2g",
            ),
        )
        val linkedButNotCreditsResolved = complete.copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                creditsBrowseId = "MPTCTSZhKssbW2g",
            ),
        )
        val linkedAndCreditsResolved = linkedButNotCreditsResolved.copy(
            artistCreditsResolved = true,
        )
        val unresolvedWithoutCredits = unresolvedArtist.copy(
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(),
        )
        val emptyArtistCredits = unresolvedArtist.copy(artists = emptyList())
        val syntheticArtist = unresolvedArtist.copy(
            artists = listOf(MediaMetadata.Artist(id = "LAsynthetic", name = "Artist")),
        )
        val localSong = unresolvedArtist.copy(isLocal = true)

        assertFalse(complete.needsPlaybackMetadataEnrichment())
        assertTrue(missingAlbum.needsPlaybackMetadataEnrichment())
        assertTrue(unresolvedArtist.needsPlaybackMetadataEnrichment())
        assertTrue(emptyArtistCredits.needsPlaybackMetadataEnrichment())
        assertTrue(syntheticArtist.needsPlaybackMetadataEnrichment())
        assertTrue(linkedButNotCreditsResolved.needsPlaybackMetadataEnrichment())
        assertFalse(linkedAndCreditsResolved.needsPlaybackMetadataEnrichment())
        assertFalse(localSong.needsPlaybackMetadataEnrichment())
        assertFalse(unresolvedWithoutCredits.needsPlaybackMetadataEnrichment())
    }

    private fun metadata(
        artistId: String?,
        album: MediaMetadata.Album?,
    ) = MediaMetadata(
        id = "TSZhKssbW2g",
        title = "It Will Fit Me Just As Well",
        artists = listOf(MediaMetadata.Artist(id = artistId, name = "Artist")),
        duration = 238,
        album = album,
        genre = null,
    )
}
