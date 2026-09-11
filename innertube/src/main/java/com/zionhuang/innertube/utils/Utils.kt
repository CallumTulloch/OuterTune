package com.zionhuang.innertube.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.PlaylistPage
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatching {
    val page = getOrThrow()
    val requestLocale = page.requestLocale ?: YouTube.locale
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    val visited = mutableSetOf<String>()
    while (continuation != null) {
        currentCoroutineContext().ensureActive()
        check(visited.add(continuation)) { "Playlist continuation did not advance" }
        val continuationPage = YouTube.playlistContinuation(continuation, requestLocale = requestLocale).getOrThrow()
        songs += continuationPage.songs
        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = null,
        continuation = page.continuation,
        requestLocale = requestLocale,
    )
}

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    val requestLocale = page.requestLocale ?: YouTube.locale
    val items = page.items.toMutableList()
    var continuation = page.continuation
    val visited = mutableSetOf<String>()
    while (continuation != null) {
        currentCoroutineContext().ensureActive()
        check(visited.add(continuation)) { "Library continuation did not advance" }
        val continuationPage = YouTube.libraryContinuation(continuation, requestLocale = requestLocale).getOrThrow()
        items += continuationPage.items
        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = null,
        requestLocale = requestLocale,
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .associate {
            val (key, value) = it.split("=")
            key to value
        }

fun String.parseTime(): Int? {
    try {
        val parts = split(":").map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
