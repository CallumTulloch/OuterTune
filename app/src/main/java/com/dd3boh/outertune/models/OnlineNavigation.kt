package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.SongItem

/**
 * A typed artist destination advertised by YouTube Music.
 *
 * [name] is present only when the destination itself, or an already verified artist relation,
 * supplied it. A menu endpoint must not inherit a nearby display credit by position or punctuation.
 */
internal data class ArtistNavigationTarget(
    val browseId: String,
    val name: String? = null,
)

internal data class SongNavigationTargets(
    val albumBrowseId: String?,
    val artists: List<ArtistNavigationTarget>,
)

/**
 * Builds menu destinations without interpreting a combined display artist string.
 *
 * Search renderers can omit the visible album and artist links while still advertising typed
 * destinations in their menu. Resolved database metadata is accepted as a fallback after a song
 * has started playing, but discovery hints remain unassociated with display names.
 */
internal fun SongItem.navigationTargets(
    persisted: MediaMetadata? = null,
): SongNavigationTargets {
    val artistTargets = buildList {
        artists.forEach { artist ->
            artist.id.artistNavigationId(isLocal = false)?.let { browseId ->
                add(ArtistNavigationTarget(browseId = browseId, name = artist.name))
            }
        }
        persisted
            ?.takeUnless(MediaMetadata::isLocal)
            ?.artists
            .orEmpty()
            .forEach { artist ->
                artist.id.artistNavigationId(isLocal = false)?.let { browseId ->
                    add(ArtistNavigationTarget(browseId = browseId, name = artist.name))
                }
            }
        metadataEndpointHints.artistCandidates.forEach { endpoint ->
            endpoint.browseId.artistNavigationId(isLocal = false)?.let { browseId ->
                add(ArtistNavigationTarget(browseId = browseId))
            }
        }
        persisted
            ?.takeUnless(MediaMetadata::isLocal)
            ?.metadataEndpointHints
            ?.artistBrowseIds
            .orEmpty()
            .forEach { candidate ->
                candidate.artistNavigationId(isLocal = false)?.let { browseId ->
                    add(ArtistNavigationTarget(browseId = browseId))
                }
            }
    }.mergeArtistNavigationTargets()

    val albumBrowseId = sequenceOf(
        metadataEndpointHints.album?.browseId,
        album?.id,
        persisted?.takeUnless(MediaMetadata::isLocal)
            ?.metadataEndpointHints
            ?.albumBrowseId,
        persisted?.takeUnless(MediaMetadata::isLocal)?.album?.id,
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()

    return SongNavigationTargets(
        albumBrowseId = albumBrowseId,
        artists = artistTargets,
    )
}

/** Returns independent typed destinations retained with a database-backed online song. */
internal fun MediaMetadata.navigationTargets(): SongNavigationTargets {
    if (isLocal) return SongNavigationTargets(albumBrowseId = null, artists = emptyList())

    val artistTargets = buildList {
        artists.forEach { artist ->
            artist.id.artistNavigationId(isLocal = false)?.let { browseId ->
                add(ArtistNavigationTarget(browseId = browseId, name = artist.name))
            }
        }
        metadataEndpointHints.artistBrowseIds.forEach { candidate ->
            candidate.artistNavigationId(isLocal = false)?.let { browseId ->
                add(ArtistNavigationTarget(browseId = browseId))
            }
        }
    }.mergeArtistNavigationTargets()

    return SongNavigationTargets(
        albumBrowseId = sequenceOf(
            metadataEndpointHints.albumBrowseId,
            album?.id,
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull(),
        artists = artistTargets,
    )
}

/** Keeps endpoint order while preferring an independently verified non-blank name. */
internal fun Iterable<ArtistNavigationTarget>.mergeArtistNavigationTargets(): List<ArtistNavigationTarget> {
    val targets = linkedMapOf<String, ArtistNavigationTarget>()
    forEach { target ->
        val browseId = target.browseId.artistNavigationId(isLocal = false) ?: return@forEach
        val normalized = target.copy(
            browseId = browseId,
            name = target.name?.trim()?.takeIf(String::isNotEmpty),
        )
        val current = targets[browseId]
        if (current == null || current.name == null && normalized.name != null) {
            targets[browseId] = normalized
        }
    }
    return targets.values.toList()
}
