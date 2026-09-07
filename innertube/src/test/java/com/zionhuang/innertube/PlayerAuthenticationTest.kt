package com.zionhuang.innertube

import com.sun.net.httpserver.HttpServer
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.utils.cookieAuthorization
import com.zionhuang.innertube.utils.parseCookieString
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Capture real serialized requests locally using synthetic credentials; never contact YouTube. */
class PlayerAuthenticationTest {
    private data class Request(val cookie: String?, val authorization: String?, val body: String)
    private val requests = LinkedBlockingQueue<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/player") { exchange ->
            requests.add(Request(
                exchange.requestHeaders.getFirst("Cookie"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.bufferedReader().use { it.readText() },
            ))
            exchange.responseHeaders.set("Content-Type", "application/json")
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }
    private val cookies = "SAPISID=test-primary; __Secure-1PAPISID=test-first-party; __Secure-3PAPISID=test-third-party"

    @After
    fun stopServer() {
        server.stop(0)
    }

    private suspend fun request(innerTube: InnerTube, client: YouTubeClient): Request {
        innerTube.player(
            client = client.copy(apiUrl = "http://127.0.0.1:${server.address.port}/"),
            videoId = "fixture-video", playlistId = null, signatureTimestamp = null, webPlayerPot = null,
        ).bodyAsText()
        return requireNotNull(requests.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `authenticated fallback clients transmit matching cookie signatures and existing confirmation flags`() = runBlocking {
        val innerTube = InnerTube().apply {
            cookie = cookies
            dataSyncId = "fixture-account"
        }
        listOf(
            YouTubeClient.WEB_REMIX, YouTubeClient.ANDROID,
            YouTubeClient.TVHTML5, YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ).forEach { client ->
            val request = request(innerTube, client)
            assertEquals(cookies, request.cookie)
            val authorization = requireNotNull(request.authorization)
            val timestamp = authorization.substringAfter(' ').substringBefore('_').toLong()
            assertEquals(
                cookieAuthorization(parseCookieString(cookies), YouTubeClient.ORIGIN_YOUTUBE_MUSIC, timestamp),
                authorization,
            )
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("fixture-video", body.getValue("videoId").jsonPrimitive.content)
            assertTrue(body.getValue("contentCheckOk").jsonPrimitive.boolean)
            assertTrue(body.getValue("racyCheckOk").jsonPrimitive.boolean)
            val user = body.getValue("context").jsonObject.getValue("user").jsonObject
            assertEquals("fixture-account", user.getValue("onBehalfOfUser").jsonPrimitive.content)
            assertFalse(user.getValue("lockedSafetyMode").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `anonymous playback clients do not receive stored account credentials`() = runBlocking {
        val innerTube = InnerTube().apply {
            cookie = cookies
            dataSyncId = "fixture-account"
        }
        listOf(YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.IOS).forEach { client ->
            val request = request(innerTube, client)
            assertNull(request.cookie)
            assertNull(request.authorization)
            val user = Json.parseToJsonElement(request.body).jsonObject
                .getValue("context").jsonObject.getValue("user").jsonObject
            assertFalse(user.containsKey("onBehalfOfUser"))
        }
    }

    @Test
    fun `cookie replacement and logout cannot reuse earlier signatures`() = runBlocking {
        val innerTube = InnerTube().apply { cookie = cookies }
        assertNotNull(request(innerTube, YouTubeClient.WEB_REMIX).authorization)

        innerTube.cookie = "__Secure-3PAPISID=test-third-party"
        val updated = request(innerTube, YouTubeClient.WEB_REMIX)
        assertEquals(innerTube.cookie, updated.cookie)
        assertTrue(requireNotNull(updated.authorization).startsWith("SAPISIDHASH "))
        assertFalse(updated.authorization.contains("SAPISID1PHASH"))
        assertTrue(updated.authorization.contains(" SAPISID3PHASH "))

        innerTube.cookie = null
        val loggedOut = request(innerTube, YouTubeClient.WEB_REMIX)
        assertNull(loggedOut.cookie)
        assertNull(loggedOut.authorization)
    }
}
