package com.dd3boh.outertune.repositories

import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import android.net.Uri
import com.zionhuang.innertube.NewPipeUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response as PipeResponse
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in whole-Application/UI replay. HTTP/audio and connectivity are local fixtures; production parsers,
 * observers, repositories, Room database and native language model remain active together.
 * Use a disposable emulator with the external library restored before instrumentation.
 * Operate the normal UI while this test runs, then create files/workflow-stop via run-as.
 * No fixture transport or reflection is present in the shipped app.
 */
class WorkflowReplayDeviceTest {
    @Test(timeout = 1_500_000)
    fun exerciseRunningApplication(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("workflowReplay") == "true")
        val context = instrumentation.targetContext
        val database = EntryPointAccessors.fromApplication(context, MetadataLanguageTestEntryPoint::class.java).database()
        // Force the production helper's initialization, then keep the optional signature lookup
        // offline. Direct fixture URLs have no signature/throttling parameter to decipher.
        NewPipeUtils.hashCode()
        val previousDownloader = NewPipe.getDownloader()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): PipeResponse = throw IOException("Offline workflow fixture has no player JavaScript")
        })
        val statusClientField = YTPlayerUtils.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val previousStatusClient = statusClientField.get(YTPlayerUtils)
        statusClientField.set(YTPlayerUtils, okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "workflow.invalid")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture cache")
                .body(ByteArray(0).toResponseBody()).build()
        }.build())
        val stop = File(context.filesDir, "workflow-stop").apply { delete() }
        val image = File(context.cacheDir, "workflow-cover.png")
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff386a91.toInt())
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val audio = silentWav()
        val fixture = ReplayResponses("file://${image.absolutePath}", audio.size)
        val parser = Json { ignoreUnknownKeys = true; explicitNulls = false }
        parser.decodeFromString<com.zionhuang.innertube.models.response.BrowseResponse>(fixture.reply("browse", "{\"browseId\":\"MPREb_workflow_01\"}")!!)
        parser.decodeFromString<com.zionhuang.innertube.models.response.SearchResponse>(fixture.reply("search", "{\"query\":\"workflow1\"}")!!)
        parser.decodeFromString<com.zionhuang.innertube.models.response.GetQueueResponse>(fixture.reply("get_queue", "{\"videoIds\":[\"wf000100001\"]}")!!)
        val innerField = YouTube.javaClass.getDeclaredField("innerTube").apply { isAccessible = true }
        val inner = innerField.get(YouTube)
        val clientField = inner.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val previous = clientField.get(inner)
        val calls = ConcurrentHashMap<String, Int>()
        val client = HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
            engine { config { addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val operation = request.url.encodedPath.substringAfterLast('/')
                calls.merge(operation, 1, Int::plus)
                val payload = fixture.reply(operation, body)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (payload == null) 503 else 200)
                    .header("Content-Type", "application/json")
                    .message("Workflow fixture").body((payload ?: "{}").toResponseBody("application/json".toMediaType())).build()
            } } }
        }
        var activity: MainActivity? = null
        var restoreDownloadRequirements: (() -> Unit)? = null
        try {
            clientField.set(inner, client)
            context.dataStore.edit {
                it[OobeStatusKey] = OOBE_VERSION
                it[ContentCountryKey] = "JP"
                it[ContentLanguageKey] = "ja"
                it[PreferEnglishOriginalKey] = true
                it.remove(InnerTubeCookieKey)
                it.remove(VisitorDataKey)
                it.remove(DataSyncIdKey)
                it[AutoLoadMoreKey] = false
                it[MaxSongCacheSizeKey] = 128
            }
            lateinit var downloads: com.dd3boh.outertune.playback.DownloadUtil
            instrumentation.runOnMainSync {
                downloads = EntryPointAccessors.fromApplication(context, MetadataLanguageTestEntryPoint::class.java).downloadUtil()
                val previousRequirements = downloads.downloadManager.requirements
                restoreDownloadRequirements = { downloads.downloadManager.requirements = previousRequirements }
                // These downloads copy preloaded fixture bytes from the player cache. They do
                // not require Android to validate the host's external internet connection.
                downloads.downloadManager.requirements = Requirements(0)
                // Replays may reuse the disposable installation; each UI click must do a fresh DL.
                for (album in 1..12) downloads.downloadManager.removeDownload("wf%04d%05d".format(album, 1))
            }
            repeat(100) {
                if ((1..12).none { downloads.downloadManager.downloadIndex.getDownload("wf%04d%05d".format(it, 1)) != null }) return@repeat
                delay(100)
            }
            for (album in 1..12) {
                val id = "wf%04d%05d".format(album, 1)
                val cache = downloads.playerCache
                if (!cache.isCached(id, 0, audio.size.toLong())) {
                    val hole = cache.startReadWrite(id, 0, audio.size.toLong())
                    val sink = CacheDataSink.Factory().setCache(cache).createDataSink()
                    sink.open(DataSpec.Builder().setUri(Uri.parse(id)).setKey(id).setLength(audio.size.toLong()).build())
                    try { sink.write(audio, 0, audio.size) } finally { sink.close(); cache.releaseHoleSpan(hole) }
                    cache.applyContentMetadataMutations(id, ContentMetadataMutations().also {
                        ContentMetadataMutations.setContentLength(it, audio.size.toLong())
                    })
                }
                assertTrue("Test audio must be retained before starting UI", cache.isCached(id, 0, audio.size.toLong()))
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                // All HTTP and audio are supplied locally. The host's intermittent internet
                // validation must not switch this deterministic online-search replay to local.
                // Keep the override inside instrumentation and close this Activity at teardown.
                val observer = activity!!.connectivityObserver
                observer.unregister()
                val monitor = observer.javaClass.getDeclaredField("monitor").apply { isAccessible = true }.get(observer)
                val state = monitor.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(monitor)
                @Suppress("UNCHECKED_CAST")
                (state as MutableStateFlow<Boolean>).value = true
            }
            Log.i("WorkflowReplay", "READY maxHeap=${Runtime.getRuntime().maxMemory()}")
            val vm = Runtime.getRuntime()
            repeat(240) {
                if (stop.exists()) return@repeat
                Log.i("WorkflowReplay", "sample=$it heap=${vm.totalMemory() - vm.freeMemory()} gc=${Debug.getRuntimeStat("art.gc.gc-count")} gcMs=${Debug.getRuntimeStat("art.gc.gc-time")} calls=$calls")
                delay(5_000)
            }
            assertTrue("Search and album UI must actually request fixture responses", (calls["search"] ?: 0) >= 7 && (calls["browse"] ?: 0) >= 7)
            val saved = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM song WHERE id GLOB 'wf*' AND inLibrary IS NOT NULL").use { it.moveToFirst(); it.getInt(0) }
            val completed = (1..12).count {
                val id = "wf%04d%05d".format(it, 1)
                downloads.downloadManager.downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED &&
                    downloads.downloadCache.isCached(id, 0, audio.size.toLong())
            }
            Log.i("WorkflowReplay", "COMPLETE saved=$saved downloaded=$completed calls=$calls")
            assertTrue("At least seven different songs must be saved from the UI", saved >= 7)
            assertTrue("At least seven UI downloads must finish and retain the complete audio", completed >= 7)
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            restoreDownloadRequirements?.let { restore -> instrumentation.runOnMainSync { restore() } }
            clientField.set(inner, previous)
            client.close()
            statusClientField.set(YTPlayerUtils, previousStatusClient)
            NewPipe.init(previousDownloader)
        }
    }

    private fun silentWav(): ByteArray {
        val pcmSize = 8_000 * 2 * 120
        return ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmSize)
        }.array()
    }
}

