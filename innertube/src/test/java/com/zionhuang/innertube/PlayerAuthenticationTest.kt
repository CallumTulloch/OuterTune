package com.zionhuang.innertube

import com.sun.net.httpserver.HttpServer
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.utils.cookieAuthorization
import com.zionhuang.innertube.utils.parseCookieString
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Capture real serialized requests locally using synthetic credentials; never contact YouTube. */
class PlayerAuthenticationTest {
    private data class Request(val cookie: String?, val authorization: String?, val body: String)
    private val requests = LinkedBlockingQueue<Request>()
    private val visitors = AtomicInteger()
    private val serverExecutor = Executors.newCachedThreadPool()
    @Volatile private var visitorGate: Pair<CountDownLatch, CountDownLatch>? = null
    @Volatile private var playerGate: Pair<CountDownLatch, CountDownLatch>? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = serverExecutor
        createContext("/visitor_id") { exchange ->
            val visitor = "visitor-${visitors.incrementAndGet()}"
            exchange.requestBody.close()
            visitorGate?.also { visitorGate = null }?.let { (started, release) ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            val response = """{"responseContext":{"visitorData":"$visitor"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        createContext("/player") { exchange ->
            requests.add(Request(
                exchange.requestHeaders.getFirst("Cookie"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.bufferedReader().use { it.readText() },
            ))
            playerGate?.also { playerGate = null }?.let { (started, release) ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
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
        serverExecutor.shutdownNow()
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

    @Test
    fun `returning to the same cookie after logout requires a new visitor`() = runBlocking {
        val innerTube = InnerTube().apply { cookie = cookies }
        val client = YouTubeClient.WEB_REMIX.copy(requiresFreshVisitorData = true)
        val first = request(innerTube, client)
        assertEquals("visitor-1", visitorIn(first))
        innerTube.cookie = null
        innerTube.cookie = cookies
        assertEquals("visitor-2", visitorIn(request(innerTube, client)))
        innerTube.cookie = cookies
        assertEquals("visitor-2", visitorIn(request(innerTube, client)))
        assertEquals(2, visitors.get())
    }

    @Test
    fun `auth changes during visitor acquisition reject the old player and cannot restore its visitor cache`() = runBlocking {
        val innerTube = InnerTube().apply { cookie = cookies; dataSyncId = "first-account" }
        val client = YouTubeClient.WEB_REMIX.copy(requiresFreshVisitorData = true)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        visitorGate = started to release
        val pending = async(Dispatchers.IO) { runCatching { request(innerTube, client) } }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            innerTube.cookie = "SAPISID=second-account"
            innerTube.dataSyncId = "second-account"
            release.countDown()
            assertTrue("An old visitor must not be combined with a new account", pending.await().isFailure)
            assertTrue("No old player request should have been sent", requests.isEmpty())

            val fresh = request(innerTube, client)
            assertEquals("visitor-2", visitorIn(fresh))
            assertEquals("SAPISID=second-account", fresh.cookie)
            val user = Json.parseToJsonElement(fresh.body).jsonObject
                .getValue("context").jsonObject.getValue("user").jsonObject
            assertEquals("second-account", user.getValue("onBehalfOfUser").jsonPrimitive.content)
            assertEquals("visitor-2", visitorIn(request(innerTube, client)))
            assertEquals(2, visitors.get())
        } finally { release.countDown() }
    }

    @Test
    fun `auth changes while player response is pending reject it while keeping sent credentials coherent`() = runBlocking {
        val innerTube = InnerTube().apply { setAuthentication(cookies, "first-visitor", "first-account", true) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        playerGate = started to release
        val pending = async(Dispatchers.IO) {
            runCatching {
                innerTube.player(YouTubeClient.WEB_REMIX.copy(apiUrl = "http://127.0.0.1:${server.address.port}/"),
                    "fixture-video", null, null, null).bodyAsText()
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val sent = requireNotNull(requests.poll(5, TimeUnit.SECONDS))
            innerTube.setAuthentication("SAPISID=second-account", "second-visitor", "second-account", false)
            release.countDown()
            assertTrue(pending.await().exceptionOrNull() is AuthenticationChangedException)
            assertEquals(cookies, sent.cookie)
            assertEquals("first-visitor", visitorIn(sent))
            val user = Json.parseToJsonElement(sent.body).jsonObject
                .getValue("context").jsonObject.getValue("user").jsonObject
            assertEquals("first-account", user.getValue("onBehalfOfUser").jsonPrimitive.content)
        } finally { release.countDown() }
    }

    private fun visitorIn(request: Request): String = Json.parseToJsonElement(request.body).jsonObject
        .getValue("context").jsonObject.getValue("client").jsonObject.getValue("visitorData").jsonPrimitive.content
}
