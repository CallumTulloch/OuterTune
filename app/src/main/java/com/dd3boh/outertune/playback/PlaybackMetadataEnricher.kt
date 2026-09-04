package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.isYouTubeArtistBrowseId
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.pages.TrackCredits
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class PlaybackMetadataResolutionStatus {
    NOT_REQUESTED,
    RESOLVED,
    ABSENT,
    RETRYABLE_FAILURE,
}

/** Result of resolving only the missing or ambiguous parts of one online song. */
data class PlaybackMetadataEnrichment(
    val metadata: MediaMetadata,
    val albumStatus: PlaybackMetadataResolutionStatus =
        PlaybackMetadataResolutionStatus.NOT_REQUESTED,
    val artistsStatus: PlaybackMetadataResolutionStatus =
        PlaybackMetadataResolutionStatus.NOT_REQUESTED,
    /** False when at least one advertised artist page could not be checked. */
    val artistIdentityResolutionComplete: Boolean =
        artistsStatus == PlaybackMetadataResolutionStatus.RESOLVED,
) {
    /** True only when a missing album was resolved from queue/menu-backed metadata. */
    val albumIsAuthoritative: Boolean
        get() = albumStatus == PlaybackMetadataResolutionStatus.RESOLVED

    /** True only when the artist list came from a matching track-credits response. */
    val artistsAreAuthoritative: Boolean
        get() = artistsStatus == PlaybackMetadataResolutionStatus.RESOLVED

    /** Applies resolved fields while retaining mutable/user state from a newer source snapshot. */
    fun applyTo(source: MediaMetadata): MediaMetadata {
        val resolvedAlbumApplies =
            albumIsAuthoritative &&
                source.needsAlbumMetadataResolution() &&
                metadata.album?.let(source::isCompatibleWithResolvedAlbum) == true
        val resolvedArtistsApply =
            artistsAreAuthoritative &&
            source.acceptsAuthoritativeArtistResolution() &&
            metadata.coversArtistMetadataRequest(source)
        val endpointHints = if (
            resolvedArtistsApply &&
            source.metadataEndpointHints.creditsBrowseId.isNullOrBlank() &&
            !metadata.metadataEndpointHints.creditsBrowseId.isNullOrBlank()
        ) {
            source.metadataEndpointHints.copy(
                creditsBrowseId = metadata.metadataEndpointHints.creditsBrowseId,
                artistBrowseIds = (
                    source.metadataEndpointHints.artistBrowseIds +
                        metadata.metadataEndpointHints.artistBrowseIds
                    ).distinct(),
            )
        } else {
            source.metadataEndpointHints
        }
        return source.copy(
            album = if (resolvedAlbumApplies) metadata.album else source.album,
            artists = if (resolvedArtistsApply) metadata.artists else source.artists,
            artistCreditsResolved = source.artistCreditsResolved || resolvedArtistsApply,
            metadataEndpointHints = endpointHints,
        )
    }
}

internal fun MediaMetadata.needsAlbumMetadataResolution(): Boolean {
    if (isLocal) return false
    val albumHint = metadataEndpointHints.albumBrowseId?.takeIf(String::isNotBlank)
    return album == null || (albumHint != null && album.id != albumHint)
}

/** Whether a verified album result still belongs to this same-ID source snapshot. */
internal fun MediaMetadata.isCompatibleWithResolvedAlbum(
    resolvedAlbum: MediaMetadata.Album,
): Boolean {
    val albumHint = metadataEndpointHints.albumBrowseId?.takeIf(String::isNotBlank)
    return if (needsAlbumMetadataResolution()) {
        albumHint == null || albumHint == resolvedAlbum.id
    } else {
        album?.id == resolvedAlbum.id
    }
}

internal fun MediaMetadata.needsPlaybackMetadataEnrichment(): Boolean =
    needsAlbumMetadataResolution() || needsArtistCreditResolution()

internal fun MediaMetadata.hasVerifiedRemoteArtists(): Boolean = artists.isNotEmpty() &&
    artists.all { artist -> artist.id?.isYouTubeArtistBrowseId() == true }

