package com.dd3boh.outertune.repositories

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.*
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.metadata.*
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.pages.AlbumPage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import kotlinx.serialization.json.*

/** Names are a cache of remote identities, not extra songs/artists in the user's library. */
@Singleton
class MetadataNameRepository internal constructor(
    private val database: MusicDatabase,
    private val context: Context,
    private val runtime: Runtime,
) {
    @Inject
    constructor(database: MusicDatabase, @ApplicationContext context: Context, albums: AlbumMetadataRepository) : this(
        database, context, Runtime(playlistReferences = { id, locale -> YouTube.playlistSongReferences(id, locale) },
            albumPage = { id, locale -> YouTube.album(id, withSongs = true, requestLocale = locale, notifyMetadata = false) },
            acceptAlbumHeader = { album, locale, contextKey, revision ->
            albums.acceptHeader(album, locale, contextKey, revision); Unit
        }),
    )

    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val localeUpdates: Flow<YouTubeLocale> = YouTube.localeUpdates,
        val authRevision: () -> Long = { YouTube.authRevision },
        val authUpdates: Flow<Long> = YouTube.authUpdates,
        val preferences: Flow<Preferences>? = null,
        val observeMetadata: ((List<YTItem>, YouTubeLocale, String) -> Unit) -> Unit = { YouTube.metadataObserver = it },
        val publishNames: (Map<OriginalNameTarget, String>, Map<OriginalNameTarget, List<String>>) -> Unit = MetadataNames::publish,
        val now: () -> Long = System::currentTimeMillis,
        val contextKey: (YouTubeLocale) -> String = ::youtubeMetadataContextKey,
        val queue: suspend (List<String>, YouTubeLocale) -> Result<List<SongItem>> = { ids, locale ->
            YouTube.queue(videoIds = ids, requestLocale = locale, notifyMetadata = false)
        },
        val album: suspend (String, YouTubeLocale) -> Result<AlbumItem> = { id, locale ->
            YouTube.album(id, withSongs = false, requestLocale = locale, notifyMetadata = false).map { it.album }
        },
        val albumContext: suspend (String, YouTubeLocale) -> Result<List<SongItem>> = { id, locale ->
            YouTube.album(id, withSongs = true, requestLocale = locale, notifyMetadata = false).map { page ->
                require(page.album.id == id) { "Album context belongs to another ID" }
                page.songs.map { it.copy(album = Album(page.album.title, id)) }
            }
        },
        // Full production responses retain their header for the later playlist identity check.
        val albumPage: (suspend (String, YouTubeLocale) -> Result<AlbumPage>)? = null,
        val artist: suspend (String, YouTubeLocale) -> Result<ArtistItem> = { id, locale ->
            YouTube.artist(id, requestLocale = locale, notifyMetadata = false).map { it.artist }
        },
        val main: suspend (String, YouTubeLocale) -> Result<ArtTrackOriginalMetadata> = { id, locale ->
            YouTube.artTrackOriginalMetadata(id, locale)
        },
        val mainSongReference: suspend (String, YouTubeLocale) -> Result<MainSongReference?> = { id, locale ->
            YouTube.mainSongReference(id, locale)
        },
        // Explicitly wired by the production constructor; isolated runtimes opt into this path.
        val playlistReferences: (suspend (String, YouTubeLocale) -> Result<PlaylistSongReferences>)? = null,
        val albumSongSources: suspend (SongItem, YouTubeLocale) -> Result<List<SongItem>> = { song, locale ->
            // Search only discovers possible sources. A result never establishes an ID relation.
            val query = (listOf(song.title) + song.artists.map { it.name }).joinToString(" ")
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, locale, notifyMetadata = false)
                .map { it.items.filterIsInstance<SongItem>() }
        },
        val acceptAlbumHeader: suspend (AlbumItem, YouTubeLocale, String, Long) -> Unit = { _, _, _, _ -> },
        val assessOriginals: suspend (List<ArtTrackOriginalName>, Long) -> List<OriginalNameAssessment> = OriginalAlbumLanguageResolver()::assess,
    )

    private val scope = runtime.scope
    private val packets = Channel<Packet>(Channel.UNLIMITED)
    private val fetches = MetadataFetchQueue<MetadataFetchRequest>()
    private val networkPermits = Semaphore(3)
    private val scheduled = ConcurrentHashMap.newKeySet<MetadataFetchRequest>()
    private val nextAttempt = ConcurrentHashMap<MetadataFetchRequest, Long>()
    private val knownArtTracks = ConcurrentHashMap.newKeySet<OriginalNameTarget>()
    private val originalEvaluations = Channel<Unit>(Channel.CONFLATED)
    private val referenceRefreshes = Channel<Unit>(Channel.CONFLATED)
    private data class EnglishSongObservation(val song: SongItem, val observedAt: Long)
    private val englishSongs = ConcurrentHashMap<String, EnglishSongObservation>()
    private data class IdentityEnrichment(val observation: EnglishSongObservation, val originalName: String,
        val musicContext: String, val token: String)
    private val identityEnrichments = ConcurrentHashMap<MetadataFetchRequest, IdentityEnrichment>()
    private val attemptedIdentityEnrichments = ConcurrentHashMap<MetadataFetchRequest, MutableSet<String>>()
    private val openedAlbumContexts = ConcurrentHashMap<MetadataFetchRequest, Long>()
    @Volatile private var foregroundAlbumId: String? = null
    @Volatile private var playingSongId: String? = null
    @Volatile private var foregroundAlbumTargets: Set<OriginalNameTarget> = emptySet()
    private data class AlbumObservation(val album: AlbumItem, val songs: List<SongItem>?, val observedAt: Long)
    private val albumResponses = ConcurrentHashMap<MetadataFetchRequest, AlbumObservation>()
    private val albumResponseLocks = Array(32) { Mutex() }
    private val albumOriginalRetryAfter = ConcurrentHashMap<MetadataFetchRequest, Long>()
    // Album recovery and the normal Art Track worker may encounter the same recording together.
    private val originalLocks = Array(32) { Mutex() }
    private val currentLocale: YouTubeLocale get() = runtime.locale()
    private var initialAuthRevision = 0L
    private var started = false
    private val initialPublicationReady = MutableStateFlow(false)
    val initialized = initialPublicationReady.asStateFlow()
    internal val pendingRequestCount: Int get() = scheduled.size

    private data class Packet(val items: List<YTItem>, val locale: YouTubeLocale, val source: String,
        val contextKey: String, val authRevision: Long)
    private data class Settings(val locale: YouTubeLocale, val preferOriginal: Boolean)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        initialAuthRevision = runtime.authRevision()
        runtime.observeMetadata { items, locale, source ->
            packets.trySend(Packet(items.toList(), locale, source, runtime.contextKey(locale), runtime.authRevision()))
            Unit
        }
        scope.launch {
            initialized.first { it }
            for (packet in packets) keepCollectorRunning {
                // The observer's callback may wait in the packet queue across a setting/account change.
                if (packet.locale.gl != currentLocale.gl || packet.contextKey != runtime.contextKey(currentLocale) ||
                    packet.authRevision != runtime.authRevision()) {
                    packet.items.forEach { scheduleItem(it) }
                    return@keepCollectorRunning
                }
                val names = metadataNameCandidates(packet.items, packet.locale.hl, packet.source)
                var saved = false
                database.awaitTransaction {
                    if (packet.locale.gl == currentLocale.gl && packet.contextKey == runtime.contextKey(currentLocale) &&
                        packet.authRevision == runtime.authRevision()) {
                        recordMetadataNames(names)
                        saved = true
                    }
                }
                if (!saved) {
                    packet.items.forEach(::scheduleItem)
                    return@keepCollectorRunning
                }
                if (packet.source == "album") {
                    packet.items.filterIsInstance<AlbumItem>().forEach { album ->
                        if (packet.items.filterIsInstance<SongItem>().any { it.album?.id == album.id }) {
                            val request = MetadataFetchRequest(
                                OriginalNameTarget(OriginalNameKind.ALBUM, album.id), packet.locale.copy(hl = "en"),
                                albumOriginalContextKey(packet.contextKey), original = true, authRevision = packet.authRevision,
                            )
                            openedAlbumContexts[request] = runtime.now()
                            val songs = packet.items.filterIsInstance<SongItem>().filter { it.album?.id == album.id }
                            cacheAlbum(request.copy(locale = packet.locale, original = false, contextKey = packet.contextKey),
                                AlbumObservation(album, songs, runtime.now()))
                            updateForegroundAlbumTargets(album.id,
                                metadataNameCandidates(listOf(album) + songs, packet.locale.hl, "album")
                                    .mapTo(mutableSetOf()) { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) })
                        }
                    }
                }
                names.map { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
                    .distinct().forEach(::schedule)
            }
        }
        repeat(3) { scope.launch {
            while (isActive) {
                val batch = fetches.takeBatch(maxSize = 50) { first, next ->
                    !first.original && first.target.kind == OriginalNameKind.SONG &&
                        next.target.kind == first.target.kind && !next.original && next.locale == first.locale &&
                        next.contextKey == first.contextKey && next.authRevision == first.authRevision
                } ?: break
                fetchBatch(batch)
            }
        } }
        val preferences = runtime.preferences ?: context.dataStore.data
        val settings = combine(runtime.localeUpdates, preferences.map { it[PreferEnglishOriginalKey] ?: false }
            .distinctUntilChanged()) { locale, preferOriginal -> Settings(locale, preferOriginal) }
        scope.launch {
            initialized.first { it }
            metadataRequestConfiguration(runtime.localeUpdates, preferences, runtime.authUpdates).collect {
                keepCollectorRunning { refreshTargets() }
            }
        }
        scope.launch {
            while (isActive) {
                keepCollectorRunning {
                    if (!initialPublicationReady.value) bootstrapOriginalPublications()
                    combine(database.metadataDisplayNames(), settings) { rows, options -> rows to options }
                        .conflate().collect { (rows, options) ->
                            val grouped = rows.groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.name.kind), it.name.targetId) }
                            val selected = grouped.mapNotNull { (target, candidates) ->
                                selectCommittedMetadataDisplayName(target, candidates.map { it.name }, options.locale.hl,
                                    options.preferOriginal, candidates.first().englishName)?.let { target to it }
                            }.toMap()
                            val aliases = grouped.mapValues { (_, names) -> names.map { it.name.name }.distinct() }
                            withContext(Dispatchers.Main) {
                                if (options.locale == currentLocale) {
                                    runtime.publishNames(selected, aliases)
                                    initialPublicationReady.value = true
                                }
                            }
                        }
                }
                delay(500)
            }
        }
        scope.launch {
            initialized.first { it }
            database.metadataRefreshTargets().collect { targets -> keepCollectorRunning {
                targets.forEach { schedule(OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId)) }
            } }
        }
        scope.launch {
            initialized.first { it }
            database.allMetadataNames().distinctUntilChangedBy(::originalPublicationInputKey)
                .collect { originalEvaluations.trySend(Unit) }
        }
        scope.launch {
            for (ignored in originalEvaluations) keepCollectorRunning { evaluateOriginals(database.metadataNameSnapshot()) }
        }
        scope.launch {
            initialized.first { it }
            for (ignored in referenceRefreshes) keepCollectorRunning { recoverPersistedSongReferences() }
        }
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                keepCollectorRunning { refreshTargets() }
                keepCollectorRunning { originalEvaluations.trySend(Unit) }
            }
        }
    }

    private suspend fun keepCollectorRunning(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A transient DB/cache failure must not permanently stop a singleton collector. */ }
    }

    /** Only pre-publication caches need conversion. Normal cold starts read display strings alone. */
    private suspend fun bootstrapOriginalPublications() {
        if (database.metadataOriginalPublicationCount() != 0) return
        val names = database.metadataNameSnapshot()
        val prepared = prepareOriginalPublications(names, emptyList(), runtime.now())
        if (prepared.isEmpty()) return
        database.awaitTransaction {
            if (metadataOriginalPublicationCount() == 0 && metadataNameSnapshot().toSet() == names.toSet()) {
                recordMetadataOriginalPublications(prepared)
            }
        }
    }

    private suspend fun evaluateOriginals(names: List<MetadataNameEntity>) {
        var snapshot = names
        // Missing classifier output remains pending. Try other independent groups without spinning
        // on the same failed input; a later observation or periodic refresh may retry it.
        val attempted = mutableSetOf<Pair<ArtTrackOriginalName, String?>>()
        while (currentCoroutineContext().isActive) {
            val rows = latestOriginalRows(snapshot)
            val inputs = originalAssessmentInputs(rows)
            val stale = rows.filterNot { hasCurrentOriginalAssessmentInputs(it, inputs) }.filter {
                val candidate = originalCandidate(it)!!
                (candidate to inputs.fingerprintFor(candidate)) !in attempted
            }
            val foregroundAlbum = foregroundAlbumId
            val playingSong = playingSongId
            val foregroundTargets = foregroundAlbumTargets
            fun priority(group: List<MetadataNameEntity>): Int = when {
                foregroundAlbum != null && group.any { originalCandidate(it)?.albumId == foregroundAlbum } -> 0
                group.any { row -> originalCandidate(row)!!.target.let {
                    it in foregroundTargets || (it.kind == OriginalNameKind.SONG && it.id == playingSong)
                } } -> 1
                else -> 2
            }
            val groups = stale.groupBy {
                val candidate = originalCandidate(it)!!
                candidate.albumId?.let { id -> "album:$id" } ?: "source:${candidate.sourceVideoId}"
            }.values.sortedWith(compareBy<List<MetadataNameEntity>>(::priority).thenBy { it.first().targetId })
            // Bound background aggregation so cache migrations do not prepare the entire display
            // projection for every orphan song. A foreground group always publishes on its own.
            val limit = if (groups.firstOrNull()?.let(::priority) == 2) 8 else 1
            val updates = mutableListOf<MetadataNameEntity>()
            var capturedInputKey: List<MetadataNameEntity>? = null
            for (selected in groups.take(limit)) {
                // Navigation may change while the preceding model call is running. Re-read its
                // new inputs before starting another background group, rather than draining eight.
                if (foregroundAlbum != foregroundAlbumId || playingSong != playingSongId ||
                    foregroundTargets != foregroundAlbumTargets) break
                // Its IDs can already be foreground when the first raw source finally arrives.
                // A notification interrupts this batch only for a changed input; an initial or
                // redundant signal must not turn a cache migration back into one-row commits.
                if (originalEvaluations.tryReceive().isSuccess) {
                    val expected = capturedInputKey ?: originalPublicationInputKey(snapshot).also { capturedInputKey = it }
                    if (originalPublicationInputKey(database.metadataNameSnapshot()) != expected) break
                }
                val selectedCandidates = selected.mapNotNull(::originalCandidate)
                val fresh = try {
                    runtime.assessOriginals(inputs.withAlbumContext(selectedCandidates), runtime.now())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed group is pending, not UNKNOWN; other independent groups still run.
                    emptyList()
                }
                selectedCandidates.forEach { attempted += it to inputs.fingerprintFor(it) }
                val assessments = fresh.associateBy { Triple(it.target, it.originalName, it.sourceVideoId) }
                updates += selected.mapNotNull { row ->
                    val candidate = originalCandidate(row)!!
                    val assessment = assessments[Triple(candidate.target, candidate.name, candidate.sourceVideoId)]
                        ?: return@mapNotNull null
                    row.copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, inputs))
                }
            }
            publishCompletedOriginals(snapshot, updates)
            if (groups.isEmpty()) return
            snapshot = database.metadataNameSnapshot()
        }
    }

    private suspend fun publishCompletedOriginals(names: List<MetadataNameEntity>, updates: List<MetadataNameEntity>) {
        fun rowKey(row: MetadataNameEntity) = listOf(row.kind, row.targetId, row.language, row.name, row.source)
        val updatesByKey = updates.associateBy { listOf(it.kind, it.targetId, it.language, it.name, it.source) }
        val assessedNames = names.map { updatesByKey[rowKey(it)] ?: it }
        val previous = database.metadataOriginalPublicationSnapshot()
        // Assemble proof off the Room transaction queue, then validate just its dependencies.
        val prepared = prepareOriginalPublications(assessedNames, previous, runtime.now())
        val previousByTarget = previous.associateBy { it.kind to it.targetId }
        val changedPublications = prepared.filter { previousByTarget[it.kind to it.targetId] != it }
        if (updates.isEmpty() && changedPublications.isEmpty()) return
        val expectedInputs = OriginalPublicationInputs(assessedNames)
        var retryPublication = false
        database.awaitTransaction {
            val currentNames = metadataNameSnapshot()
            val currentRows = latestOriginalRows(currentNames).associateBy(::originalCandidate)
            val currentAssessmentInputs = originalAssessmentInputs(currentRows.values.toList())
            val stillValid = updates.mapNotNull { update ->
                val current = currentRows[originalCandidate(update)] ?: return@mapNotNull null
                if (hasCurrentOriginalAssessmentInputs(current, currentAssessmentInputs) ||
                    !hasCurrentOriginalAssessmentInputs(update, currentAssessmentInputs)) return@mapNotNull null
                current.copy(originEvidenceJson = update.originEvidenceJson)
            }
            val validByKey = stillValid.associateBy(::rowKey)
            val currentInputs = OriginalPublicationInputs(currentNames.map { validByKey[rowKey(it)] ?: it })
            val currentPublications = metadataOriginalPublicationSnapshot().associateBy { it.kind to it.targetId }
            val publishable = changedPublications.filter { publication ->
                val target = OriginalNameTarget(OriginalNameKind.valueOf(publication.kind), publication.targetId)
                expectedInputs.forTarget(target) == currentInputs.forTarget(target) &&
                    previousByTarget[publication.kind to publication.targetId] == currentPublications[publication.kind to publication.targetId]
            }
            if (stillValid.isNotEmpty()) recordMetadataNames(stillValid)
            if (publishable.isNotEmpty()) recordMetadataOriginalPublications(publishable)
            retryPublication = publishable.size != changedPublications.size
        }
        if (retryPublication) originalEvaluations.trySend(Unit)
        if (updates.isNotEmpty()) referenceRefreshes.trySend(Unit)
    }

    internal suspend fun refreshTargets() {
        openedAlbumContexts.entries.removeAll { (request, observedAt) ->
            !isCurrent(request) || (request.target.id != foregroundAlbumId && observedAt + 10 * 60_000L < runtime.now())
        }
        foregroundAlbumId?.let { id ->
            val request = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.ALBUM, id), currentLocale.copy(hl = "en"),
                albumOriginalContextKey(runtime.contextKey(currentLocale)), original = true, authRevision = runtime.authRevision())
            openedAlbumContexts[request] = runtime.now()
        }
        val saved = database.metadataRefreshTargets().first().map { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
        (saved + foregroundAlbumTargets + listOfNotNull(
            foregroundAlbumId?.let { OriginalNameTarget(OriginalNameKind.ALBUM, it) },
            playingSongId?.let { OriginalNameTarget(OriginalNameKind.SONG, it) },
        )).distinct().forEach(::schedule)
        referenceRefreshes.trySend(Unit)
    }

    /** UI lifecycle signals carry IDs only; all DB work stays off the main thread. */
    @Synchronized
    fun setForegroundAlbum(albumId: String, active: Boolean) {
        if (!albumId.startsWith("MPRE") && !albumId.startsWith("FEmusic_library_privately_owned_release")) return
        if (!active) {
            if (foregroundAlbumId == albumId) { foregroundAlbumId = null; foregroundAlbumTargets = emptySet() }
            updateFetchPriority()
            return
        }
        if (foregroundAlbumId == albumId) return
        foregroundAlbumId = albumId
        foregroundAlbumTargets = setOf(OriginalNameTarget(OriginalNameKind.ALBUM, albumId))
        updateFetchPriority()
        scope.launch {
            initialized.first { it }
            if (foregroundAlbumId != albumId) return@launch
            val request = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.ALBUM, albumId), currentLocale.copy(hl = "en"),
                albumOriginalContextKey(runtime.contextKey(currentLocale)), original = true, authRevision = runtime.authRevision())
            openedAlbumContexts[request] = runtime.now()
            schedule(request.target)
        }
    }

    @Synchronized
    fun setPlayingSong(songId: String?) {
        playingSongId = songId
        updateFetchPriority()
        if (songId != null) scope.launch {
            initialized.first { it }
            if (playingSongId == songId) schedule(OriginalNameTarget(OriginalNameKind.SONG, songId))
        }
    }

    private fun isForeground(target: OriginalNameTarget): Boolean = target in foregroundAlbumTargets ||
        (target.kind == OriginalNameKind.SONG && target.id == playingSongId)

    @Synchronized
    private fun updateForegroundAlbumTargets(albumId: String, targets: Set<OriginalNameTarget>) {
        if (foregroundAlbumId != albumId) return
        foregroundAlbumTargets = targets + OriginalNameTarget(OriginalNameKind.ALBUM, albumId)
        updateFetchPriority()
    }

    private fun updateFetchPriority() {
        val albumTargets = foregroundAlbumTargets
        val playing = playingSongId
        fetches.updatePriority { it.target in albumTargets ||
            (it.target.kind == OriginalNameKind.SONG && it.target.id == playing) }
    }

    /** Recover existing album rows too, without requiring the provider to repeat an old ID list. */
    private suspend fun recoverPersistedSongReferences() {
        val snapshot = database.metadataNameSnapshot()
        val originals = latestOriginalRows(snapshot).mapNotNull(::originalCandidate).filter {
            it.target.kind == OriginalNameKind.SONG && it.albumId != null
        }
        val relevantIds = (database.metadataRefreshTargets().first().filter { it.kind == "SONG" }.map { it.targetId } +
            foregroundAlbumTargets.filter { it.kind == OriginalNameKind.SONG }.map { it.id } + listOfNotNull(playingSongId)).toSet()
        val directIds = originals.mapTo(mutableSetOf()) { it.target.id }
        val sourcesByAlbumAndName = originals.groupBy { it.albumId to comparableMetadataName(it.name) }
        val locale = currentLocale.copy(hl = "en")
        val contextKey = runtime.contextKey(locale)
        val revision = runtime.authRevision()
        val groups = snapshot.filter { it.kind == "SONG" && it.language == "en" && it.targetId in relevantIds }
            .groupBy { it.targetId }
        for ((id, names) in groups) {
            if (id in directIds) continue
            val raw = database.songForArtistCredit(id)?.takeUnless { it.isLocal } ?: continue
            val albumId = raw.albumId ?: continue
            val sources = names.flatMap { sourcesByAlbumAndName[albumId to comparableMetadataName(it.name)].orEmpty() }
                .filter { it.sourceVideoId != id }
                .distinctBy { it.sourceVideoId }.take(3)
            if (sources.isEmpty()) continue
            val parent = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.ALBUM, albumId), locale,
                albumOriginalContextKey(contextKey), original = true, authRevision = revision)
            if (!isCurrent(parent)) return
            for (source in sources) {
                val target = SongItem(id, source.name, emptyList(), Album(raw.albumName.orEmpty(), albumId),
                    thumbnail = raw.thumbnailUrl.orEmpty())
                if (captureSongReference(parent, source.sourceVideoId, target)) break
            }
        }
    }

    private fun scheduleItem(item: YTItem) {
        when (item) {
            is SongItem -> schedule(OriginalNameTarget(OriginalNameKind.SONG, item.id))
            is AlbumItem -> schedule(OriginalNameTarget(OriginalNameKind.ALBUM, item.id))
            is ArtistItem -> schedule(OriginalNameTarget(OriginalNameKind.ARTIST, item.id))
            else -> Unit
        }
    }

    private fun schedule(target: OriginalNameTarget) {
        if (target.id.isBlank()) return
        val locale = currentLocale
        val revision = runtime.authRevision()
        val contextKey = runtime.contextKey(locale)
        // A full page explicitly opened by the user also warrants its bilingual track names.
        // Search results and queue stubs alone still do not expand every related album.
        if (target.kind == OriginalNameKind.ALBUM) {
            val request = MetadataFetchRequest(target, locale.copy(hl = "en"), albumOriginalContextKey(contextKey),
                original = true, authRevision = revision)
            if (albumContextAllowed(request)) enqueue(request)
        }
        for (language in listOf("en", locale.hl).distinct()) {
            enqueue(MetadataFetchRequest(target, locale.copy(hl = language), contextKey, authRevision = revision))
        }
        if (target.kind == OriginalNameKind.SONG && (target in knownArtTracks ||
                database.metadataFetchStates(target.kind.name, target.id).any {
                    it.language == "und" && it.contextKey.startsWith("main:")
                })) {
            knownArtTracks.add(target)
            scheduleOriginal(target)
        }
    }

    private fun scheduleOriginal(target: OriginalNameTarget) {
        if (target !in knownArtTracks) return
        val locale = currentLocale.copy(hl = "en")
        enqueue(MetadataFetchRequest(target, locale, originalMetadataContextKey(locale),
            original = true, authRevision = runtime.authRevision()))
    }

    @Synchronized
    private fun enqueue(request: MetadataFetchRequest) {
        if ((nextAttempt[request] ?: 0) > runtime.now()) return
        val foreground = isForeground(request.target)
        if (scheduled.add(request)) {
            if (!fetches.offer(request, foreground)) scheduled.remove(request)
        } else if (foreground) fetches.promote(request)
    }

    private fun isCurrent(request: MetadataFetchRequest): Boolean {
        val locale = currentLocale
        return isMetadataFetchCurrent(request, locale, runtime.contextKey(locale), runtime.authRevision())
    }

    private fun requeueIfObsolete(request: MetadataFetchRequest): Boolean {
        if (isCurrent(request)) return false
        schedule(request.target)
        return true
    }

    private fun due(request: MetadataFetchRequest): Boolean {
        if (requeueIfObsolete(request)) return false
        // An authentication change may bypass an older session's persisted failure once.
        // Attempts completed in this request's own session still obey their retry delay,
        // including album workers which call due directly instead of passing enqueue again.
        if ((nextAttempt[request] ?: 0L) > runtime.now()) return false
        val previous = database.metadataFetch(request.target.kind.name, request.target.id, request.storedLanguage, request.contextKey)
        // A newly complete Music identity may enrich a successful album-only source once. Failed
        // attempts still follow the ordinary retry window; queued work is revalidated under its lock.
        if (request.enrichment && previous?.status == MetadataFetchEntity.SUCCESS && identityEnrichments.containsKey(request)) return true
        // Successful names remain useful across sessions. A fresh login can retry a failure or
        // empty response from the earlier session, while a normal process restart keeps its TTL.
        val expires = previous?.takeIf { request.authRevision == initialAuthRevision || it.status == MetadataFetchEntity.SUCCESS }
            ?.let { it.updatedAt + metadataRetryDelay(request, it.status) } ?: 0L
        if (expires > runtime.now()) {
            nextAttempt[request] = expires
            return false
        }
        return true
    }

    private suspend fun fetchBatch(batch: List<MetadataFetchRequest>) {
        var dueRequests = emptyList<MetadataFetchRequest>()
        try {
            dueRequests = batch.filter(::due)
            dueRequests = dueRequests.filterNot(::requeueIfObsolete)
            if (dueRequests.isEmpty()) return
            val first = dueRequests.first()
            if (first.original) {
                if (first.target.kind == OriginalNameKind.ALBUM) captureAlbumOriginals(first) else captureOriginalTitle(first)
                return
            }
            val items: List<YTItem> = when (first.target.kind) {
                OriginalNameKind.SONG -> networkPermits.withPermit { runtime.queue(dueRequests.map { it.target.id }, first.locale).getOrThrow() }
                OriginalNameKind.ALBUM -> listOf(loadAlbumHeader(first))
                OriginalNameKind.ARTIST -> networkPermits.withPermit { listOf(runtime.artist(first.target.id, first.locale).getOrThrow()) }
            }
            currentCoroutineContext().ensureActive()
            val byTarget = metadataItemsByTarget(items)
            dueRequests.forEach { request ->
                try { saveMusicResult(request, byTarget[request.target]) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { recordFailure(request) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // TTL/query failures are recoverable too; keep all three workers alive.
            (dueRequests.ifEmpty { batch }).forEach { recordFailure(it) }
        } finally {
            batch.forEach(scheduled::remove)
            batch.filter { it.enrichment }.forEach { request ->
                val pending = identityEnrichments[request] ?: return@forEach
                // A different complete detail may arrive while this source's Main call is held.
                // Its payload belongs to the next attempt, not to the finishing worker's cleanup.
                val state = runCatching { database.metadataFetch("SONG", request.target.id, "und", request.contextKey) }.getOrNull()
                if (isCurrent(request) && state?.status == MetadataFetchEntity.SUCCESS) {
                    nextAttempt.remove(request)
                    enqueue(request)
                } else identityEnrichments.remove(request, pending)
            }
        }
    }

    private suspend fun saveMusicResult(request: MetadataFetchRequest, item: YTItem?) {
        if (requeueIfObsolete(request)) return
        val now = runtime.now()
        val status = if (item == null) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS
        val names = metadataNameCandidates(listOfNotNull(item), request.locale.hl, "detail", now)
        val song = item as? SongItem
        val isArtTrack = song?.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        val mainKey = originalMetadataContextKey(request.locale)
        var saved = false
        database.awaitTransaction {
            if (!isCurrent(request)) return@awaitTransaction
            recordMetadataNames(names, request.state(status, now))
            if (item is ArtistItem) saveArtistProfile(item)
            if (isArtTrack && request.locale.hl == "en" &&
                metadataFetch(request.target.kind.name, request.target.id, "und", mainKey) == null) {
                recordMetadataFetch(MetadataFetchEntity(request.target.kind.name, request.target.id,
                    "und", MetadataFetchEntity.PENDING, now, mainKey))
            }
            saved = true
        }
        if (!saved) { schedule(request.target); return }
        if (item is AlbumItem && request.locale.hl == currentLocale.hl) {
            runtime.acceptAlbumHeader(item, request.locale, request.contextKey, request.authRevision)
        }
        nextAttempt[request] = now + metadataRetryDelay(request, status)
        // Embedded names also need both locales and their own authoritative header. A detail
        // fetch never expands artist sections or album tracks, so this remains bounded by IDs
        // actually returned; TTL and scheduled keys share repeated requests.
        names.map { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
            .distinct().forEach(::schedule)
        if (isArtTrack && request.locale.hl == "en") {
            if (englishSongs.size > 1024) englishSongs.clear()
            englishSongs["${request.authRevision}:${request.contextKey}:${request.target.id}"] = EnglishSongObservation(song!!, now)
            knownArtTracks.add(request.target)
            scheduleIdentityEnrichment(request, song, now)
            scheduleOriginal(request.target)
        }
    }

    private fun scheduleIdentityEnrichment(musicRequest: MetadataFetchRequest, song: SongItem, observedAt: Long) {
        val request = musicRequest.copy(original = true, contextKey = originalMetadataContextKey(musicRequest.locale), enrichment = true)
        if (!isCurrent(request)) return
        val state = database.metadataFetch("SONG", song.id, "und", request.contextKey)
        if (state?.status != MetadataFetchEntity.SUCCESS) return
        val source = latestOriginalRows(database.metadataNameSnapshot()).mapNotNull(::originalCandidate)
            .filter { it.sourceVideoId == song.id }
        val priorSong = source.singleOrNull { it.target.kind == OriginalNameKind.SONG } ?: return
        if (observedAt < state.updatedAt || !addsOriginalIdentityProof(song, source)) return
        val artists = song.artistCredit?.artists ?: song.artists
        // Observation times do not change the meaning of an identity. Unsuccessful corroboration
        // must not create a loop when the same queue response arrives again in this session.
        val parts = listOf(musicRequest.contextKey, priorSong.name, song.album?.id.orEmpty(), song.album?.name.orEmpty()) +
            artists.flatMap { listOf(it.id.orEmpty(), it.name) }
        val token = parts.joinToString("") { "${it.length}:$it" }
        val attempted = attemptedIdentityEnrichments.getOrPut(request) { mutableSetOf() }
        synchronized(attempted) {
            if (attempted.size >= 8 || token in attempted) return
        }
        identityEnrichments[request] = IdentityEnrichment(EnglishSongObservation(song, observedAt), priorSong.name,
            musicRequest.contextKey, token)
        nextAttempt.remove(request)
        enqueue(request)
    }

    private fun addsOriginalIdentityProof(song: SongItem, source: List<ArtTrackOriginalName>): Boolean {
        val priorSong = source.singleOrNull { it.target.kind == OriginalNameKind.SONG } ?: return false
        val artists = song.artistCredit?.artists ?: song.artists
        val completeArtists = artists.isNotEmpty() && artists.all {
            ArtistIdentity.onlineId(it.id) != null && it.name.isNotBlank() && it.name.none { c -> c == '\n' || c == '\r' }
        }
        val addsArtists = completeArtists && artists.any { artist ->
            source.none { it.target.kind == OriginalNameKind.ARTIST && it.target.id == ArtistIdentity.onlineId(artist.id) }
        }
        val addsAlbum = priorSong.albumId == null && song.album?.let {
            Regex("(?:MPRE|FEmusic_library_privately_owned_release)[A-Za-z0-9_-]+").matches(it.id) &&
                it.name.isNotBlank() && it.name.none { c -> c == '\n' || c == '\r' }
        } == true
        return addsArtists || addsAlbum
    }

    private fun albumContextAllowed(request: MetadataFetchRequest): Boolean =
        openedAlbumContexts.containsKey(request) || database.isAlbumOriginalContextEligible(request.target.id)

    private fun albumResponseKey(request: MetadataFetchRequest) = request.copy(original = false,
        contextKey = runtime.contextKey(request.locale))

    private fun cacheAlbum(key: MetadataFetchRequest, observation: AlbumObservation) {
        if (albumResponses.size >= 32) albumResponses.entries.removeAll { it.value.observedAt + 60_000L <= runtime.now() }
        if (albumResponses.size >= 32) albumResponses.entries.minByOrNull { it.value.observedAt }?.let {
            albumResponses.remove(it.key, it.value)
        }
        albumResponses.compute(key) { _, previous ->
            if (observation.songs == null && previous?.songs != null && previous.album == observation.album &&
                previous.observedAt + 60_000L > runtime.now()) previous else observation
        }
    }

    private suspend fun loadAlbumHeader(request: MetadataFetchRequest): AlbumItem {
        val key = albumResponseKey(request)
        return albumResponseLocks[(request.target.id.hashCode() and Int.MAX_VALUE) % albumResponseLocks.size].withLock {
            albumResponses[key]?.takeIf { it.observedAt + 60_000L > runtime.now() }?.album
                ?: networkPermits.withPermit { runtime.album(request.target.id, request.locale).getOrThrow() }.also {
                    require(it.id == request.target.id)
                    if (isCurrent(request)) cacheAlbum(key, AlbumObservation(it, null, runtime.now()))
                }
        }
    }

    private suspend fun loadAlbumSongs(request: MetadataFetchRequest): List<SongItem> {
        val fetch = runtime.albumPage ?: return networkPermits.withPermit {
            runtime.albumContext(request.target.id, request.locale).getOrThrow()
        }
        val key = albumResponseKey(request)
        return albumResponseLocks[(request.target.id.hashCode() and Int.MAX_VALUE) % albumResponseLocks.size].withLock {
            albumResponses[key]?.takeIf { it.observedAt + 60_000L > runtime.now() }?.songs
                ?: networkPermits.withPermit { fetch(request.target.id, request.locale).getOrThrow() }.let { page ->
                    require(page.album.id == request.target.id)
                    val songs = page.songs.map { it.copy(album = Album(page.album.title, page.album.id)) }
                    if (isCurrent(request)) cacheAlbum(key, AlbumObservation(page.album, songs, runtime.now()))
                    songs
                }
        }
    }

    private suspend fun captureAlbumOriginals(request: MetadataFetchRequest) {
        if (requeueIfObsolete(request) || yieldAlbumToForeground(request) || !albumContextAllowed(request)) return
        val contextKey = runtime.contextKey(request.locale)
        val songs = loadAlbumSongs(request)
        require(songs.all { it.album?.id == request.target.id }) { "Track context belongs to another album" }
        currentCoroutineContext().ensureActive()
        val now = runtime.now()
        var saved = false
        database.awaitTransaction {
            if (!isCurrent(request) || contextKey != runtime.contextKey(request.locale) ||
                !albumContextAllowed(request)) return@awaitTransaction
            recordMetadataNames(metadataNameCandidates(songs, "en", "album-original-context", now))
            saved = true
        }
        if (!saved) return
        updateForegroundAlbumTargets(request.target.id,
            metadataNameCandidates(songs, "en", "album-original-context")
                .mapTo(mutableSetOf()) { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) })
        val directComplete = checkAlbumItems(request, songs.distinctBy { it.id }) { song ->
            val target = OriginalNameTarget(OriginalNameKind.SONG, song.id)
            schedule(target)
            val originalRequest = MetadataFetchRequest(target, request.locale,
                originalMetadataContextKey(request.locale), original = true, authRevision = request.authRevision)
            originalLock(song.id).withLock {
                try {
                    val state = database.metadataFetch("SONG", song.id, "und", originalRequest.contextKey)
                    val hasAlbumContext = latestOriginalRows(database.metadataNames("SONG", song.id)).any {
                        originalCandidate(it)?.albumId != null
                    }
                    // A preceding single-song lookup can succeed without this album's context.
                    // Its seven-day TTL must not suppress the stronger, directly observed link.
                    // A shared recording may already have another verified edition's context;
                    // do not replace that evidence just because a second edition was opened.
                    val albumRecoveryDue = (state?.status != MetadataFetchEntity.SUCCESS || !hasAlbumContext) &&
                        (albumOriginalRetryAfter[originalRequest] ?: 0L) <= runtime.now()
                    if (due(originalRequest) || albumRecoveryDue) {
                        saveOriginalTitle(originalRequest, song, fromAlbumPage = true)
                    }
                    if (database.metadataFetch("SONG", song.id, "und", originalRequest.contextKey)?.status != MetadataFetchEntity.SUCCESS) {
                        albumOriginalRetryAfter[originalRequest] = runtime.now() + metadataRetryDelay(originalRequest, MetadataFetchEntity.EMPTY)
                        false
                    } else {
                        albumOriginalRetryAfter.remove(originalRequest)
                        true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    albumOriginalRetryAfter[originalRequest] = runtime.now() + metadataRetryDelay(MetadataFetchEntity.FAILED)
                    recordFailure(originalRequest)
                    false
                }
            }
        } ?: return
        var complete = songs.isNotEmpty() && directComplete
        val playlistComplete = if (isCurrent(request)) captureAlbumPlaylistReferences(request, songs) else null
        if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
        if (!complete && isCurrent(request)) {
            recoverAlbumSongReferences(request, songs)
            if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
            val snapshot = database.metadataNameSnapshot()
            complete = songs.isNotEmpty() && songs.all { song ->
                database.metadataFetch("SONG", song.id, "und", originalMetadataContextKey(request.locale))?.status ==
                    MetadataFetchEntity.SUCCESS || (playlistComplete == true && currentPlaylistSongReference(snapshot, song) != null) ||
                    currentSongReference(snapshot, song)?.let { reference ->
                        database.metadataFetch("SONG", reference.sourceVideoId, "und", "main-song-card:${request.locale.gl}:v1")
                            ?.let { it.status == MetadataFetchEntity.SUCCESS &&
                                it.updatedAt + metadataRetryDelay(MetadataFetchEntity.SUCCESS) > runtime.now() } == true
                    } == true
            }
        }
        // Failure to process an observed identity pair remains unfinished. Explicit restricted
        // omissions are separate: their album tracks may already have independent direct proof.
        if (playlistComplete == false) complete = false
        // Do not cache the album as complete before its original-title work finishes. On failure
        // or process death the next visit/restart can recover using a fresh authoritative page.
        val status = if (complete) MetadataFetchEntity.SUCCESS else MetadataFetchEntity.FAILED
        val completedAt = runtime.now()
        database.awaitTransaction {
            if (isCurrent(request) && contextKey == runtime.contextKey(request.locale)) {
                recordMetadataFetch(request.state(status, completedAt))
            }
        }
        if (isCurrent(request)) nextAttempt[request] = completedAt + metadataRetryDelay(request, status)
    }

    // Finish at most the current three songs before yielding a background album to the page
    // the user just opened. Unfinished work keeps its prior state and can resume on refresh.
    private fun yieldAlbumToForeground(request: MetadataFetchRequest): Boolean =
        foregroundAlbumId?.let { it != request.target.id } == true

    private suspend fun <T> checkAlbumItems(request: MetadataFetchRequest, items: List<T>,
        check: suspend (T) -> Boolean): Boolean? = coroutineScope {
        var complete = true
        for (chunk in items.chunked(3)) {
            if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return@coroutineScope null
            val results = chunk.map { item -> async { check(item) } }.awaitAll()
            if (results.any { !it }) complete = false
        }
        if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) null else complete
    }

    /** A shared provider playlist entry supplies identity; title/order similarity never does. */
    private suspend fun captureAlbumPlaylistReferences(request: MetadataFetchRequest, albumSongs: List<SongItem>): Boolean? {
        val fetch = runtime.playlistReferences ?: return null
        val fetchKey = "album-playlist-reference:${runtime.contextKey(request.locale)}:v2"
        try {
            val album = loadAlbumHeader(request)
            require(album.id == request.target.id && album.browseId == request.target.id)
            val playlistId = album.playlistId ?: return null
            val snapshot = networkPermits.withPermit { fetch(playlistId, request.locale).getOrThrow() }
            val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
            val entryIdPattern = Regex("[A-Za-z0-9_-]{1,512}")
            require(snapshot.playlistId == playlistId && snapshot.references.isNotEmpty())
            require(snapshot.references.all { it.playlistId == playlistId && entryIdPattern.matches(it.playlistSetVideoId) &&
                videoIdPattern.matches(it.sourceVideoId) && videoIdPattern.matches(it.targetVideoId) })
            require(snapshot.references.map { it.playlistSetVideoId }.distinct().size == snapshot.references.size)
            require(snapshot.references.map { it.sourceVideoId }.distinct().size == snapshot.references.size)
            require(snapshot.references.map { it.targetVideoId }.distinct().size == snapshot.references.size)
            require(snapshot.sourceSongs.map { it.id }.distinct().size == snapshot.sourceSongs.size)
            require(snapshot.targetSongs.map { it.id }.distinct().size == snapshot.targetSongs.size)
            require(snapshot.sourceSongs.map { it.id }.toSet() == snapshot.references.map { it.sourceVideoId }.toSet())
            require(snapshot.targetSongs.all { song -> snapshot.references.any { it.targetVideoId == song.id } })
            require(snapshot.unavailableSourceEntries.all { entry ->
                entryIdPattern.matches(entry.playlistSetVideoId) && videoIdPattern.matches(entry.sourceVideoId) &&
                    snapshot.references.none { it.playlistSetVideoId == entry.playlistSetVideoId || it.sourceVideoId == entry.sourceVideoId }
            })
            require(snapshot.unavailableSourceEntries.map { it.playlistSetVideoId }.distinct().size == snapshot.unavailableSourceEntries.size)
            require(snapshot.unavailableSourceEntries.map { it.sourceVideoId }.distinct().size == snapshot.unavailableSourceEntries.size)
            require((snapshot.sourceSongs + snapshot.targetSongs).all { song -> song.album?.id?.let { it == album.id } != false })
            require((snapshot.sourceSongs + snapshot.targetSongs).all { song -> song.endpoint?.videoId?.let { it == song.id } != false })
            require(snapshot.sourceSongs.all { song -> song.setVideoId?.let { entry ->
                snapshot.references.any { it.sourceVideoId == song.id && it.playlistSetVideoId == entry }
            } != false })
            require(snapshot.targetSongs.all { song -> song.setVideoId?.let { entry ->
                snapshot.references.any { it.targetVideoId == song.id && it.playlistSetVideoId == entry }
            } != false })
            if (requeueIfObsolete(request)) return false
            val sources = snapshot.sourceSongs.map { it.copy(album = Album(album.title, album.id)) }
            val sourceMusicById = sources.associateBy { it.id }
            val targets = (albumSongs + snapshot.targetSongs.map { it.copy(album = Album(album.title, album.id)) })
                .associateBy { it.id }
            database.awaitTransaction {
                if (isCurrent(request)) recordMetadataNames(metadataNameCandidates(sources + targets.values,
                    "en", "album-playlist-context", runtime.now()))
            }
            var complete = checkAlbumItems(request, sources) { source ->
                val sourceRequest = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.SONG, source.id),
                    request.locale, originalMetadataContextKey(request.locale), original = true, authRevision = request.authRevision)
                originalLock(source.id).withLock {
                    try {
                        val originals = latestOriginalRows(database.metadataNameSnapshot()).mapNotNull(::originalCandidate)
                        val hasContext = originals.any { it.target.kind == OriginalNameKind.SONG &&
                            it.sourceVideoId == source.id && it.albumId != null }
                        val state = database.metadataFetch("SONG", source.id, "und", sourceRequest.contextKey)
                        // A full album may strengthen an earlier ordinary lookup once. If that
                        // stronger attempt failed, a playlist containing the same source must
                        // not immediately retry it merely because its context is still absent.
                        val albumRecoveryDue = (state?.status != MetadataFetchEntity.SUCCESS || !hasContext) &&
                            (albumOriginalRetryAfter[sourceRequest] ?: 0L) <= runtime.now()
                        if (due(sourceRequest) || albumRecoveryDue)
                            saveOriginalTitle(sourceRequest, source, fromAlbumPage = true)
                        if (database.metadataFetch("SONG", source.id, "und", sourceRequest.contextKey)?.status !=
                            MetadataFetchEntity.SUCCESS) {
                            albumOriginalRetryAfter[sourceRequest] = runtime.now() + metadataRetryDelay(MetadataFetchEntity.FAILED)
                            false
                        } else {
                            albumOriginalRetryAfter.remove(sourceRequest)
                            true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        albumOriginalRetryAfter[sourceRequest] = runtime.now() + metadataRetryDelay(MetadataFetchEntity.FAILED)
                        recordFailure(sourceRequest)
                        false
                    }
                }
            } ?: return null
            database.awaitTransaction {
                if (!isCurrent(request)) { complete = false; return@awaitTransaction }
                val names = metadataNameSnapshot()
                val originals = latestOriginalRows(names).mapNotNull(::originalCandidate)
                val references = snapshot.references.mapNotNull { edge ->
                    val source = originals.singleOrNull { it.target.kind == OriginalNameKind.SONG &&
                        it.sourceVideoId == edge.sourceVideoId && it.albumId != null }
                    if (source == null) { complete = false; return@mapNotNull null }
                    if (edge.sourceVideoId == edge.targetVideoId) return@mapNotNull null
                    val sourceMusic = sourceMusicById[edge.sourceVideoId]
                    val observedNames = listOfNotNull(source.name, sourceMusic?.title).map(::comparableMetadataName).toSet()
                    // Restricted next rows can omit text but still carry an exact item identity.
                    // Use only a separately observed English alias for that target ID, never the
                    // source title copied onto an otherwise unnamed target.
                    val target = targets[edge.targetVideoId]?.takeIf {
                        comparableMetadataName(it.title) in observedNames
                    } ?: names.firstOrNull { it.kind == "SONG" && it.targetId == edge.targetVideoId &&
                        it.language == "en" && comparableMetadataName(it.name) in observedNames }
                        ?.let { SongItem(edge.targetVideoId, it.name, emptyList(), Album(album.title, album.id), thumbnail = "") }
                    if (target == null) { complete = false; return@mapNotNull null }
                    playlistSongReference(edge, source, target, playlistId, expectedAlbumId = album.id, sourceMusic = sourceMusic)
                        .also { if (it == null) complete = false }
                }
                val previous = names.filter { row -> row.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) &&
                    row.source.endsWith(":$playlistId") }
                val now = maxOf(runtime.now(), (previous.maxOfOrNull { it.observedAt } ?: 0L) + 1L)
                val matchedByEntry = snapshot.references.associateBy { it.playlistSetVideoId }
                val unavailableByEntry = snapshot.unavailableSourceEntries.associateBy { it.playlistSetVideoId }
                val obsoleteKeys = latestPlaylistReferenceRows(previous).mapNotNull { row ->
                    val prior = PlaylistSongReferenceCodec.decode(row) ?: return@mapNotNull null
                    val matched = matchedByEntry[prior.playlistSetVideoId]
                    val sameIdentity = matched != null && matched.sourceVideoId == prior.sourceVideoId && matched.targetVideoId == prior.targetVideoId
                    val restrictedWithoutTarget = matched == null &&
                        unavailableByEntry[prior.playlistSetVideoId]?.sourceVideoId == prior.sourceVideoId &&
                        snapshot.references.none { it.targetVideoId == prior.targetVideoId }
                    val replacement = references.firstOrNull { it.sourceVideoId == prior.sourceVideoId &&
                        it.targetVideoId == prior.targetVideoId }
                    // Stable-entry replacement/removal is new evidence. A missing source lookup,
                    // or the same explicitly restricted source without a target row, is not.
                    if ((!sameIdentity && !restrictedWithoutTarget) || (replacement != null && replacement != prior))
                        row.targetId to row.source else null
                }.toSet()
                recordMetadataNames(previous.filter { (it.targetId to it.source) in obsoleteKeys }
                    .map { it.copy(observedAt = now, originEvidenceJson = "{}") })
                recordMetadataNames(references.map { it.toMetadataName(now) })
                recordMetadataFetch(MetadataFetchEntity("ALBUM", album.id, "und",
                    if (complete) MetadataFetchEntity.SUCCESS else MetadataFetchEntity.FAILED, runtime.now(), fetchKey))
            }
            return complete
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            database.awaitTransaction {
                if (isCurrent(request)) recordMetadataFetch(MetadataFetchEntity("ALBUM", request.target.id, "und",
                    MetadataFetchEntity.FAILED, runtime.now(), fetchKey))
            }
            return false
        }
    }

    private fun currentPlaylistSongReference(rows: List<MetadataNameEntity>, target: SongItem): AlbumPlaylistSongReference? {
        val originals = latestOriginalRows(rows).mapNotNull(::originalCandidate)
        return latestPlaylistReferenceRows(rows).mapNotNull(PlaylistSongReferenceCodec::decode).firstOrNull { reference ->
            reference.targetVideoId == target.id && originals.any { original ->
                playlistSongReference(PlaylistSongReference(reference.playlistId, reference.playlistSetVideoId,
                    reference.sourceVideoId, reference.targetVideoId), original, target, reference.playlistId,
                    expectedAlbumId = reference.targetAlbumId,
                    sourceMusic = SongItem(reference.sourceVideoId, reference.sourceMusicName, emptyList(),
                        Album("", reference.targetAlbumId), thumbnail = "")) == reference
            }
        }
    }

    private fun latestPlaylistReferenceRows(rows: List<MetadataNameEntity>): List<MetadataNameEntity> = rows
        .filter { it.kind == "SONG" && it.language == "und" && it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) }
        .groupBy { it.targetId to it.source }.values.flatMap { observations ->
            val latest = observations.maxOf { it.observedAt }
            observations.filter { it.observedAt == latest }
        }

    /** A provider can put official-video IDs in an ordinary album's track shelf. */
    private suspend fun recoverAlbumSongReferences(request: MetadataFetchRequest, songs: List<SongItem>) {
        for (song in songs.distinctBy { it.id }) {
            if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
            val snapshot = database.metadataNameSnapshot()
            if (currentPlaylistSongReference(snapshot, song) != null) continue
            val existingReference = currentSongReference(snapshot, song)
            if (existingReference != null && captureSongReference(request, existingReference.sourceVideoId, song)) continue
            if (latestOriginalRows(snapshot).any { it.kind == "SONG" && it.targetId == song.id }) continue

            // Prefer already verified sources in this exact album. No title-based ID replacement.
            val known = latestOriginalRows(snapshot).mapNotNull(::originalCandidate).filter {
                it.target.kind == OriginalNameKind.SONG && it.albumId == request.target.id &&
                    it.sourceVideoId != song.id && it.name == song.title
            }.distinctBy { it.sourceVideoId }.take(3)
            var recovered = false
            for (source in known) {
                if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
                if (captureSongReference(request, source.sourceVideoId, song)) { recovered = true; break }
            }
            if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
            if (recovered || !isCurrent(request)) continue
            try {
                val candidates = networkPermits.withPermit { runtime.albumSongSources(song, request.locale).getOrThrow() }.filter {
                    it.id != song.id && it.album?.id == request.target.id && it.title == song.title &&
                        it.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
                }.distinctBy { it.id }.take(3)
                if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
                for (source in candidates) {
                    if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
                    val sourceRequest = MetadataFetchRequest(OriginalNameTarget(OriginalNameKind.SONG, source.id),
                        request.locale, originalMetadataContextKey(request.locale), original = true, authRevision = request.authRevision)
                    originalLock(source.id).withLock {
                        if (due(sourceRequest)) saveOriginalTitle(sourceRequest, source, fromAlbumPage = false)
                    }
                    if (requeueIfObsolete(request) || yieldAlbumToForeground(request)) return
                    if (captureSongReference(request, source.id, song)) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The album remains retryable; a missing association cannot authorize English.
            }
        }
    }

    private fun currentSongReference(rows: List<MetadataNameEntity>, target: SongItem): ProviderSongReference? {
        val originals = latestOriginalRows(rows).mapNotNull(::originalCandidate)
        return rows.asSequence().mapNotNull(ProviderSongReferenceCodec::decode).firstOrNull { reference ->
            reference.targetVideoId == target.id && originals.any { original ->
                providerSongReference(MainSongReference(reference.sourceVideoId, reference.targetVideoId), original, target) == reference
            }
        }
    }

    private suspend fun captureSongReference(parent: MetadataFetchRequest, sourceId: String, target: SongItem): Boolean =
        originalLock(sourceId).withLock {
            if (!isCurrent(parent)) return@withLock false
            val snapshot = database.metadataNameSnapshot()
            val source = latestOriginalRows(snapshot).mapNotNull(::originalCandidate).singleOrNull {
                it.target.kind == OriginalNameKind.SONG && it.sourceVideoId == sourceId && it.albumId == parent.target.id
            } ?: return@withLock false
            val fetchKey = "main-song-card:${parent.locale.gl}:v1"
            val state = database.metadataFetch("SONG", sourceId, "und", fetchKey)
            val ttl = if (state?.status == MetadataFetchEntity.SUCCESS) metadataRetryDelay(MetadataFetchEntity.SUCCESS)
                else metadataRetryDelay(MetadataFetchEntity.FAILED)
            val oldReferences = snapshot.filter { it.source == PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + sourceId }
            val sourceUnchanged = oldReferences.mapNotNull(ProviderSongReferenceCodec::decode).any {
                it.originalName == source.name && it.sourceAlbumId == source.albumId
            }
            if (state?.status == MetadataFetchEntity.SUCCESS && state.updatedAt + ttl > runtime.now() &&
                currentSongReference(snapshot, target)?.sourceVideoId == sourceId) return@withLock true
            if (state != null && state.updatedAt + ttl > runtime.now() &&
                (state.status != MetadataFetchEntity.SUCCESS || sourceUnchanged)) return@withLock false
            try {
                val edge = networkPermits.withPermit { runtime.mainSongReference(sourceId, parent.locale).getOrThrow() }
                if (!isCurrent(parent)) return@withLock false
                val reference = edge?.let { providerSongReference(it, source, target) }
                val now = runtime.now()
                var saved = false
                database.awaitTransaction {
                    if (!isCurrent(parent)) return@awaitTransaction
                    // Revalidate against concurrent original-source updates before publishing a link.
                    val latest = latestOriginalRows(metadataNameSnapshot()).mapNotNull(::originalCandidate)
                    if (source !in latest) return@awaitTransaction
                    val obsolete = metadataNameSnapshot().filter {
                        it.source == PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + sourceId &&
                            (reference != null || ProviderSongReferenceCodec.decode(it)?.let { prior ->
                                prior.targetVideoId != edge?.targetVideoId
                            } == true)
                    }
                    // A null payload means "retain existing evidence" to the generic name merger.
                    // An explicit invalid reference payload withdraws the old link but keeps aliases.
                    recordMetadataNames(obsolete.map { it.copy(observedAt = now, originEvidenceJson = "{}") })
                    reference?.let { recordMetadataNames(listOf(it.toMetadataName(now))) }
                    // A real card pointing at another candidate is not a missing source card.
                    // Do not let an earlier same-name row suppress the actual target, or withdraw
                    // an existing link that still matches this fresh provider observation.
                    if (reference != null || edge == null) {
                        recordMetadataFetch(MetadataFetchEntity("SONG", sourceId, "und",
                            if (reference == null) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS, now, fetchKey))
                    }
                    saved = reference != null
                }
                saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                database.awaitTransaction {
                    if (isCurrent(parent)) recordMetadataFetch(MetadataFetchEntity("SONG", sourceId, "und",
                        MetadataFetchEntity.FAILED, runtime.now(), fetchKey))
                }
                false
            }
        }

    private fun originalLock(id: String): Mutex = originalLocks[(id.hashCode() and Int.MAX_VALUE) % originalLocks.size]

    private suspend fun captureOriginalTitle(request: MetadataFetchRequest) = originalLock(request.target.id).withLock {
        try {
            if (!due(request)) return@withLock
            val musicContext = runtime.contextKey(request.locale)
            val previous = database.metadataFetch("SONG", request.target.id, "und", request.contextKey)
            if (request.enrichment) {
                val enrichment = identityEnrichments.remove(request) ?: return@withLock
                val source = latestOriginalRows(database.metadataNameSnapshot()).mapNotNull(::originalCandidate)
                    .filter { it.sourceVideoId == request.target.id }
                if (enrichment.musicContext != musicContext || previous?.status != MetadataFetchEntity.SUCCESS ||
                    runtime.now() - enrichment.observation.observedAt >= metadataRetryDelay(MetadataFetchEntity.SUCCESS) ||
                    source.none { it.target.kind == OriginalNameKind.SONG && it.name == enrichment.originalName } ||
                    !addsOriginalIdentityProof(enrichment.observation.song, source)) return@withLock
                val attempted = attemptedIdentityEnrichments.getOrPut(request) { mutableSetOf() }
                synchronized(attempted) {
                    if (attempted.size >= 8 || !attempted.add(enrichment.token)) return@withLock
                }
                saveOriginalTitle(request, enrichment.observation.song, fromAlbumPage = false)
                return@withLock
            }
            // Share the just-fetched detail batch once, but never pair a new Main observation
            // with Music identities left over from an earlier completed/failed Main attempt.
            val cached = englishSongs.remove("${request.authRevision}:$musicContext:${request.target.id}")?.takeIf {
                runtime.now() - it.observedAt < metadataRetryDelay(MetadataFetchEntity.SUCCESS) &&
                    (previous == null || it.observedAt > previous.updatedAt ||
                        (previous.status == MetadataFetchEntity.PENDING && it.observedAt == previous.updatedAt))
            }
            val englishSong = cached?.song
                ?: networkPermits.withPermit { runtime.queue(listOf(request.target.id), request.locale).getOrThrow() }
                    .singleOrNull { it.id == request.target.id }
                ?: error("Missing exact Art Track identity")
            saveOriginalTitle(request, englishSong, fromAlbumPage = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure(request)
        }
    }

    private suspend fun saveOriginalTitle(request: MetadataFetchRequest, englishSong: SongItem, fromAlbumPage: Boolean) {
        if (requeueIfObsolete(request)) return
        require(englishSong.id == request.target.id) { "Music metadata belongs to another video" }
        val musicContext = runtime.contextKey(request.locale)
        val original = networkPermits.withPermit { runtime.main(request.target.id, request.locale).getOrThrow() }
        currentCoroutineContext().ensureActive()
        require(original.videoId == request.target.id) { "Main metadata belongs to another video" }
        if (requeueIfObsolete(request) || musicContext != runtime.contextKey(request.locale)) return
        val now = runtime.now()
        var status = MetadataFetchEntity.EMPTY
        var saved = false
        var incompleteMusicIdentities = false
        database.awaitTransaction {
            if (!isCurrent(request) || musicContext != runtime.contextKey(request.locale)) return@awaitTransaction
            recordMetadataNames(metadataNameCandidates(listOf(englishSong), "en",
                if (fromAlbumPage) "album-original-context" else "detail", now))
            val previous = metadataNameSnapshot()
            val sourceRows = previous.filter { it.source == ORIGINAL_NAME_SOURCE_PREFIX + original.videoId }
            val observedAt = maxOf(now, (sourceRows.maxOfOrNull { it.observedAt } ?: 0L) + 1L)
            // Missing or incomplete distributor fields do not establish an affirmative withdrawal.
            // Retain the last proof and retry; a new corroborated original replaces it atomically.
            val priorOriginals = latestOriginalRows(previous).mapNotNull(::originalCandidate)
            // An exact queue response can finish while this album's Main request is in flight.
            // Consume its independently identified credit once, using the same freshness boundary
            // as the ordinary source worker. Never copy the album header's artist onto its songs.
            val priorFetch = metadataFetch("SONG", request.target.id, "und", request.contextKey)
            val currentArtists = englishSong.artistCredit?.artists ?: englishSong.artists
            val creditKey = "${request.authRevision}:$musicContext:${request.target.id}"
            val incompleteArtists = currentArtists.isEmpty() || currentArtists.any { artist ->
                ArtistIdentity.onlineId(artist.id) == null || artist.name.isBlank() ||
                    artist.name.any { c -> c == '\n' || c == '\r' }
            }
            val concurrentMusic = if (fromAlbumPage && incompleteArtists) englishSongs[creditKey]?.takeIf {
                val artists = it.song.artistCredit?.artists ?: it.song.artists
                it.song.id == englishSong.id && artists.isNotEmpty() && artists.all { artist ->
                    ArtistIdentity.onlineId(artist.id) != null && artist.name.isNotBlank() &&
                        artist.name.none { c -> c == '\n' || c == '\r' }
                } && currentArtists.mapNotNull { artist -> ArtistIdentity.onlineId(artist.id) }.all { id ->
                    artists.any { artist -> ArtistIdentity.onlineId(artist.id) == id }
                } && runtime.now() - it.observedAt < metadataRetryDelay(MetadataFetchEntity.SUCCESS) &&
                    (priorFetch == null || it.observedAt > priorFetch.updatedAt ||
                        (priorFetch.status == MetadataFetchEntity.PENDING && it.observedAt == priorFetch.updatedAt))
            } else null
            val identifiedSong = concurrentMusic?.let {
                englishSongs.remove(creditKey, it)
                englishSong.copy(artists = it.song.artists, artistCredit = it.song.artistCredit)
            } ?: englishSong
            val snapshot = reconcileOriginalSourceSnapshot(original, identifiedSong, priorOriginals, fromAlbumPage)
            incompleteMusicIdentities = snapshot.incompleteMusicIdentities
            status = if (snapshot.names.isEmpty()) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS
            val refreshedNames = snapshot.names.map { candidate ->
                MetadataNameEntity(candidate.target.kind.name, candidate.target.id, "und", candidate.name,
                    ORIGINAL_NAME_SOURCE_PREFIX + original.videoId, sourcePriority = 10, observedAt = observedAt,
                    originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate))
            }
            recordMetadataNames(retainOriginalAssessments(previous, original.videoId,
                refreshedNames), request.state(status, now))
            saved = true
        }
        if (saved && incompleteMusicIdentities) {
            // Retrying Main must also refresh the missing Music identities, rather than reuse
            // an incomplete successful detail response throughout its seven-day cache lifetime.
            val key = "${request.authRevision}:$musicContext:${request.target.id}"
            englishSongs[key]?.takeIf { it.song == englishSong }?.let { englishSongs.remove(key, it) }
        }
        if (saved) {
            val retryAt = now + metadataRetryDelay(request, status)
            nextAttempt[request] = retryAt
            // Both routes write the same persisted Main state. A failed/empty enrichment must
            // replace the ordinary route's former seven-day success deadline as well.
            nextAttempt[request.copy(enrichment = false)] = retryAt
        } else scheduleOriginal(request.target)
    }

    private suspend fun recordFailure(request: MetadataFetchRequest) {
        val now = runtime.now()
        // Do not poison an old credential/language cache with an attempt made in a newer context.
        if (!isCurrent(request)) {
            runCatching { schedule(request.target) }
            return
        }
        nextAttempt[request] = now + metadataRetryDelay(MetadataFetchEntity.FAILED)
        if (request.enrichment) nextAttempt[request.copy(enrichment = false)] =
            now + metadataRetryDelay(MetadataFetchEntity.FAILED)
        try {
            var saved = false
            database.awaitTransaction {
                if (isCurrent(request)) {
                    recordMetadataFetch(request.state(MetadataFetchEntity.FAILED, now))
                    saved = true
                }
            }
            if (!saved) schedule(request.target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the in-memory retry time even when storage is temporarily unavailable.
        }
    }
}

/** Missing configured names leave the per-item provider text intact until acquisition succeeds. */
internal fun selectMetadataDisplayName(
    target: OriginalNameTarget,
    candidates: List<MetadataNameEntity>,
    language: String,
    preferOriginal: Boolean,
    assessments: List<OriginalNameAssessment> = emptyList(),
): String? {
    val sorted = candidates.filter { it.kind == target.kind.name && it.targetId == target.id && it.name.isNotBlank() }
        .sortedWith(compareByDescending<MetadataNameEntity> { metadataNameSourcePriority(it.source, it.sourcePriority) }
            .thenByDescending { it.observedAt }.thenBy { it.name }.thenBy { it.source })
    sorted.firstOrNull { it.source == "manual" }?.let { return it.name }
    // Detail titles can contain video decorations while the observed album alias is the exact
    // verified original. Choose that existing alias only; the policy below still rejects unknown,
    // non-English or conflicting assessments and never invents a title from an assessment alone.
    val assessedOriginal = assessments.filter { it.target == target }.map { comparableMetadataName(it.originalName) }
        .distinct().singleOrNull()
    val matchingEnglish = assessedOriginal?.let { original ->
        sorted.firstOrNull { it.language == "en" && comparableMetadataName(it.name) == original }?.name
    }
    // Main can include a formal edition suffix which Music omits. Keep the actually observed
    // original verbatim, only from a direct source or a verified ID relation. An assessment by
    // itself cannot manufacture a display name, and no suffix is guessed or stripped.
    val observedOriginal = assessedOriginal?.let { original ->
        sorted.firstOrNull { comparableMetadataName(it.name) == original &&
            (originalCandidate(it) != null || ProviderSongReferenceCodec.decode(it) != null ||
                PlaylistSongReferenceCodec.decode(it) != null) }?.name
    }
    val selection = OriginalNamePolicy.select(target,
        configuredName = sorted.firstOrNull { it.language == language }?.name,
        englishName = matchingEnglish ?: observedOriginal ?: sorted.firstOrNull { it.language == "en" }?.name,
        fallbackName = "",
        preferEnglishOriginal = preferOriginal,
        evidence = emptyList(),
        assessments = assessments,
    )
    return selection.name.takeUnless { selection.reason == OriginalNameSelectionReason.AVAILABLE_NAME_FALLBACK }
}

private fun comparableMetadataName(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)

/** List/card observations cannot outrank a dedicated detail fetch, including rows cached by older builds. */
private fun metadataNameSourcePriority(source: String, observedPriority: Int): Int = when {
    source == "detail" || source == "manual" || source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX) -> observedPriority
    else -> observedPriority.coerceAtMost(50)
}

