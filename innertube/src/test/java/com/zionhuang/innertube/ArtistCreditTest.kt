@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.zionhuang.innertube

import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.NextPage
import com.zionhuang.innertube.pages.SearchSummaryPage
import com.zionhuang.innertube.pages.SearchPage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class ArtistCreditTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun fixture(name: String) = javaClass.getResource("/artist-credit/$name")!!.readText()
    private fun queue(name: String) = NextPage.fromPlaylistPanelVideoRenderer(
        json.decodeFromString<PlaylistPanelVideoRenderer>(fixture(name)))!!
    private fun raw(text: String) = ArtistCredit(text, emptyList(), ArtistCreditStatus.RAW, "test", "ja")
    private fun candidates(vararg names: String) = names.map { Artist(it, null) }
    private val audioId = "UChWKQRswWTLRXp98zmgHtdQ"

    @Test fun `captured target starts as literal and credits establish two people with one page`() {
        val target = queue("target-queue.json")
        assertEquals("TSZhKssbW2g", target.id)
        val initial = target.artistCredit!!
        assertEquals("翟锦彦、8082Audio", initial.rawText)
        assertEquals(ArtistCreditStatus.RAW, initial.status)
        assertTrue(initial.artists.isEmpty())
        assertTrue(audioId in target.artistBrowseIds)
        val names = ArtistCreditResolver.performers(json.parseToJsonElement(fixture("target-credits.json")), target.id)
        assertEquals(listOf("翟锦彦", "8082Audio"), names.map { it.name })
        val split = ArtistCreditResolver.fromPerformers(initial, names)
        assertEquals(ArtistCreditStatus.COMPLETE, split.status)
        assertEquals(listOf(null, null), split.artists.map { it.id })
        val page = ArtistCreditResolver.pageArtist(json.parseToJsonElement(fixture("target-artist.json")), audioId)!!
        val resolved = ArtistCreditResolver.withPageNames(split, listOf(page))
        assertEquals(listOf("翟锦彦", "8082Audio"), resolved.artists.map { it.name })
        assertEquals(listOf(null, audioId), resolved.artists.map { it.id })
        assertEquals(initial.rawText, resolved.rawText)
    }

    @Test fun `captured search card preserves unlinked byline and independent menu candidate`() {
        val song = SearchSummaryPage.fromMusicCardShelfRenderer(
            json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))) as SongItem
        assertEquals("翟锦彦、8082Audio", song.artistCredit!!.rawText)
        assertEquals(ArtistCreditStatus.RAW, song.artistCredit!!.status)
        assertTrue(audioId in song.artistBrowseIds)
        assertTrue(song.artistCredit!!.artists.isEmpty())
    }

    @Test fun `captured normal duet preserves two independent ids`() {
        val credit = queue("duet-queue.json").artistCredit!!
        assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
        assertEquals(listOf("レディー・ガガ", "ブルーノ・マーズ"), credit.artists.map { it.name })
        assertEquals(listOf("UCGKXb1syicud01CJOOFRykg", "UCZn4r7heNOPY-C43YIywnVA"), credit.artists.map { it.id })
    }

    @Test fun `captured group name with punctuation is one linked artist and never split`() {
        val credit = queue("band-queue.json").artistCredit!!
        assertEquals("Earth, Wind & Fire", credit.artists.single().name)
        assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
        assertEquals(credit, ArtistCreditResolver.fromPerformers(credit, candidates("Earth", "Wind", "Fire")))
    }

    @Test fun `album parser retains combined unlinked byline and mixed linked names`() {
        val album = AlbumItem("album", "playlist", title = "Album", artists = emptyList(), thumbnail = "cover")
        val rawRow = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("target-album-row.json"))
        val target = AlbumPage.getSong(rawRow, album)!!
        assertEquals("翟锦彦、8082Audio", target.artistCredit!!.rawText)
        assertEquals(ArtistCreditStatus.RAW, target.artistCredit!!.status)
        // Synthetic mutation of captured normal row: the second name loses only its endpoint.
        val normal = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("duet-album-row.json"))
        val columns = normal.flexColumns.toMutableList()
        val column = columns[1].musicResponsiveListItemFlexColumnRenderer
        columns[1] = columns[1].copy(musicResponsiveListItemFlexColumnRenderer = column.copy(
            text = Runs(column.text!!.runs!!.mapIndexed { i, run -> if (i == 2) run.copy(navigationEndpoint = null) else run })))
        val mixed = AlbumPage.getSong(normal.copy(flexColumns = columns), album)!!
        assertEquals(listOf("陈鸿宇", "8082Audio"), mixed.artistCredit!!.artists.map { it.name })
        assertNull(mixed.artistCredit!!.artists[1].id)
    }

    @Test fun `two separate unlinked names are supported as a synthetic boundary case`() {
        val credit = listOf(Run("A", null), Run("、", null), Run("B", null)).toArtistCredit("synthetic")
        assertEquals(ArtistCreditStatus.COMPLETE, credit.status)
        assertEquals(candidates("A", "B"), credit.artists)
        assertEquals(ArtistCreditStatus.RAW, listOf(Run("A、B", null)).toArtistCredit("synthetic").status)
    }

    @Test fun `search category handling does not discard an unlinked artist section`() {
        val names = listOf(Run("A", null), Run("、", null), Run("B", null))
        assertEquals(listOf(names), listOf(names).clean())
        assertEquals(listOf(names), listOf(listOf(Run("曲", null)), names).clean())
    }

    @Test fun `a single name can be confirmed without acquiring an online id`() {
        val confirmed = ArtistCreditResolver.fromPerformers(raw("A & B"), candidates("A & B"))
        assertEquals(ArtistCreditStatus.COMPLETE, confirmed.status)
        assertEquals(candidates("A & B"), confirmed.artists)
    }

    @Test fun `partial evidence does not invent a name or discard unresolved text`() {
        val partial = ArtistCreditResolver.fromPerformers(raw("A、unknown、other"), candidates("A", "Producer"))
        assertEquals(ArtistCreditStatus.PARTIAL, partial.status)
        assertEquals(candidates("A"), partial.artists)
        assertEquals("A、unknown、other", partial.rawText)
        val updated = ArtistCreditResolver.withPageNames(partial, listOf(Artist("A", "id-a")))
        assertEquals(ArtistCreditStatus.PARTIAL, updated.status)
        assertEquals("id-a", updated.artists.single().id)
        assertEquals(partial.rawText, updated.rawText)
    }

    @Test fun `ambiguous decompositions and repeated same names remain raw`() {
        assertEquals(ArtistCreditStatus.RAW,
            ArtistCreditResolver.fromPerformers(raw("A & B"), candidates("A", "B", "A & B")).status)
        assertEquals(ArtistCreditStatus.RAW,
            ArtistCreditResolver.fromPerformers(raw("A、A"), candidates("A", "A")).status)
        assertEquals(ArtistCreditStatus.RAW,
            ArtistCreditResolver.fromPerformers(raw("AB"), candidates("A")).status)
    }

    @Test fun `credits for another song and non performer roles are rejected`() {
        val credits = json.parseToJsonElement(fixture("target-credits.json"))
        assertTrue(ArtistCreditResolver.performers(credits, "other-video").isEmpty())
        val names = ArtistCreditResolver.performers(credits, "TSZhKssbW2g").map { it.name }
        assertFalse("往生咒" in names)
        assertFalse("李佳骐" in names)
        assertEquals(2, names.size)
        val producerOnly = json.parseToJsonElement(fixture("target-credits.json").replace("演奏", "プロデューサー"))
        assertTrue(ArtistCreditResolver.performers(producerOnly, "TSZhKssbW2g").isEmpty())
    }

    @Test fun `a menu page is not assigned by substring or to an unverified resource`() {
        val before = raw("A、B")
        assertEquals(before, ArtistCreditResolver.withPageNames(before, listOf(Artist("B", "id-b"))))
        assertNull(ArtistCreditResolver.pageArtist(json.parseToJsonElement(fixture("target-artist.json")), "wrong-id"))
        val names = before.copy(status = ArtistCreditStatus.COMPLETE, artists = candidates("A", "B"))
        assertEquals(listOf(null, null), ArtistCreditResolver.withPageNames(names,
            listOf(Artist("B", "id-b1"), Artist("B", "id-b2"))).artists.map { it.id })
    }

    @Test fun `late ids keep internal refs names order and literal provenance`() {
        val before = raw("A & B").copy(status = ArtistCreditStatus.COMPLETE,
            artists = listOf(Artist("A", null, "ref-a"), Artist("B", null, "ref-b")))
        val after = before.merge(before.copy(rawText = "A、B",
            artists = listOf(Artist("A", "id-a", "new-a"), Artist("B", null))))
        assertEquals(listOf("ref-a", "ref-b"), after.artists.map { it.ref })
        assertEquals(listOf("id-a", null), after.artists.map { it.id })
        assertEquals("A & B", after.rawText)
        assertEquals(after, after.merge(raw("A")))
    }

    @Test fun `conflicting complete evidence preserves adopted display and records conflict`() {
        val before = raw("A & B").copy(status = ArtistCreditStatus.COMPLETE, artists = candidates("A", "B"))
        val after = before.merge(before.copy(artists = candidates("A")))
        assertEquals(before.artists, after.artists)
        assertEquals(before.status, after.status)
        assertTrue(after.evidence.any { it.startsWith("conflict:") })
        val firstId = before.copy(artists = listOf(Artist("A", "id-a"), Artist("B", null)))
        assertEquals("id-a", firstId.merge(firstId.copy(artists = listOf(Artist("A", "wrong"), Artist("B", null)))).artists[0].id)
    }

    @Test fun `partial superset grows adopted names without claiming complete and language is guarded`() {
        val before = raw("A、B、C").copy(status = ArtistCreditStatus.PARTIAL, artists = listOf(Artist("A", null, "ref-a")))
        val after = before.merge(before.copy(artists = candidates("A", "B")))
        assertEquals(listOf("A", "B"), after.artists.map { it.name })
        assertEquals("ref-a", after.artists[0].ref)
        assertEquals(ArtistCreditStatus.PARTIAL, after.status)
        assertEquals(before, before.merge(before.copy(language = "en", artists = candidates("Wrong"))))
    }

    @Test fun `empty old language placeholder cannot reject a newly identified artist`() {
        val recovered = ArtistCredit("椎名林檎", listOf(Artist("椎名林檎", "UCbrWU0y_rLsEOYgaTX5Y74A", "LA-kept")),
            ArtistCreditStatus.COMPLETE, "structured-byline", "ja")
        for (emptyText in listOf("", " \t\n")) {
            val placeholder = ArtistCredit(emptyText, emptyList(), ArtistCreditStatus.RAW, "context-refresh", "en")
            assertEquals(recovered, placeholder.merge(recovered))
            assertEquals(recovered, recovered.merge(placeholder))
        }
    }

    @Test fun `repairing an empty placeholder does not permit merging real credits across languages`() {
        val english = ArtistCredit("Sheena Ringo", listOf(Artist("Sheena Ringo", "UCbrWU0y_rLsEOYgaTX5Y74A", "LA-kept")),
            ArtistCreditStatus.COMPLETE, "structured-byline", "en")
        val japanese = english.copy(rawText = "椎名林檎", language = "ja",
            artists = listOf(english.artists.single().copy(name = "椎名林檎")))
        assertEquals(english, english.merge(japanese))
        assertEquals(japanese, japanese.merge(english))
        val literal = english.copy(artists = emptyList(), status = ArtistCreditStatus.RAW)
        assertEquals(literal, literal.merge(japanese))
        // Blank literal text does not make an already adopted artist list a placeholder.
        val adoptedWithoutLiteral = english.copy(rawText = "")
        assertEquals(adoptedWithoutLiteral, adoptedWithoutLiteral.merge(japanese))
    }

    @Test fun `credit and references survive json and java queue serialization`() {
        val original = raw("A").copy(status = ArtistCreditStatus.COMPLETE, artists = listOf(Artist("A", "id-a", "ref-a")))
        assertEquals(original, json.decodeFromString<ArtistCredit>(json.encodeToString(original)))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { stream -> stream.writeObject(original) } }.toByteArray()
        assertEquals(original, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    }

    @Test fun `album header literal is not turned into a registered combined artist`() {
        assertEquals(ArtistCreditStatus.RAW,
            listOf(Run("A、B", null)).toAlbumArtistCredit("album", "ja").status)
        val metadata = listOf(Run("シングル", null), Run(" • ", null), Run("2024年", null))
        assertTrue(metadata.toAlbumArtistCredit("album", "ja").rawText.isEmpty())
        val names = listOf(Run("A", null), Run("、", null), Run("B", null))
        assertEquals(candidates("A", "B"), names.toAlbumArtistCredit("album", "ja").artists)
    }

    @Test fun `explicit video source is recorded independently of missing album information`() {
        val renderer = json.decodeFromString<PlaylistPanelVideoRenderer>(fixture("duet-queue.json"))
        val watch = renderer.navigationEndpoint.watchEndpoint!!
        val video = renderer.copy(navigationEndpoint = renderer.navigationEndpoint.copy(watchEndpoint = watch.copy(
            watchEndpointMusicSupportedConfigs = WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_OMV")))))
        assertTrue(NextPage.fromPlaylistPanelVideoRenderer(video)!!.artistCredit!!.evidence.contains("video-source:MUSIC_VIDEO_TYPE_OMV"))
        assertFalse(queue("target-queue.json").artistCredit!!.evidence.any { it.startsWith("video-source:") })
    }

    @Test fun `overlapping credit candidates have a bounded search and preserve partial identities`() {
        val names = (0 until 20).map { "Artist$it" }
        val text = names.joinToString("、") + "、unknown"
        val overlapping = (names + names.zipWithNext { a, b -> "$a、$b" }).map { Artist(it, null) }
        val before = raw(text).copy(status = ArtistCreditStatus.PARTIAL,
            artists = listOf(Artist(names.first(), null, "stable-ref")))
        val after = ArtistCreditResolver.fromPerformers(before, overlapping)
        assertEquals(before.rawText, after.rawText)
        assertEquals(before.artists, after.artists)
        assertEquals(before.status, after.status)
        assertTrue("credits:search-limit" in after.evidence)
    }

    @Test fun `one full match cannot be adopted when uniqueness search reaches its limit`() {
        val names = (0 until 20).map { "Artist$it" }
        val text = names.joinToString("、") + "、unknown"
        val overlapping = (listOf(text) + names + names.zipWithNext { a, b -> "$a、$b" })
            .map { Artist(it, null) }
        val after = ArtistCreditResolver.fromPerformers(raw(text), overlapping)
        assertEquals(ArtistCreditStatus.RAW, after.status)
        assertTrue(after.artists.isEmpty())
        assertTrue("credits:search-limit" in after.evidence)
    }

    @Test fun `a large unambiguous independently named credit list still resolves`() {
        val names = (0 until 100).map { "Artist$it" }
        val after = ArtistCreditResolver.fromPerformers(raw(names.joinToString("、")),
            names.map { Artist(it, null) })
        assertEquals(ArtistCreditStatus.COMPLETE, after.status)
        assertEquals(names, after.artists.map { it.name })
        assertFalse("credits:search-limit" in after.evidence)
    }

    @Test fun `a verified whole group page protects an initially unlinked name before credits`() {
        val verified = ArtistCreditResolver.withPageNames(raw("Earth, Wind & Fire"),
            listOf(Artist("Earth, Wind & Fire", "band-id")))
        val after = ArtistCreditResolver.fromPerformers(verified, candidates("Earth", "Wind", "Fire"))
        assertEquals(verified, after)
        assertEquals("band-id", after.artists.single().id)
    }

    @Test fun `a new resolution attempt clears only previous transport retry markers`() {
        val before = raw("A").copy(status = ArtistCreditStatus.PARTIAL,
            artists = listOf(Artist("A", null, "stable-ref")),
            evidence = listOf("retry:credits:video", "retry:artist-page:id", "credits:performers:unique-partial-coverage",
                "conflict:old", "video-source:MUSIC_VIDEO_TYPE_OMV"))
        val after = ArtistCreditResolver.beginAttempt(before)
        assertEquals(before.copy(evidence = before.evidence.drop(2)), after)
        assertEquals(after, ArtistCreditResolver.beginAttempt(after))
    }

    @Test fun `captured search album is filled by the same queue request that resolves its artists`() = runBlocking {
        val song = SearchSummaryPage.fromMusicCardShelfRenderer(
            json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))) as SongItem
        assertNull(song.album)
        var queueCalls = 0
        val response = YouTube.resolveTrackArtistCredit(song,
            getQueue = { queueCalls++; listOf(queue("target-queue.json")) },
            browse = { id -> json.parseToJsonElement(fixture(when (id) {
                audioId -> "target-artist.json"
                "MPTC${song.id}" -> "target-credits.json"
                else -> error("Unexpected browse: $id")
            })) }).getOrThrow()
        assertEquals(1, queueCalls)
        assertEquals("MPREb_NUdafp1DlA5", response.album!!.id)
        assertEquals(queue("target-queue.json").album!!.name, response.album!!.name)
        assertEquals(listOf("翟锦彦", "8082Audio"), response.credit.artists.map { it.name })
        assertEquals(listOf(null, audioId), response.credit.artists.map { it.id })
        assertEquals(response, json.decodeFromString<ArtistCreditResolution>(json.encodeToString(response)))
    }

    @Test fun `complete artists still allow one queue lookup for a missing album`() = runBlocking {
        val queued = queue("duet-queue.json")
        val source = queued.copy(album = null)
        var queueCalls = 0
        var browseCalls = 0
        val response = YouTube.resolveTrackArtistCredit(source,
            getQueue = { queueCalls++; listOf(queued) },
            browse = { browseCalls++; error("Complete artists must not need browse") }).getOrThrow()
        assertEquals(1, queueCalls)
        assertEquals(0, browseCalls)
        assertEquals(queued.album, response.album)
        assertEquals(source.artistCredit!!.artists, response.credit.artists)

        val complete = YouTube.resolveTrackArtistCredit(queued,
            getQueue = { error("Known album and complete artists must not need queue") },
            browse = { error("Unexpected browse") }).getOrThrow()
        assertEquals(queued.album, complete.album)
    }

    @Test fun `queue never overwrites an existing album even when artist evidence needs refreshing`() = runBlocking {
        val queued = queue("target-queue.json")
        val knownAlbum = Album("Already selected release", "known-album")
        val source = queued.copy(album = knownAlbum, artistBrowseIds = emptyList())
        val response = YouTube.resolveTrackArtistCredit(source,
            getQueue = { listOf(queued.copy(artistBrowseIds = emptyList())) },
            browse = { json.parseToJsonElement(fixture("target-credits.json")) }).getOrThrow()
        assertEquals(knownAlbum, response.album)
        assertEquals(ArtistCreditStatus.COMPLETE, response.credit.status)
    }

    @Test fun `an unrelated queue item cannot fill the requested tracks album or artists`() = runBlocking {
        val source = queue("duet-queue.json").copy(album = null, artistBrowseIds = emptyList())
        var browseCalls = 0
        val response = YouTube.resolveTrackArtistCredit(source,
            getQueue = { listOf(queue("target-queue.json")) },
            browse = { browseCalls++; error("An unrelated queue item must not introduce browse candidates") }).getOrThrow()
        assertEquals(0, browseCalls)
        assertNull(response.album)
        assertEquals(source.artistCredit!!.artists, response.credit.artists)
    }

    @Test fun `an album obtained before a credits failure remains available`() = runBlocking {
        val queued = queue("target-queue.json").copy(artistBrowseIds = emptyList())
        val response = YouTube.resolveTrackArtistCredit(queued.copy(album = null),
            getQueue = { listOf(queued) },
            browse = { throw java.io.IOException("credits unavailable") }).getOrThrow()
        assertEquals(queued.album, response.album)
        assertEquals(ArtistCreditStatus.RAW, response.credit.status)
        assertTrue(response.credit.evidence.any { it.startsWith("retry:credits:") })
    }

    @Test fun `captured search card retains its supplied audio playback endpoint`() {
        val renderer = json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))
        val song = SearchSummaryPage.fromMusicCardShelfRenderer(renderer) as SongItem
        assertEquals(renderer.onTap.watchEndpoint, song.endpoint)
        assertEquals("MUSIC_VIDEO_TYPE_ATV",
            song.endpoint!!.watchEndpointMusicSupportedConfigs!!.watchEndpointMusicConfig.musicVideoType)
        assertEquals("TSZhKssbW2g", song.endpoint!!.videoId)
    }

    @Test fun `responsive search parsers retain supplied audio and video types`() {
        // Actual search rows include their own thumbnail. Album rows inherit the parent
        // cover and cannot be passed unchanged to a search parser that requires an image.
        val audio = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("target-search-row.json"))
        val video = json.decodeFromString<MusicResponsiveListItemRenderer>(fixture("video-search-row.json"))
        // Synthetic type-only change supplies OMV coverage without inventing an observed capture.
        val musicVideo = json.decodeFromString<MusicResponsiveListItemRenderer>(
            fixture("video-search-row.json").replace("MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_OMV"))
        listOf(audio to "MUSIC_VIDEO_TYPE_ATV", video to "MUSIC_VIDEO_TYPE_UGC", musicVideo to "MUSIC_VIDEO_TYPE_OMV")
            .forEach { (renderer, type) ->
                val songs = listOf(
                    SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem,
                    SearchPage.toYTItem(renderer) as SongItem,
                )
                songs.forEach { song ->
                    assertEquals(renderer.playlistItemData!!.videoId, song.endpoint!!.videoId)
                    assertEquals(type,
                        song.endpoint!!.watchEndpointMusicSupportedConfigs!!.watchEndpointMusicConfig.musicVideoType)
                }
            }
    }

    @Test fun `unknown or missing search playback types are preserved without audio inference`() {
        val renderer = json.decodeFromString<MusicCardShelfRenderer>(fixture("target-search-card.json"))
        val watch = renderer.onTap.watchEndpoint!!
        // Synthetic type-only mutations: names, duration and album absence are unchanged.
        listOf(null, "MUSIC_VIDEO_TYPE_UGC", "FUTURE_TYPE").forEach { type ->
            val endpoint = watch.copy(watchEndpointMusicSupportedConfigs = type?.let {
                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(it))
            })
            val song = SearchSummaryPage.fromMusicCardShelfRenderer(renderer.copy(
                onTap = renderer.onTap.copy(watchEndpoint = endpoint))) as SongItem
            assertEquals(endpoint, song.endpoint)
            assertEquals(type, song.endpoint!!.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType)
        }
    }
}
