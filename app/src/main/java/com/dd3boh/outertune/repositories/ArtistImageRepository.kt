package com.dd3boh.outertune.repositories

import android.content.Context
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** Repairs saved artist profiles independently of the name cache and the currently open screen. */
@Singleton
class ArtistImageRepository internal constructor(
    private val database: MusicDatabase,
    context: Context,
    private val runtime: Runtime,
) {
    @Inject
    constructor(database: MusicDatabase, @ApplicationContext context: Context) : this(database, context, Runtime())

    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val now: () -> Long = System::currentTimeMillis,
        val locale: () -> YouTubeLocale = { YouTube.locale.copy(hl = "en") },
        val contextKey: (YouTubeLocale) -> String = ::youtubeMetadataContextKey,
        val fetch: suspend (String, YouTubeLocale) -> Result<ArtistItem> = { id, locale ->
            YouTube.artist(id, requestLocale = locale, notifyMetadata = false).map { it.artist }
        },
    )

    private data class Request(val onlineId: String, val locale: YouTubeLocale, val contextKey: String) {
        val key: String get() = "$contextKey:$onlineId"
    }

    private val scope = runtime.scope
    private val preferences = context.getSharedPreferences("artist_image_retries", Context.MODE_PRIVATE)
    private val pending = ConcurrentHashMap.newKeySet<Request>()
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            database.allRemoteArtists().collect { artists -> safely { artists.forEach(::schedule) } }
        }
        repeat(2) { scope.launch { for (request in requests) fetch(request) } }
        scope.launch {
            while (isActive) {
                delay(60_000L)
                safely { refreshSavedArtists() }
            }
        }
    }

    internal suspend fun refreshSavedArtists() = database.allRemoteArtists().first().forEach(::schedule)
    internal val pendingRequestCount: Int get() = pending.size

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the singleton alive after a temporary database/cache failure. */ }
    }

    private fun schedule(artist: ArtistEntity) {
        val id = artist.onlineArtistId ?: return
        if (!artistImageNeedsRefresh(artist, runtime.now())) return
        val locale = runtime.locale()
        val request = Request(id, locale, runtime.contextKey(locale))
        if (preferences.getLong(request.key, 0L) > runtime.now()) return
        if (pending.add(request) && requests.trySend(request).isFailure) pending.remove(request)
    }

    private fun current(request: Request): Boolean {
        val locale = runtime.locale()
        return request.locale.gl == locale.gl && request.contextKey == runtime.contextKey(locale)
    }

    private suspend fun fetch(request: Request) {
        var retryInNewContext = false
        try {
            if (!current(request)) { retryInNewContext = true; return }
            val existing = database.artistByOnlineId(request.onlineId) ?: return
            // Another route can fill the image while this request waits behind other artists.
            if (!artistImageNeedsRefresh(existing, runtime.now())) return
            if (preferences.getLong(request.key, 0L) > runtime.now()) return
            val profile = runtime.fetch(request.onlineId, request.locale).getOrThrow()
            currentCoroutineContext().ensureActive()
            require(profile.id == request.onlineId) { "Artist profile belongs to another ID" }
            if (!current(request)) { retryInNewContext = true; return }
            val now = runtime.now()
            var saved = false
            database.awaitTransaction {
                if (current(request)) {
                    saveArtistProfile(profile, LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()))
                    saved = true
                }
            }
            if (!saved) { retryInNewContext = true; return }
            if (profile.thumbnail.isNullOrBlank()) {
                preferences.edit().putLong(request.key, now + IMAGE_EMPTY_RETRY_MS).apply()
            } else {
                // A successful cache lives in the image row itself. If that row is later recreated
                // without an image, a preferences-only success must not block repair for ten days.
                preferences.edit().remove(request.key).apply()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current(request)) {
                runCatching { preferences.edit().putLong(request.key, runtime.now() + IMAGE_FAILURE_RETRY_MS).apply() }
            } else retryInNewContext = true
        } finally {
            pending.remove(request)
            if (retryInNewContext) runCatching { database.artistByOnlineId(request.onlineId)?.let(::schedule) }
        }
    }
}

internal const val IMAGE_REFRESH_MS = 10 * 24 * 60 * 60_000L
internal const val IMAGE_EMPTY_RETRY_MS = 24 * 60 * 60_000L
internal const val IMAGE_FAILURE_RETRY_MS = 5 * 60_000L

internal fun artistImageNeedsRefresh(artist: ArtistEntity, now: Long): Boolean {
    if (artist.onlineArtistId == null) return false
    if (artist.thumbnailUrl.isNullOrBlank()) return true
    val updatedAt = artist.lastUpdateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return now - updatedAt >= IMAGE_REFRESH_MS
}
