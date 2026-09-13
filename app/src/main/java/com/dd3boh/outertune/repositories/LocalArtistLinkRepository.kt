package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.isPublicYouTubeArtistId
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.ArtistPage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LocalArtistLinkCandidate(
    val onlineId: String,
    val name: String,
    val thumbnailUrl: String?,
    val albumTitles: List<String>,
    val contextToken: String,
)

enum class LocalArtistLinkFailure { INVALID_INPUT, INVALID_ARTIST, STALE_CONTEXT, LINK_CHANGED }

class LocalArtistLinkException(val reason: LocalArtistLinkFailure) :
    IllegalStateException("Local artist link: ${reason.name}")

/** A link is written only after an explicit confirmation of an artist-page preview. */
@Singleton
class LocalArtistLinkRepository internal constructor(
    private val storage: Storage,
    private val runtime: Runtime,
) {
    @Inject
    constructor(database: MusicDatabase) : this(DatabaseStorage(database), Runtime())

    internal class Runtime(
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val authRevision: () -> Long = { YouTube.authRevision },
        val search: suspend (String, YouTubeLocale) -> List<ArtistItem> = { query, locale ->
            YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST, locale, notifyMetadata = false)
                .getOrThrow().items.filterIsInstance<ArtistItem>()
        },
        val resolveUrl: suspend (String, YouTubeLocale) -> String = { input, locale ->
            YouTube.resolveArtistUrl(input, locale).getOrThrow()
        },
        val artist: suspend (String, YouTubeLocale) -> ArtistPage = { id, locale ->
            YouTube.artist(id, locale, notifyMetadata = false).getOrThrow()
        },
        val newRevision: () -> String = { UUID.randomUUID().toString() },
    )

    internal interface Storage {
        suspend fun transaction(block: Transaction.() -> Unit)

        interface Transaction {
            fun current(localId: String): LocalArtistLink?
            fun put(link: LocalArtistLink)
            fun remove(localId: String, expectedRevision: String): Boolean
        }
    }

    private class DatabaseStorage(private val database: MusicDatabase) : Storage {
        override suspend fun transaction(block: Storage.Transaction.() -> Unit) {
            database.awaitTransaction {
                block(object : Storage.Transaction {
                    override fun current(localId: String) = localArtistLinkById(localId)
                    override fun put(link: LocalArtistLink) = setLocalArtistLink(link)
                    override fun remove(localId: String, expectedRevision: String) =
                        removeLocalArtistLink(localId, expectedRevision)
                })
            }
        }
    }

    private data class RequestContext(val locale: YouTubeLocale, val authRevision: Long) {
        val token: String get() = "$authRevision:${locale.gl}:${locale.hl}"
    }

    private fun snapshot() = RequestContext(runtime.locale(), runtime.authRevision())

    fun contextToken(): String = snapshot().token

    private fun requireCurrent(token: String) {
        if (contextToken() != token) throw LocalArtistLinkException(LocalArtistLinkFailure.STALE_CONTEXT)
    }

    suspend fun findCandidates(input: String): List<ArtistItem> {
        val value = input.trim()
        if (value.isEmpty() || value.length > 2048) throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_INPUT)
        val context = snapshot()
        val candidates = if (looksLikeArtistUrl(value)) {
            val id = try {
                runtime.resolveUrl(value, context.locale)
            } catch (_: IllegalArgumentException) {
                throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_INPUT)
            }
            requireCurrent(context.token)
            requireArtistId(id)
            listOf(fetchArtist(id, context).artist)
        } else {
            runtime.search(value, context.locale)
        }
        requireCurrent(context.token)
        return candidates.filter { isPublicYouTubeArtistId(it.id) && it.title.isNotBlank() }
            .distinctBy { it.id }
    }

    suspend fun preview(id: String): LocalArtistLinkCandidate {
        requireArtistId(id)
        val context = snapshot()
        val page = fetchArtist(id, context)
        return LocalArtistLinkCandidate(
            onlineId = page.artist.id,
            name = page.artist.title,
            thumbnailUrl = page.artist.thumbnail,
            albumTitles = page.sections.asSequence().flatMap { it.items.asSequence() }
                .filterIsInstance<AlbumItem>().map { it.title }.filter { it.isNotBlank() }
                .distinct().take(3).toList(),
            contextToken = context.token,
        )
    }

    private suspend fun fetchArtist(id: String, context: RequestContext): ArtistPage {
        val page = runtime.artist(id, context.locale)
        requireCurrent(context.token)
        if (page.artist.id != id || page.artist.title.isBlank()) {
            throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_ARTIST)
        }
        return page
    }

    suspend fun confirm(localId: String, candidate: LocalArtistLinkCandidate, expectedRevision: String?) {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        requireArtistId(candidate.onlineId)
        if (localId.isBlank() || candidate.name.isBlank()) throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_ARTIST)
        requireCurrent(candidate.contextToken)
        storage.transaction {
            callerContext.ensureActive()
            requireCurrent(candidate.contextToken)
            if (current(localId)?.revision != expectedRevision) {
                throw LocalArtistLinkException(LocalArtistLinkFailure.LINK_CHANGED)
            }
            put(LocalArtistLink(
                localArtistId = localId,
                onlineArtistId = candidate.onlineId,
                onlineName = candidate.name,
                thumbnailUrl = candidate.thumbnailUrl,
                revision = runtime.newRevision(),
            ))
            // A queued transaction must not commit a preview from a superseded session.
            requireCurrent(candidate.contextToken)
            // Room's synchronous transaction block does not otherwise observe cancellation.
            callerContext.ensureActive()
        }
    }

    suspend fun unlink(localId: String, expectedRevision: String): Boolean {
        var removed = false
        storage.transaction { removed = remove(localId, expectedRevision) }
        return removed
    }

    private fun requireArtistId(id: String) {
        if (!isPublicYouTubeArtistId(id)) throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_ARTIST)
    }
}

private fun looksLikeArtistUrl(value: String): Boolean =
    isPublicYouTubeArtistId(value) || value.startsWith("@") || value.contains("://") ||
        value.startsWith("//") || value.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")) ||
        value.matches(Regex("^[^\\s/]+\\.[^\\s/]+/.*")) ||
        value.matches(Regex("(?i)^(music\\.)?(www\\.)?youtube\\.com.*"))
