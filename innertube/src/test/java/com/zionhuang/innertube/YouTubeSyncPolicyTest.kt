package com.zionhuang.innertube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class YouTubeSyncPolicyTest {
    @Test(timeout = 15_000)
    fun `all account write entry points refuse transport while retaining signed in state`() = runBlocking {
        val originalAuthentication = YouTube.authentication
        val originalProxy = YouTube.proxy
        val attempts = AtomicInteger()
        // Any regression is confined to this loopback rejector; no request can reach YouTube.
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val receiver = thread(isDaemon = true, name = "disabled-sync-proxy") {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        attempts.incrementAndGet()
                        socket.getOutputStream().write(
                            "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                    }
                } catch (closed: SocketException) {
                    if (!listener.isClosed) throw closed
                }
            }
        }
        try {
            assertFalse(YouTubeSyncPolicy.ENABLED)
            YouTube.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", listener.localPort))
            YouTube.setAuthentication("SAPISID=synthetic-account-cookie", "synthetic-visitor", "synthetic-session", true)
            val signedIn = YouTube.authentication
            val operations: List<Pair<String, suspend () -> Result<*>>> = listOf(
                operation("like video") { YouTube.likeVideo("synthetic-video", true) },
                operation("unlike video") { YouTube.likeVideo("synthetic-video", false) },
                operation("like playlist") { YouTube.likePlaylist("synthetic-playlist", true) },
                operation("unlike playlist") { YouTube.likePlaylist("synthetic-playlist", false) },
                operation("subscribe") { YouTube.subscribeChannel("synthetic-channel", true) },
                operation("unsubscribe") { YouTube.subscribeChannel("synthetic-channel", false) },
                operation("add song") { YouTube.addToPlaylist("synthetic-playlist", "synthetic-video") },
                operation("add playlist") { YouTube.addPlaylistToPlaylist("synthetic-playlist", "synthetic-source") },
                operation("remove song") { YouTube.removeFromPlaylist("synthetic-playlist", "synthetic-video", "synthetic-set-id") },
                operation("move song") { YouTube.moveSongPlaylist("synthetic-playlist", "synthetic-set-id", "synthetic-successor") },
                operation("rename playlist") { YouTube.renamePlaylist("synthetic-playlist", "synthetic-title") },
                operation("delete playlist") { YouTube.deletePlaylist("synthetic-playlist") },
                operation("register playback") {
                    YouTube.registerPlayback("synthetic-playlist", "https://media.invalid/tracking?token=synthetic-token")
                },
            )
            operations.forEach { (name, operation) ->
                assertBlocked(name, operation().exceptionOrNull())
                assertEquals("$name attempted transport", 0, attempts.get())
                assertSame("$name changed the signed-in session", signedIn, YouTube.authentication)
            }
            assertBlocked("create playlist", runCatching { YouTube.createPlaylist("synthetic-title") }.exceptionOrNull())
            assertEquals(0, attempts.get())
            assertSame(signedIn, YouTube.authentication)
        } finally {
            try {
                YouTube.proxy = originalProxy
                YouTube.setAuthentication(originalAuthentication.cookie, originalAuthentication.visitorData,
                    originalAuthentication.dataSyncId, originalAuthentication.useLoginForBrowse)
            } finally {
                listener.close()
                receiver.join(1_000)
            }
        }
    }

    private fun operation(name: String, block: suspend () -> Result<*>) = name to block

    private fun assertBlocked(operation: String, failure: Throwable?) {
        assertTrue("$operation must report disabled synchronization", failure is YouTubeSyncDisabledException)
        assertEquals("YouTube synchronization is disabled", failure!!.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.stackTraceToString().contains("synthetic-account-cookie"))
        assertFalse(failure.stackTraceToString().contains("synthetic-token"))
    }
}
