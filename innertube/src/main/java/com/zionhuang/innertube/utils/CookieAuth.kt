package com.zionhuang.innertube.utils

/** Build each authorization scheme from its own cookie, omitting unavailable credentials. */
internal fun cookieAuthorization(
    cookies: Map<String, String>,
    origin: String,
    timestamp: Long = System.currentTimeMillis() / 1000,
): String? {
    val thirdPartySid = cookies["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }
    val primarySid = cookies["SAPISID"]?.takeIf { it.isNotBlank() } ?: thirdPartySid
    val firstPartySid = cookies["__Secure-1PAPISID"]?.takeIf { it.isNotBlank() }

    return listOf(
        "SAPISIDHASH" to primarySid,
        "SAPISID1PHASH" to firstPartySid,
        "SAPISID3PHASH" to thirdPartySid,
    ).mapNotNull { (scheme, sid) ->
        sid?.let { "$scheme ${timestamp}_${sha1("$timestamp $it $origin")}" }
    }.joinToString(" ").ifEmpty { null }
}
