package com.dd3boh.outertune.repositories

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import io.ktor.client.call.body
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit anonymous live probe. Captures public music subtrees, never an entire API response. */
class LiveAlbumIdentityProbeTest {
    @Test(timeout = 120_000)
    fun captureNevermindAlbumAndNextIdentities(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in live identity probe", arguments.getString("liveAlbumIdentityProbe") == "true")
        var stage = "SETUP"
        try {
            val country = arguments.getString("probeCountry") ?: "JP"
            require(Regex("[A-Z]{2}").matches(country))
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "album-language-probe").apply { check(isDirectory || mkdirs()) }
            val visitorFile = File(directory, "visitor.txt")
            val visitor = if (visitorFile.isFile) {
                require(visitorFile.length() <= 8_192)
                visitorFile.readText().trim().takeIf { it.isNotEmpty() }
            } else null
            // This instance has no access to application preferences or YouTube's singleton auth.
            val client = InnerTube().apply {
                setAuthentication(cookie = null, visitorData = visitor, dataSyncId = null, useLoginForBrowse = false)
            }
            val albumCounts = mutableListOf<Int>()
            withTimeout(100_000) {
                for (language in listOf("ja", "en")) {
                    val locale = YouTubeLocale(gl = country, hl = language)
                    stage = "BROWSE_$language"
                    val response = withTimeout(25_000) {
                        client.browse(YouTubeClient.WEB_REMIX, browseId = ALBUM_ID,
                            setLogin = false, requestLocale = locale).body<JsonElement>()
                    }
                    val rows = albumTrackRows(response)
                    albumCounts += rows.size
                    save(File(directory, "album-$country-$language.json"), buildJsonObject {
                        put("albumId", ALBUM_ID)
                        put("country", country)
                        put("language", language)
                        put("trackRenderers", JsonArray(rows.map(::publicSubtree)))
                    })
                    Log.i(TAG, "album language=$language trackRows=${rows.size}")

                    for ((index, videoId) in VIDEO_IDS.withIndex()) {
                        stage = "NEXT_${language}_$index"
                        val next = withTimeout(25_000) {
                            client.next(YouTubeClient.WEB_REMIX, videoId = videoId,
                                playlistId = null, playlistSetVideoId = null, index = null, params = null,
                                requestLocale = locale).body<JsonElement>()
                        }
                        val identities = nextIdentitySubtrees(next)
                        save(File(directory, "next-$videoId-$country-$language.json"), buildJsonObject {
                            put("requestedVideoId", videoId)
                            put("country", country)
                            put("language", language)
                            put("identitySubtrees", JsonArray(identities))
                        })
                        Log.i(TAG, "next language=$language sample=$index identitySubtrees=${identities.size}")
                    }
                }
            }
            stage = "ALBUM_ROWS"
            assertTrue("Both language responses must contain album track rows",
                albumCounts.size == 2 && albumCounts.all { it > 0 })
        } catch (failure: Throwable) {
            // HTTP exceptions may contain a response, URL or request values: do not attach them.
            throw AssertionError("Live album identity probe failed at $stage: ${failure.javaClass.simpleName}")
        }
    }

    /** The same primary/secondary shelf locations used by AlbumPage.trackContents(). */
    private fun albumTrackRows(response: JsonElement): List<JsonElement> {
        val contents = response.child("contents")
        val twoColumn = contents?.child("twoColumnBrowseResultsRenderer")
        val primary = contents?.child("singleColumnBrowseResultsRenderer") ?: twoColumn
        val tabs = primary?.child("tabs") as? JsonArray
        val primarySections = tabs?.firstOrNull()?.child("tabRenderer", "content", "sectionListRenderer", "contents")
        val secondarySections = twoColumn?.child("secondaryContents", "sectionListRenderer", "contents")
        return (elements(primarySections) + elements(secondarySections)).flatMap { section ->
            val shelf = section.child("musicShelfRenderer") ?: section.child("musicPlaylistShelfRenderer")
            elements(shelf?.child("contents")).mapNotNull { item ->
                item.child("musicResponsiveListItemRenderer")
            }
        }
    }

    /** Keep exact wrapper/counterpart shapes while omitting unrelated next response sections. */
    private fun nextIdentitySubtrees(response: JsonElement): List<JsonElement> = buildList {
        fun visit(value: JsonElement, path: String) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    val childPath = "$path.$key"
                    if (key == "playlistPanelVideoWrapperRenderer" || key == "playlistPanelVideoRenderer" ||
                        key == "currentVideoEndpoint" ||
                        key == "musicVideoType" || key.contains("counterpart", ignoreCase = true)) {
                        add(buildJsonObject {
                            put("path", childPath)
                            put("value", publicSubtree(child))
                        })
                    }
                    visit(child, childPath)
                }
                is JsonArray -> value.forEachIndexed { index, child -> visit(child, "$path[$index]") }
                else -> Unit
            }
        }
        visit(response, "$")
    }

    private fun publicSubtree(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it.lowercase() !in OMIT_KEYS }
            .mapValues { (_, child) -> publicSubtree(child) })
        is JsonArray -> JsonArray(value.map(::publicSubtree))
        else -> value
    }

    private fun JsonElement.child(vararg keys: String): JsonElement? {
        var result: JsonElement? = this
        keys.forEach { key -> result = (result as? JsonObject)?.get(key) }
        return result
    }

    private fun elements(value: JsonElement?): List<JsonElement> = (value as? JsonArray)?.toList().orEmpty()

    private fun save(file: File, content: JsonElement) {
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), content))
    }

    companion object {
        private const val TAG = "LiveAlbumIdentityProbe"
        private const val ALBUM_ID = "MPREb_jPOYfjGgApr"
        private val VIDEO_IDS = listOf("hTWKbfoikeg", "ljUtuoFt-8c")
        private val OMIT_KEYS = setOf("visitordata", "cookie", "authorization", "datasyncid", "responsecontext",
            "trackingparams", "clicktrackingparams", "trackingtoken", "feedbacktoken", "loggingdirectives")
    }
}
