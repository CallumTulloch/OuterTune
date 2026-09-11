package com.zionhuang.innertube

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MetadataAuthenticationTest {
    private val savedCookie = YouTube.cookie
    private val savedDataSyncId = YouTube.dataSyncId
    private val savedVisitorData = YouTube.visitorData
    private val savedUseLogin = YouTube.useLoginForBrowse
    private val savedLocale = YouTube.locale
    private val savedObserver = YouTube.metadataObserver
    private val requestLocale = YouTubeLocale("JP", "ja")
    private val response = listOf(SongItem("track", "取得した曲名", emptyList(), thumbnail = ""))

    @After fun restoreGlobals() {
        YouTube.cookie = savedCookie
        YouTube.dataSyncId = savedDataSyncId
        YouTube.visitorData = savedVisitorData
        YouTube.useLoginForBrowse = savedUseLogin
        YouTube.locale = savedLocale
        YouTube.metadataObserver = savedObserver
    }

    @Test fun `responses suspended across each auth input change never reach the new observer context`() = runBlocking {
        val changes = listOf<Pair<String, () -> Unit>>(
            "cookie" to { YouTube.cookie = "SAPISID=second-account" },
            "dataSyncId" to { YouTube.dataSyncId = "second-sync" },
            "visitorData" to { YouTube.visitorData = "second-visitor" },
            "useLoginForBrowse" to { YouTube.useLoginForBrowse = false },
        )
        for ((name, change) in changes) {
            establishAccount()
            var deliveries = 0
            YouTube.metadataObserver = { _, _, _ -> deliveries++ }
            val release = CompletableDeferred<List<SongItem>>()
            val oldRequest = async(start = CoroutineStart.UNDISPATCHED) {
                YouTube.metadataRequest(requestLocale, "queue", items = { songs: List<SongItem> -> songs }) {
                    release.await()
                }
            }
            assertFalse(oldRequest.isCompleted)
            change()
            release.complete(response)
            assertSame(response, oldRequest.await().getOrThrow())
            assertEquals("Old request crossed $name change", 0, deliveries)

            YouTube.metadataRequest(requestLocale, "queue", items = { songs: List<SongItem> -> songs }) { response }.getOrThrow()
            assertEquals("New request should still collect metadata after $name change", 1, deliveries)
        }
    }

    @Test fun `account round trip also rejects the original in flight response`() = runBlocking {
        establishAccount()
        var deliveries = 0
        YouTube.metadataObserver = { _, _, _ -> deliveries++ }
        val release = CompletableDeferred<List<SongItem>>()
        val oldRequest = async(start = CoroutineStart.UNDISPATCHED) {
            YouTube.metadataRequest(requestLocale, "albumSongs", items = { songs: List<SongItem> -> songs }) { release.await() }
        }
        YouTube.cookie = "SAPISID=second-account"
        YouTube.cookie = "SAPISID=first-account"
        release.complete(response)
        assertTrue(oldRequest.await().isSuccess)
        assertEquals(0, deliveries)
    }

    @Test fun `locale changes and unchanged auth values retain raw response and original request language`() = runBlocking {
        establishAccount()
        YouTube.locale = requestLocale
        var deliveries = 0
        YouTube.metadataObserver = { items, locale, source ->
            deliveries++
            assertSame(response, items)
            assertSame(response.single(), items.single())
            assertEquals(requestLocale, locale)
            assertEquals("search", source)
        }
        val release = CompletableDeferred<List<SongItem>>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            YouTube.metadataRequest(requestLocale, "search", items = { songs: List<SongItem> -> songs }) { release.await() }
        }
        YouTube.locale = YouTubeLocale("US", "en")
        establishAccount() // Reassigning the same values is not an authentication change.
        release.complete(response)
        assertSame(response, request.await().getOrThrow())
        assertEquals(1, deliveries)
        assertEquals("取得した曲名", response.single().title)
    }

    @Test fun `disabled collection and failed requests do not notify while successful browse remains usable`() = runBlocking {
        establishAccount()
        var deliveries = 0
        YouTube.metadataObserver = { _, _, _ -> deliveries++ }
        val disabled = YouTube.metadataRequest(requestLocale, "queue", enabled = false,
            items = { songs: List<SongItem> -> songs }) { response }
        assertSame(response, disabled.getOrThrow())
        val failure = IllegalStateException("fixture offline")
        val failed = YouTube.metadataRequest<List<SongItem>>(requestLocale, "queue", items = { it }) { throw failure }
        assertSame(failure, failed.exceptionOrNull())
        assertEquals(0, deliveries)
    }

    private fun establishAccount() {
        YouTube.cookie = "SAPISID=first-account"
        YouTube.dataSyncId = "first-sync"
        YouTube.visitorData = "first-visitor"
        YouTube.useLoginForBrowse = true
    }
}
