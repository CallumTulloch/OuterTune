package com.dd3boh.outertune.repositories

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import androidx.datastore.preferences.core.edit
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.*
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.menu.playerAlbumId
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response as PipeResponse

/** Real captured provider structure through HTTP, production album resolution and persistent Room. */
class AlbumSourceIdentityDeviceTest {
    @Test(timeout = 120_000)
    fun capturedAlbumKeepsPlaybackLibraryAndDownloadIdentityAcrossReopenAndFailedRefresh(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "album-source-identity-test.db"
        context.deleteDatabase(filename)
        var internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
        val transport = AlbumSourceFixtureTransport()
        try {
            val source = YouTube.queue(videoIds = listOf(SOURCE_ID), notifyMetadata = false).getOrThrow().single()
            assertEquals(SOURCE_ID, source.id)
            assertEquals(295, source.duration)
            assertEquals(ALBUM_ID, source.toMediaMetadata().playerAlbumId(null))
            // Search playback must finish YouTubeQueue initialization, not merely play its preload.
            val queueStatus = YouTubeQueue(WatchEndpoint(SOURCE_ID), source.toMediaMetadata()).getInitialStatus()
            assertEquals(listOf(SOURCE_ID), queueStatus.items.map { it.id })
            assertEquals(295, queueStatus.items.single().duration)
            assertEquals(ALBUM_ID, queueStatus.items.single().album?.id)
            assertEquals(0, queueStatus.mediaItemIndex)
            assertTrue(YouTube.searchSuggestions("Billie Jean").getOrThrow().queries.isEmpty())
            val album = YouTube.album(ALBUM_ID, notifyMetadata = false).getOrThrow()
            assertFalse(album.hasUnresolvedTrackSources)
            assertEquals(CANONICAL_IDS, album.songs.map { it.id })
            assertEquals(295, album.songs.single { it.id == SOURCE_ID }.duration)
            assertFalse(album.songs.any { it.id == VIDEO_ID })

            var database = MusicDatabase(internal)
            // This is the same album insertion and song update path used by AlbumViewModel/UI.
            database.insert(source.toMediaMetadata())
            database.insert(album)
            val savedAt = LocalDateTime.of(2026, 9, 30, 15, 0)
            database.update(database.song(SOURCE_ID).first()!!.song.copy(
                inLibrary = savedAt, liked = true, likedDate = savedAt,
            ))
            database.update(database.albumById(ALBUM_ID)!!.copy(bookmarkedAt = savedAt))
            database.insert(PlaylistEntity(id = SAVED_PLAYLIST, name = "Album source identity", isLocal = true))
            database.insert(PlaylistSongMap(playlistId = SAVED_PLAYLIST, songId = SOURCE_ID, position = 0))
            // Exercise the production Media3 completion mapping, without claiming a real download.
            database.updateMedia3DownloadStatus(SOURCE_ID, savedAt.plusMinutes(1))
            database.upsert(FormatEntity(SOURCE_ID, 140, "audio/wav", "pcm", 128_000, 8_000,
                contentLength = AUDIO_BYTES.toLong()))
            assertPersisted(database, savedAt)

            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            database = MusicDatabase(internal)
            assertPersisted(database, savedAt)

            transport.playlistMode = PlaylistMode.FAIL
            val failed = YouTube.album(ALBUM_ID, notifyMetadata = false)
            assertTrue("Failed canonical refresh must remain a failure", failed.isFailure)
            failed.onSuccess { database.update(database.albumById(ALBUM_ID)!!, it) }
            assertPersisted(database, savedAt)

            transport.playlistMode = PlaylistMode.HEADER_ONLY
            val unresolved = YouTube.album(ALBUM_ID, notifyMetadata = false).getOrThrow()
            assertTrue(unresolved.hasUnresolvedTrackSources)
            assertTrue("Fixture retains the real OMV shelf fallback", unresolved.songs.any { it.id == VIDEO_ID })
            database.update(database.albumById(ALBUM_ID)!!, unresolved)
            assertPersisted(database, savedAt)

            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            assertPersisted(MusicDatabase(internal), savedAt)
            Log.i(TAG, "ROUNDTRIP_OK id=$SOURCE_ID duration=295 tracks=9 failedRefreshPreserved=true")
        } finally {
            transport.close()
            internal.close()
            context.deleteDatabase(filename)
        }
    }

