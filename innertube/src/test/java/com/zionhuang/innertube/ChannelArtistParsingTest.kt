@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.zionhuang.innertube

import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.NextPage
import com.zionhuang.innertube.pages.PageHelper
import com.zionhuang.innertube.pages.SearchPage
import com.zionhuang.innertube.pages.SearchSuggestionPage
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class ChannelArtistParsingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val channelId = "UCsHGT0TKdKlnkqd33nf3vzg"
    private val artistId = "UCGKXb1syicud01CJOOFRykg"
    private fun fixture(name: String) = javaClass.getResource("/artist-credit/$name")!!.readText()
    private fun run(name: String, id: String, pageType: String? = "MUSIC_PAGE_TYPE_USER_CHANNEL") = Run(
        name, NavigationEndpoint(browseEndpoint = BrowseEndpoint(id,
            browseEndpointContextSupportedConfigs = pageType?.let {
                BrowseEndpoint.BrowseEndpointContextSupportedConfigs(
                    BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig(it))
            })))
    private fun channelCredit(name: String = "Uploader", id: String = channelId) =
        listOf(run(name, id)).toArtistCredit("video", "ja")

    @Test fun `captured channel video search uses uploader as an internal artist without online id`() {
        val renderer = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("video-search-row.json"))
        val songs = listOf(
            SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem,
            SearchPage.toYTItem(renderer) as SongItem,
            SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem,
            AlbumPage.getSong(renderer)!!,
        )
        songs.forEach { song ->
            val credit = song.artistCredit!!
            assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
            assertEquals("Chinese Brother", credit.rawText)
            assertEquals(credit.artists, song.artists)
            val artist = credit.artists.single()
            assertTrue(artist.isChannel)
            assertNull(artist.id)
            assertEquals(channelId, artist.sourceChannelId)
            assertTrue(credit.evidence.any { it.startsWith("channel-byline:") })
            assertTrue("video-source:MUSIC_VIDEO_TYPE_UGC" in credit.evidence)
        }
    }

    @Test fun `queue uploader survives even when its endpoint omits a page type`() {
        val captured = json.decodeFromString<PlaylistPanelVideoRenderer>(fixture("duet-queue.json"))
        val renderer = captured.copy(longBylineText = Runs(listOf(run("Uploader", channelId, null))))
        val song = NextPage.fromPlaylistPanelVideoRenderer(renderer)!!
        assertEquals(channelCredit().artists, song.artists)
        assertEquals(song.artists, song.artistCredit!!.artists)
        assertNull(song.artists.single().id)
    }

    @Test fun `typed artist byline has priority over a channel name in the same metadata section`() {
        val runs = listOf(run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST"), Run("、", null),
            run("Uploader", channelId))
        val credit = runs.toArtistCredit("video", "ja")
        assertEquals(listOf(Artist("Performer", artistId)), credit.artists)
        assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
        assertFalse(credit.isChannelByline())
        assertEquals(credit.artists, runs.artistElements().map { it.toArtist() })
    }

    @Test fun `responsive metadata prioritizes an explicit artist section over an uploader section`() {
        val columns = listOf(
            MusicResponsiveListItemRenderer.FlexColumn(
                MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                    Runs(listOf(run("Uploader", channelId), Run(" • ", null),
                        run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST")))))
        )
        assertEquals(listOf(Artist("Performer", artistId)),
            PageHelper.artistRuns(columns).toArtistCredit("video").artists)
    }

    @Test fun `video search parsers prioritize an explicit artist after the uploader section or column`() {
        val captured = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("video-search-row.json"))
        val uploaderColumn = captured.flexColumns[1]
        val metadata = uploaderColumn.musicResponsiveListItemFlexColumnRenderer
        val performer = run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST")
        // Mutate only captured metadata: retain category, channel and playback evidence.
        val laterSection = captured.copy(flexColumns = captured.flexColumns.toMutableList().apply {
            this[1] = uploaderColumn.copy(musicResponsiveListItemFlexColumnRenderer = metadata.copy(
                text = Runs(metadata.text!!.runs!!.take(3) + listOf(Run(" • ", null), performer))))
        })
        val laterColumn = captured.copy(flexColumns = captured.flexColumns +
            uploaderColumn.copy(musicResponsiveListItemFlexColumnRenderer = metadata.copy(text = Runs(listOf(performer)))))
        for (renderer in listOf(laterSection, laterColumn)) {
            val songs = listOf(
                SearchPage.toYTItem(renderer) as SongItem,
                SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem,
                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem,
            )
            songs.forEach { song ->
                assertEquals(listOf(Artist("Performer", artistId)), song.artistCredit!!.artists)
                assertEquals(song.artistCredit!!.artists, song.artists)
                assertEquals("Performer", song.artistCredit!!.rawText)
                assertFalse(song.artistCredit!!.isChannelByline())
                assertTrue("video-source:MUSIC_VIDEO_TYPE_UGC" in song.artistCredit!!.evidence)
                assertNull("An artist endpoint is not an album endpoint", song.album)
            }
        }
    }

    @Test fun `video search card prioritizes its later artist section and accepts only an album endpoint`() {
        val captured = json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))
        val videoWatch = captured.onTap.watchEndpoint!!.copy(watchEndpointMusicSupportedConfigs =
            WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_UGC")))
        val names = listOf(Run("動画", null), Run(" • ", null), run("Uploader", channelId),
            Run(" • ", null), run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST"))
        val album = Album("A real album", "MPRE-card-regression")
        for (includeAlbum in listOf(false, true)) {
            val albumRuns = if (includeAlbum) listOf(Run(" • ", null),
                run(album.name, album.id, "MUSIC_PAGE_TYPE_ALBUM")) else emptyList()
            val renderer = captured.copy(
                onTap = captured.onTap.copy(watchEndpoint = videoWatch),
                subtitle = Runs(names + albumRuns + listOf(Run(" • ", null), Run("3:58", null))))
            val song = SearchSummaryPage.fromMusicCardShelfRenderer(renderer) as SongItem
            assertEquals(listOf(Artist("Performer", artistId)), song.artistCredit!!.artists)
            assertEquals(song.artistCredit!!.artists, song.artists)
            assertEquals("Performer", song.artistCredit!!.rawText)
            assertFalse(song.artistCredit!!.isChannelByline())
            assertTrue("video-source:MUSIC_VIDEO_TYPE_UGC" in song.artistCredit!!.evidence)
            assertEquals(if (includeAlbum) album else null, song.album)
            assertEquals(238, song.duration)
            assertEquals(videoWatch, song.endpoint)
        }
    }

    @Test fun `an unknown channel endpoint never supplies an online artist id`() {
        val untyped = run("Uploader", channelId, null).toArtist()
        assertTrue(untyped.isChannel)
        assertEquals(channelId, untyped.sourceChannelId)
        assertNull(untyped.id)
        val unsupported = listOf(run("Unclassified", channelId, "FUTURE_PAGE_TYPE")).toArtistCredit("video")
        assertEquals(ArtistCreditStatus.RAW, unsupported.status)
        assertTrue(unsupported.artists.isEmpty())
        assertEquals(Artist("Unclassified", null), run("Unclassified", channelId, "FUTURE_PAGE_TYPE").toArtist())
        assertEquals(ArtistCreditStatus.RAW, listOf(Run("An unlinked name", null)).toArtistCredit("video").status)
    }

    @Test fun `different channels with the same name remain distinct`() {
        val first = channelCredit("Same name", channelId)
        val second = channelCredit("Same name", artistId)
        assertNotEquals(first.artists.single(), second.artists.single())
        val merged = first.merge(second)
        assertEquals(first.artists, merged.artists)
        assertTrue(merged.evidence.any { it.startsWith("conflict:") })
        val combined = listOf(run("Same name", channelId), Run("、", null), run("Same name", artistId))
            .toArtistCredit("video")
        assertEquals(listOf(channelId, artistId), combined.artists.map { it.sourceChannelId })
        assertEquals(2, combined.artists.size)
    }

    @Test fun `performer evidence replaces uploader fallback and a later uploader cannot replace it`() {
        val channel = channelCredit()
        val performer = listOf(run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST")).toArtistCredit("queue", "ja")
        for (merged in listOf(channel.merge(performer), performer.merge(channel))) {
            assertEquals(performer.rawText, merged.rawText)
            assertEquals(performer.artists, merged.artists)
            assertEquals(performer.status, merged.status)
            assertEquals(performer.source, merged.source)
        }
        // Equal display names do not turn a channel reference into a performer reference.
        val sameName = performer.copy(rawText = "Uploader", artists = listOf(Artist("Uploader", artistId, "artist-ref")))
        val referencedChannel = channel.copy(artists = listOf(channel.artists.single().copy(ref = "channel-ref")))
        assertEquals(sameName.artists, referencedChannel.merge(sameName).artists)
    }

    @Test fun `thinner responses retain channel provenance and no page name can automatically link it`() {
        val channel = channelCredit().copy(artists = listOf(channelCredit().artists.single().copy(ref = "channel-ref")))
        val empty = ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "failed-request", "ja")
        assertEquals(channel, channel.merge(empty))
        assertEquals(channel, ArtistCreditResolver.withPageNames(channel,
            listOf(Artist("Uploader", artistId))))
        val renamed = channelCredit("A later channel name").copy(source = "queue")
        assertEquals(channel.artists, channel.merge(renamed).artists)
        val unresolvedUploader = channel.copy(artists = emptyList(), status = ArtistCreditStatus.RAW)
        assertEquals(channel.artists, unresolvedUploader.merge(channel).artists)
    }

    @Test fun `a channel-only byline never triggers automatic performer or page lookup`() = runBlocking {
        val channel = channelCredit()
        val song = SongItem("video", "Video", channel.artists, thumbnail = "cover",
            artistCredit = channel, artistBrowseIds = listOf(artistId))
        val resolved = YouTube.resolveTrackArtistCredit(song,
            getQueue = { error("A channel byline is already usable in the app") },
            browse = { error("A matching artist page must not silently link an uploader") }).getOrThrow()
        assertEquals(channel, resolved.credit)
        assertNull(resolved.album)
    }

    @Test fun `channel links in album subtitles are not adopted as album artists`() {
        val channel = listOf(run("Uploader", channelId))
        assertEquals(ArtistCreditStatus.RAW, channel.toAlbumArtistCredit("album").status)
        assertTrue(channel.toArtistCredit("album-header", allowChannelFallback = false).artists.isEmpty())
        val mixed = channel + Run("、", null) + run("Performer", artistId, "MUSIC_PAGE_TYPE_ARTIST")
        assertEquals(listOf(Artist("Performer", artistId)), mixed.toAlbumArtistCredit("album").artists)
    }

    @Test fun `channel source survives both JSON and Java queue serialization`() {
        val original = channelCredit()
        assertEquals(original, json.decodeFromString<ArtistCredit>(json.encodeToString(original)))
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(original) }
        }.toByteArray()
        assertEquals(original, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
        val withoutId = Artist("Uploader", null, isChannel = true)
        assertEquals(withoutId, json.decodeFromString<Artist>(json.encodeToString(withoutId)))
    }
}