/**
 * Credits remain the authority for display names even if a search renderer happens to attach a
 * valid artist browse id to its combined display string. Once credits are stored, unresolved ids
 * may still be upgraded by rechecking the typed artist candidates.
 */
internal fun MediaMetadata.needsArtistCreditResolution(): Boolean =
    !isLocal &&
        metadataEndpointHints.creditsBrowseId?.isNotBlank() == true &&
        (!artistCreditsResolved || !hasVerifiedRemoteArtists())

internal fun MediaMetadata.acceptsAuthoritativeArtistResolution(): Boolean =
    !artistCreditsResolved || !hasVerifiedRemoteArtists()

internal fun MediaMetadata.coversMetadataEnrichmentRequest(
    requested: MediaMetadata,
): Boolean {
    if (requested.needsAlbumMetadataResolution() && !coversAlbumMetadataRequest(requested)) {
        return false
    }

    val requestedNeedsArtists = requested.needsArtistCreditResolution()
    if (!requestedNeedsArtists) return true

    val thisResolvesArtists = needsArtistCreditResolution()
    return thisResolvesArtists && coversArtistMetadataRequest(requested)
}

internal fun MediaMetadata.coversAlbumMetadataRequest(requested: MediaMetadata): Boolean {
    if (!requested.needsAlbumMetadataResolution()) return true
    if (!needsAlbumMetadataResolution()) return false
    val requestedAlbumHint = requested.metadataEndpointHints.albumBrowseId
        ?.takeIf(String::isNotBlank)
    val currentAlbumHint = metadataEndpointHints.albumBrowseId
        ?.takeIf(String::isNotBlank)
    return currentAlbumHint == requestedAlbumHint
}

internal fun MediaMetadata.coversArtistMetadataRequest(requested: MediaMetadata): Boolean {
    val requestedCredits = requested.metadataEndpointHints.creditsBrowseId
        ?.takeIf(String::isNotBlank)
        ?: return true
    return metadataEndpointHints.creditsBrowseId == requestedCredits &&
        metadataEndpointHints.artistBrowseIds.toSet()
            .containsAll(requested.metadataEndpointHints.artistBrowseIds)
}

internal fun normalizedArtistName(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFC)
    .trim()
    .replace(Regex("\\s+"), " ")

/**
 * Converts the structurally separate credits header into display artists without splitting text on
 * punctuation. A linked run is already an explicit boundary; otherwise every run remains one
 * opaque display string.
 */
internal fun primaryArtistsFromCredits(
    runs: List<Run>,
    verifiedArtistNames: Map<String, String>,
): List<MediaMetadata.Artist> {
    val contentRuns = runs.filter { it.text.isNotBlank() }
    val linkedArtists = contentRuns.mapNotNull { run ->
        val browseId = run.navigationEndpoint?.browseEndpoint?.browseId
            ?.takeIf(String::isYouTubeArtistBrowseId)
            ?: return@mapNotNull null
        run.text.trim().takeIf(String::isNotEmpty)?.let { name ->
            MediaMetadata.Artist(id = browseId, name = name)
        }
    }.distinctBy { it.id }
    if (contentRuns.isNotEmpty() && linkedArtists.size == contentRuns.size) {
        return linkedArtists
    }

    val displayName = runs.joinToString(separator = "", transform = Run::text)
        .trim()
        .takeIf(String::isNotEmpty)
        ?: return emptyList()
    val normalizedDisplayName = normalizedArtistName(displayName)
    val matchingBrowseIds = verifiedArtistNames
        .filterValues { normalizedArtistName(it) == normalizedDisplayName }
        .keys
    val browseId = matchingBrowseIds.singleOrNull()
    return listOf(MediaMetadata.Artist(id = browseId, name = displayName))
}

/**
 * Resolves metadata for the selected song. Playback callers run this asynchronously, so a network
 * failure never prevents playback and never erases the original presentation metadata.
 */
@Singleton
class PlaybackMetadataEnricher @Inject constructor() {
    private val artistNameCache = ConcurrentHashMap<String, String>()