internal data class MetadataFetchRequest(
    val target: OriginalNameTarget,
    val locale: YouTubeLocale,
    val contextKey: String,
    val original: Boolean = false,
    val authRevision: Long = 0,
    val enrichment: Boolean = false,
) {
    val storedLanguage: String get() = if (original) "und" else locale.hl
    fun state(status: String, now: Long) = MetadataFetchEntity(target.kind.name, target.id, storedLanguage, status, now, contextKey)
}

internal fun youtubeMetadataContextKey(locale: YouTubeLocale): String = YouTube.authentication.let { authentication ->
    metadataFetchContextKey(locale, authentication.useLoginForBrowse, authentication.cookie,
        authentication.dataSyncId, authentication.visitorData)
}

internal fun metadataFetchContextKey(locale: YouTubeLocale, useLogin: Boolean, cookie: String?, dataSyncId: String?,
    visitorData: String? = null): String {
    val auth = MessageDigest.getInstance("SHA-256")
        .digest("$useLogin:${cookie.orEmpty().length}:${cookie.orEmpty()}:${dataSyncId.orEmpty().length}:${dataSyncId.orEmpty()}:${visitorData.orEmpty().length}:${visitorData.orEmpty()}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    // Re-fetch authoritative names after correcting the old priority/timestamp merge policy.
    return "${locale.gl}:$auth:names-v2"
}

internal fun isMetadataFetchCurrent(request: MetadataFetchRequest, locale: YouTubeLocale, contextKey: String,
    authRevision: Long = 0): Boolean =
    request.authRevision == authRevision && request.locale.gl == locale.gl && if (request.original) {
        request.contextKey == if (request.target.kind == OriginalNameKind.ALBUM) {
            albumOriginalContextKey(contextKey)
        } else {
            originalMetadataContextKey(locale)
        }
    } else {
        request.contextKey == contextKey && request.locale.hl in setOf("en", locale.hl)
    }

internal fun originalMetadataContextKey(locale: YouTubeLocale) = "main:${locale.gl}:v3"
// v6 independently accepts source proofs and handles terminal restricted playlist omissions.
internal fun albumOriginalContextKey(contextKey: String) = "album-original-context:$contextKey:v6"
internal const val ORIGINAL_NAME_SOURCE_PREFIX = "art-track-original:"

internal fun originalCandidate(row: MetadataNameEntity): ArtTrackOriginalName? {
    if (row.language != "und" || !row.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX)) return null
    val target = OriginalNameTarget(OriginalNameKind.valueOf(row.kind), row.targetId)
    val candidate = ArtTrackOriginalNameCodec.decode(row.originEvidenceJson, target, row.name) ?: return null
    return candidate.takeIf { row.source == ORIGINAL_NAME_SOURCE_PREFIX + it.sourceVideoId }
}

