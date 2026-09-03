package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity

/**
 * Unicode-aware fallback for artist names that SQLite NOCASE cannot compare (for example,
 * full-width Latin characters). Source is always part of the identity.
 */
internal fun selectArtistByNormalizedName(
    name: String,
    isLocal: Boolean,
    candidates: Iterable<ArtistEntity>,
): ArtistEntity? {
    val normalizedName = normalizeLocalMetadataText(name)
    return candidates.firstOrNull { candidate ->
        candidate.isLocal == isLocal &&
            normalizeLocalMetadataText(candidate.name) == normalizedName
    }
}
