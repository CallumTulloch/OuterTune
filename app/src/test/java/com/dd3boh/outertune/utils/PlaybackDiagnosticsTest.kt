package com.dd3boh.outertune.utils

import kotlinx.serialization.SerializationException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PlaybackDiagnosticsTest {
    private fun diagnostics() = PlaybackDiagnostics(
        cookiePresent = true,
        visitorPresent = true,
        sessionPresent = false,
        authRevision = 3L,
        requiredItag = 251,
        authorizationAvailable = true,
    )

    @Test fun `last anonymous login rejection preserves earlier authenticated failure`() {
        val diagnostics = diagnostics()
        diagnostics.poTokenAvailable = true
        diagnostics.signatureTimestampAvailable = true
        diagnostics.record(
            "WEB_REMIX", true, PlaybackFailureStage.STREAM_HTTP,
            status = "OK", httpCode = 403, selectedItag = 251,
        )
        diagnostics.record("IOS", false, PlaybackFailureStage.PLAYABILITY, status = "LOGIN_REQUIRED")

        val report = diagnostics.asException().message!!
        assertTrue(report.contains("cookiePresent=true visitorPresent=true sessionPresent=false"))
        assertTrue(report.contains("authRevision=3 requiredItag=251"))
        assertTrue(report.contains("authorizationAvailable=true"))
        assertTrue(report.contains("poTokenAvailable=true signatureTimestampAvailable=true"))
        assertTrue(report.contains("1: client=WEB_REMIX cookieHeaderConfigured=true stage=STREAM_HTTP status=OK http=403 itag=251"))
        assertTrue(report.contains("2: client=IOS cookieHeaderConfigured=false stage=PLAYABILITY status=LOGIN_REQUIRED"))
    }

    @Test fun `untrusted client status messages causes and suppressed failures cannot enter report`() {
        val secret = "Cookie: SID=secret-cookie Authorization=secret-auth " +
            "https://media.example/stream?pot=secret-token&sig=secret-signature"
        val failure = IOException(secret, IllegalStateException(secret))
        failure.addSuppressed(IllegalArgumentException(secret))
        val diagnostics = diagnostics()
        diagnostics.record(secret, true, PlaybackFailureStage.REQUEST, status = secret, failure = failure)

        val exception = diagnostics.asException()
        val report = exception.stackTraceToString()
        for (forbidden in listOf("secret-cookie", "secret-auth", "secret-token", "secret-signature", "https://", "Cookie:")) {
            assertFalse("Leaked $forbidden", report.contains(forbidden))
        }
        assertTrue(report.contains("client=OTHER"))
        assertTrue(report.contains("status=OTHER"))
        assertTrue(report.contains("exception=IOException"))
        assertNull(exception.cause)
        assertTrue(exception.suppressed.isEmpty())
    }

    @Test fun `throwable toString is never called and unknown classes are not reported`() {
        val diagnostics = diagnostics()
        diagnostics.record("WEB_REMIX", true, PlaybackFailureStage.REQUEST, failure = object : Exception() {
            override fun toString(): String = error("Unsafe throwable rendering")
        })
        diagnostics.record("ANDROID", true, PlaybackFailureStage.REQUEST, failure = SecretTokenClass())
        val report = diagnostics.asException().message!!
        assertFalse(report.contains("SecretTokenClass"))
        assertEquals(2, report.lines().count { it.contains("exception=OTHER") })
    }

    @Test fun `attempt count and payload size stay bounded under repeated failures`() {
        val diagnostics = diagnostics()
        repeat(100) {
            diagnostics.record(
                "IOS" + "untrusted".repeat(1000), false, PlaybackFailureStage.REQUEST,
                status = "secret".repeat(1000), failure = SocketTimeoutException("secret".repeat(1000)),
            )
        }
        val report = diagnostics.asException().message!!
        assertEquals(7, report.lines().count { it.matches(Regex("[1-7]: .*")) })
        assertTrue(report.contains("attempts=7 truncated=true"))
        assertFalse(report.contains("untrusted"))
        assertFalse(report.contains("secret"))
        assertTrue(report.length < 2_000)
    }

    @Test fun `exception captures original attempt history and availability flags`() {
        val diagnostics = diagnostics()
        diagnostics.record("WEB_REMIX", true, PlaybackFailureStage.REQUEST, failure = SocketTimeoutException())
        val snapshot = diagnostics.asException()
        val original = snapshot.message

        diagnostics.poTokenAvailable = true
        diagnostics.signatureTimestampAvailable = true
        diagnostics.record("IOS", false, PlaybackFailureStage.PLAYABILITY, status = "LOGIN_REQUIRED")

        assertEquals(original, snapshot.message)
        assertTrue(snapshot.message!!.contains("attempts=1"))
        assertTrue(snapshot.message!!.contains("poTokenAvailable=false signatureTimestampAvailable=false"))
        assertFalse(snapshot.message!!.contains("IOS"))
        assertTrue(diagnostics.asException().message!!.contains("attempts=2"))
        assertNull(snapshot.cause)
    }

    private class SecretTokenClass : Exception()

    @Test fun `response decoding failure classification survives an unnamed subclass`() {
        val diagnostics = diagnostics()
        diagnostics.record("WEB_REMIX", true, PlaybackFailureStage.REQUEST,
            failure = object : SerializationException("secret response payload") {})
        val report = diagnostics.asException().message!!
        assertTrue(report.contains("exception=SerializationException"))
        assertFalse(report.contains("secret response payload"))
    }

    @Test fun `API rejection retains HTTP number without response body or request URL`() = runBlocking {
        val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val serving = executor.submit {
            repeat(3) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val target = input.readLine().split(' ')[1]
                    val code = target.substringBefore('?').removePrefix("/").toInt()
                    while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    val body = "secret response payload".toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $code Rejected\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        val client = HttpClient { expectSuccess = true }
        try {
            for (code in listOf(401, 403, 429)) {
                val failure = runCatching {
                    client.get("http://127.0.0.1:${server.localPort}/$code?token=secret-query")
                }.exceptionOrNull()
                assertTrue(failure is ResponseException)
                val diagnostics = diagnostics()
                diagnostics.record("WEB_REMIX", true, PlaybackFailureStage.REQUEST, failure = failure)
                val report = diagnostics.asException().stackTraceToString()
                assertTrue(report.contains("http=$code"))
                assertTrue(report.contains("exception=ResponseException"))
                assertFalse(report.contains("secret response payload"))
                assertFalse(report.contains("secret-query"))
                assertFalse(report.contains("http://"))
            }
            serving.get(5, TimeUnit.SECONDS)
            Unit
        } finally {
            client.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
