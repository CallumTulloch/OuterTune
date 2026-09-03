package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

internal fun mergeResolvedMetadata(
    original: MediaMetadata,
    resolved: MediaMetadata,
): MediaMetadata = original.copy(
    artists = original.artists.map { artist ->
        if (artist.id != null) {
            artist
        } else {
            resolved.artists.firstOrNull { it.name == artist.name } ?: artist
        }
    },
    duration = original.duration.takeIf { it > 0 } ?: resolved.duration,
    thumbnailUrl = original.thumbnailUrl ?: resolved.thumbnailUrl,
    album = original.album ?: resolved.album,
)

internal fun downloadIdsToClear(
    indexedMediaIds: Set<String>,
    cacheMediaIds: Set<String>,
    databaseDownloadIds: Set<String>,
    deletedMainMediaIds: Set<String>,
    remainingCustomIds: Set<String>,
): Set<String> = (indexedMediaIds + cacheMediaIds + databaseDownloadIds + deletedMainMediaIds) -
        remainingCustomIds

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1
        if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }

        songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
            return@Factory dataSpec.withUri(it.first.toUri())
        }

        val playbackData = resolvePlaybackData(mediaId)
        dataSpec.withUri(playbackData.streamUrl.toUri())
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
        }
    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    private val downloadStateMutex = Mutex()
    private val downloadStateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )
    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    private suspend inline fun <T> withDownloadProcessing(crossinline block: suspend () -> T): T =
        downloadStateMutex.withLock {
            isProcessingDownloads.value = true
            try {
                block()
            } finally {
                isProcessingDownloads.value = false
            }
        }

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    /**
     * Removes internal Media3 downloads and app-managed files in the main external download
     * directory while retaining files imported through extra directories.
     */
    suspend fun clearAllDownloads(): Boolean = withDownloadProcessing {
        try {
            val indexedMediaIds = mutableSetOf<String>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            try {
                while (cursor.moveToNext()) {
                    indexedMediaIds += cursor.download.request.id
                }
            } finally {
                cursor.close()
            }

            val cacheMediaIds = downloadCache.keys.toSet()
            val databaseDownloadIds = database.downloadedOrQueuedSongs().first().mapTo(mutableSetOf()) {
                it.song.id
            }

            // Let Media3 cancel active jobs and remove both its index entries and owned cache data.
            withContext(Dispatchers.Main) {
                downloadManager.removeAllDownloads()
            }

            // Media3 cannot remove orphaned cache entries that no longer exist in its index.
            (cacheMediaIds - indexedMediaIds).forEach { mediaId ->
                downloadCache.removeResource(mediaId)
            }

            val mainDeletion = localMgr.deleteMainDownloads()
            val remainingCustomIds = localMgr.getAvailableFiles().keys.toSet()
            val idsToClear = downloadIdsToClear(
                indexedMediaIds = indexedMediaIds,
                cacheMediaIds = cacheMediaIds,
                databaseDownloadIds = databaseDownloadIds,
                deletedMainMediaIds = mainDeletion.deletedMediaIds,
                remainingCustomIds = remainingCustomIds,
            )

            database.awaitTransaction {
                idsToClear.forEach { removeDownloadSong(it) }
            }
            downloads.update { current ->
                current.filterKeys(remainingCustomIds::contains)
            }

            mainDeletion.failedMediaIds.isEmpty()
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to clear downloads", throwable)
            reportException(throwable)
            false
        }
    }

    fun download(songs: List<MediaMetadata>) {
        prepareDownloads(songs)
    }

    fun download(song: MediaMetadata) {
        prepareDownloads(listOf(song))
    }

    fun download(song: SongEntity) {
        CoroutineScope(dlCoroutine).launch {
            val metadata = database.song(song.id).first()?.toMediaMetadata()
            if (metadata != null) {
                prepareAndDownload(listOf(metadata))
            } else {
                downloadSong(song.id, song.title)
            }
        }
    }

    private fun prepareDownloads(songs: List<MediaMetadata>) {
        CoroutineScope(dlCoroutine).launch {
            prepareAndDownload(songs)
        }
    }

    private suspend fun prepareAndDownload(songs: List<MediaMetadata>) {
        val songsById = songs.associateBy(MediaMetadata::id)
        val missingAlbumIds = songs.filter { !it.isLocal && it.album == null }.map(MediaMetadata::id)
        val queueSongs = missingAlbumIds.chunked(YouTube.MAX_GET_QUEUE_SIZE).flatMap { videoIds ->
            YouTube.queue(videoIds = videoIds).onFailure {
                reportException(it)
                Log.w(TAG, "Unable to resolve album metadata for ${videoIds.size} download(s)", it)
            }.getOrDefault(emptyList())
        }.associateBy(SongItem::id)

        songsById.values.forEach { original ->
            val metadata = queueSongs[original.id]?.let { resolved ->
                mergeResolvedMetadata(original, resolved.toMediaMetadata())
            } ?: original
            database.awaitTransaction {
                insert(metadata)
            }
            CoroutineScope(dlCoroutine).launch {
                downloadSong(metadata.id, metadata.title)
            }
        }
    }

    private fun downloadSong(id: String, title: String) {
        if (downloads.value[id] != null) return
        runCatching {
            val playbackData = resolvePlaybackData(id)
            val contentLength = playbackData.format.contentLength
                ?: error("Missing content length for $id")
            val downloadRequest = DownloadRequest.Builder(id, id.toUri())
                .setCustomCacheKey(id)
                .setData(title.toByteArray())
                .setByteRange(0, contentLength)
                .build()
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false
            )
        }.onFailure {
            reportException(it)
            Log.e(TAG, "Unable to resolve download: $id", it)
        }
    }

    private fun resolvePlaybackData(mediaId: String): YTPlayerUtils.PlaybackData {
        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrThrow()
        val format = playbackData.format

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength!!,
                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                    playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }

        // Keep the signed stream URL unchanged. Media3 sends byte ranges through the
        // HTTP Range header; appending query parameters can invalidate the CDN request.
        songUrlCache[mediaId] = playbackData.streamUrl to
                (System.currentTimeMillis() + playbackData.streamExpiresInSeconds * 1000L)
        return playbackData
    }

    fun resumeDownloadsOnStart() {
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }


