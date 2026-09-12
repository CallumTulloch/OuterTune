package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.*
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MetadataFetchSchedulingTest {
    private val ja = YouTubeLocale(gl = "JP", hl = "ja")
    private val en = ja.copy(hl = "en")
    private val target = OriginalNameTarget(OriginalNameKind.SONG, "track")

    @Test fun `alternating bilingual song requests form separate bounded batches`() {
        val requests = (1..52).flatMap { number ->
            listOf(request("track$number", en), request("track$number", ja))
        }
        val batches = groupMetadataFetchRequests(requests + requests.first())
        assertEquals(listOf(50, 2, 50, 2), batches.map { it.size })
        assertEquals(requests.toSet(), batches.flatten().toSet())
        assertEquals(requests.size, batches.sumOf { it.size })
        assertTrue(batches.all { batch -> batch.map { it.locale }.distinct().size == 1 })
    }

    @Test fun `accounts kinds and original requests never share a network batch`() {
        val first = request("first", en)
        val otherAccount = request("second", en, "JP:account2")
        val artist = first.copy(target = OriginalNameTarget(OriginalNameKind.ARTIST, "artist"))
        val originals = listOf(first, otherAccount).map { it.copy(contextKey = originalMetadataContextKey(ja), original = true) }
        val batches = groupMetadataFetchRequests(listOf(first, otherAccount, artist) + originals)
        assertEquals(5, batches.size)
        assertTrue(batches.all { it.size == 1 })
    }

    @Test fun `setting changes retain English but reject obsolete language country and account`() {
        val english = request("track", en)
        val japanese = request("track", ja)
        assertTrue(isMetadataFetchCurrent(english, ja.copy(hl = "fr"), "JP:account1"))
        assertFalse(isMetadataFetchCurrent(japanese, ja.copy(hl = "fr"), "JP:account1"))
        assertFalse(isMetadataFetchCurrent(english, ja.copy(gl = "US"), "US:account1"))
        assertFalse(isMetadataFetchCurrent(english, ja, "JP:account2"))
    }

    @Test fun `Main retries have a separate language context and failure deadline`() {
        val music = request("track", en)
        val original = music.copy(contextKey = originalMetadataContextKey(ja), original = true)
        val musicState = music.state(MetadataFetchEntity.SUCCESS, 1000)
        val mainState = original.state(MetadataFetchEntity.FAILED, 1000)
        assertEquals("en", musicState.language)
        assertEquals("und", mainState.language)
        assertNotEquals(musicState.contextKey, mainState.contextKey)
        assertEquals(5 * 60_000L, metadataRetryDelay(mainState.status))
        assertTrue(metadataRetryDelay(musicState.status) > metadataRetryDelay(mainState.status))
        assertEquals(0, metadataRetryDelay(MetadataFetchEntity.PENDING))
        // Main uses a public request, so account or content-language changes do not invalidate it.
        assertTrue(isMetadataFetchCurrent(original, ja.copy(hl = "fr"), "JP:anotherAccount"))
        assertFalse(isMetadataFetchCurrent(original, ja.copy(gl = "US"), "US:account1"))
    }

    @Test fun `album original context is invalidated by account changes unlike public Main titles`() {
        val album = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.ALBUM, "album"), en,
            albumOriginalContextKey("JP:account1"), original = true)
        assertTrue(isMetadataFetchCurrent(album, ja.copy(hl = "fr"), "JP:account1"))
        assertFalse(isMetadataFetchCurrent(album, ja, "JP:account2"))
        assertFalse(isMetadataFetchCurrent(album, ja.copy(gl = "US"), "US:account1"))
        assertNotEquals(album.state(MetadataFetchEntity.SUCCESS, 1000).contextKey,
            albumOriginalContextKey("JP:account2"))
        val publicSong = request("track", en).copy(contextKey = originalMetadataContextKey(ja), original = true)
        assertTrue(isMetadataFetchCurrent(publicSong, ja, "JP:account2"))
    }

    @Test fun `context digest includes account sync identity but excludes requested language`() {
        val initial = metadataFetchContextKey(ja, true, "private-cookie", "sync1")
        assertEquals(initial, metadataFetchContextKey(en, true, "private-cookie", "sync1"))
        assertNotEquals(initial, metadataFetchContextKey(ja, true, "private-cookie", "sync2"))
        assertNotEquals(initial, metadataFetchContextKey(ja, true, "other-cookie", "sync1"))
        assertNotEquals(initial, metadataFetchContextKey(ja, false, "private-cookie", "sync1"))
        assertNotEquals(initial, metadataFetchContextKey(ja.copy(gl = "US"), true, "private-cookie", "sync1"))
        assertNotEquals(initial, metadataFetchContextKey(ja, true, "private-cookie", "sync1", "visitor"))
        assertFalse(initial.contains("private-cookie"))
        assertFalse(initial.contains("sync1"))
    }

    @Test fun `same account authentication generations cannot share a batch or accept an old response`() {
        val before = request("before", en).copy(authRevision = 1)
        val after = request("after", en).copy(authRevision = 3)
        assertFalse(isMetadataFetchCurrent(before, ja, before.contextKey, 3))
        assertTrue(isMetadataFetchCurrent(after, ja, after.contextKey, 3))
        assertEquals(listOf(listOf(before), listOf(after)), groupMetadataFetchRequests(listOf(before, after)))
        assertEquals(before.state(MetadataFetchEntity.SUCCESS, 1000).contextKey,
            after.state(MetadataFetchEntity.SUCCESS, 1000).contextKey)
        val albumBefore = before.copy(target = OriginalNameTarget(OriginalNameKind.ALBUM, "album"),
            original = true, contextKey = albumOriginalContextKey(before.contextKey))
        assertFalse(isMetadataFetchCurrent(albumBefore, ja, before.contextKey, 3))
    }

    @Test fun `reordered and missing queue results match exact kind and ID`() {
        val first = SongItem("first", "First", emptyList(), thumbnail = "")
        val second = SongItem("second", "Second", emptyList(), thumbnail = "")
        val sameIdArtist = ArtistItem("first", "Artist", null, shuffleEndpoint = null, radioEndpoint = null)
        val results = metadataItemsByTarget(listOf(second, sameIdArtist, first))
        assertSame(first, results[OriginalNameTarget(OriginalNameKind.SONG, "first")])
        assertSame(second, results[OriginalNameTarget(OriginalNameKind.SONG, "second")])
        assertSame(sameIdArtist, results[OriginalNameTarget(OriginalNameKind.ARTIST, "first")])
        assertNull(results[OriginalNameTarget(OriginalNameKind.SONG, "missing")])
    }

    @Test fun `reviewed evidence requires same entity and exact name and preserves Main unknown status`() {
        val main = MetadataNameEntity("SONG", target.id, "und", "Formal Title", "youtube-main",
            originEvidenceJson = """{"source":"YOUTUBE_MAIN","language":"UNKNOWN","verification":"UNVERIFIED"}""")
        val reviewed = OriginalNameEvidence(target, "Formal Title", OriginalNameSourceKind.RIGHTS_HOLDER,
            "https://example.test/official", OriginalNameLanguage.ENGLISH, OriginalNameVerification.CONFIRMED,
            reviewedOn = "2026-09-07", reviewNote = "Exact target reviewed")
        val rejected = metadataNameWithEvidence(main, listOf(
            reviewed.copy(target = target.copy(id = "another")), reviewed.copy(originalName = "Other Title")))
        assertEquals(main, rejected)
        val stored = metadataNameWithEvidence(main, listOf(reviewed)).originEvidenceJson!!
        val json = Json.parseToJsonElement(stored).jsonObject
        assertEquals("UNKNOWN", json.getValue("language").jsonPrimitive.content)
        assertEquals("UNVERIFIED", json.getValue("verification").jsonPrimitive.content)
        val attached = json.getValue("reviewedEvidence").jsonArray.single().jsonObject
        assertEquals(reviewed.sourceUrl, attached.getValue("sourceUrl").jsonPrimitive.content)
        assertEquals("CONFIRMED", attached.getValue("verification").jsonPrimitive.content)
        assertEquals("2026-09-07", attached.getValue("reviewedOn").jsonPrimitive.content)
    }

    private fun request(id: String, locale: YouTubeLocale, contextKey: String = "JP:account1") =
        MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.SONG, id), locale, contextKey)
}
