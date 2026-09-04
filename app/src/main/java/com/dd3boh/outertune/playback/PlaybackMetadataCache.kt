package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.isYouTubeArtistBrowseId
import kotlin.math.min

internal const val METADATA_RETRY_BASE_MS = 15_000L
internal const val METADATA_RETRY_MAX_MS = 5 * 60_000L
internal const val METADATA_ABSENT_TTL_MS = 30 * 60_000L

/**
 * Field-specific playback metadata cache.
 *
 * Album and artist lookups can succeed independently. Keeping them in separate slots prevents a
 * later partial response from discarding a previously verified field. Each slot is also tied to
 * the typed endpoints that produced it so cached values cannot cross conflicting search hints.
 */
internal data class PlaybackMetadataCacheEntry(
    val album: AlbumSlot? = null,
    val artists: ArtistSlot? = null,
) {
    data class AlbumRequestKey(val browseId: String?)

    data class ArtistRequestKey(
        val creditsBrowseId: String?,
        val candidateBrowseIds: Set<String>,
    )

    data class AlbumSlot(
        val requestKey: AlbumRequestKey,
        val status: PlaybackMetadataResolutionStatus,
        val value: MediaMetadata.Album? = null,
        val retryAtMs: Long = Long.MAX_VALUE,
        val failureCount: Int = 0,
    )

    data class ArtistSlot(
        val requestKey: ArtistRequestKey,
        val status: PlaybackMetadataResolutionStatus,
        val value: List<MediaMetadata.Artist>? = null,
        val identityComplete: Boolean = false,
        val retryAtMs: Long = Long.MAX_VALUE,
        val failureCount: Int = 0,
    )

    fun applyTo(source: MediaMetadata): MediaMetadata {
        val cachedAlbum = album
            ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
            ?.takeIf { it.matches(source) }
            ?.value
        val cachedArtistSlot = artists
            ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
            ?.takeIf { it.matches(source) }
            ?.takeIf { source.acceptsAuthoritativeArtistResolution() }
            ?.takeIf { !it.value.isNullOrEmpty() }
        val cachedArtists = cachedArtistSlot?.value
        val endpointHints = if (
            cachedArtistSlot != null &&
            source.metadataEndpointHints.creditsBrowseId.isNullOrBlank()
        ) {
            source.metadataEndpointHints.copy(
                creditsBrowseId = cachedArtistSlot.requestKey.creditsBrowseId,
                artistBrowseIds = (
                    source.metadataEndpointHints.artistBrowseIds +
                        cachedArtistSlot.requestKey.candidateBrowseIds
                    ).distinct(),
            )
        } else {
            source.metadataEndpointHints
        }
        return source.copy(
            album = if (source.needsAlbumMetadataResolution()) {
                cachedAlbum ?: source.album
            } else {
                source.album
            },
            artists = cachedArtists ?: source.artists,
            artistCreditsResolved = source.artistCreditsResolved || cachedArtists != null,
            metadataEndpointHints = endpointHints,
        )
    }

    fun asEnrichmentFor(source: MediaMetadata): PlaybackMetadataEnrichment {
        val albumApplies = source.needsAlbumMetadataResolution() && album
            ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
            ?.takeIf { it.matches(source) }
            ?.value != null
        val artistSlot = artists
            ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
            ?.takeIf { it.matches(source) }
            ?.takeIf { source.acceptsAuthoritativeArtistResolution() }
            ?.takeIf { !it.value.isNullOrEmpty() }
        return PlaybackMetadataEnrichment(
            metadata = applyTo(source),
            albumStatus = if (albumApplies) {
                PlaybackMetadataResolutionStatus.RESOLVED
            } else {
                PlaybackMetadataResolutionStatus.NOT_REQUESTED
            },
            artistsStatus = if (artistSlot != null) {
                PlaybackMetadataResolutionStatus.RESOLVED
            } else {
                PlaybackMetadataResolutionStatus.NOT_REQUESTED
            },
            artistIdentityResolutionComplete = artistSlot?.identityComplete ?: false,
        )
    }

    fun shouldResolveAlbum(source: MediaMetadata, nowMs: Long): Boolean {
        if (!source.needsAlbumMetadataResolution()) return false
        val slot = album?.takeIf { it.matches(source) } ?: return true
        return when (slot.status) {
            PlaybackMetadataResolutionStatus.RESOLVED -> slot.value == null
            PlaybackMetadataResolutionStatus.ABSENT,
            PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE -> nowMs >= slot.retryAtMs
            PlaybackMetadataResolutionStatus.NOT_REQUESTED -> true
        }
    }

    fun shouldResolveArtists(source: MediaMetadata, nowMs: Long): Boolean {
        if (!source.needsArtistCreditResolution()) return false
        val slot = artists?.takeIf { it.matches(source) } ?: return true
        return when (slot.status) {
            PlaybackMetadataResolutionStatus.RESOLVED ->
                !slot.identityComplete && nowMs >= slot.retryAtMs
            PlaybackMetadataResolutionStatus.ABSENT,
            PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE -> nowMs >= slot.retryAtMs
            PlaybackMetadataResolutionStatus.NOT_REQUESTED -> true
        }
    }

    fun merge(
        resolution: PlaybackMetadataEnrichment,
        nowMs: Long,
    ): PlaybackMetadataCacheEntry = copy(
        album = mergeAlbum(resolution, nowMs),
        artists = mergeArtists(resolution, nowMs),
    )

    fun nextRetryAt(source: MediaMetadata): Long? = listOfNotNull(
        album
            ?.takeIf { source.needsAlbumMetadataResolution() }
            ?.takeIf { it.matches(source) }
            ?.takeIf {
                it.status == PlaybackMetadataResolutionStatus.ABSENT ||
                    it.status == PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE
            }
            ?.retryAtMs,
        artists
            ?.takeIf { source.needsArtistCreditResolution() }
            ?.takeIf { it.matches(source) }
            ?.takeIf {
                it.status == PlaybackMetadataResolutionStatus.ABSENT ||
                    it.status == PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE ||
                    (it.status == PlaybackMetadataResolutionStatus.RESOLVED && !it.identityComplete)
            }
            ?.retryAtMs,
    ).minOrNull()

    fun retryFailuresNow(source: MediaMetadata): PlaybackMetadataCacheEntry = copy(
        album = album?.let { slot ->
            if (
                slot.matches(source) &&
                slot.status == PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE
            ) {
                slot.copy(retryAtMs = 0)
            } else {
                slot
            }
        },
        artists = artists?.let { slot ->
            if (
                slot.matches(source) &&
                (slot.status == PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE ||
                    (slot.status == PlaybackMetadataResolutionStatus.RESOLVED &&
                        !slot.identityComplete))
            ) {
                slot.copy(retryAtMs = 0)
            } else {
                slot
            }
        },
    )

    private fun mergeAlbum(
        resolution: PlaybackMetadataEnrichment,
        nowMs: Long,
    ): AlbumSlot? {
        if (resolution.albumStatus == PlaybackMetadataResolutionStatus.NOT_REQUESTED) return album
        val requestKey = resolution.metadata.albumRequestKey()
        val existing = album?.takeIf { it.requestKey == requestKey }
        return when (resolution.albumStatus) {
            PlaybackMetadataResolutionStatus.RESOLVED -> resolution.metadata.album?.let { value ->
                AlbumSlot(
                    requestKey = requestKey,
                    status = PlaybackMetadataResolutionStatus.RESOLVED,
                    value = value,
                )
            } ?: retryableAlbumSlot(requestKey, existing, nowMs)
            PlaybackMetadataResolutionStatus.ABSENT -> existing
                ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
                ?: AlbumSlot(
                    requestKey = requestKey,
                    status = PlaybackMetadataResolutionStatus.ABSENT,
                    retryAtMs = nowMs + METADATA_ABSENT_TTL_MS,
                )
            PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE -> existing
                ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
                ?: retryableAlbumSlot(requestKey, existing, nowMs)
            PlaybackMetadataResolutionStatus.NOT_REQUESTED -> album
        }
    }

    private fun mergeArtists(
        resolution: PlaybackMetadataEnrichment,
        nowMs: Long,
    ): ArtistSlot? {
        if (resolution.artistsStatus == PlaybackMetadataResolutionStatus.NOT_REQUESTED) return artists
        val requestKey = resolution.metadata.artistRequestKey()
        val existing = artists?.takeIf { it.requestKey == requestKey }
        return when (resolution.artistsStatus) {
            PlaybackMetadataResolutionStatus.RESOLVED -> {
                val value = resolution.metadata.artists.takeIf(List<MediaMetadata.Artist>::isNotEmpty)
                    ?: return retryableArtistSlot(requestKey, existing, nowMs)
                if (resolution.artistIdentityResolutionComplete) {
                    ArtistSlot(
                        requestKey = requestKey,
                        status = PlaybackMetadataResolutionStatus.RESOLVED,
                        value = value,
                        identityComplete = true,
                    )
                } else {
                    val failureCount = (existing?.failureCount ?: 0) + 1
                    ArtistSlot(
                        requestKey = requestKey,
                        status = PlaybackMetadataResolutionStatus.RESOLVED,
                        value = value,
                        identityComplete = false,
                        retryAtMs = nowMs + retryDelayMs(failureCount),
                        failureCount = failureCount,
                    )
                }
            }
            PlaybackMetadataResolutionStatus.ABSENT -> existing
                ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED }
                ?.let { resolved ->
                    if (resolved.identityComplete) {
                        resolved
                    } else {
                        // Keep the verified display value, but move its next identity lookup into
                        // the negative-result TTL. Leaving an expired retryAt here would create an
                        // immediate retry loop while the credits endpoint keeps returning empty.
                        resolved.copy(
                            retryAtMs = nowMs + METADATA_ABSENT_TTL_MS,
                            failureCount = 0,
                        )
                    }
                }
                ?: ArtistSlot(
                    requestKey = requestKey,
                    status = PlaybackMetadataResolutionStatus.ABSENT,
                    retryAtMs = nowMs + METADATA_ABSENT_TTL_MS,
                )
            PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE -> existing
                ?.takeIf {
                    it.status == PlaybackMetadataResolutionStatus.RESOLVED && it.identityComplete
                }
                ?: retryableArtistSlot(requestKey, existing, nowMs)
            PlaybackMetadataResolutionStatus.NOT_REQUESTED -> artists
        }
    }

    private fun retryableAlbumSlot(
        requestKey: AlbumRequestKey,
        existing: AlbumSlot?,
        nowMs: Long,
    ): AlbumSlot {
        val failureCount = (existing?.failureCount ?: 0) + 1
        return AlbumSlot(
            requestKey = requestKey,
            status = PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
            retryAtMs = nowMs + retryDelayMs(failureCount),
            failureCount = failureCount,
        )
    }

    private fun retryableArtistSlot(
        requestKey: ArtistRequestKey,
        existing: ArtistSlot?,
        nowMs: Long,
    ): ArtistSlot {
        val failureCount = (existing?.failureCount ?: 0) + 1
        return ArtistSlot(
            requestKey = requestKey,
            status = existing
                ?.takeIf { it.status == PlaybackMetadataResolutionStatus.RESOLVED && it.value != null }
                ?.status
                ?: PlaybackMetadataResolutionStatus.RETRYABLE_FAILURE,
            value = existing?.value,
            identityComplete = false,
            retryAtMs = nowMs + retryDelayMs(failureCount),
            failureCount = failureCount,
        )
    }

    private fun AlbumSlot.matches(source: MediaMetadata): Boolean {
        val sourceHint = source.metadataEndpointHints.albumBrowseId?.takeIf(String::isNotBlank)
        return sourceHint == null ||
            (requestKey.browseId == sourceHint && (value == null || value.id == sourceHint))
    }

    private fun ArtistSlot.matches(source: MediaMetadata): Boolean {
        val requested = source.artistRequestKey()
        return requested.creditsBrowseId == null ||
            (requestKey.creditsBrowseId == requested.creditsBrowseId &&
                requestKey.candidateBrowseIds.containsAll(requested.candidateBrowseIds))
    }
}

private fun MediaMetadata.albumRequestKey() = PlaybackMetadataCacheEntry.AlbumRequestKey(
    browseId = metadataEndpointHints.albumBrowseId?.takeIf(String::isNotBlank),
)

private fun MediaMetadata.artistRequestKey() = PlaybackMetadataCacheEntry.ArtistRequestKey(
    creditsBrowseId = metadataEndpointHints.creditsBrowseId?.takeIf(String::isNotBlank),
    candidateBrowseIds = metadataEndpointHints.artistBrowseIds
        .asSequence()
        .filter(String::isYouTubeArtistBrowseId)
        .toSet(),
)

internal fun retryDelayMs(failureCount: Int): Long {
    val exponent = min((failureCount - 1).coerceAtLeast(0), 10)
    return (METADATA_RETRY_BASE_MS * (1L shl exponent)).coerceAtMost(METADATA_RETRY_MAX_MS)
}