    suspend fun enrich(
        original: MediaMetadata,
        shouldResolveAlbum: Boolean = original.needsAlbumMetadataResolution(),
        shouldResolveArtists: Boolean = original.needsArtistCreditResolution(),
    ): PlaybackMetadataEnrichment {
        if (!shouldResolveAlbum && !shouldResolveArtists) {
            return PlaybackMetadataEnrichment(original)
        }

        return supervisorScope {
            val album = async {
                if (shouldResolveAlbum) resolveAlbum(original) else FieldResolution.notRequested()
            }
            val artists = async {
                if (shouldResolveArtists) resolveArtists(original) else ArtistResolution.notRequested()
            }
            val resolvedAlbum = album.await()
            val resolvedArtists = artists.await()
            PlaybackMetadataEnrichment(
                metadata = original.copy(
                    album = if (
                        resolvedAlbum.status == PlaybackMetadataResolutionStatus.RESOLVED
                    ) {
                        resolvedAlbum.value
                    } else {
                        original.album
                    },
                    artists = resolvedArtists.value ?: original.artists,
                ),
                albumStatus = resolvedAlbum.status,
                artistsStatus = resolvedArtists.status,
                artistIdentityResolutionComplete = resolvedArtists.identityComplete,
            )
        }
    }

    private suspend fun resolveAlbum(
        original: MediaMetadata,
    ): FieldResolution<MediaMetadata.Album> {
        if (!original.needsAlbumMetadataResolution()) return FieldResolution.notRequested()

        val hintedBrowseId = original.metadataEndpointHints.albumBrowseId
            ?.takeIf(String::isNotBlank)
        var queueRequestCompleted = false
        val queueSong = YouTube.queue(videoIds = listOf(original.id))
            .fold(
                onSuccess = { songs ->
                    queueRequestCompleted = true
                    songs.firstOrNull { it.id == original.id }
                },
                onFailure = {
                    Log.w(TAG, "Unable to resolve queue metadata for ${original.id}", it)
                    null
                },
            )
        val queueAlbum = queueSong
            ?.toMediaMetadata()
            ?.album

        if (queueAlbum != null && (hintedBrowseId == null || queueAlbum.id == hintedBrowseId)) {
            return FieldResolution.resolved(queueAlbum)
        }

        if (queueAlbum != null) {
            Log.w(
                TAG,
                "Album metadata conflict for ${original.id}: " +
                    "menu=$hintedBrowseId queue=${queueAlbum.id}",
            )
        }

        if (hintedBrowseId != null) {
            return YouTube.album(hintedBrowseId, withSongs = false).fold(
                onSuccess = { page ->
                    if (page.album.browseId == hintedBrowseId) {
                        FieldResolution.resolved(
                            MediaMetadata.Album(
                                id = page.album.browseId,
                                title = page.album.title,
                            ),
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Album page conflict for ${original.id}: " +
                                "requested=$hintedBrowseId returned=${page.album.browseId}",
                        )
                        FieldResolution.retryableFailure()
                    }
                },
                onFailure = {
                    Log.w(TAG, "Unable to resolve album $hintedBrowseId", it)
                    FieldResolution.retryableFailure()
                },
            )
        }

        return if (queueRequestCompleted) {
            FieldResolution.absent()
        } else {
            FieldResolution.retryableFailure()
        }
    }

