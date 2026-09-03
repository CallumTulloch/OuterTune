package com.dd3boh.outertune.models

import java.text.Normalizer
import java.util.Locale

private val repeatedWhitespace = Regex("\\s+")

data class LocalAlbumCandidateRow(
    val albumRowId: Long,
    val albumId: String,
    val albumTitle: String,
    val albumYear: Int?,
    val albumMusicBrainzId: String?,
    val albumArtistName: String?,
    val albumArtistOrder: Int?,
    val localPath: String?,
)

internal data class LocalAlbumCandidate(
    val id: String,
    val title: String,
    val year: Int?,
    val musicBrainzId: String?,
    val artistNames: List<String>,
    val localPaths: List<String>,
)

internal fun cleanLocalMetadataText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(repeatedWhitespace, " ")

internal fun normalizeLocalMetadataText(value: String): String =
    cleanLocalMetadataText(value)
        .lowercase(Locale.ROOT)

internal fun albumArtistSignature(artistNames: Iterable<String>): List<String> =
    artistNames
        .map(::normalizeLocalMetadataText)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()

internal fun List<LocalAlbumCandidateRow>.toLocalAlbumCandidates(): List<LocalAlbumCandidate> =
    groupBy(LocalAlbumCandidateRow::albumId).values.map { rows ->
        val first = rows.first()
        LocalAlbumCandidate(
            id = first.albumId,
            title = first.albumTitle,
            year = first.albumYear,
            musicBrainzId = first.albumMusicBrainzId,
            artistNames = rows.sortedBy { it.albumArtistOrder ?: Int.MAX_VALUE }
                .mapNotNull(LocalAlbumCandidateRow::albumArtistName)
                .distinctBy(::normalizeLocalMetadataText),
            localPaths = rows.mapNotNull(LocalAlbumCandidateRow::localPath).distinct(),
        )
    }

private fun parentDirectory(path: String?): String? = path
    ?.replace('\\', '/')
    ?.substringBeforeLast('/', missingDelimiterValue = "")
    ?.takeIf(String::isNotEmpty)

private fun LocalAlbumCandidate.hasSameParentDirectory(localPath: String): Boolean {
    val incomingParent = parentDirectory(localPath) ?: return false
    return localPaths.any { parentDirectory(it) == incomingParent }
}

private fun LocalAlbumCandidate.hasCompatibleYear(year: Int?): Boolean =
    this.year?.takeIf { it > 0 } == null ||
        year?.takeIf { it > 0 } == null ||
        this.year == year

private fun String?.normalizedMusicBrainzId(): String? =
    this?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

private fun LocalAlbumCandidate.hasSameMusicBrainzId(musicBrainzId: String): Boolean =
    this.musicBrainzId.normalizedMusicBrainzId() == musicBrainzId

private fun LocalAlbumCandidate.hasConflictingMusicBrainzId(musicBrainzId: String?): Boolean {
    val existingId = this.musicBrainzId.normalizedMusicBrainzId()
    return existingId != null && musicBrainzId != null && existingId != musicBrainzId
}

/**
 * Selects an existing local album without guessing across ambiguous Album Artist values.
 * Candidates are expected in oldest-first order.
 */
internal fun selectMatchingLocalAlbum(
    albumArtistNames: Iterable<String>,
    year: Int?,
    localPath: String?,
    musicBrainzId: String? = null,
    candidates: List<LocalAlbumCandidate>,
): LocalAlbumCandidate? {
    val normalizedMusicBrainzId = musicBrainzId.normalizedMusicBrainzId()
    if (normalizedMusicBrainzId != null) {
        candidates.firstOrNull { it.hasSameMusicBrainzId(normalizedMusicBrainzId) }
            ?.let { return it }
    }

    // A known, different MusicBrainz release id is conclusive even when all display metadata is
    // identical. Candidates without an id may still be promoted by a tagged file.
    val eligible = candidates.filterNot {
        it.hasConflictingMusicBrainzId(normalizedMusicBrainzId)
    }
    val incomingSignature = albumArtistSignature(albumArtistNames)

    if (incomingSignature.isNotEmpty()) {
        val sameArtist = eligible.filter {
            albumArtistSignature(it.artistNames) == incomingSignature
        }
        // Files in one directory commonly disagree on year (original release vs remaster). Album
        // Artist plus directory is stronger evidence than that optional field.
        if (localPath != null) {
            val sameDirectory = sameArtist.filter { it.hasSameParentDirectory(localPath) }
            if (sameDirectory.size == 1) return sameDirectory.single()
            if (sameDirectory.size > 1) return null
        }
        val sameArtistAndYear = sameArtist.filter { it.hasCompatibleYear(year) }
        if (sameArtistAndYear.size == 1) return sameArtistAndYear.single()
        if (sameArtistAndYear.size > 1) return null

        val withoutAlbumArtist = eligible.filter {
            albumArtistSignature(it.artistNames).isEmpty()
        }
        if (localPath != null) {
            val sameDirectory = withoutAlbumArtist.filter { it.hasSameParentDirectory(localPath) }
            if (sameDirectory.size == 1) return sameDirectory.single()
            if (sameDirectory.size > 1) return null
            return withoutAlbumArtist
                .filter { it.localPaths.isEmpty() && it.hasCompatibleYear(year) }
                .singleOrNull()
        }
        return withoutAlbumArtist.filter { it.hasCompatibleYear(year) }.singleOrNull()
    }

    if (localPath != null) {
        val sameDirectory = eligible.filter { it.hasSameParentDirectory(localPath) }
        if (sameDirectory.size == 1) return sameDirectory.single()

        val unassignedInDirectory = sameDirectory.filter {
            albumArtistSignature(it.artistNames).isEmpty()
        }
        if (unassignedInDirectory.size == 1) return unassignedInDirectory.single()

        val unassignedWithoutSongs = eligible.filter {
            it.localPaths.isEmpty() &&
                albumArtistSignature(it.artistNames).isEmpty() &&
                it.hasCompatibleYear(year)
        }
        return unassignedWithoutSongs.singleOrNull()
    }

    val compatible = eligible.filter { it.hasCompatibleYear(year) }
    val withoutAlbumArtist = compatible.filter {
        albumArtistSignature(it.artistNames).isEmpty()
    }
    return when {
        withoutAlbumArtist.size == 1 -> withoutAlbumArtist.single()
        compatible.size == 1 -> compatible.single()
        else -> null
    }
}