    /**
     * Opt-in ordinary UI proof on a disposable emulator. Start with albumSourceUi=true, wait for
     * READY, search Billie Jean, play the 4:55 audio, open its album from player, save and download
     * that row, then create files/album-source-stop. Root captures the album-row playing marker.
     * Playback bytes are silent, but HTTP parsing, UI, cache and download production paths are real.
     */
    @Test(timeout = 900_000)
    fun normalPlayerToAlbumUiUsesCanonicalAudioAndDownload(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("albumSourceUi") == "true")
        runNormalUi(loadingRegression = false)
    }

    /**
     * Opt-in ordinary AlbumScreen regression on a disposable emulator, with no UI-test dependency.
     * albumLoadingUi=true seeds the actual production DB with one incomplete remote album row.
     * The host operates the UI and writes files/album-loading-mode: hold, fail, empty or complete.
     * While a request is held, capture/check within 20 seconds (the production VM times out at 30).
     * Save the host's ordinary uiautomator dump as files/album-loading-{phase}.xml, then touch
     * files/album-loading-check-{loading,failure,empty,complete,cached,single,local} while visible.
     * The test checks the real accessibility XML and DB state, saves a text snapshot and
     * acknowledges CHECK_OK. Loading/failure/empty/complete/cached are required; single/local are
     * optional extra fixtures under Library albums. Touch files/album-loading-stop when finished.
     */
    @Test(timeout = 900_000)
    fun normalAlbumLoadingUiDoesNotExposeThePlayingSongAsACompleteAlbum(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("albumLoadingUi") == "true")
        runNormalUi(loadingRegression = true)
    }

    private suspend fun runNormalUi(loadingRegression: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, MetadataLanguageTestEntryPoint::class.java)
        val stop = File(context.filesDir, if (loadingRegression) "album-loading-stop" else "album-source-stop").apply { delete() }
        val loadingMode = if (loadingRegression) File(context.filesDir, "album-loading-mode").apply { writeText("hold") } else null
        if (loadingRegression) LOADING_CHECKS.forEach { phase ->
            File(context.filesDir, "album-loading-check-$phase").delete()
            File(context.filesDir, "album-loading-checked-$phase").delete()
            File(context.filesDir, "album-loading-$phase.xml").delete()
        }
        val cover = File(context.cacheDir, "album-source-cover.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff27213d.toInt())
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val transport = AlbumSourceFixtureTransport("file://${cover.absolutePath}", loadingMode)
        NewPipeUtils.hashCode()
        val previousDownloader = NewPipe.getDownloader()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): PipeResponse = throw IOException("Offline album identity fixture")
        })
        val statusField = YTPlayerUtils.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        val previousStatus = statusField.get(YTPlayerUtils)
        statusField.set(YTPlayerUtils, okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "album-source.invalid")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Cached fixture")
                .body(ByteArray(0).toResponseBody()).build()
        }.build())
        var activity: MainActivity? = null
        try {
            context.dataStore.edit {
                it[OobeStatusKey] = OOBE_VERSION
                it[ContentCountryKey] = "JP"
                it[ContentLanguageKey] = "ja"
                it[PreferEnglishOriginalKey] = false
                it.remove(InnerTubeCookieKey)
                it.remove(VisitorDataKey)
                it.remove(DataSyncIdKey)
                it[AutoLoadMoreKey] = false
                if (loadingRegression) it[AutomaticScannerKey] = false
                it[MaxSongCacheSizeKey] = 128
            }
            lateinit var downloads: DownloadUtil
            instrumentation.runOnMainSync {
                downloads = entry.downloadUtil()
                downloads.downloadManager.removeDownload(SOURCE_ID)
            }
            withTimeout(20_000) {
                while (downloads.downloadManager.downloadIndex.getDownload(SOURCE_ID) != null) delay(100)
            }
            cacheAudio(downloads, silentWav())
            if (loadingRegression) seedLoadingAlbums(entry.database(), cover)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            if (loadingRegression) {
                // Same offline-UI override as WorkflowReplayDeviceTest. All HTTP stays intercepted.
                instrumentation.runOnMainSync {
                    val observer = activity!!.connectivityObserver
                    observer.unregister()
                    val monitor = observer.javaClass.getDeclaredField("monitor").apply { isAccessible = true }.get(observer)
                    val state = monitor.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(monitor)
                    @Suppress("UNCHECKED_CAST")
                    (state as MutableStateFlow<Boolean>).value = true
                }
                Log.i(LOADING_TAG, "READY search=Billie_Jean source=$SOURCE_ID mode=hold stubTracks=1 stop=album-loading-stop")
                verifyLoadingScreens(entry.database(), transport, stop)
                return
            }
            Log.i(TAG, "READY search=Billie_Jean source=$SOURCE_ID album=$ALBUM_ID duration=295 stop=album-source-stop")
            withTimeout(800_000) { while (!stop.exists()) delay(500) }

            assertTrue("Album page must be requested through the UI", (transport.calls["browse:$ALBUM_ID"] ?: 0) > 0)
            assertTrue("Canonical playlist must be requested through the UI", (transport.calls["browse:VL$PLAYLIST_ID"] ?: 0) > 0)
            assertTrue("Search must use the real captured response", (transport.calls["search:"] ?: 0) > 0)
            assertTrue("Search playback must initialize its queue", (transport.calls["next:"] ?: 0) > 0)
            lateinit var controllerFuture: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                controllerFuture = MediaController.Builder(context,
                    SessionToken(context, ComponentName(context, MusicService::class.java))).buildAsync()
            }
            val controller = controllerFuture.get(10, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                try {
                    assertEquals("Player must keep the canonical audio after album navigation", SOURCE_ID,
                        controller.currentMediaItem?.mediaId)
                } finally {
                    controller.release()
                }
            }
            val database = entry.database()
            assertEquals(CANONICAL_IDS, database.albumSongs(ALBUM_ID).first().map { it.id })
            val stored = database.song(SOURCE_ID).first()!!.song
            assertEquals(295, stored.duration)
            assertNotNull("Save the canonical album row in the UI", stored.inLibrary)
            withTimeout(20_000) {
                while (downloads.downloadManager.downloadIndex.getDownload(SOURCE_ID)?.state != Download.STATE_COMPLETED) delay(100)
            }
            val download = downloads.downloadManager.downloadIndex.getDownload(SOURCE_ID)!!
            assertEquals(SOURCE_ID, download.request.id)
            assertEquals(SOURCE_ID, download.request.customCacheKey)
            assertTrue(downloads.downloadCache.isCached(SOURCE_ID, 0, AUDIO_BYTES.toLong()))
            assertNull("Album flow must not download the MV identity", downloads.downloadManager.downloadIndex.getDownload(VIDEO_ID))
            Log.i(TAG, "UI_OK source=$SOURCE_ID duration=295 tracks=9 saved=true downloaded=true calls=${transport.calls}")
        } finally {
            if (loadingRegression) {
                loadingMode?.writeText("complete")
                activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            }
            transport.close()
            statusField.set(YTPlayerUtils, previousStatus)
            NewPipe.init(previousDownloader)
        }
    }

    private suspend fun seedLoadingAlbums(database: MusicDatabase, cover: File) {
        check(database.albumById(ALBUM_ID) == null) {
            "Use a disposable clean app DB: the Thriller fixture must not already be complete"
        }
        val source = YouTube.queue(videoIds = listOf(SOURCE_ID), notifyMetadata = false).getOrThrow().single()
        database.awaitTransaction {
            insert(source.toMediaMetadata())
            val now = LocalDateTime.now()
            insert(AlbumEntity(SINGLE_ALBUM_ID, title = SINGLE_ALBUM_TITLE, songCount = 1, duration = 295,
                thumbnailUrl = cover.absolutePath, bookmarkedAt = now, hasTrackList = true))
            insert(SongEntity(SINGLE_SONG_ID, SINGLE_SONG_TITLE, duration = 295, localPath = null,
                albumId = SINGLE_ALBUM_ID, albumName = SINGLE_ALBUM_TITLE, inLibrary = now))
            insert(SongAlbumMap(SINGLE_SONG_ID, SINGLE_ALBUM_ID, 0))
            // Local imports legitimately keep hasTrackList=false: the screen must use isLocal too.
            insert(AlbumEntity(LOCAL_ALBUM_ID, title = LOCAL_ALBUM_TITLE, songCount = 1, duration = 295,
                thumbnailUrl = cover.absolutePath, bookmarkedAt = now, isLocal = true, hasTrackList = false))
            val audio = File(cover.parentFile, "album-loading-local.wav").apply { writeBytes(silentWav()) }
            insert(SongEntity(LOCAL_SONG_ID, LOCAL_SONG_TITLE, duration = 295, localPath = audio.absolutePath,
                albumId = LOCAL_ALBUM_ID, albumName = LOCAL_ALBUM_TITLE, inLibrary = now, isLocal = true))
            insert(SongAlbumMap(LOCAL_SONG_ID, LOCAL_ALBUM_ID, 0))
        }
        assertFalse(database.albumById(ALBUM_ID)!!.hasTrackList)
        assertEquals(listOf(SOURCE_ID), database.albumSongs(ALBUM_ID).first().map { it.id })
    }

    private suspend fun verifyLoadingScreens(database: MusicDatabase, transport: AlbumSourceFixtureTransport, stop: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val checked = mutableSetOf<String>()
        var completeBrowseCount = 0
        withTimeout(800_000) {
            while (!stop.exists()) {
                for (phase in LOADING_CHECKS) {
                    val marker = File(context.filesDir, "album-loading-check-$phase")
                    if (!marker.exists() || phase in checked) continue
                    val snapshot = screenLabels(File(context.filesDir, "album-loading-$phase.xml"))
                    val nodes = snapshot.labels
                    File(context.filesDir, "album-loading-$phase.txt").writeText(nodes.joinToString("\n") {
                        "enabled=${it.enabled} top=${it.top} bottom=${it.bottom} text=${it.text}"
                    })
                    val texts = nodes.map { it.text }
                    val play = context.getString(R.string.play)
                    val shuffle = context.getString(R.string.shuffle)
                    val retry = context.getString(R.string.retry)
                    val oneSong = context.resources.getQuantityString(R.plurals.n_song, 1, 1)
                    val nineSongs = context.resources.getQuantityString(R.plurals.n_song, 9, 9)
                    if (phase in setOf("loading", "failure", "empty")) {
                        assertFalse("An incomplete online album must remain a stub", database.albumById(ALBUM_ID)!!.hasTrackList)
                        assertEquals(listOf(SOURCE_ID), database.albumSongs(ALBUM_ID).first().map { it.id })
                        assertTrue("Keep the known album header visible: $texts", texts.any { it == "Thriller" })
                        assertFalse("The partial one-row cache must not appear as the album's track count: $texts",
                            texts.any { it.contains(oneSong) })
                        // The selected track remains visible in the collapsed player at the bottom.
                        // It must never also appear in the album's upper track-list area.
                        val playingLabels = nodes.filter { it.text.contains("Billie Jean") }
                        val miniPlayerTop = snapshot.bottom - (180 * context.resources.displayMetrics.density).toInt()
                        assertEquals("Only the mini-player may show the playing song: $nodes", 1, playingLabels.size)
                        assertTrue("The incomplete album must not expose the playing song as a track row: $playingLabels",
                            playingLabels.single().top >= miniPlayerTop)
                        for (label in listOf(play, shuffle)) {
                            assertTrue("The album action must be visible and disabled: $label; $nodes",
                                nodes.any { it.text == label && !it.enabled })
                        }
                        if (phase == "loading") {
                            assertTrue("The real album response must still be pending", transport.heldPlaylistRequests.get() > 0)
                            assertFalse("Pending must not already show failure", texts.contains(retry))
                        }
                        else assertTrue("A failed/empty response must offer retry: $texts", texts.contains(retry))
                    } else if (phase == "complete" || phase == "cached") {
                        assertTrue(database.albumById(ALBUM_ID)!!.hasTrackList)
                        assertEquals(CANONICAL_IDS, database.albumSongs(ALBUM_ID).first().map { it.id })
                        assertTrue("Full album count must be visible: $texts", texts.any { it.contains(nineSongs) })
                        assertTrue("A complete album can be played", nodes.any { it.text == play && it.enabled })
                        if (phase == "complete") completeBrowseCount = transport.calls["browse:$ALBUM_ID"] ?: 0
                        else {
                            assertTrue("Check complete before revisiting", "complete" in checked)
                            assertTrue("Revisit must perform a fresh album request",
                                (transport.calls["browse:$ALBUM_ID"] ?: 0) > completeBrowseCount)
                            assertTrue("Cached tracks must remain visible while refresh is pending",
                                transport.heldPlaylistRequests.get() > 0)
                        }
                    } else {
                        val local = phase == "local"
                        val albumId = if (local) LOCAL_ALBUM_ID else SINGLE_ALBUM_ID
                        val title = if (local) LOCAL_ALBUM_TITLE else SINGLE_ALBUM_TITLE
                        val songTitle = if (local) LOCAL_SONG_TITLE else SINGLE_SONG_TITLE
                        assertEquals(local, database.albumById(albumId)!!.isLocal)
                        assertEquals(!local, database.albumById(albumId)!!.hasTrackList)
                        assertTrue("Expected fixture album screen: $texts", texts.contains(title))
                        assertTrue("A legitimate one-track album must display its track: $texts", texts.contains(songTitle))
                        assertTrue(texts.any { it.contains(oneSong) })
                        assertTrue(nodes.any { it.text == play && it.enabled })
                    }
                    checked += phase
                    File(context.filesDir, "album-loading-checked-$phase").writeText("OK")
                    Log.i(LOADING_TAG, "CHECK_OK phase=$phase tracks=${database.albumSongs(ALBUM_ID).first().size}")
                }
                delay(100)
            }
        }
        assertTrue("Required UI checkpoints missing: $checked",
            checked.containsAll(listOf("loading", "failure", "empty", "complete", "cached")))
        Log.i(LOADING_TAG, "UI_OK checkpoints=$checked calls=${transport.calls}")
    }

    private data class ScreenLabel(val text: String, val enabled: Boolean, val top: Int, val bottom: Int)
    private data class ScreenSnapshot(val labels: List<ScreenLabel>, val bottom: Int)

    /** The host owns UiAutomation; consuming its dump avoids competing accessibility connections. */
    private fun screenLabels(file: File): ScreenSnapshot {
        check(file.isFile) { "Save the current uiautomator XML before its checkpoint: ${file.name}" }
        val labels = mutableListOf<ScreenLabel>()
        val parentsEnabled = mutableListOf<Boolean>()
        val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
        var bottom = 0
        file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val enabled = (parentsEnabled.lastOrNull() ?: true) && parser.getAttributeValue(null, "enabled") != "false"
                    parentsEnabled += enabled
                    val bounds = boundsPattern.matchEntire(parser.getAttributeValue(null, "bounds").orEmpty())
                    val top = bounds?.groupValues?.get(2)?.toInt() ?: 0
                    val nodeBottom = bounds?.groupValues?.get(4)?.toInt() ?: 0
                    bottom = maxOf(bottom, nodeBottom)
                    parser.getAttributeValue(null, "text")?.takeIf { it.isNotBlank() }?.let {
                        labels += ScreenLabel(it, enabled, top, nodeBottom)
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "node") {
                    parentsEnabled.removeAt(parentsEnabled.lastIndex)
                }
                parser.next()
            }
        }
        check(labels.isNotEmpty() && bottom > 0) { "A populated current UI hierarchy is required" }
        return ScreenSnapshot(labels, bottom)
    }

    private suspend fun assertPersisted(database: MusicDatabase, savedAt: LocalDateTime) {
        assertEquals(CANONICAL_IDS, database.albumSongs(ALBUM_ID).first().map { it.id })
        val album = database.albumWithSongs(ALBUM_ID).first()!!
        assertEquals(CANONICAL_IDS, album.songs.map { it.id })
        assertEquals(1, album.downloadCount)
        assertEquals(savedAt, album.album.bookmarkedAt)
        val source = database.song(SOURCE_ID).first()!!
        assertEquals(295, source.song.duration)
        assertEquals(ALBUM_ID, source.song.albumId)
        assertEquals(ALBUM_ID, source.toMediaMetadata().playerAlbumId(source))
        assertEquals(savedAt, source.song.inLibrary)
        assertTrue(source.song.liked)
        assertEquals(savedAt.plusMinutes(1), source.song.dateDownload)
        assertEquals(listOf(SOURCE_ID), database.downloadedSongs().first().map { it.id })
        assertEquals(listOf(SOURCE_ID), database.playlistSongs(SAVED_PLAYLIST).first().map { it.song.id })
        assertEquals(listOf(ALBUM_ID), database.albumIdsForSong(SOURCE_ID))
        assertEquals(AUDIO_BYTES.toLong(), database.format(SOURCE_ID).first()!!.contentLength)
        assertNull("An unresolved refresh must not insert the alternate MV song", database.song(VIDEO_ID).first())
    }

    private fun cacheAudio(downloads: DownloadUtil, audio: ByteArray) {
        val cache = downloads.playerCache
        if (!cache.isCached(SOURCE_ID, 0, audio.size.toLong())) {
            val hole = cache.startReadWrite(SOURCE_ID, 0, audio.size.toLong())
            val sink = CacheDataSink.Factory().setCache(cache).createDataSink()
            sink.open(DataSpec.Builder().setUri(Uri.parse(SOURCE_ID)).setKey(SOURCE_ID).setLength(audio.size.toLong()).build())
            try { sink.write(audio, 0, audio.size) } finally { sink.close(); cache.releaseHoleSpan(hole) }
            cache.applyContentMetadataMutations(SOURCE_ID, ContentMetadataMutations().also {
                ContentMetadataMutations.setContentLength(it, audio.size.toLong())
            })
        }
        assertTrue(cache.isCached(SOURCE_ID, 0, audio.size.toLong()))
    }

    private fun silentWav(): ByteArray = ByteBuffer.allocate(AUDIO_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(AUDIO_BYTES - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(AUDIO_BYTES - 44)
    }.array()

    companion object {
        private const val TAG = "AlbumSourceIdentity"
        private const val LOADING_TAG = "AlbumLoadingUi"
        private val LOADING_CHECKS = listOf("loading", "failure", "empty", "complete", "cached", "single", "local")
        private const val SINGLE_ALBUM_ID = "MPRE_loading_single"
        private const val SINGLE_ALBUM_TITLE = "Loading regression single"
        private const val SINGLE_SONG_ID = "loading0001"
        private const val SINGLE_SONG_TITLE = "Complete single track"
        private const val LOCAL_ALBUM_ID = "LB_loading_local"
        private const val LOCAL_ALBUM_TITLE = "Loading regression local"
        private const val LOCAL_SONG_ID = "LS_loading_local"
        private const val LOCAL_SONG_TITLE = "Local single track"
        private const val SOURCE_ID = "Kr4EQDVETuA"
        private const val VIDEO_ID = "Zi_XLOBDo_Y"
        private const val ALBUM_ID = "MPREb_dqWTncCjkSp"
        private const val PLAYLIST_ID = "OLAK5uy_l1U925dsiDi2DqlG-KCbODG6BaibpxbQE"
        private const val SAVED_PLAYLIST = "LP-album-source-identity"
        private const val AUDIO_BYTES = 44 + 8_000 * 2 * 295
        private val CANONICAL_IDS = listOf("8KWf_-ofYgI", "COSMzAASQj4", "SX5vM6F57_E", "Z85lxckrtzg",
            "kOn-HdEg6AQ", SOURCE_ID, "oqLpko9Gprs", "y32ejtuxSjM", "Eqcw7tLnrd8")
    }

    private enum class PlaylistMode { COMPLETE, HEADER_ONLY, FAIL }

    /** Same test-only HTTP interception pattern as WorkflowReplayDeviceTest; never uses the network. */
    private class AlbumSourceFixtureTransport(
        private val image: String = "https://fixture.invalid/cover.jpg",
        private val loadingMode: File? = null,
    ) : AutoCloseable {
        @Volatile var playlistMode = PlaylistMode.COMPLETE
        val calls = ConcurrentHashMap<String, Int>()
        val heldPlaylistRequests = AtomicInteger()
        private val assets = InstrumentationRegistry.getInstrumentation().context.assets
        private fun load(name: String) = assets.open("album-source-identity/$name.json").bufferedReader().use { it.readText() }
            .replace("https://fixture.invalid/cover.jpg", image)
        private val album = load("album-thriller")
        private val emptyAlbum by lazy {
            val root = JSONObject(album)
            fun emptyShelves(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                        if (key == "musicShelfRenderer" || key == "musicPlaylistShelfRenderer")
                            value.getJSONObject(key).put("contents", JSONArray())
                        else emptyShelves(value.opt(key))
                    }
                    is JSONArray -> (0 until value.length()).forEach { emptyShelves(value.opt(it)) }
                }
            }
            emptyShelves(root)
            root.toString()
        }
        private val playlist = load("thriller-playlist-browse")
        private val search = load("search-billie-jean")
        private val queue = load("queue-billie-source")
        private val next = nextReply()
        private val innerField = YouTube.javaClass.getDeclaredField("innerTube").apply { isAccessible = true }
        private val inner = innerField.get(YouTube)
        private val clientField = inner.javaClass.getDeclaredField("httpClient").apply { isAccessible = true }
        private val previous = clientField.get(inner)
        private val client = HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
            engine { config { addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val input = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                val operation = request.url.encodedPath.substringAfterLast('/')
                val id = input.optString("browseId")
                calls.merge("$operation:$id", 1, Int::plus)
                val payload = when (operation) {
                    "search" -> search
                    "get_search_suggestions" -> "{\"contents\":[]}"
                    "browse" -> when (id) {
                        ALBUM_ID -> if (loadingMode?.readText()?.trim() == "empty") emptyAlbum else album
                        "VL$PLAYLIST_ID" -> playlistReply()
                        else -> null
                    }
                    "get_queue" -> if (input.optJSONArray("videoIds")?.optString(0) == SOURCE_ID) queue else null
                    "next" -> if (input.optString("videoId") == SOURCE_ID) next else null
                    "player" -> if (input.optString("videoId") == SOURCE_ID) playerReply(image) else null
                    else -> null
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (payload == null) 503 else 200)
                    .header("Content-Type", "application/json").message("Captured album fixture")
                    .body((payload ?: "{}").toResponseBody("application/json".toMediaType())).build()
            } } }
        }
        init { clientField.set(inner, client) }
        override fun close() { clientField.set(inner, previous); client.close() }

        private fun playlistReply(): String? {
            loadingMode?.let { control ->
                var mode = control.readText().trim()
                if (mode == "hold") {
                    heldPlaylistRequests.incrementAndGet()
                    try {
                        Log.i(LOADING_TAG, "GATE_WAIT canonicalPlaylist=true releaseWithinSeconds=20")
                        val deadline = SystemClock.elapsedRealtime() + 25_000
                        while (mode == "hold" && SystemClock.elapsedRealtime() < deadline) {
                            Thread.sleep(50)
                            mode = control.readText().trim()
                        }
                        if (mode == "hold") throw IOException("Album loading fixture gate was not released within 25 seconds")
                    } finally {
                        heldPlaylistRequests.decrementAndGet()
                    }
                }
                return when (mode) {
                    "fail" -> null
                    "empty" -> "{\"responseContext\":{}}"
                    "complete" -> playlist
                    else -> throw IOException("Unknown album loading fixture mode: $mode")
                }
            }
            return when (playlistMode) {
                PlaylistMode.COMPLETE -> playlist
                PlaylistMode.HEADER_ONLY -> "{\"responseContext\":{}}"
                PlaylistMode.FAIL -> null
            }
        }

        private fun nextReply(): String {
            // Use the captured ATV renderer, never the captured playlist-next MV counterpart.
            val item = JSONObject(queue).getJSONArray("queueDatas").getJSONObject(0).getJSONObject("content")
            val renderer = item.getJSONObject("playlistPanelVideoRenderer")
            check(renderer.getString("videoId") == SOURCE_ID)
            renderer.put("selected", true)
            val panel = JSONObject().put("contents", JSONArray().put(item))
                .put("currentIndex", 0).put("isInfinite", false).put("numItemsToShow", 1)
            val queueRenderer = JSONObject().put("content", JSONObject().put("playlistPanelRenderer", panel))
            val tab = JSONObject().put("tabRenderer", JSONObject().put("content",
                JSONObject().put("musicQueueRenderer", queueRenderer)))
            val tabs = JSONObject().put("tabs", JSONArray().put(tab))
            val tabbed = JSONObject().put("watchNextTabbedResultsRenderer", tabs)
            val watchNext = JSONObject().put("tabbedRenderer", tabbed)
            val contents = JSONObject().put("singleColumnMusicWatchNextResultsRenderer", watchNext)
            return JSONObject().put("responseContext", JSONObject())
                .put("currentVideoEndpoint", renderer.getJSONObject("navigationEndpoint"))
                .put("contents", contents)
                .toString()
        }

        private fun playerReply(image: String): String {
            val format = JSONObject().put("itag", 140).put("url", "https://album-source.invalid/audio/$SOURCE_ID")
                .put("mimeType", "audio/wav").put("bitrate", 128_000).put("contentLength", AUDIO_BYTES)
                .put("quality", "tiny").put("audioSampleRate", 8_000)
            val streaming = JSONObject().put("expiresInSeconds", 3600).put("adaptiveFormats", JSONArray().put(format))
            val thumbnail = JSONObject().put("url", image).put("width", 64).put("height", 64)
            val thumbnails = JSONObject().put("thumbnails", JSONArray().put(thumbnail))
            val details = JSONObject().put("videoId", SOURCE_ID).put("title", "Billie Jean")
                .put("author", "Michael Jackson").put("viewCount", "1").put("lengthSeconds", "295")
                .put("channelId", "UCoIOOL7QKuBhQHV0oHRkQwg")
                .put("thumbnail", thumbnails)
            return JSONObject().put("responseContext", JSONObject())
                .put("playabilityStatus", JSONObject().put("status", "OK"))
                .put("streamingData", streaming)
                .put("videoDetails", details)
                .toString()
        }
    }
}