/**
 * A successful Main snapshot always contains its own song row. Its timestamp also governs the
 * optional artist/album rows, including when those links disappear from a newer response.
 * Keep the older rows as search aliases, but do not use them as current classification evidence.
 * This must receive all names, before grouping by target: an artist row cannot date its source.
 */
internal fun latestOriginalRows(names: List<MetadataNameEntity>): List<MetadataNameEntity> {
    val originals = names.mapNotNull { row -> originalCandidate(row)?.let { row to it } }
    val latestSnapshots = originals.filter { (_, candidate) -> candidate.target.kind == OriginalNameKind.SONG }
        .groupBy { (_, candidate) -> candidate.sourceVideoId }
        .mapValues { (_, rows) -> rows.maxOf { (row, _) -> row.observedAt } }
    return originals.filter { (row, candidate) -> row.observedAt == latestSnapshots[candidate.sourceVideoId] }
        .map { (row, _) -> row }
        // Preserve conflicting names within one snapshot instead of choosing a lexical winner.
        .distinctBy { listOf(it.kind, it.targetId, it.source, it.name) }
        .sortedWith(compareBy(MetadataNameEntity::kind, MetadataNameEntity::targetId, MetadataNameEntity::source, MetadataNameEntity::name))
}

internal fun originalAssessmentsByTarget(names: List<MetadataNameEntity>): Map<OriginalNameTarget, List<OriginalNameAssessment>> =
    (latestOriginalRows(names).mapNotNull { row ->
        val candidate = originalCandidate(row) ?: return@mapNotNull null
        OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            ?.takeIf { it.sourceVideoId == candidate.sourceVideoId }
    } + associatedOriginalAssessments(names) + playlistAssociatedOriginalAssessments(names)).groupBy(OriginalNameAssessment::target)

