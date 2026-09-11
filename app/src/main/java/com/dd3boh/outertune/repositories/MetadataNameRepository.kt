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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow

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
        database, context, Runtime(acceptAlbumHeader = { album, locale, contextKey -> albums.acceptHeader(album, locale, contextKey); Unit }),
    )

    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val localeUpdates: Flow<YouTubeLocale> = YouTube.localeUpdates,
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
        val artist: suspend (String, YouTubeLocale) -> Result<ArtistItem> = { id, locale ->
            YouTube.artist(id, requestLocale = locale, notifyMetadata = false).map { it.artist }
        },
        val main: suspend (String, YouTubeLocale) -> Result<ArtTrackOriginalMetadata> = { id, locale ->
            YouTube.artTrackOriginalMetadata(id, locale)
        },
        val acceptAlbumHeader: suspend (AlbumItem, YouTubeLocale, String) -> Unit = { _, _, _ -> },
        val assessOriginals: suspend (List<ArtTrackOriginalName>, Long) -> List<OriginalNameAssessment> = OriginalAlbumLanguageResolver()::assess,
    )

    private val scope = runtime.scope
    private val packets = Channel<Packet>(Channel.UNLIMITED)
    private val fetches = Channel<MetadataFetchRequest>(Channel.UNLIMITED)
    private val batches = Channel<List<MetadataFetchRequest>>(Channel.BUFFERED)
    private val scheduled = ConcurrentHashMap.newKeySet<MetadataFetchRequest>()
    private val nextAttempt = ConcurrentHashMap<MetadataFetchRequest, Long>()
    private val knownArtTracks = ConcurrentHashMap.newKeySet<OriginalNameTarget>()
    private val originalEvaluations = Channel<List<MetadataNameEntity>>(Channel.CONFLATED)
    private val englishSongs = ConcurrentHashMap<String, SongItem>()
    private val currentLocale: YouTubeLocale get() = runtime.locale()
    private var started = false

    private data class Packet(val items: List<YTItem>, val locale: YouTubeLocale, val source: String, val contextKey: String)
    private data class Settings(val locale: YouTubeLocale, val preferOriginal: Boolean)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        runtime.observeMetadata { items, locale, source ->
            packets.trySend(Packet(items.toList(), locale, source, runtime.contextKey(locale)))
            Unit
        }
        scope.launch {
            for (packet in packets) keepCollectorRunning {
                // The observer's callback may wait in the packet queue across a setting/account change.
                if (packet.locale.gl != currentLocale.gl || packet.contextKey != runtime.contextKey(currentLocale)) {
                    packet.items.forEach { scheduleItem(it) }
                    return@keepCollectorRunning
                }
                val names = metadataNameCandidates(packet.items, packet.locale.hl, packet.source)
                var saved = false
                database.awaitTransaction {
                    if (packet.locale.gl == currentLocale.gl && packet.contextKey == runtime.contextKey(currentLocale)) {
                        recordMetadataNames(names)
                        saved = true
                    }
                }
                if (!saved) {
                    packet.items.forEach(::scheduleItem)
                    return@keepCollectorRunning
                }
                names.map { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
                    .distinct().forEach(::schedule)
            }
        }
        scope.launch {
            while (isActive) {
                val first = fetches.receiveCatching().getOrNull() ?: break
                // One short collection window gathers alternating English/configured-language requests.
                delay(25)
                val pending = mutableListOf(first)
                while (pending.size < 100) pending += fetches.tryReceive().getOrNull() ?: break
                groupMetadataFetchRequests(pending).forEach { batches.send(it) }
            }
        }
        repeat(3) { scope.launch { for (batch in batches) fetchBatch(batch) } }
        val preferences = runtime.preferences ?: context.dataStore.data
        val settings = combine(runtime.localeUpdates, preferences.map { it[PreferEnglishOriginalKey] ?: false }
            .distinctUntilChanged()) { locale, preferOriginal -> Settings(locale, preferOriginal) }
        scope.launch {
            metadataRequestConfiguration(runtime.localeUpdates, preferences).collect {
                keepCollectorRunning { refreshTargets() }
            }
        }
        scope.launch {
            combine(database.allMetadataNames(), settings) { names, options -> names to options }
                .collectLatest { (names, options) ->
                    val grouped = names.groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
                    val assessments = originalAssessmentsByTarget(names)
                    val selected = grouped.mapNotNull { (target, candidates) ->
                        selectMetadataDisplayName(target, candidates, options.locale.hl,
                            options.preferOriginal, assessments[target].orEmpty())?.let { target to it }
                    }.toMap()
                    withContext(Dispatchers.Main) {
                        if (options.locale == currentLocale) {
                            runtime.publishNames(selected, grouped.mapValues { (_, names) -> names.map { it.name }.distinct() })
                        }
                    }
                }
        }
        scope.launch {
            database.metadataLibraryTargets().collect { targets -> keepCollectorRunning {
                targets.forEach { schedule(OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId)) }
            } }
        }
        scope.launch {
            database.allMetadataNames().map(::latestOriginalRows).distinctUntilChangedBy(::originalInputKey)
                .collect { originalEvaluations.trySend(it) }
        }
        scope.launch {
            for (rows in originalEvaluations) keepCollectorRunning { evaluateOriginals(rows) }
        }
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                keepCollectorRunning { refreshTargets() }
                keepCollectorRunning { originalEvaluations.trySend(latestOriginalRows(database.metadataNameSnapshot())) }
            }
        }
    }

    private suspend fun keepCollectorRunning(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A transient DB/cache failure must not permanently stop a singleton collector. */ }
    }

    private suspend fun evaluateOriginals(rows: List<MetadataNameEntity>) {
        if (rows.isEmpty()) return
        val candidates = rows.mapNotNull(::originalCandidate)
        val assessments = runtime.assessOriginals(candidates, runtime.now())
            .associateBy { Triple(it.target, it.originalName, it.sourceVideoId) }
        val updates = rows.mapNotNull { row ->
            val candidate = originalCandidate(row) ?: return@mapNotNull null
            val assessment = assessments[Triple(candidate.target, candidate.name, candidate.sourceVideoId)] ?: return@mapNotNull null
            val prior = OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            if (prior?.inputFingerprint == assessment.inputFingerprint && prior.method == assessment.method) null
            else row.copy(originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate, assessment))
        }
        if (updates.isEmpty()) return
        database.awaitTransaction {
            // A new original or album context arriving during classification supersedes this batch.
            if (originalInputKey(latestOriginalRows(metadataNameSnapshot())) == originalInputKey(rows)) recordMetadataNames(updates)
        }
    }

    private suspend fun refreshTargets() {
        (database.allMetadataTargets() + database.metadataLibraryTargets().first()).distinct()
            .forEach { schedule(OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId)) }
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
        val contextKey = runtime.contextKey(locale)
        for (language in listOf("en", locale.hl).distinct()) {
            enqueue(MetadataFetchRequest(target, locale.copy(hl = language), contextKey))
        }
        // Queue persistence creates album stubs for every search result in the queue. Expand only
        // albums explicitly saved/bookmarked/downloaded, not every unplayed queued album.
        if (target.kind == OriginalNameKind.ALBUM && database.isAlbumOriginalContextEligible(target.id)) {
            enqueue(MetadataFetchRequest(target, locale.copy(hl = "en"), albumOriginalContextKey(contextKey), original = true))
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
        enqueue(MetadataFetchRequest(target, locale, originalMetadataContextKey(locale), original = true))
    }

    private fun enqueue(request: MetadataFetchRequest) {
        if ((nextAttempt[request] ?: 0) > runtime.now()) return
        if (scheduled.add(request) && fetches.trySend(request).isFailure) scheduled.remove(request)
    }

    private fun isCurrent(request: MetadataFetchRequest): Boolean {
        val locale = currentLocale
        return isMetadataFetchCurrent(request, locale, runtime.contextKey(locale))
    }

    private fun requeueIfObsolete(request: MetadataFetchRequest): Boolean {
        if (isCurrent(request)) return false
        schedule(request.target)
        return true
    }

    private fun due(request: MetadataFetchRequest): Boolean {
        if (requeueIfObsolete(request)) return false
        val previous = database.metadataFetch(request.target.kind.name, request.target.id, request.storedLanguage, request.contextKey)
        val expires = previous?.let { it.updatedAt + metadataRetryDelay(it.status) } ?: 0L
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
                OriginalNameKind.SONG -> runtime.queue(dueRequests.map { it.target.id }, first.locale).getOrThrow()
                OriginalNameKind.ALBUM -> listOf(runtime.album(first.target.id, first.locale).getOrThrow())
                OriginalNameKind.ARTIST -> listOf(runtime.artist(first.target.id, first.locale).getOrThrow())
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
            runtime.acceptAlbumHeader(item, request.locale, request.contextKey)
        }
        nextAttempt[request] = now + metadataRetryDelay(status)
        // Embedded names also need both locales and their own authoritative header. A detail
        // fetch never expands artist sections or album tracks, so this remains bounded by IDs
        // actually returned; TTL and scheduled keys share repeated requests.
        names.map { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
            .distinct().forEach(::schedule)
        if (isArtTrack && request.locale.hl == "en") {
            if (englishSongs.size > 1024) englishSongs.clear()
            englishSongs[runtime.contextKey(request.locale) + ":" + request.target.id] = song!!
            knownArtTracks.add(request.target)
            scheduleOriginal(request.target)
        }
    }

    private suspend fun captureAlbumOriginals(request: MetadataFetchRequest) {
        if (requeueIfObsolete(request) || !database.isAlbumOriginalContextEligible(request.target.id)) return
        val contextKey = runtime.contextKey(request.locale)
        val songs = runtime.albumContext(request.target.id, request.locale).getOrThrow()
        currentCoroutineContext().ensureActive()
        val now = runtime.now()
        var saved = false
        database.awaitTransaction {
            if (!isCurrent(request) || contextKey != runtime.contextKey(request.locale) ||
                !isAlbumOriginalContextEligible(request.target.id)) return@awaitTransaction
            recordMetadataNames(metadataNameCandidates(songs, "en", "album-original-context", now),
                request.state(if (songs.isEmpty()) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS, now))
            saved = true
        }
        if (!saved) return
        nextAttempt[request] = now + metadataRetryDelay(if (songs.isEmpty()) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS)
        songs.forEach { schedule(OriginalNameTarget(OriginalNameKind.SONG, it.id)) }
    }

    private suspend fun captureOriginalTitle(request: MetadataFetchRequest) {
        if (requeueIfObsolete(request)) return
        val musicContext = runtime.contextKey(request.locale)
        val englishSong = englishSongs[musicContext + ":" + request.target.id]
            ?: runtime.queue(listOf(request.target.id), request.locale).getOrThrow().singleOrNull { it.id == request.target.id }
            ?: error("Missing exact Art Track identity")
        val original = runtime.main(request.target.id, request.locale).getOrThrow()
        currentCoroutineContext().ensureActive()
        require(original.videoId == request.target.id) { "Main metadata belongs to another video" }
        if (requeueIfObsolete(request) || musicContext != runtime.contextKey(request.locale)) return
        val now = runtime.now()
        val names = artTrackOriginalNames(original, englishSong).map { candidate ->
            MetadataNameEntity(candidate.target.kind.name, candidate.target.id, "und", candidate.name,
                ORIGINAL_NAME_SOURCE_PREFIX + original.videoId, sourcePriority = 10, observedAt = now,
                originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate))
        }
        val status = if (names.isEmpty()) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS
        var saved = false
        database.awaitTransaction {
            if (!isCurrent(request) || musicContext != runtime.contextKey(request.locale)) return@awaitTransaction
            recordMetadataNames(metadataNameCandidates(listOf(englishSong), "en", "detail", now))
            recordMetadataNames(names, request.state(status, now))
            saved = true
        }
        if (saved) nextAttempt[request] = now + metadataRetryDelay(status) else scheduleOriginal(request.target)
    }

    private suspend fun recordFailure(request: MetadataFetchRequest) {
        val now = runtime.now()
        // Do not poison an old credential/language cache with an attempt made in a newer context.
        if (!isCurrent(request)) {
            runCatching { schedule(request.target) }
            return
        }
        nextAttempt[request] = now + metadataRetryDelay(MetadataFetchEntity.FAILED)
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
        .sortedWith(compareByDescending<MetadataNameEntity> { it.sourcePriority }
            .thenByDescending { it.observedAt }.thenBy { it.name }.thenBy { it.source })
    sorted.firstOrNull { it.source == "manual" }?.let { return it.name }
    val selection = OriginalNamePolicy.select(target,
        configuredName = sorted.firstOrNull { it.language == language }?.name,
        englishName = sorted.firstOrNull { it.language == "en" }?.name,
        fallbackName = "",
        preferEnglishOriginal = preferOriginal,
        evidence = emptyList(),
        assessments = assessments,
    )
    return selection.name.takeUnless { selection.reason == OriginalNameSelectionReason.AVAILABLE_NAME_FALLBACK }
}

