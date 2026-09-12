package com.zionhuang.innertube

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AuthenticationSnapshotTest {
    private val saved = YouTube.authentication
    private val savedObserver = YouTube.metadataObserver

    @Test fun `diagnostic authorization availability distinguishes partial cookies and stays with snapshot`() {
        YouTube.setAuthentication("VISITOR_INFO1_LIVE=fixture; SAPISID=", null, null, true)
        val partial = YouTube.authentication
        assertFalse(partial.authorizationAvailable)
        for (key in listOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID")) {
            YouTube.setAuthentication("$key=fixture", null, null, true)
            val signed = YouTube.authentication
            assertTrue(signed.authorizationAvailable)
            YouTube.setAuthentication(null, null, null, true)
            assertFalse(YouTube.authentication.authorizationAvailable)
            assertTrue(signed.authorizationAvailable)
            assertFalse(partial.authorizationAvailable)
        }
    }

    @After fun restore() {
        YouTube.metadataObserver = savedObserver
        YouTube.setAuthentication(saved.cookie, saved.visitorData, saved.dataSyncId, saved.useLoginForBrowse)
    }

    @Test fun `four authentication inputs publish one immutable generation and no-op assignments publish none`() = runBlocking {
        YouTube.setAuthentication(null, null, null, false)
        val before = YouTube.authentication
        val observed = mutableListOf<YouTubeAuthentication>()
        val collector = launch(Dispatchers.Unconfined) {
            YouTube.authUpdates.drop(1).collect { observed += YouTube.authentication }
        }
        try {
            YouTube.setAuthentication("SAPISID=cookie-secret", "visitor-secret", "sync-secret", true)
            val accepted = YouTube.authentication
            assertEquals(before.revision + 1, accepted.revision)
            assertEquals(accepted.revision, YouTube.authRevision)
            assertEquals(accepted.revision, YouTube.authUpdates.value)
            assertEquals(listOf(accepted), observed)
            assertEquals("SAPISID=cookie-secret", accepted.cookie)
            assertEquals("visitor-secret", accepted.visitorData)
            assertEquals("sync-secret", accepted.dataSyncId)
            assertTrue(accepted.useLoginForBrowse)
            assertNull(before.cookie)
            assertNull(before.visitorData)
            assertNull(before.dataSyncId)
            assertFalse(before.useLoginForBrowse)

            YouTube.setAuthentication(accepted.cookie, accepted.visitorData, accepted.dataSyncId, accepted.useLoginForBrowse)
            YouTube.cookie = accepted.cookie
            YouTube.visitorData = accepted.visitorData
            YouTube.dataSyncId = accepted.dataSyncId
            YouTube.useLoginForBrowse = accepted.useLoginForBrowse
            assertSame(accepted, YouTube.authentication)
            assertEquals(listOf(accepted), observed)
            for (secret in listOf("cookie-secret", "visitor-secret", "sync-secret")) {
                assertFalse(accepted.toString().contains(secret))
            }
        } finally { collector.cancelAndJoin() }
    }

    @Test fun `individual setters retain the other authentication fields and advance exactly once`() {
        YouTube.setAuthentication("SAPISID=first", "first-visitor", "first-sync", true)
        val before = YouTube.authentication
        YouTube.visitorData = "new-visitor"
        val after = YouTube.authentication
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.cookie, after.cookie)
        assertEquals(before.dataSyncId, after.dataSyncId)
        assertEquals(before.useLoginForBrowse, after.useLoginForBrowse)
        assertEquals("new-visitor", after.visitorData)
        assertEquals("first-visitor", before.visitorData)
    }

    @Test fun `atomic logout and return to the same account still reject old metadata notifications`() = runBlocking {
        YouTube.setAuthentication("SAPISID=first", "first-visitor", "first-sync", true)
        val before = YouTube.authentication
        var deliveries = 0
        YouTube.metadataObserver = { _, _, _ -> deliveries++ }
        val release = CompletableDeferred<List<SongItem>>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            YouTube.metadataRequest(YouTubeLocale("JP", "ja"), "queue", items = { songs: List<SongItem> -> songs }) {
                release.await()
            }
        }
        YouTube.setAuthentication(null, null, null, true)
        YouTube.setAuthentication(before.cookie, before.visitorData, before.dataSyncId, before.useLoginForBrowse)
        assertEquals(before.revision + 2, YouTube.authRevision)
        release.complete(listOf(SongItem("track", "Title", emptyList(), thumbnail = "")))
        assertTrue(pending.await().isSuccess)
        assertEquals(0, deliveries)
    }
}