/** Evaluation output is excluded; names, raw source proofs, links and withdrawals are inputs. */
internal fun originalPublicationInputKey(names: List<MetadataNameEntity>): List<MetadataNameEntity> =
    names.filter { it.language in setOf("en", "und") && it.source != "manual" }.map { row ->
        if (row.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX)) row.copy(originEvidenceJson =
            originalCandidate(row)?.let { ArtTrackOriginalNameCodec.encode(it) } ?: row.originEvidenceJson)
        else row
    }.sortedWith(compareBy(MetadataNameEntity::kind, MetadataNameEntity::targetId, MetadataNameEntity::language,
        MetadataNameEntity::source, MetadataNameEntity::name))

internal fun originalInputKey(rows: List<MetadataNameEntity>): List<Pair<ArtTrackOriginalName?, Long>> =
    rows.map { originalCandidate(it) to it.observedAt }

internal fun groupMetadataFetchRequests(requests: List<MetadataFetchRequest>, maxSongBatchSize: Int = 50): List<List<MetadataFetchRequest>> {
    require(maxSongBatchSize in 1..YouTube.MAX_GET_QUEUE_SIZE)
    return requests.distinct().groupBy { Triple(it.locale, it.contextKey to it.authRevision, it.original to it.target.kind) }
        .values.flatMap { group ->
            group.chunked(if (!group.first().original && group.first().target.kind == OriginalNameKind.SONG) maxSongBatchSize else 1)
        }
}

