package com.zionhuang.innertube

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Prevent an API from collecting metadata outside the request-start authentication guard. */
class MetadataObserverArchitectureTest {
    @Test fun `every metadata emitting endpoint captures authentication before its request`() {
        val source = sourceFile().readText()
        val guardedEndpoint = Regex(
            """(?m)^    (?:private )?suspend fun (\w+)\([^\r\n]* = metadataRequest\(\s*requestLocale, "([^"]+)""",
        )
        val guarded = guardedEndpoint.findAll(source).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val expected = listOf(
            "searchSuggestions", "searchSummary", "search", "searchContinuation", "album", "albumSongs",
            "artist", "artistItems", "artistItemsContinuation", "playlist", "playlistContinuation",
            "home", "homeContinuation", "explore", "newReleaseAlbums", "browse", "library",
            "libraryContinuation", "libraryRecentActivity", "musicHistory", "next", "related", "queue",
        )
        assertEquals(expected.map { it to it }, guarded)
    }

    @Test fun `production notifications only run inside the guarded collector`() {
        val source = sourceFile().readText()
        val notificationLines = source.lineSequence().filter {
            Regex("""\bnotifyMetadata\s*\(""").containsMatchIn(it)
        }.toList()
        assertEquals(2, notificationLines.size) // The declaration and the single guarded call.
        assertTrue(notificationLines.any { it.trim().startsWith("internal fun notifyMetadata(") })
        assertTrue(notificationLines.any { it.contains("if (revision == metadataAuthRevision) notifyMetadata(") })
        assertEquals(1, Regex("""metadataObserver\?\.invoke\(""").findAll(source).count())
    }

    private fun sourceFile(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "innertube/src/main/java/com/zionhuang/innertube/YouTube.kt") }
        .firstOrNull(File::isFile)
        ?: error("Cannot locate YouTube sources")
}