    private suspend fun resolveArtists(original: MediaMetadata): ArtistResolution {
        val credits = resolveCredits(original)
        if (credits.status != PlaybackMetadataResolutionStatus.RESOLVED) {
            return ArtistResolution(status = credits.status)
        }
        val trackCredits = credits.value ?: return ArtistResolution.retryableFailure()
        val unverifiedArtists = primaryArtistsFromCredits(
            runs = trackCredits.primaryArtistDisplayRuns,
            verifiedArtistNames = emptyMap(),
        ).takeIf(List<MediaMetadata.Artist>::isNotEmpty)
            ?: return ArtistResolution.absent()

        val candidateResolutions = if (unverifiedArtists.any { it.id == null }) {
            supervisorScope {
                original.metadataEndpointHints.artistBrowseIds
                    .asSequence()
                    .filter { it.isYouTubeArtistBrowseId() }
                    .distinct()
                    .map { browseId ->
                        browseId to async { resolveArtistName(browseId) }
                    }
                    .toList()
                    .map { (browseId, deferredName) -> browseId to deferredName.await() }
            }
        } else {
            emptyList()
        }
        val candidateVerificationComplete = candidateResolutions
            .all { (_, resolution) -> resolution.completed }
        val verifiedArtistNames = if (candidateVerificationComplete) {
            candidateResolutions
                .mapNotNull { (browseId, resolution) ->
                    resolution.name?.let { browseId to it }
                }
                .toMap()
        } else {
            emptyMap()
        }
        val resolvedArtists = primaryArtistsFromCredits(
            runs = trackCredits.primaryArtistDisplayRuns,
            verifiedArtistNames = verifiedArtistNames,
        ).takeIf(List<MediaMetadata.Artist>::isNotEmpty)
            ?: return ArtistResolution.absent()

        return ArtistResolution(
            status = PlaybackMetadataResolutionStatus.RESOLVED,
            value = resolvedArtists,
            identityComplete = candidateVerificationComplete,
        )
    }

    private suspend fun resolveCredits(
        original: MediaMetadata,
    ): FieldResolution<TrackCredits> {
        val browseId = original.metadataEndpointHints.creditsBrowseId
            ?.takeIf(String::isNotBlank)
            ?: return FieldResolution.notRequested()
        return YouTube.trackCredits(browseId).fold(
            onSuccess = { credits ->
                if (credits.videoId != original.id) {
                    Log.w(
                        TAG,
                        "Ignoring credits for ${credits.videoId}; expected ${original.id}",
                    )
                    FieldResolution.retryableFailure()
                } else {
                    FieldResolution.resolved(credits)
                }
            },
            onFailure = {
                Log.w(TAG, "Unable to resolve track credits for ${original.id}", it)
                FieldResolution.retryableFailure()
            },
        )
    }

    private suspend fun resolveArtistName(browseId: String): ArtistNameResolution {
        artistNameCache[browseId]?.let { return ArtistNameResolution(completed = true, name = it) }
        return YouTube.artist(browseId).fold(
            onSuccess = { page ->
                val name = page.artist.title.takeIf(String::isNotBlank)
                if (name == null) {
                    ArtistNameResolution(completed = false, name = null)
                } else {
                    artistNameCache[browseId] = name
                    ArtistNameResolution(completed = true, name = name)
                }
            },
            onFailure = {
                Log.w(TAG, "Unable to verify artist $browseId", it)
                ArtistNameResolution(completed = false, name = null)
            },
        )
    }

    private data class ArtistNameResolution(
        val completed: Boolean,
        val name: String?,
    )

    private data class ArtistResolution(
        val status: PlaybackMetadataResolutionStatus,
        val value: List<MediaMetadata.Artist>? = null,
        val identityComplete: Boolean = false,
    ) {
        companion object {
            fun notRequested() = ArtistResolution(PlaybackMetadataResolutionStatus.NOT_REQUESTED)
            fun absent() = ArtistResolution(PlaybackMetadataResolutionStatus.ABSENT)
            fun retryableFailure() =
                ArtistResolution(PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE)
        }
    }

    private data class FieldResolution<T>(
        val status: PlaybackMetadataResolutionStatus,
        val value: T? = null,
    ) {
        companion object {
            fun <T> notRequested() = FieldResolution<T>(
                PlaybackMetadataResolutionStatus.NOT_REQUESTED,
            )

            fun <T> resolved(value: T) = FieldResolution(
                status = PlaybackMetadataResolutionStatus.RESOLVED,
                value = value,
            )

            fun <T> absent() = FieldResolution<T>(PlaybackMetadataResolutionStatus.ABSENT)

            fun <T> retryableFailure() = FieldResolution<T>(
                PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
            )
        }
    }

    private companion object {
        const val TAG = "PlaybackMetadata"
    }
}
