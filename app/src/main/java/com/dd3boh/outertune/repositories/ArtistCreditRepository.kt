package com.dd3boh.outertune.repositories

import android.content.Context
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.withChannelFallback
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditResolution
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.isEmptyByline
import com.zionhuang.innertube.models.merge
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shares accepted track credits without adding search results to the music library. */
@Singleton
class ArtistCreditRepository internal constructor(
    private val database: MusicDatabase,
    context: Context,
    private val runtime: Runtime,
) {
    @Inject
    constructor(database: MusicDatabase, @ApplicationContext context: Context) :
        this(database, context, Runtime())

    /** Boundary dependencies make response ordering testable without issuing live requests. */
    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val fetch: suspend (SongItem) -> Result<ArtistCreditResolution> = { YouTube.resolveTrackArtistCredit(it) },
        val contextToken: () -> String = ::youtubeArtistContextToken,
        val authRevision: () -> Long = { YouTube.authRevision },
        val language: () -> String = { YouTube.locale.hl },
        val now: () -> Long = System::currentTimeMillis,
    )

    data class ArtistContext(
        val id: String,
        val name: String,
        val onlineId: String?,
        val sourceSongs: List<MediaMetadata>,
        val isChannel: Boolean = false,
        val sourceChannelId: String? = null,
    )

    private val scope = runtime.scope
    private val preferences = context.getSharedPreferences("track_artist_credits", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val states = ConcurrentHashMap<String, MutableStateFlow<ArtistCredit?>>()
    private val albums = ConcurrentHashMap<String, MutableStateFlow<Album?>>()
    private val contexts = ConcurrentHashMap<String, MutableStateFlow<ArtistContext?>>()
    private val sourceSongs = ConcurrentHashMap<String, MediaMetadata>()
    private val jobs = mutableMapOf<String, Job>()
    private val priorityJobs = mutableSetOf<String>()
    private val resolving = mutableSetOf<String>()
    private val loaded = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(2)
    // Reserve capacity for an explicit selection while visible rows resolve in the background.
    private val backgroundPermit = Semaphore(1)
    private val lock = Any()
    private val mutableUpdates = MutableSharedFlow<Pair<String, ArtistCredit>>(extraBufferCapacity = 128)
    val updates: SharedFlow<Pair<String, ArtistCredit>> = mutableUpdates.asSharedFlow()

    private fun contextKey(): String {
        // Authentication can change back to the same credentials while an earlier request is
        // suspended. Keep the generation in memory, and read the token within that generation.
        while (true) {
            val revision = runtime.authRevision()
            val token = runtime.contextToken()
            if (revision == runtime.authRevision()) return "$revision:$token"
        }
    }

    fun contextToken(): String = contextKey()

    private fun key(videoId: String, context: String = contextKey()) = "$context:$videoId"

    // A successful credit/album remains useful after a process restart. Retry delays and jobs
    // belong to one authentication generation, while their content uses the stable auth token.
    private fun storedKey(cacheKey: String): String = cacheKey.substringAfter(':')

    fun observe(videoId: String): StateFlow<ArtistCredit?> {
        val cacheKey = key(videoId)
        return states.getOrPut(cacheKey) { MutableStateFlow(readCache(cacheKey)) }
    }

    private fun readCache(cacheKey: String, requestLanguage: String = runtime.language()): ArtistCredit? {
        val credit = runCatching {
            preferences.getString(storedKey(cacheKey), null)?.let { json.decodeFromString<ArtistCredit>(it) }
        }.getOrNull() ?: return null
        if (credit.language.isNotEmpty() && credit.language != requestLanguage) {
            // Older entries could be filed under a different request language. Neither their
            // byline nor their retry delay is valid evidence for this language's next request.
            preferences.edit().remove(storedKey(cacheKey)).remove("retry:$cacheKey").apply()
            return null
        }
        return credit
    }

    fun observeAlbum(videoId: String): StateFlow<Album?> {
        val cacheKey = key(videoId)
        return albums.getOrPut(cacheKey) { MutableStateFlow(runCatching {
            preferences.getString("album:${storedKey(cacheKey)}", null)?.let { json.decodeFromString<Album>(it) }
        }.getOrNull()) }
    }

    fun artistContext(artistId: String): StateFlow<ArtistContext?> =
        contexts.getOrPut(key(artistId)) { MutableStateFlow(null) }

    fun withCredit(song: SongItem): SongItem {
        val album = song.album ?: observeAlbum(song.id).value
        val credit = combinedCredit(song.id, song.artistCredit) ?: return song.copy(album = album)
        return song.copy(album = album, artistCredit = credit, artists = credit.artists.takeIf { it.isNotEmpty() } ?: song.artists)
    }

    fun withCredit(metadata: MediaMetadata): MediaMetadata {
        if (metadata.isLocal) return metadata
        val credit = combinedCredit(metadata.id, metadata.artistCredit) ?: return metadata.copy(
            album = metadata.album ?: observeAlbum(metadata.id).value?.let { MediaMetadata.Album(it.id, it.name) },
        )
        return apply(metadata, credit)
    }

    private fun combinedCredit(videoId: String, source: ArtistCredit?): ArtistCredit? {
        // Changing language creates an empty RAW request state. It is not evidence that
        // previously identified people disappeared, including when the new request is offline.
        val cached = observe(videoId).value?.takeUnless { it.isEmptyByline() }
        val merged = if (cached != null && source != null) cached.merge(source) else cached ?: source
        return merged?.let { ArtistIdentity.withStableRefs(videoId, it, cached) }
    }

    fun adopt(metadata: MediaMetadata): MediaMetadata {
        request(metadata, priority = true)
        return withCredit(metadata)
    }

    internal fun acceptUploader(metadata: MediaMetadata, author: String, channelId: String?, musicVideoType: String?): MediaMetadata {
        val candidate = metadata.withChannelFallback(author, channelId, musicVideoType)
        if (candidate == metadata) return withCredit(metadata)
        val cacheKey = key(metadata.id)
        sourceSongs.putIfAbsent(cacheKey, metadata)
        publish(metadata.id, cacheKey, candidate.artistCredit!!, persist = true)
        return withCredit(candidate)
    }

    fun request(metadata: MediaMetadata, priority: Boolean = false) {
        if (metadata.isLocal) return
        sourceSongs[key(metadata.id)] = metadata
        request(
            SongItem(
                id = metadata.id,
                title = metadata.title,
                artists = metadata.artists.map { Artist(it.name,
                    if (it.isChannel) null else it.onlineId ?: it.id?.takeIf(::isOnlineId), it.id,
                    sourceChannelId = it.sourceChannelId, isChannel = it.isChannel) },
                album = metadata.album?.let { Album(it.title, it.id) },
                duration = metadata.duration.takeIf { it >= 0 },
                thumbnail = metadata.thumbnailUrl.orEmpty(),
                artistCredit = metadata.artistCredit,
            ), priority,
        )
    }

    /** Call for settled visible rows or explicitly selected tracks, never for entire result pages. */
    fun request(song: SongItem, priority: Boolean = false) {
        val requestContext = contextKey()
        val cacheKey = key(song.id, requestContext)
        val requestLanguage = runtime.language()
        sourceSongs.putIfAbsent(cacheKey, song.toMediaMetadata())
        val initial = (song.artistCredit ?: legacyCredit(song)).let {
            if (it.language.isNotEmpty() && it.language != requestLanguage)
                ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "context-refresh", requestLanguage)
            else it
        }
        publish(song.id, cacheKey, initial, persist = false)
        observeAlbum(song.id)
        song.album?.let { publishAlbum(song.id, cacheKey, it) }
        synchronized(lock) {
            if (jobs[cacheKey]?.isActive == true) {
                if (!priority || cacheKey in priorityJobs || cacheKey in resolving) return
                // Promote a queued visible-row request; an already running HTTP request is shared.
                jobs[cacheKey]?.cancel()
            }
            if (priority) priorityJobs.add(cacheKey)
            jobs[cacheKey] = scope.launch {
                try {
                    if (cacheKey !in loaded) {
                        if (requestContext != contextKey()) return@launch
                        readCache(cacheKey, requestLanguage)?.let {
                            publish(song.id, cacheKey, it, persist = false, preferStoredRefs = true)
                        }
                        val stored = database.artistCredit(song.id).first()
                        if (requestContext != contextKey()) return@launch
                        stored?.takeIf { it.language.isEmpty() || it.language == initial.language }
                            ?.let { publish(song.id, cacheKey, it, persist = false, preferStoredRefs = true) }
                        database.songForArtistCredit(song.id)?.let { row ->
                            if (requestContext == contextKey() && row.albumId != null && row.albumName != null)
                                publishAlbum(song.id, cacheKey, Album(row.albumName, row.albumId))
                        }
                        // Mark only a completed load. A promoted/cancelled job must not make
                        // its replacement skip the initial database read.
                        loaded.add(cacheKey)
                    }
                    if (requestContext != contextKey()) return@launch
                    var current = states[cacheKey]?.value ?: initial
                    preferences.edit().putString(storedKey(cacheKey), json.encodeToString(current)).apply()
                    // Also apply a warm-cache result when the song was saved after it was resolved.
                    applyCredit(song.id, cacheKey, current)
                    applyKnownAlbum(song.id, cacheKey)
                    if (requestContext != contextKey()) return@launch
                    val saved = database.artistCredit(song.id).first()
                    if (requestContext != contextKey()) return@launch
                    saved?.takeIf { it.language.isEmpty() || it.language == initial.language }?.let {
                        current = publish(song.id, cacheKey, it, persist = true, preferStoredRefs = true)
                    }
                    val retryAt = preferences.getLong("retry:$cacheKey", 0L)
                    if (!needsResolution(current, cacheKey) || runtime.now() < retryAt) return@launch
                    suspend fun resolve() {
                        permits.withPermit {
                            if (requestContext != contextKey()) return@withPermit
                            val latest = states[cacheKey]?.value ?: current
                            if (!needsResolution(latest, cacheKey)) return@withPermit
                            synchronized(lock) { resolving.add(cacheKey) }
                            val result = runtime.fetch(withCredit(song).copy(artistCredit = latest))
                            if (requestContext != contextKey()) return@withPermit
                            result.onSuccess { resolution ->
                                resolution.album?.let { publishAlbum(song.id, cacheKey, it) }
                                val incoming = resolution.credit
                                val accepted = publish(song.id, cacheKey, incoming, persist = true)
                                applyCredit(song.id, cacheKey, accepted)
                                applyKnownAlbum(song.id, cacheKey)
                                // The database may have resolved an existing online identity or an alias.
                                val stored = database.artistCredit(song.id).first()
                                if (requestContext != contextKey()) return@withPermit
                                stored?.takeIf { it.language.isEmpty() || it.language == initial.language }?.let {
                                    publish(song.id, cacheKey, it, persist = true, preferStoredRefs = true)
                                }
                                val retryDelay = if (incoming.evidence.any { it.startsWith("retry:") }) 60_000L else 30 * 60_000L
                                preferences.edit().putLong("retry:$cacheKey", runtime.now() + retryDelay).apply()
                            }.onFailure {
                                if (it is CancellationException) throw it
                                preferences.edit().putLong("retry:$cacheKey", runtime.now() + 60_000L).apply()
                            }
                        }
                    }
                    if (priority) resolve() else backgroundPermit.withPermit { resolve() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed DB/cache operation must not escape a SupervisorJob into the app.
                    runCatching {
                        if (requestContext == contextKey()) preferences.edit()
                            .putLong("retry:$cacheKey", runtime.now() + 60_000L).apply()
                    }
                } finally {
                    synchronized(lock) {
                        if (jobs[cacheKey] === coroutineContext[Job]) {
                            jobs.remove(cacheKey)
                            priorityJobs.remove(cacheKey)
                            resolving.remove(cacheKey)
                        }
                    }
                }
            }
        }
    }

    private fun legacyCredit(song: SongItem): ArtistCredit {
        val complete = song.artists.size > 1 || song.artists.singleOrNull()?.let { it.id != null || it.isChannel } == true
        return ArtistCredit(
            rawText = song.artists.joinToString(", ") { it.name },
            artists = if (complete) song.artists else emptyList(),
            status = if (complete) ArtistCreditStatus.COMPLETE else ArtistCreditStatus.RAW,
            source = "track",
            language = runtime.language(),
        )
    }

    private fun needsResolution(credit: ArtistCredit, cacheKey: String) =
        credit.status != ArtistCreditStatus.CONFLICT &&
            credit.artists.none { it.isChannel } &&
            // Video album rows may omit the byline; fetch that video's own basic credit once it is needed.
            (credit.isEmptyByline() || credit.evidence.none { it.startsWith("video-source:") }) &&
            (credit.status != ArtistCreditStatus.COMPLETE || credit.artists.any { it.id == null } || albums[cacheKey]?.value == null)

    private fun publishAlbum(videoId: String, cacheKey: String, album: Album) = synchronized(lock) {
        if (cacheKey != key(videoId) || album.id.isBlank() || album.name.isBlank()) return@synchronized
        val state = albums.getOrPut(cacheKey) { MutableStateFlow(null) }
        if (state.value != null) return@synchronized
        state.value = album
        preferences.edit().putString("album:${storedKey(cacheKey)}", json.encodeToString(album)).apply()
        states[cacheKey]?.value?.let { credit ->
            publish(videoId, cacheKey, credit, persist = false)
            mutableUpdates.tryEmit(videoId to credit)
        }
    }

    private suspend fun applyCredit(videoId: String, cacheKey: String, credit: ArtistCredit) {
        database.awaitTransaction {
            // A Room transaction can wait behind another writer while the account changes.
            if (cacheKey == key(videoId)) applyArtistCredit(videoId, credit)
        }
    }

    private suspend fun applyKnownAlbum(videoId: String, cacheKey: String) {
        val album = albums[cacheKey]?.value ?: return
        database.awaitTransaction {
            if (cacheKey != key(videoId)) return@awaitTransaction
            val saved = songForArtistCredit(videoId) ?: return@awaitTransaction
            if (saved.isLocal || saved.albumId != null) return@awaitTransaction
            // Insert only when a saved track already exists; search results remain unsaved.
            sourceSongs[cacheKey]?.let { source ->
                insert(withCredit(source).copy(album = MediaMetadata.Album(album.id, album.name)))
            }
        }
    }

    private fun publish(
        videoId: String,
        cacheKey: String,
        incoming: ArtistCredit,
        persist: Boolean,
        preferStoredRefs: Boolean = false,
    ): ArtistCredit = synchronized(lock) {
        val state = states.getOrPut(cacheKey) { MutableStateFlow(null) }
        val old = state.value
        if (cacheKey != key(videoId)) return@synchronized old ?: incoming
        val merged = old?.merge(incoming) ?: incoming
        fun findArtist(artists: List<Artist>, artist: Artist): Artist? {
            val sameSource = artists.filter { it.isChannel == artist.isChannel }
            return if (artist.isChannel) sameSource.singleOrNull {
                it.sourceChannelId == artist.sourceChannelId && (artist.sourceChannelId != null || it.name == artist.name)
            } else artist.id?.let { id -> sameSource.singleOrNull { it.id == id } }
                ?: sameSource.singleOrNull { it.name == artist.name && (it.id == null || artist.id == null || it.id == artist.id) }
        }
        val accepted = merged.copy(artists = merged.artists.map { artist ->
            val previous = findArtist(old?.artists.orEmpty(), artist)
            val stored = findArtist(incoming.artists, artist)
            val ref = if (preferStoredRefs) stored?.ref ?: artist.ref ?: previous?.ref else previous?.ref ?: artist.ref
            artist.copy(ref = if (artist.isChannel) ArtistIdentity.sourceId(videoId, artist)
                else ref ?: ArtistIdentity.sourceId(videoId, artist))
        })
        if (accepted != old) {
            state.value = accepted
            if (cacheKey == key(videoId)) mutableUpdates.tryEmit(videoId to accepted)
        }
        // Warm-cache flows can already contain this exact credit before a visible row
        // supplies its source track. The context still needs to be populated in that case.
        sourceSongs[cacheKey]?.takeIf { cacheKey == key(videoId) }?.let { original ->
            val updated = apply(original, accepted)
            sourceSongs[cacheKey] = updated
            val acceptedRefs = accepted.artists.mapNotNull { it.ref }.toSet()
            old?.artists.orEmpty().filter { it.isChannel && it.ref !in acceptedRefs }.forEach { artist ->
                artist.ref?.let { previousRef ->
                    contexts[key(previousRef)]?.let { contextState ->
                        contextState.value?.let { previous ->
                            val remaining = previous.sourceSongs.filterNot { it.id == videoId }
                            contextState.value = if (remaining.isEmpty()) null else previous.copy(sourceSongs = remaining)
                        }
                    }
                }
            }
            accepted.artists.forEach { artist ->
                val artistId = artist.ref ?: return@forEach
                val artistState = contexts.getOrPut(key(artistId)) { MutableStateFlow(null) }
                val previous = artistState.value
                artistState.value = ArtistContext(
                    artistId, artist.name, if (artist.isChannel) null else artist.id ?: previous?.onlineId,
                    (previous?.sourceSongs.orEmpty().filterNot { it.id == videoId } + updated).takeLast(30),
                    isChannel = artist.isChannel,
                    sourceChannelId = artist.sourceChannelId,
                )
                // A route opened before DB alias reconciliation must receive later updates too.
                findArtist(old?.artists.orEmpty(), artist)?.ref?.takeIf { it != artistId }?.let { previousRef ->
                    contexts[key(previousRef)]?.value = artistState.value
                }
            }
        }
        if (persist) preferences.edit().putString(storedKey(cacheKey), json.encodeToString(accepted)).apply()
        accepted
    }

    private fun apply(metadata: MediaMetadata, credit: ArtistCredit) = metadata.copy(
        artistCredit = credit.takeUnless { it.isEmptyByline() } ?: metadata.artistCredit,
        album = metadata.album ?: observeAlbum(metadata.id).value?.let { MediaMetadata.Album(it.id, it.name) },
        artists = if (credit.isEmptyByline()) metadata.artists else credit.artists.map {
            MediaMetadata.Artist(
                id = ArtistIdentity.sourceId(metadata.id, it),
                name = it.name,
                onlineId = if (it.isChannel) null else it.id,
                isChannel = it.isChannel,
                sourceChannelId = it.sourceChannelId,
            )
        },
    )

    private fun isOnlineId(id: String) = id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")
}

private fun youtubeArtistContextToken(): String {
    val authentication = YouTube.authentication
    val locale = YouTube.locale
    val auth = artistCreditAuthenticationToken(authentication.cookie, authentication.visitorData,
        authentication.dataSyncId, authentication.useLoginForBrowse)
    return "WEB_REMIX:${locale.gl}:${locale.hl}:$auth"
}

/** Stable across process/auth generations, but separate for every request credential setting. */
internal fun artistCreditAuthenticationToken(cookie: String?, visitorData: String?, dataSyncId: String?, useLoginForBrowse: Boolean): String {
    val credentials = listOf(cookie.orEmpty(), visitorData.orEmpty(),
        dataSyncId.orEmpty(), useLoginForBrowse.toString()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(credentials.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}