/** Responses can omit or reorder results; an adjacent item is never a substitute for a missing ID. */
internal fun metadataItemsByTarget(items: List<YTItem>): Map<OriginalNameTarget, YTItem> = items.mapNotNull { item ->
    val kind = when (item) {
        is SongItem -> OriginalNameKind.SONG
        is AlbumItem -> OriginalNameKind.ALBUM
        is ArtistItem -> OriginalNameKind.ARTIST
        else -> return@mapNotNull null
    }
    OriginalNameTarget(kind, item.id) to item
}.toMap()

internal fun metadataNameWithEvidence(name: MetadataNameEntity, evidence: List<OriginalNameEvidence>): MetadataNameEntity {
    fun comparable(text: String) = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFC)
    val matching = evidence.filter {
        it.target.kind.name == name.kind && it.target.id == name.targetId && comparable(it.originalName) == comparable(name.name)
    }.sortedWith(compareBy(OriginalNameEvidence::sourceUrl, { it.reviewedOn.orEmpty() }, OriginalNameEvidence::originalName))
    if (matching.isEmpty()) return name
    val existing = name.originEvidenceJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val provenance = buildJsonObject {
        existing?.forEach { (key, value) -> put(key, value) }
        put("reviewedEvidence", buildJsonArray {
            matching.forEach { evidence -> add(buildJsonObject {
                put("source", evidence.source.name)
                put("sourceUrl", evidence.sourceUrl)
                put("originalName", evidence.originalName)
                put("language", evidence.language.name)
                put("verification", evidence.verification.name)
                evidence.reviewedOn?.let { put("reviewedOn", it) }
                put("reviewNote", evidence.reviewNote)
            }) }
        })
    }
    return name.copy(originEvidenceJson = provenance.toString())
}

