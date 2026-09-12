package com.dd3boh.outertune.repositories

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Saved albums receive their own header credits even when the album page has never been opened. */
@Singleton
class AlbumMetadataRepository internal constructor(
    private val database: MusicDatabase,
    private val context: Context,
    private val runtime: Runtime,
) {
    @Inject
    constructor(database: MusicDatabase, @ApplicationContext context: Context) : this(database, context, Runtime())

    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val now: () -> Long = System::currentTimeMillis,
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val configuration: Flow<YouTubeLocale>? = null,
        val authRevision: () -> Long = { YouTube.authRevision },
        val authUpdates: Flow<Long> = YouTube.authUpdates,
        val contextKey: (YouTubeLocale) -> String = ::youtubeMetadataContextKey,
        val fetch: suspend (String, YouTubeLocale) -> Result<AlbumItem> = { id, locale ->
            YouTube.album(id, withSongs = false, requestLocale = locale, notifyMetadata = false).map { it.album }
        },
    )

    private data class Request(val albumId: String, val locale: YouTubeLocale, val contextKey: String, val authRevision: Long) {
        val stateContext: String get() = "album-credit:$contextKey"
        fun state(status: String, now: Long) = MetadataFetchEntity(
            "ALBUM", albumId, locale.hl, status, now, stateContext,
        )
    }

    private val scope = runtime.scope
    private val pending = ConcurrentHashMap.newKeySet<Request>()
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val nextAttempt = ConcurrentHashMap<Request, Long>()
    private var initialAuthRevision = 0L
    private var started = false
    internal val pendingRequestCount: Int get() = pending.size

    @Synchronized
    fun start() {
        if (started) return
        started = true
        initialAuthRevision = runtime.authRevision()
        scope.launch {
            database.remoteAlbumsForMetadata().collect { albums -> safely { albums.forEach(::schedule) } }
        }
        scope.launch {
            val configuration = runtime.configuration
                ?: metadataRequestConfiguration(YouTube.localeUpdates, context.dataStore.data)
            combine(configuration, runtime.authUpdates) { locale, revision -> locale to revision }.collect {
                safely { refreshSavedAlbums() }
            }
        }
        repeat(2) { scope.launch { for (request in requests) fetch(request) } }
        scope.launch {
            while (isActive) {
                delay(60_000L)
                safely { refreshSavedAlbums() }
            }
        }
    }

    internal suspend fun refreshSavedAlbums() = database.remoteAlbumsForMetadata().first().forEach(::schedule)

    /** Share a header already fetched for names, using the original request's locale/auth snapshot. */
    suspend fun acceptHeader(header: AlbumItem, requestLocale: YouTubeLocale, requestContextKey: String,
        requestAuthRevision: Long = runtime.authRevision()): Boolean {
        val request = Request(header.browseId, requestLocale, requestContextKey, requestAuthRevision)
        if (!current(request)) return false
        val now = runtime.now()
        var accepted = false
        var status: String? = null
        database.awaitTransaction {
            if (!current(request)) return@awaitTransaction
            val existing = albumById(request.albumId)?.takeUnless { it.isLocal } ?: return@awaitTransaction
            accepted = applySavedAlbumHeader(existing.id, header, request.locale.hl)
            status = if (albumCreditNeedsRefresh(albumById(existing.id) ?: existing)) MetadataFetchEntity.EMPTY else MetadataFetchEntity.SUCCESS
            recordMetadataFetch(request.state(status!!, now))
        }
        status?.let { nextAttempt[request] = now + albumCreditRetryDelay(it) }
        return accepted
    }

    private fun schedule(album: AlbumEntity) {
        if (!albumCreditNeedsRefresh(album)) return
        val locale = runtime.locale()
        val revision = runtime.authRevision()
        val request = Request(album.id, locale, runtime.contextKey(locale), revision)
        if ((nextAttempt[request] ?: 0) > runtime.now()) return
        if (pending.add(request) && requests.trySend(request).isFailure) pending.remove(request)
    }

    private fun current(request: Request): Boolean {
        // Consult the published locale directly: the refresh collector may still be queued.
        val locale = runtime.locale()
        return request.authRevision == runtime.authRevision() && request.locale == locale && request.contextKey == runtime.contextKey(locale)
    }

    private suspend fun fetch(request: Request) {
        var retryInNewContext = false
        try {
            if (!current(request)) { retryInNewContext = true; return }
            val album = database.albumById(request.albumId) ?: return
            if (!albumCreditNeedsRefresh(album)) return
            val previous = database.metadataFetch("ALBUM", request.albumId, request.locale.hl, request.stateContext)
            val retryAt = previous?.takeIf { request.authRevision == initialAuthRevision }
                ?.let { it.updatedAt + albumCreditRetryDelay(it.status) } ?: 0L
            if (retryAt > runtime.now()) { nextAttempt[request] = retryAt; return }
            val header = runtime.fetch(request.albumId, request.locale).getOrThrow()
            currentCoroutineContext().ensureActive()
            require(header.browseId == request.albumId) { "Album header belongs to another ID" }
            if (!current(request)) { retryInNewContext = true; return }
            acceptHeader(header, request.locale, request.contextKey, request.authRevision)
            if (!current(request)) retryInNewContext = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current(request)) recordFailure(request) else retryInNewContext = true
        } finally {
            pending.remove(request)
            if (retryInNewContext) runCatching { database.albumById(request.albumId)?.let(::schedule) }
        }
    }

    private suspend fun recordFailure(request: Request) {
        val now = runtime.now()
        nextAttempt[request] = now + ALBUM_CREDIT_FAILURE_RETRY_MS
        safely {
            database.awaitTransaction {
                if (current(request) && albumById(request.albumId)?.isLocal == false) {
                    recordMetadataFetch(request.state(MetadataFetchEntity.FAILED, now))
                }
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A transient storage failure must not stop the background observer. */ }
    }
}

/** Shared request inputs for name and album repair; account changes also retry the same locale. */
internal fun metadataRequestConfiguration(
    locales: Flow<YouTubeLocale>,
    preferences: Flow<Preferences>,
    authUpdates: Flow<Long> = flowOf(0L),
): Flow<YouTubeLocale> = combine(locales, preferences, authUpdates) { locale, settings, revision ->
    Triple(locale, metadataFetchContextKey(locale, settings[UseLoginForBrowse] != false,
        settings[InnerTubeCookieKey], settings[DataSyncIdKey], settings[VisitorDataKey]), revision)
}.distinctUntilChanged().map { it.first }

internal fun albumCreditNeedsRefresh(album: AlbumEntity): Boolean {
    if (album.isLocal) return false
    val credit = album.artistCredit ?: return true
    if (credit.status == ArtistCreditStatus.CONFLICT) return false
    return credit.status != ArtistCreditStatus.COMPLETE || credit.artists.isEmpty() ||
        credit.artists.any { it.name.isBlank() || ArtistIdentity.onlineId(it.id) == null }
}

internal const val ALBUM_CREDIT_FAILURE_RETRY_MS = 5 * 60_000L
internal const val ALBUM_CREDIT_EMPTY_RETRY_MS = 24 * 60 * 60_000L
internal fun albumCreditRetryDelay(status: String): Long = when (status) {
    MetadataFetchEntity.PENDING -> 0L
    // Completeness is stored in the album itself. A subsequently missing credit needs a fresh request.
    MetadataFetchEntity.SUCCESS -> 0L
    MetadataFetchEntity.EMPTY -> ALBUM_CREDIT_EMPTY_RETRY_MS
    else -> ALBUM_CREDIT_FAILURE_RETRY_MS
}