private class ReplayResponses(private val image: String, private val audioBytes: Int) {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun array(values: Iterable<Any>) = JSONArray().apply { values.forEach(::put) }
    private fun runs(vararg values: JSONObject) = obj("runs" to array(values.toList()))
    private fun text(value: String) = obj("text" to value)
    private fun songId(album: Int, track: Int) = "wf%04d%05d".format(album, track)
    private fun albumId(album: Int) = "MPREb_workflow_%02d".format(album)
    private fun title(album: Int, track: Int) = "The morning light $album track $track"
    private fun albumName(album: Int) = "A journey through the night $album"
    private fun artist(album: Int) = "Workflow artist $album"
    private fun artistId(album: Int) = "UCworkflowartist%08d".format(album)
    private fun browse(id: String, type: String) = obj("browseEndpoint" to obj("browseId" to id,
        "browseEndpointContextSupportedConfigs" to obj("browseEndpointContextMusicConfig" to obj("pageType" to type))))
    private fun endpoint(id: String) = obj("watchEndpoint" to obj("videoId" to id,
        "watchEndpointMusicSupportedConfigs" to obj("watchEndpointMusicConfig" to obj("musicVideoType" to "MUSIC_VIDEO_TYPE_ATV"))))
    private fun thumbs() = obj("thumbnails" to array(listOf(obj("url" to image, "width" to 512, "height" to 512))))
    private fun credit(album: Int) = obj("text" to artist(album), "navigationEndpoint" to browse(artistId(album), "MUSIC_PAGE_TYPE_ARTIST"))
    private fun albumRun(album: Int) = obj("text" to albumName(album), "navigationEndpoint" to browse(albumId(album), "MUSIC_PAGE_TYPE_ALBUM"))
    private fun column(value: JSONObject) = obj("musicResponsiveListItemFlexColumnRenderer" to obj("text" to value))
    private fun row(album: Int, track: Int): JSONObject = obj("musicResponsiveListItemRenderer" to obj(
        "flexColumns" to array(listOf(column(runs(obj("text" to title(album, track), "navigationEndpoint" to endpoint(songId(album, track))))),
            column(runs(credit(album), text(" • "), albumRun(album), text(" • "), text("3:00"))))),
        "fixedColumns" to array(listOf(column(runs(text("3:00"))))),
        "playlistItemData" to obj("videoId" to songId(album, track)),
        "navigationEndpoint" to endpoint(songId(album, track)),
        "thumbnail" to obj("musicThumbnailRenderer" to obj("thumbnail" to thumbs()))))
    private fun sections(contents: List<JSONObject>) = obj("sectionListRenderer" to obj("contents" to array(contents)))
    private fun tabs(content: JSONObject) = obj("tabs" to array(listOf(obj("tabRenderer" to obj("content" to content)))))
    fun reply(operation: String, body: String): String? {
        val request = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        return when (operation) {
            "search" -> {
                val album = Regex("\\d+").find(request.optString("query"))?.value?.toInt()?.coerceIn(1, 12) ?: 1
                obj("contents" to obj("tabbedSearchResultsRenderer" to tabs(sections(listOf(obj("musicShelfRenderer" to obj(
                    "title" to runs(text("Songs")), "contents" to array((1..12).map { row(album, it) }))))))))
            }
            "browse" -> {
                val id = request.optString("browseId")
                val album = id.substringAfterLast('_').toIntOrNull()
                if (album == null || !id.startsWith("MPREb_workflow_")) return null
                obj("contents" to obj("twoColumnBrowseResultsRenderer" to tabs(sections(listOf(obj("musicResponsiveHeaderRenderer" to obj(
                    "buttons" to JSONArray(),
                    "title" to runs(text(albumName(album))), "subtitle" to runs(text("Album"), text(" • "), text("2026")),
                    "straplineTextOne" to runs(credit(album)), "thumbnail" to obj("musicThumbnailRenderer" to obj("thumbnail" to thumbs()))))))).put(
                    "secondaryContents", sections(listOf(obj("musicShelfRenderer" to obj("contents" to array((1..12).map { row(album, it) }))))))))
            }
            "get_queue" -> {
                val ids = request.optJSONArray("videoIds") ?: JSONArray()
                obj("queueDatas" to array((0 until ids.length()).mapNotNull { index ->
                    val id = ids.getString(index)
                    if (!id.startsWith("wf") || id.length != 11) return@mapNotNull null
                    val album = id.substring(2, 6).toInt(); val track = id.substring(6).toInt()
                    obj("content" to obj("playlistPanelVideoRenderer" to obj("videoId" to id, "selected" to false,
                        "title" to runs(text(title(album, track))), "lengthText" to runs(text("3:00")),
                        "longBylineText" to runs(credit(album), text(" • "), albumRun(album)),
                        "shortBylineText" to runs(credit(album)), "thumbnail" to thumbs(), "navigationEndpoint" to endpoint(id))))
                }))
            }
            "player" -> {
                val id = request.optString("videoId")
                if (!id.startsWith("wf") || id.length != 11) return null
                val album = id.substring(2, 6).toInt(); val track = id.substring(6).toInt()
                obj("playabilityStatus" to obj("status" to "OK"),
                    "streamingData" to obj("expiresInSeconds" to 3600, "adaptiveFormats" to array(listOf(obj(
                        "itag" to 140, "url" to "https://workflow.invalid/audio/$id", "mimeType" to "audio/wav",
                        "bitrate" to 128000, "contentLength" to audioBytes, "quality" to "tiny", "audioSampleRate" to 8000)))),
                    "videoDetails" to obj("videoId" to id, "title" to title(album, track), "author" to artist(album),
                    "viewCount" to "1", "thumbnail" to thumbs(),
                    "channelId" to artistId(album), "lengthSeconds" to "180",
                    "shortDescription" to "Provided to YouTube by Workflow Fixture\n\n${title(album, track)} · ${artist(album)}\n\n${albumName(album)}\n\nAuto-generated by YouTube."))
            }
            else -> return null
        }.put("responseContext", JSONObject()).toString()
    }
}
