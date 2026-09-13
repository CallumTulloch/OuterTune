package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.ResolveUrlResponse
import java.net.URI

data class YouTubeArtistUrl internal constructor(val canonicalUrl: String, val artistId: String?)

fun isPublicYouTubeArtistId(id: String): Boolean = id.matches(Regex("UC[A-Za-z0-9_-]{22}"))

/** Only channel identities are accepted; an input URL is never fetched as a network destination. */
fun parseYouTubeArtistUrl(input: String): YouTubeArtistUrl {
    fun invalid(): Nothing = throw IllegalArgumentException("Invalid YouTube artist URL")
    val value = input.trim().takeIf { it.length in 1..2048 } ?: invalid()
    if (isPublicYouTubeArtistId(value)) {
        return YouTubeArtistUrl("https://music.youtube.com/channel/$value", value)
    }
    val source = when {
        value.startsWith("@") -> "https://music.youtube.com/$value"
        value.startsWith("music.youtube.com/", true) || value.startsWith("youtube.com/", true) ||
            value.startsWith("www.youtube.com/", true) -> "https://$value"
        else -> value
    }
    val uri = try { URI(source) } catch (_: Exception) { invalid() }
    if (uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.host?.lowercase() !in setOf("music.youtube.com", "youtube.com", "www.youtube.com") ||
        uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.contains("\\")) invalid()
    val path = uri.path.removeSuffix("/")
    val id = when {
        path.startsWith("/channel/") -> path.removePrefix("/channel/")
        path.startsWith("/browse/") -> path.removePrefix("/browse/")
        else -> null
    }
    if (id != null) {
        if (!isPublicYouTubeArtistId(id)) invalid()
        return YouTubeArtistUrl("https://music.youtube.com/channel/$id", id)
    }
    if (!path.matches(Regex("/@[\\p{L}\\p{M}\\p{N}_.-]{1,100}"))) invalid()
    return YouTubeArtistUrl(URI("https", "music.youtube.com", path, null).toASCIIString(), null)
}

internal suspend fun resolveYouTubeArtistUrl(
    input: String,
    resolve: suspend (String) -> ResolveUrlResponse,
): String {
    val parsed = parseYouTubeArtistUrl(input)
    parsed.artistId?.let { return it }
    val endpoint = resolve(parsed.canonicalUrl).endpoint
    val id = endpoint?.browseEndpoint?.browseId
    require(endpoint?.watchEndpoint == null && endpoint?.watchPlaylistEndpoint == null &&
        id != null && isPublicYouTubeArtistId(id)) { "URL does not identify a public YouTube artist" }
    return id
}