// Deletes from custom dl

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: SongItem) = deleteSong(song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    private fun deleteSong(id: String): Boolean {
        val deleted = localMgr.deleteFile(id)
        if (!deleted) return false
        downloads.update { map ->
            map.toMutableMap().apply {
                remove(id)
            }
        }

        runBlocking {
            database.song(id).first()?.song?.copy(localPath = null)
            database.updateDownloadStatus(id, null)
        }
        return true
    }

    /**
     * Retrieve song from cache, and delete it from cache afterwards
     */
    fun getFromCache(cache: SimpleCache, mediaId: String): ByteArray? {
        val spans: Set<CacheSpan> = cache.getCachedSpans(mediaId)
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        try {
            for (span in spans) {
                val file: File? = span.file
                FileInputStream(file).use { fis ->
                    fis.copyTo(output)
                }
            }
            return output.toByteArray()
        } catch (e: IOException) {
            reportException(e)
        } finally {
            output.close()
        }
        return null
    }

    /**
     * Migrated existing downloads from the download cache to the new system in external storage
     */
    suspend fun migrateDownloads() = withDownloadProcessing {
        var runs = 0
        try {
            // "skeleton" of old download manager to access old download data
            val dataSourceFactory = ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient.Builder()
                                .proxy(YouTube.proxy)
                                .build()
                        )
                    )
            ) { dataSpec ->
                return@Factory dataSpec
            }

            val downloadManager: DownloadManager = DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run)
            ).apply {
                maxParallelDownloads = 3
            }

            // actual migration code
            val downloadedSongs = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            try {
                while (cursor.moveToNext()) {
                    downloadedSongs[cursor.download.request.id] = cursor.download
                }
            } finally {
                cursor.close()
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val songFromCache = getFromCache(downloadCache, s.key)
                if (songFromCache != null) {
                    downloadCache.removeResource(s.key)
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = songFromCache,
                        displayName = runBlocking { database.song(s.key).first()?.title ?: "" })
                }
            }
            scanDownloadsLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
        }
    }


    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() = withDownloadProcessing {
        rescanDownloadsLocked()
    }

    private suspend fun rescanDownloadsLocked() {
        Log.i(TAG, "+rescanDownloads()")
        val dbDownloads = database.downloadedOrQueuedSongs().first()
        val result = mutableMapOf<String, LocalDateTime>()

        // get missing files not in custom downloads or in internal downloads, remove them
        val missingFiles =
            localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
        Log.d(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
        val cursor = downloadManager.downloadIndex.getDownloads()
        try {
            while (cursor.moveToNext()) {
                missingFiles.removeIf { it.id == cursor.download.request.id }
            }
        } finally {
            cursor.close()
        }
        Log.d(
            TAG,
            "Found ${missingFiles.size}/${dbDownloads.size} song not in custom download directories + internal cache. Removing these files now"
        )

        database.awaitTransaction {
            missingFiles.forEach {
                Log.v(TAG, "Shedding: [${it.id}] ${it.song.title}")
                removeDownloadSong(it.song.id)
            }
        }

        // new files
        val availableDownloads = dbDownloads.minus(missingFiles)
        availableDownloads.forEach { s ->
            result[s.song.id] = s.song.dateDownload!! // sql should cover our butts
        }

        downloads.value = result
        Log.i(TAG, "-rescanDownloads()")
    }

    /**
     * Reconciles Media3's download index into the music database without clearing existing
     * download records first.
     *
     * The DownloadManager listener normally keeps both stores in sync. This is a safe manual
     * fallback for a completion event that happened before the song insert committed or while
     * the listener was not active. Downloads absent from the Media3 index are intentionally left
     * untouched because they may be managed by a custom download directory.
     */
    suspend fun reconcileDownloadIndex(): Boolean = withDownloadProcessing {
        try {
            val completedDownloads = mutableMapOf<String, LocalDateTime>()
            val cursor = downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED)
            try {
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    completedDownloads[download.request.id] =
                        completedAtForDownloadState(download.state, download.updateTimeMs)
                            ?: continue
                }
            } finally {
                cursor.close()
            }

            database.awaitTransaction {
                completedDownloads.forEach { (mediaId, completedAt) ->
                    updateMedia3DownloadStatus(mediaId, completedAt)
                }
            }

            downloads.update { current ->
                current.toMutableMap().apply {
                    completedDownloads.forEach { (mediaId, completedAt) ->
                        this[mediaId] = completedAt
                    }
                }
            }
            true
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to reconcile Media3 downloads", throwable)
            reportException(throwable)
            false
        }
    }


    /**
     * Scan and import downloaded songs from main and extra directories.
     *
     * This is intended for re-importing existing songs (ex. songs get moved, after restoring app backup), thus all
     * songs will already need to exist in the database.
     */
    suspend fun scanDownloads() = withDownloadProcessing {
        scanDownloadsLocked()
    }

    private suspend fun scanDownloadsLocked() {
        Log.i(TAG, "+scanDownloads()")

//            val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_DL)
        database.removeAllDownloadedSongs()
        val timeNow = LocalDateTime.now()

        // add custom downloads
        val availableFiles = localMgr.getAvailableFiles(false)
        database.awaitTransaction {
            availableFiles.forEach { f ->
                try {
                    val file = fileFromUri(context, f.value)
                    if (file == null) throw (InvalidAudioFileException("Hello darkness my old friend"))
                    // TODO: validate files in download folder
//                        val format: FormatEntity? = scanner.advancedScan(f.value).format
//                        if (format != null) {
//                            database.upsert(format)
//                        }
                    registerDownloadSong(f.key, timeNow, file.absolutePath)

                } catch (e: InvalidAudioFileException) {
                    reportException(e)
                }
            }
        }
//            LocalMediaScanner.destroyScanner(SCANNER_OWNER_DL)
        Log.d(TAG, "Registered ${availableFiles.size} files from custom downloads")

        // add internal downloads
        val cursor = downloadManager.downloadIndex.getDownloads()
        var count = 0
        try {
            database.awaitTransaction {
                while (cursor.moveToNext()) {
                    updateDownloadStatus(cursor.download.request.id, stateToLocalDateTime(cursor.download))
                    count++
                }
            }
        } finally {
            cursor.close()
        }
        Log.d(TAG, "Registered $count files from internal downloads")
        Log.d(TAG, "Database registration complete, triggering map registry rebuild")
        rescanDownloadsLocked()
        Log.i(TAG, "-scanDownloads()")
    }

    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()
    }


    init {
        Log.i(TAG, "DownloadUtil init")
        // TODO: make sure db is update when download is queued
        downloadStateScope.launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    downloadStateScope.launch {
                        try {
                            downloadStateMutex.withLock {
                                val state = stateToLocalDateTime(download)
                                val hasCustomDownload = database.song(download.request.id).first()
                                    ?.song?.localPath != null
                                database.awaitTransaction {
                                    updateMedia3DownloadStatus(
                                        songId = download.request.id,
                                        dateDownload = completedAtForDownloadState(
                                            download.state,
                                            download.updateTimeMs,
                                        ),
                                    )
                                }

                                downloads.update { map ->
                                    map.toMutableMap().apply {
                                        if (state == STATE_INVALID && !hasCustomDownload) {
                                            Log.w(
                                                TAG,
                                                "Invalid download state for ${download.request.id}. Removing download",
                                            )
                                            remove(download.request.id)
                                        } else if (state != STATE_INVALID) {
                                            set(download.request.id, state)
                                        }
                                    }
                                }
                            }
                        } catch (throwable: CancellationException) {
                            throw throwable
                        } catch (throwable: Throwable) {
                            Log.e(TAG, "Failed to persist download state for ${download.request.id}", throwable)
                            reportException(throwable)
                        }
                    }
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloadStateScope.launch {
                        try {
                            downloadStateMutex.withLock {
                                val hasCustomDownload = database.song(download.request.id).first()
                                    ?.song?.localPath != null
                                database.awaitTransaction {
                                    updateMedia3DownloadStatus(download.request.id, null)
                                }
                                if (!hasCustomDownload) {
                                    downloads.update { map ->
                                        map.toMutableMap().apply {
                                            remove(download.request.id)
                                        }
                                    }
                                }
                            }
                        } catch (throwable: CancellationException) {
                            throw throwable
                        } catch (throwable: Throwable) {
                            Log.e(TAG, "Failed to persist removal for ${download.request.id}", throwable)
                            reportException(throwable)
                        }
                    }
                }
            }
        )
    }
}

fun stateToLocalDateTime(download: Download): LocalDateTime {
    return when (download.state) {
        Download.STATE_COMPLETED -> {
            Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        }

        Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> STATE_DOWNLOADING
        else -> STATE_INVALID
    }
}

internal fun completedAtForDownloadState(state: Int, updateTimeMs: Long): LocalDateTime? =
    if (state == Download.STATE_COMPLETED) {
        Instant.ofEpochMilli(updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
    } else {
        null
    }
