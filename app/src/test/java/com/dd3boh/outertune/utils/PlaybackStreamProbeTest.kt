package com.dd3boh.outertune.utils

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackStreamProbeTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @After
    fun closeClient() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun headSuccessCannotHideAForbiddenPlaybackGet() {
        Fixture(requestCount = 2) { request, socket ->
            val code = if (request.method == "HEAD") 200 else 403
            respond(socket, code)
        }.use { fixture ->
            client.newCall(Request.Builder().url(fixture.url).head().build()).execute().use { response ->
                assertEquals(200, response.code)
            }
            val result = probePlaybackStream(client, fixture.url)
            assertTrue("An HTTP rejection is a status, not a transport failure", result.isSuccess)
            assertEquals(403, result.getOrThrow())
            val requests = fixture.requests()
            assertEquals(listOf("HEAD", "GET"), requests.map { it.method })
            assertEquals("bytes=0-0", requests.last().headers["range"])
            assertEquals("identity", requests.last().headers["accept-encoding"])
        }
    }

    @Test
    fun partialContentFromTheActualPlaybackGetIsAccepted() {
        Fixture { request, socket ->
            assertEquals("GET", request.method)
            assertEquals("bytes=0-0", request.headers["range"])
            assertEquals("identity", request.headers["accept-encoding"])
            socket.getOutputStream().apply {
                write(("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\n" +
                    "Content-Range: bytes 0-0/1024\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(byteArrayOf(1))
                flush()
            }
        }.use { fixture ->
            assertEquals(206, probePlaybackStream(client, fixture.url).getOrThrow())
            assertEquals("GET", fixture.requests().single().method)
        }
    }

    @Test
    fun anIgnoredRangeDoesNotDownloadTheLargeBodyAndClosesTheResponse() {
        Fixture { request, socket ->
            assertEquals("bytes=0-0", request.headers["range"])
            socket.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Length: 67108864\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(byteArrayOf(1))
                flush()
            }
            // The advertised body is deliberately unfinished. Reading it all would time out;
            // closing the response must end the connection without requesting further data.
            try {
                assertEquals("The probe must close its response", -1, socket.getInputStream().read())
            } catch (_: SocketException) {
                // A reset also proves the client closed an unfinished response body.
            }
        }.use { fixture ->
            assertEquals(200, probePlaybackStream(client, fixture.url).getOrThrow())
            fixture.requests()
        }
    }

    @Test
    fun aConnectionClosedWithoutAnHttpResponseIsATransportFailure() {
        Fixture { _, _ -> /* Close the accepted socket before writing any response. */ }.use { fixture ->
            val result = probePlaybackStream(client, fixture.url)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals("GET", fixture.requests().single().method)
        }
    }

    @Test
    fun cancellationIsRethrownInsteadOfReturnedAsANetworkFailure() {
        val cancellation = CancellationException("probe cancelled")
        val cancelledClient = client.newBuilder().addInterceptor { throw cancellation }.build()
        try {
            probePlaybackStream(cancelledClient, "http://127.0.0.1:1/stream")
            fail("Expected cancellation to escape the probe")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private data class RecordedRequest(val method: String, val headers: Map<String, String>)

    private class Fixture(
        requestCount: Int = 1,
        handler: (RecordedRequest, Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, requestCount, InetAddress.getByName("127.0.0.1"))
        private val activeSocket = AtomicReference<Socket?>()
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${server.localPort}/stream"
        private val serving = executor.submit(Callable {
            buildList {
                repeat(requestCount) {
                    server.accept().use { socket ->
                        activeSocket.set(socket)
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val method = reader.readLine().substringBefore(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: throw IOException("Request headers ended early")
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val request = RecordedRequest(method, headers)
                        add(request)
                        handler(request, socket)
                        activeSocket.set(null)
                    }
                }
            }
        })

        fun requests(): List<RecordedRequest> = serving.get(5, TimeUnit.SECONDS)

        override fun close() {
            activeSocket.getAndSet(null)?.close()
            server.close()
            serving.cancel(true)
            executor.shutdownNow()
        }
    }

    private companion object {
        fun respond(socket: Socket, code: Int) {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $code Fixture\r\nContent-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }
}
