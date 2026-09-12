package com.dd3boh.outertune.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtistCreditAuthenticationTest {
    @Test
    fun cacheContextSeparatesEveryAuthenticationInputButRetainsSuccessfulSameAccountData() {
        val initial = token()
        assertNotEquals(initial, token(cookie = "SAPISID=other-cookie"))
        assertNotEquals(initial, token(visitorData = "other-visitor"))
        assertNotEquals(initial, token(dataSyncId = "other-channel"))
        assertNotEquals(initial, token(useLogin = false))
        // In-flight requests and retry windows use the revision separately. Accepted persistent
        // credit/album data can still be reused after signing back into the same account.
        assertEquals(initial, token())
    }

    private fun token(cookie: String = "SAPISID=test-cookie", visitorData: String = "test-visitor",
        dataSyncId: String = "test-channel", useLogin: Boolean = true) =
        artistCreditAuthenticationToken(cookie, visitorData, dataSyncId, useLogin)
}
