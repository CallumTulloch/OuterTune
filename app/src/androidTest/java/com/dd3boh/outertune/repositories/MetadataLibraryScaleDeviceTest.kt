package com.dd3boh.outertune.repositories

import android.os.Debug
import android.util.Log
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageDetector
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, offline heap regression against a checkpointed, externally supplied library snapshot.
 * Pass -e metadataScaleDatabase /data/local/tmp/metadata-scale-song.db. Use a fresh debug app data
 * directory so the Application's separate production repository does not load a second library.
 * The input is only streamed to a unique test DB; no names, IDs or fixture contents are logged.
 * This checks repository/storage scale, not the native language model or complete UI memory use.
 */
class MetadataLibraryScaleDeviceTest {
    @Test(timeout = 300_000)
    fun repeatedSourceObservationsAndFiftyDetailsCompleteWithinTheSmallHeap(): Unit = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("metadataScaleDatabase")
        assumeTrue("Opt-in: supply metadataScaleDatabase with a checkpointed library copy", !path.isNullOrBlank())
        val vm = java.lang.Runtime.getRuntime()
        assertTrue("This regression requires a managed heap limit of at most 256 MiB; got ${vm.maxMemory()}",
            vm.maxMemory() <= 256L * 1024 * 1024)
        val source = File(requireNotNull(path)).canonicalFile
        assertTrue("The supplied database must be a readable file", source.isFile && source.canRead())
        assertTrue("Supply a checkpointed export, not a live database with pending WAL data",
            !File(source.path + "-wal").let { it.exists() && it.length() > 0 })
        val sourceDigest = digest(source)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "metadata-library-scale-${UUID.randomUUID()}.db"
        val destination = context.getDatabasePath(databaseName)
        assertTrue("The fixture must not overwrite its source", source != destination.canonicalFile)
        destination.parentFile!!.mkdirs()
        var database: MusicDatabase? = null
        val job = SupervisorJob()
        val firstFailure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(job + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            firstFailure.compareAndSet(null, failure)
        })
        val releaseDetails = CompletableDeferred<Unit>()
        val peakHeap = AtomicLong()
        val displayedNames = AtomicReference(emptyMap<OriginalNameTarget, String>())
        val displayedAliases = AtomicReference(emptyMap<OriginalNameTarget, List<String>>())
        fun usedHeap(): Long = (vm.totalMemory() - vm.freeMemory()).also { used ->
            peakHeap.updateAndGet { maxOf(it, used) }
        }
        fun recordMemory(phase: String) {
            Log.i(TAG, "phase=$phase heapBytes=${usedHeap()} peakHeapBytes=${peakHeap.get()} " +
                "maxHeapBytes=${vm.maxMemory()} gcCount=${Debug.getRuntimeStat("art.gc.gc-count")} " +
                "gcTime=${Debug.getRuntimeStat("art.gc.gc-time")}")
        }
        try {
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            val db = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
            database = db
            assertEquals("Fixture schema must match without destructive fallback", MusicDatabase.MUSIC_DATABASE_VERSION,
                db.openHelper.readableDatabase.version)
            val nameCount = scalar(db, "SELECT COUNT(*) FROM metadata_name")
            val publicationCount = scalar(db, "SELECT COUNT(*) FROM metadata_original_publication")
            val publicationBytes = scalar(db,
                "SELECT COALESCE(SUM(LENGTH(CAST(evidenceJson AS BLOB))), 0) FROM metadata_original_publication")
            assertTrue("A small synthetic database cannot exercise the reported failure: names=$nameCount", nameCount >= 30_000)
            assertTrue("The full publication set is required: publications=$publicationCount", publicationCount >= 7_000)
            assertTrue("The full proof payload is required: bytes=$publicationBytes", publicationBytes >= 20_000_000)
            Log.i(TAG, "fixture names=$nameCount publications=$publicationCount publicationBytes=$publicationBytes")

            // Only fifty small replay items are retained by the fixture. Never hold a full DAO
            // snapshot or publication proof list alongside the repository under measurement.
            val songs = replaySongs(db)
            assertEquals("Fixture needs fifty valid, directly observed original songs", DETAIL_COUNT, songs.size)
            val songsById = songs.associateBy { it.id }
            val firstSource = ORIGINAL_NAME_SOURCE_PREFIX + songs.first().id
            val clock = AtomicLong(maxOf(System.currentTimeMillis(),
                scalar(db, "SELECT COALESCE(MAX(observedAt), 0) FROM metadata_name"),
                scalar(db, "SELECT COALESCE(MAX(evaluatedAt), 0) FROM metadata_original_publication")) + 1_000)
            val locale = YouTubeLocale("JP", "ja")
            // These responses already have direct source proof. Preserve it and suppress Main
            // acquisition so detail replies exercise identity checks without external services.
            db.awaitTransaction {
                val savedAt = LocalDateTime.of(2026, 9, 30, 12, 0)
                songs.forEach { song ->
                    // The fifty lookups are explicit test interests, not eager search-card work.
                    // Only this disposable database copy changes; the supplied source stays intact.
                    val existing = songForArtistCredit(song.id)
                    check(existing?.isLocal != true) { "Replay identities must refer to remote tracks" }
                    if (existing == null) {
                        insert(SongEntity(song.id, song.title, localPath = null, inLibrary = savedAt))
                    } else if (existing.inLibrary == null) {
                        update(existing.copy(inLibrary = savedAt))
                    }
                    recordMetadataFetch(MetadataFetchEntity("SONG", song.id, "und",
                        MetadataFetchEntity.SUCCESS, clock.get(), originalMetadataContextKey(locale)))
                }
            }
            val queueReplies = ConcurrentHashMap.newKeySet<String>()
            val observedDetailCount = AtomicInteger()
            val detailEpoch = AtomicLong(Long.MAX_VALUE)
            val displayPublications = AtomicInteger()
            val observedSourceEpoch = AtomicLong()
            lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
            // Exercise the real resolver's version/fingerprint and commit path with deterministic
            // UNKNOWN/OTHER decisions. This detector never loads the native model or a network API.
            val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector { emptyList() })
            val repository = MetadataNameRepository(db, context, MetadataNameRepository.Runtime(
                scope = scope, locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L), now = clock::get,
                contextKey = { CONTEXT },
                preferences = MutableStateFlow(preferencesOf(ContentCountryKey to "JP", ContentLanguageKey to "ja",
                    PreferEnglishOriginalKey to false)),
                observeMetadata = { observer = it },
                publishNames = { names, aliases ->
                    // Match production's retention of one current display/alias map.
                    displayedNames.updateAndGet { if (it == names) it else names }
                    displayedAliases.updateAndGet { if (it == aliases) it else aliases }
                    displayPublications.incrementAndGet()
                },
                onOriginalInputsObserved = { rows ->
                    var latestSource = 0L
                    val details = HashSet<String>()
                    val epoch = detailEpoch.get()
                    for (row in rows) {
                        if (row.source == firstSource) latestSource = maxOf(latestSource, row.observedAt)
                        if (row.kind == "SONG" && row.language == "en" && row.source == "detail" &&
                            row.observedAt >= epoch && row.targetId in songsById) details.add(row.targetId)
                    }
                    observedSourceEpoch.updateAndGet { maxOf(it, latestSource) }
                    observedDetailCount.set(details.size)
                },
                queue = { ids, requested ->
                    releaseDetails.await()
                    val replies = ids.mapNotNull(songsById::get)
                    if (requested.hl == "en") queueReplies.addAll(replies.map { it.id })
                    Result.success(replies)
                },
                album = { _, _ -> Result.failure(IOException("Scale fixture has no album response")) },
                artist = { _, _ -> Result.failure(IOException("Scale fixture has no artist response")) },
                albumContext = { _, _ -> Result.success(emptyList()) },
                albumPage = null, playlistReferences = null,
                main = { _, _ -> Result.failure(IOException("Scale fixture retains existing direct originals")) },
                mainSongReference = { _, _ -> Result.success(null) },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                assessOriginals = resolver::assess,
            ))
            scope.launch { while (isActive) { usedHeap(); delay(1_000) } }
            suspend fun await(phase: String, predicate: () -> Boolean) {
                withTimeout(60_000) {
                    while (true) {
                        firstFailure.get()?.let { throw AssertionError("Repository failed during $phase", it) }
                        if (predicate()) return@withTimeout
                        delay(25)
                    }
                }
            }
            suspend fun reobserveSource(iteration: Int) {
                val now = clock.incrementAndGet()
                // Read just one complete source snapshot, keeping withdrawal timestamps coherent.
                val rows = latestOriginalRows(db.metadataNamesForSource(firstSource))
                assertTrue("The replay source must remain present", rows.isNotEmpty())
                db.awaitTransaction { recordMetadataNames(rows.map { it.copy(observedAt = now) }) }
                await("source observation $iteration") { observedSourceEpoch.get() >= now }
                await("publication commit $iteration") {
                    scalar(db, "SELECT COALESCE(MAX(evaluatedAt), 0) FROM metadata_original_publication " +
                        "WHERE kind = 'SONG' AND targetId = ?", arrayOf(songs.first().id)) >= now
                }
                recordMemory("source-$iteration-committed")
            }
            withTimeout(240_000) {
                recordMemory("before-start")
                repository.start()
                repository.setPlayingSong(songs.first().id)
                withTimeout(60_000) { repository.initialized.first { it } }
                assertTrue("A display snapshot must actually have been published", displayPublications.get() > 0)
                recordMemory("initialized")
                repeat(19) { reobserveSource(it + 1) }

                val epoch = clock.incrementAndGet()
                detailEpoch.set(epoch)
                // A provider-observer packet preserves raw names for the selected saved interests.
                observer(songs, locale.copy(hl = "en"), "detail")
                releaseDetails.complete(Unit)
                await("fifty English queue replies and observed details") {
                    queueReplies.size == DETAIL_COUNT && observedDetailCount.get() == DETAIL_COUNT
                }
                await("fifty detail fetch commits") {
                    songs.all { song -> db.metadataFetch("SONG", song.id, "en", CONTEXT)?.let {
                        it.status == MetadataFetchEntity.SUCCESS && it.updatedAt >= epoch
                    } == true }
                }
                recordMemory("fifty-details-committed")
                // This last commit proves publication still advances after all fifty detail writes.
                reobserveSource(20)
                await("fetch workers drained") { repository.pendingRequestCount == 0 }
                firstFailure.get()?.let { throw AssertionError("Repository background failure", it) }
                assertTrue("The original library must not be truncated", scalar(db, "SELECT COUNT(*) FROM metadata_name") >= nameCount)
                assertTrue("Committed publications must be retained", scalar(db,
                    "SELECT COUNT(*) FROM metadata_original_publication") >= publicationCount)
                recordMemory("complete-20-sources-50-details")
            }
        } finally {
            withContext(NonCancellable) {
                releaseDetails.complete(Unit)
                job.cancelAndJoin()
                database?.close()
                context.deleteDatabase(databaseName)
                assertTrue("The externally supplied original must remain unchanged", sourceDigest.contentEquals(digest(source)))
            }
        }
        // A collector can fail after the final progress assertion but before cancellation joins.
        firstFailure.get()?.let { throw AssertionError("Repository failed before all workers stopped", it) }
    }

    private fun replaySongs(database: MusicDatabase): List<SongItem> {
        val ids = database.openHelper.readableDatabase.query("""
            SELECT DISTINCT n.targetId FROM metadata_name n
            WHERE n.kind = 'SONG' AND n.language = 'und' AND n.source = 'art-track-original:' || n.targetId
                AND EXISTS (SELECT 1 FROM metadata_original_publication p WHERE p.kind = n.kind AND p.targetId = n.targetId)
            ORDER BY n.targetId LIMIT 250
        """.trimIndent()).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return ids.asSequence().mapNotNull { id ->
            latestOriginalRows(database.metadataNamesForSource(ORIGINAL_NAME_SOURCE_PREFIX + id))
                .mapNotNull(::originalCandidate).singleOrNull { it.target.kind == OriginalNameKind.SONG && it.target.id == id }
                ?.let { original -> SongItem(id, original.name, emptyList(), thumbnail = "",
                    endpoint = WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                        WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                            WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV")))) }
        }.take(DETAIL_COUNT).toList()
    }

    private fun scalar(database: MusicDatabase, sql: String, arguments: Array<out Any?> = emptyArray()): Long =
        database.openHelper.readableDatabase.query(sql, arguments).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    companion object {
        private const val TAG = "MetadataLibraryScale"
        private const val CONTEXT = "JP:offline-library-scale"
        private const val DETAIL_COUNT = 50
    }
}
