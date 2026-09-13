package com.zionhuang.innertube

import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.NavigationEndpoint
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.response.ResolveUrlResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class YouTubeArtistUrlTest {
    private val id = "UCabcdefghijklmnopqrstuv"

    @Test fun `public channel and browse URLs resolve without a request`() = runBlocking {
        listOf(id, "https://music.youtube.com/channel/$id", "https://youtube.com/channel/$id/",
            "http://www.youtube.com/channel/$id?si=share", "music.youtube.com/browse/$id").forEach { input ->
            assertEquals(id, resolveYouTubeArtistUrl(input) { error("ID URLs need no request") })
        }
    }

    @Test fun `handles use only the canonical YouTube API input and remain available while sync is disabled`() = runBlocking {
        assertFalse(YouTubeSyncPolicy.ENABLED)
        val calls = mutableListOf<String>()
        for (input in listOf("@example.artist", "https://www.youtube.com/@example.artist?si=share")) {
            assertEquals(id, resolveYouTubeArtistUrl(input) {
                calls += it
                Json { ignoreUnknownKeys = true }.decodeFromString<ResolveUrlResponse>(
                    """{"endpoint":{"browseEndpoint":{"browseId":"$id"}},"responseContext":{}}""",
                )
            })
        }
        assertEquals(List(2) { "https://music.youtube.com/@example.artist" }, calls)
        assertEquals("https://music.youtube.com/@%E6%97%A5%E6%9C%AC%E8%AA%9E",
            parseYouTubeArtistUrl("https://music.youtube.com/@日本語").canonicalUrl)
    }

    @Test fun `videos playlists external URLs and malformed identities never reach the resolver`() = runBlocking {
        var requests = 0
        listOf(
            "https://music.youtube.com/watch?v=video", "https://youtube.com/playlist?list=playlist",
            "https://youtu.be/video", "https://example.com/@artist", "https://youtube.com.evil.test/@artist",
            "https://youtube.com@evil.test/@artist", "https://evil.test@youtube.com/@artist",
            "file:///channel/$id", "javascript:alert(1)", "https://youtube.com:443/@artist",
            "https://youtube.com/@artist/videos", "https://youtube.com/channel/UCshort",
            "https://music.youtube.com/browse/FEmusic_library_privately_owned_artist123",
            "https://youtube.com/channel/$id/../watch", "https://youtube.com/@artist%2Fwatch",
        ).forEach { input ->
            val failure = runCatching { resolveYouTubeArtistUrl(input) { requests++; ResolveUrlResponse() } }.exceptionOrNull()
            assertTrue(input, failure is IllegalArgumentException)
            assertFalse(failure!!.message.orEmpty().contains(input))
        }
        assertEquals(0, requests)
    }

    @Test fun `resolved video playlist private artist and empty endpoints are rejected`() = runBlocking {
        listOf(
            ResolveUrlResponse(),
            ResolveUrlResponse(NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "video"))),
            ResolveUrlResponse(NavigationEndpoint(browseEndpoint = BrowseEndpoint("VLplaylist"))),
            ResolveUrlResponse(NavigationEndpoint(browseEndpoint = BrowseEndpoint("FEmusic_library_privately_owned_artist123"))),
            ResolveUrlResponse(NavigationEndpoint(browseEndpoint = BrowseEndpoint(id), watchEndpoint = WatchEndpoint(videoId = "video"))),
        ).forEach { response ->
            assertTrue(runCatching { resolveYouTubeArtistUrl("@example") { response } }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
