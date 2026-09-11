@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.zionhuang.innertube

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.MusicCardShelfRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.PlaylistPanelVideoRenderer
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.NextPage
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.SearchPage
import com.zionhuang.innertube.pages.SearchSummaryPage
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MetadataRequestLocaleTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val savedLocale = YouTube.locale
    private val savedObserver = YouTube.metadataObserver
    private val requestLocale = YouTubeLocale(gl = "JP", hl = "en")
    private fun fixture(name: String) = javaClass.getResource("/artist-credit/$name")!!.readText()

    @After
    fun restoreGlobals() {
        YouTube.locale = savedLocale
        YouTube.metadataObserver = savedObserver
    }

    @Test
    fun `active consumers receive a changed locale after new requests can use it`() = runBlocking {
        YouTube.locale = requestLocale
        val capturedRequest = YouTube.locale
        val observed = mutableListOf<YouTubeLocale>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            YouTube.localeUpdates.take(2).collect { locale ->
                assertEquals(locale, YouTube.locale)
                observed += locale
            }
        }

        val changedLocale = YouTubeLocale("JP", "ja")
        YouTube.locale = changedLocale
        collector.join()

        assertEquals(listOf(requestLocale, changedLocale), observed)
        assertEquals("en", capturedRequest.hl)
    }

    @Test
    fun `queue and album rows preserve request language when app language changes before parsing`() {
        YouTube.locale = requestLocale
        val captured = YouTube.locale
        YouTube.locale = YouTubeLocale("JP", "ja")
        val queued = NextPage.fromPlaylistPanelVideoRenderer(
            json.decodeFromString<PlaylistPanelVideoRenderer>(fixture("duet-queue.json")), captured.hl,
        )!!
        val album = AlbumItem("album", "playlist", title = "Album", artists = emptyList(), thumbnail = "cover")
        val track = AlbumPage.getSong(
            json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("duet-album-row.json")),
            album,
            captured.hl,
        )!!

        assertEquals("en", queued.artistCredit!!.language)
        assertEquals(queued.id, queued.endpoint?.videoId)
        assertEquals("MUSIC_VIDEO_TYPE_ATV", queued.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType)
        assertEquals("en", track.artistCredit!!.language)
        // Requested English is provenance only; Japanese names in an actual response remain unchanged.
        assertEquals(listOf("レディー・ガガ", "ブルーノ・マーズ"), queued.artists.map { it.name })
        assertEquals(listOf("UCGKXb1syicud01CJOOFRykg", "UCZn4r7heNOPY-C43YIywnVA"), queued.artists.map { it.id })
        assertEquals("ja", YouTube.locale.hl)
    }

    @Test
    fun `search card row and nested artist section inherit their supplied request language`() {
        YouTube.locale = YouTubeLocale("JP", "ja")
        val cardRenderer = json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))
        val card = SearchSummaryPage.fromMusicCardShelfRenderer(cardRenderer, requestLocale.hl) as SongItem
        val rowRenderer = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("target-search-row.json"))
        val row = SearchPage.toYTItem(rowRenderer, requestLocale.hl) as SongItem
        val section = json.decodeFromString<SectionListRenderer.Content>(
            """{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[{"musicResponsiveListItemRenderer":${fixture("target-search-row.json")}}]}}"""
        )
        val artistSong = ArtistPage.fromSectionListRendererContent(section, requestLocale.hl)!!.items.single() as SongItem
        val summary = parseSearchSummary(listOf(section), requestLocale.hl)
        val summarySong = summary.summaries.single().items.single() as SongItem

        listOf(card, row, artistSong, summarySong).forEach { song ->
            assertEquals("en", song.artistCredit!!.language)
            assertEquals("TSZhKssbW2g", song.id)
            assertEquals("翟锦彦、8082Audio", song.artistCredit!!.rawText)
        }
    }

    @Test
    fun `resolver fallback keeps explicit request language across suspended fetches`() = runBlocking {
        val song = NextPage.fromPlaylistPanelVideoRenderer(
            json.decodeFromString<PlaylistPanelVideoRenderer>(fixture("target-queue.json")), requestLocale.hl,
        )!!.copy(artistCredit = null, artists = emptyList(), artistBrowseIds = emptyList())
        val result = YouTube.resolveTrackArtistCredit(
            song,
            getQueue = {
                YouTube.locale = YouTubeLocale("JP", "ja")
                emptyList()
            },
            browse = { error("Unavailable metadata") },
            requestLocale = requestLocale,
        ).getOrThrow()
        assertEquals("en", result.credit.language)
    }

    @Test
    fun `observer receives the same raw item and fixed locale without changing display names`() {
        val song = NextPage.fromPlaylistPanelVideoRenderer(
            json.decodeFromString<PlaylistPanelVideoRenderer>(fixture("duet-queue.json")), requestLocale.hl,
        )!!
        YouTube.locale = YouTubeLocale("JP", "ja")
        var calls = 0
        YouTube.metadataObserver = { items, locale, source ->
            calls++
            assertSame(song, items.single())
            assertEquals(requestLocale, locale)
            assertEquals("queue", source)
        }
        YouTube.notifyMetadata(listOf(song), requestLocale, "queue")
        assertEquals(1, calls)
        assertEquals(listOf("レディー・ガガ", "ブルーノ・マーズ"), song.artists.map { it.name })

        YouTube.metadataObserver = { _, _, _ -> error("Local metadata collector failed") }
        YouTube.notifyMetadata(listOf(song), requestLocale, "queue")
        YouTube.metadataObserver = { _, _, _ -> throw CancellationException("Cancelled") }
        assertTrue(runCatching { YouTube.notifyMetadata(listOf(song), requestLocale, "queue") }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `completed page retains its request locale after settings change`() = runBlocking {
        val page = LibraryPage(emptyList(), continuation = null, requestLocale = requestLocale)
        YouTube.locale = YouTubeLocale("JP", "ja")
        val completed = Result.success(page).completed().getOrThrow()
        assertEquals(requestLocale, completed.requestLocale)
    }

    @Test
    fun `Main metadata permits unavailable playback but never treats channel names as artist identities`() {
        val response = json.parseToJsonElement(
            """{"playabilityStatus":{"status":"UNPLAYABLE"},"videoDetails":{"videoId":"track","title":"Lemon","author":"Kenshi Yonezu - Topic","channelId":"channel","shortDescription":"Lemon · Kenshi Yonezu"}}"""
        )
        val metadata = parseArtTrackOriginalMetadata(response, "track")
        assertEquals("Lemon", metadata.title)
        assertEquals("Kenshi Yonezu - Topic", metadata.author)
        assertEquals("Lemon · Kenshi Yonezu", metadata.shortDescription)
        assertTrue(runCatching { parseArtTrackOriginalMetadata(response, "other-track") }.isFailure)
        assertTrue(runCatching { parseArtTrackOriginalMetadata(json.parseToJsonElement("{}"), "track") }.isFailure)
    }
}
