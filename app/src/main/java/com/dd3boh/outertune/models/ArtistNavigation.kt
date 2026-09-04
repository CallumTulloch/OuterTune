package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity

private const val YOUTUBE_CHANNEL_PREFIX = "UC"
private const val PRIVATE_LIBRARY_ARTIST_PREFIX = "FEmusic_library_privately_owned_artist"

/** Returns whether this is a browse id accepted by the YouTube artist page. */
internal fun String.isYouTubeArtistBrowseId(): Boolean =
    startsWith(YOUTUBE_CHANNEL_PREFIX) || startsWith(PRIVATE_LIBRARY_ARTIST_PREFIX)

/**
 * Normalizes an artist id before it is placed in an artist navigation route.
 *
 * Local artist pages use generated database ids, while online pages must only receive a
 * YouTube artist browse id. In particular, an `LA...` id on online metadata is an unresolved
 * database fallback and must not be sent to YouTube.
 */
internal fun String?.artistNavigationId(isLocal: Boolean): String? {
    val candidate = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return candidate.takeIf { isLocal || it.isYouTubeArtistBrowseId() }
}

/** Returns the local primary key or a validated remote browse id, as appropriate. */
internal fun ArtistEntity.artistNavigationId(): String? =
    navigationId.artistNavigationId(isLocal = isLocal)

internal fun Artist.artistNavigationId(): String? = artist.artistNavigationId()