internal fun metadataRetryDelay(status: String): Long = when (status) {
    MetadataFetchEntity.PENDING -> 0L
    MetadataFetchEntity.SUCCESS -> 7 * 24 * 60 * 60_000L
    MetadataFetchEntity.EMPTY -> 24 * 60 * 60_000L
    else -> 5 * 60_000L
}

/** Missing song details often reflect temporary availability, not an absent translation. */
internal fun metadataRetryDelay(request: MetadataFetchRequest, status: String): Long =
    if (request.target.kind == OriginalNameKind.SONG && status == MetadataFetchEntity.EMPTY) 5 * 60_000L
    else metadataRetryDelay(status)

/** Capture only names linked to stable IDs. Never split a literal credit or infer another identity. */
internal fun metadataNameCandidates(items: List<YTItem>, language: String, source: String,
    observedAt: Long = System.currentTimeMillis()): List<MetadataNameEntity> = buildList {
    fun name(kind: OriginalNameKind, id: String?, text: String, priority: Int) {
        if (!id.isNullOrBlank() && text.isNotBlank()) add(MetadataNameEntity(kind.name, id, language,
            text, source, metadataNameSourcePriority(source, priority), observedAt))
    }
    fun artists(artists: List<Artist>, priority: Int) = artists.forEach {
        name(OriginalNameKind.ARTIST, ArtistIdentity.onlineId(it.id), it.name, priority)
    }
    items.forEach { item -> when (item) {
        is SongItem -> {
            name(OriginalNameKind.SONG, item.id, item.title, if (source == "detail") 100 else 50)
            artists(item.artistCredit?.artists ?: item.artists, 20)
            item.album?.let { name(OriginalNameKind.ALBUM, it.id, it.name, 20) }
        }
        is AlbumItem -> {
            name(OriginalNameKind.ALBUM, item.browseId, item.title, 100)
            artists(item.artistCredit?.artists ?: item.artists.orEmpty(), 30)
        }
        is ArtistItem -> name(OriginalNameKind.ARTIST, item.id, item.title, 100)
        else -> Unit
    } }
}.groupBy { listOf(it.kind, it.targetId, it.language, it.name, it.source) }
    .values.map { candidates -> candidates.maxBy { it.sourcePriority } }