internal data class MetadataFetchRequest(
    val target: OriginalNameTarget,
    val locale: YouTubeLocale,
    val contextKey: String,
    val original: Boolean = false,
) {
    val storedLanguage: String get() = if (original) "und" else locale.hl
    fun state(status: String, now: Long) = MetadataFetchEntity(target.kind.name, target.id, storedLanguage, status, now, contextKey)
}

internal fun youtubeMetadataContextKey(locale: YouTubeLocale): String = metadataFetchContextKey(
    locale, YouTube.useLoginForBrowse, YouTube.cookie, YouTube.dataSyncId,
)

internal fun metadataFetchContextKey(locale: YouTubeLocale, useLogin: Boolean, cookie: String?, dataSyncId: String?): String {
    val auth = MessageDigest.getInstance("SHA-256")
        .digest("$useLogin:${cookie.orEmpty().length}:${cookie.orEmpty()}:${dataSyncId.orEmpty().length}:${dataSyncId.orEmpty()}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    // Re-fetch authoritative names after correcting the old priority/timestamp merge policy.
    return "${locale.gl}:$auth:names-v2"
}

internal fun isMetadataFetchCurrent(request: MetadataFetchRequest, locale: YouTubeLocale, contextKey: String): Boolean =
    request.locale.gl == locale.gl && if (request.original) {
        request.contextKey == if (request.target.kind == OriginalNameKind.ALBUM) {
            albumOriginalContextKey(contextKey)
        } else {
            originalMetadataContextKey(locale)
        }
    } else {
        request.contextKey == contextKey && request.locale.hl in setOf("en", locale.hl)
    }

internal fun originalMetadataContextKey(locale: YouTubeLocale) = "main:${locale.gl}:v3"
internal fun albumOriginalContextKey(contextKey: String) = "album-original-context:$contextKey"
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
    latestOriginalRows(names).mapNotNull { row ->
        val candidate = originalCandidate(row) ?: return@mapNotNull null
        OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            ?.takeIf { it.sourceVideoId == candidate.sourceVideoId }
    }.groupBy(OriginalNameAssessment::target)

internal fun originalInputKey(rows: List<MetadataNameEntity>): List<Pair<ArtTrackOriginalName?, Long>> =
    rows.map { originalCandidate(it) to it.observedAt }

internal fun groupMetadataFetchRequests(requests: List<MetadataFetchRequest>, maxSongBatchSize: Int = 50): List<List<MetadataFetchRequest>> {
    require(maxSongBatchSize in 1..YouTube.MAX_GET_QUEUE_SIZE)
    return requests.distinct().groupBy { Triple(it.locale, it.contextKey, it.original to it.target.kind) }
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

/** Capture only names linked to stable IDs. Never split a literal credit or infer another identity. */
internal fun metadataNameCandidates(items: List<YTItem>, language: String, source: String,
    observedAt: Long = System.currentTimeMillis()): List<MetadataNameEntity> = buildList {
    fun name(kind: OriginalNameKind, id: String?, text: String, priority: Int) {
        if (!id.isNullOrBlank() && text.isNotBlank()) add(MetadataNameEntity(kind.name, id, language,
            text, source, priority, observedAt))
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
