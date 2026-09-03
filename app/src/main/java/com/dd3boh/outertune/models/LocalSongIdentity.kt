package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.entities.Song

data class LocalSongAlbumArtistRow(
    val songId: String,
    val artistName: String,
)

internal data class LocalSongIdentity(
    val id: String?,
    val title: String,
    val artistNames: List<String>,
    val albumTitle: String?,
    val albumArtistNames: List<String>,
    val albumMusicBrainzId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val duration: Int,
    val localPath: String?,
)

internal fun SongTempData.toLocalSongIdentity() = LocalSongIdentity(
    id = song.id,
    title = song.title,
    artistNames = song.artists.map { it.name },
    albumTitle = song.album?.title ?: song.song.albumName,
    albumArtistNames = albumArtists.map { it.name },
    albumMusicBrainzId = albumMusicBrainzId,
    trackNumber = song.song.trackNumber,
    discNumber = song.song.discNumber,
    duration = song.song.duration,
    localPath = song.song.localPath,
)

internal fun Song.toLocalSongIdentity(albumArtistNames: List<String>) = LocalSongIdentity(
    id = id,
    title = title,
    artistNames = artists.map { it.name },
    albumTitle = album?.title ?: song.albumName,
    albumArtistNames = albumArtistNames,
    albumMusicBrainzId = album?.musicBrainzId,
    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    duration = song.duration,
    localPath = song.localPath,
)

private fun normalizedNullable(value: String?): String? =
    value?.let(::normalizeLocalMetadataText)?.takeIf(String::isNotEmpty)

private fun fileName(path: String?): String? =
    path?.replace('\\', '/')?.substringAfterLast('/')

private fun sameOptionalText(left: String?, right: String?): Boolean = when {
    left == null && right == null -> true
    left == null || right == null -> false
    else -> normalizeLocalMetadataText(left) == normalizeLocalMetadataText(right)
}

private fun conflictingKnownValues(left: String?, right: String?): Boolean {
    val normalizedLeft = normalizedNullable(left)
    val normalizedRight = normalizedNullable(right)
    return normalizedLeft != null && normalizedRight != null && normalizedLeft != normalizedRight
}

/**
 * Returns a confidence score, or null when the pair cannot represent one local file entry.
 * Exact path is authoritative so corrected tags update the existing row. For metadata fallback,
 * conflicting Album Artist or MusicBrainz release ids are never collapsed.
 */
internal fun localSongMatchScore(
    incoming: LocalSongIdentity,
    candidate: LocalSongIdentity,
    matchStrength: ScannerMatchCriteria,
    strictFileNames: Boolean,
    strictFilePaths: Boolean,
): Int? {
    val samePath = incoming.localPath != null && incoming.localPath == candidate.localPath
    if (samePath) return 10_000
    if (strictFilePaths) return null

    val incomingFileName = fileName(incoming.localPath)
    val candidateFileName = fileName(candidate.localPath)
    if (strictFileNames && incomingFileName != candidateFileName) return null

    if (normalizeLocalMetadataText(incoming.title) != normalizeLocalMetadataText(candidate.title)) {
        return null
    }
    if (conflictingKnownValues(
            incoming.albumMusicBrainzId,
            candidate.albumMusicBrainzId,
        )
    ) return null

    val incomingAlbumArtists = albumArtistSignature(incoming.albumArtistNames)
    val candidateAlbumArtists = albumArtistSignature(candidate.albumArtistNames)
    if (incomingAlbumArtists.isNotEmpty() &&
        candidateAlbumArtists.isNotEmpty() &&
        incomingAlbumArtists != candidateAlbumArtists
    ) return null

    val incomingArtists = albumArtistSignature(incoming.artistNames)
    val candidateArtists = albumArtistSignature(candidate.artistNames)
    val sameArtists = incomingArtists == candidateArtists
    val sameAlbum = sameOptionalText(incoming.albumTitle, candidate.albumTitle)
    when (matchStrength) {
        ScannerMatchCriteria.LEVEL_1 -> Unit
        ScannerMatchCriteria.LEVEL_2 -> if (!sameArtists) return null
        ScannerMatchCriteria.LEVEL_3 -> if (!sameArtists || !sameAlbum) return null
    }

    var score = 100
    if (sameArtists) score += 40
    if (sameAlbum && incoming.albumTitle != null) score += 20
    if (incomingAlbumArtists.isNotEmpty() && incomingAlbumArtists == candidateAlbumArtists) {
        score += 80
    }
    if (normalizedNullable(incoming.albumMusicBrainzId) != null &&
        normalizedNullable(incoming.albumMusicBrainzId) ==
        normalizedNullable(candidate.albumMusicBrainzId)
    ) score += 160
    if (incoming.trackNumber != null && incoming.trackNumber == candidate.trackNumber) score += 12
    if (incoming.discNumber != null && incoming.discNumber == candidate.discNumber) score += 8
    if (incoming.duration > 0 && candidate.duration > 0 &&
        kotlin.math.abs(incoming.duration - candidate.duration) <= 2
    ) score += 6
    if (incomingFileName != null && incomingFileName == candidateFileName) score += 4
    return score
}

/** Selects only a unique best metadata match; ties are left as separate songs. */
internal fun selectMatchingLocalSong(
    incoming: LocalSongIdentity,
    candidates: Iterable<LocalSongIdentity>,
    matchStrength: ScannerMatchCriteria,
    strictFileNames: Boolean,
    strictFilePaths: Boolean,
): LocalSongIdentity? {
    val candidateList = candidates.toList()
    if (incoming.localPath != null) {
        candidateList.firstOrNull { it.localPath == incoming.localPath }
            ?.let { return it }
    }
    if (strictFilePaths) return null

    val scored = candidateList.mapNotNull { candidate ->
        localSongMatchScore(
            incoming = incoming,
            candidate = candidate,
            matchStrength = matchStrength,
            strictFileNames = strictFileNames,
            strictFilePaths = strictFilePaths,
        )?.let { score -> candidate to score }
    }
    val highestScore = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == highestScore }.singleOrNull()?.first
}

/** Avoids an O(n²) full-library comparison by comparing only equal normalized titles. */
internal fun deduplicateLocalSongs(
    songs: List<SongTempData>,
    matchStrength: ScannerMatchCriteria,
    strictFileNames: Boolean,
    strictFilePaths: Boolean,
): ArrayList<SongTempData> {
    if (strictFilePaths) return ArrayList(songs)

    val result = ArrayList<SongTempData>(songs.size)
    val byTitle = mutableMapOf<String, MutableList<LocalSongIdentity>>()
    songs.forEach { song ->
        val identity = song.toLocalSongIdentity()
        val titleKey = normalizeLocalMetadataText(identity.title)
        val candidates = byTitle.getOrPut(titleKey) { mutableListOf() }
        val duplicate = candidates.any { candidate ->
            localSongMatchScore(
                incoming = identity,
                candidate = candidate,
                matchStrength = matchStrength,
                strictFileNames = strictFileNames,
                strictFilePaths = false,
            ) != null
        }
        if (!duplicate) {
            result.add(song)
            candidates.add(identity)
        }
    }
    return result
}
