package com.zionhuang.innertube

import com.zionhuang.innertube.utils.cookieAuthorization
import com.zionhuang.innertube.utils.parseCookieString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookieAuthTest {
    private val origin = "https://music.youtube.com"
    private val timestamp = 1700000000L
    private val primary = "1700000000_9ee2a085d4a2b8fca108d01b2d22e227e3fca98d"
    private val firstParty = "1700000000_25897fa8bfb656de38bab68b5b81f7d237c8ed0e"
    private val thirdParty = "1700000000_2609b10e1f4328336fc1e75b4e917be084bc052c"

    @Test
    fun `cookie parsing accepts separators without spaces and preserves equals in values`() {
        assertEquals(mapOf("SAPISID" to "test-primary", "token" to "a=b==", "empty" to ""),
            parseCookieString(" SAPISID=test-primary;token=a=b== ; empty= "))
    }

    @Test
    fun `malformed cookie fragments do not prevent valid credentials from loading`() {
        assertEquals(mapOf("SAPISID" to "test-primary"), parseCookieString("; broken; =ignored; SAPISID=test-primary;;"))
        assertEquals(emptyMap<String, String>(), parseCookieString("broken; ; =ignored"))
        val innerTube = InnerTube()
        innerTube.setAuthentication("broken; SAPISID=test-primary", "visitor", "account", true)
        assertEquals("test-primary", innerTube.authentication.cookieMap["SAPISID"])
    }

    @Test
    fun `distinct SID cookies produce their own signatures`() {
        assertEquals(
            "SAPISIDHASH $primary SAPISID1PHASH $firstParty SAPISID3PHASH $thirdParty",
            cookieAuthorization(
                mapOf(
                    "SAPISID" to "test-primary",
                    "__Secure-1PAPISID" to "test-first-party",
                    "__Secure-3PAPISID" to "test-third-party",
                ), origin, timestamp,
            ),
        )
    }

    @Test
    fun `legacy primary cookie does not invent first or third party credentials`() {
        assertEquals(
            "SAPISIDHASH $primary",
            cookieAuthorization(mapOf("SAPISID" to "test-primary"), origin, timestamp),
        )
    }

    @Test
    fun `third party cookie supplies missing or empty primary cookie`() {
        listOf(emptyMap(), mapOf("SAPISID" to "")).forEach { primaryCookie ->
            assertEquals(
                "SAPISIDHASH $thirdParty SAPISID3PHASH $thirdParty",
                cookieAuthorization(
                    primaryCookie + ("__Secure-3PAPISID" to "test-third-party"), origin, timestamp,
                ),
            )
        }
    }

    @Test
    fun `first party cookie only signs its corresponding scheme`() {
        assertEquals(
            "SAPISID1PHASH $firstParty",
            cookieAuthorization(mapOf("__Secure-1PAPISID" to "test-first-party"), origin, timestamp),
        )
    }

    @Test
    fun `missing or blank SID cookies omit authorization`() {
        assertNull(cookieAuthorization(emptyMap(), origin, timestamp))
        assertNull(cookieAuthorization(mapOf("VISITOR_INFO1_LIVE" to "visitor"), origin, timestamp))
        assertNull(cookieAuthorization(
            mapOf("SAPISID" to "", "__Secure-1PAPISID" to " ", "__Secure-3PAPISID" to ""),
            origin, timestamp,
        ))
    }

    @Test
    fun `signature binds the supplied origin and timestamp`() {
        assertEquals(
            "SAPISIDHASH 1700000001_a1d44fba3f84c3f161a5dca37d0def1d633ccf38",
            cookieAuthorization(mapOf("SAPISID" to "test-primary"), "https://www.youtube.com", 1700000001L),
        )
    }
}
