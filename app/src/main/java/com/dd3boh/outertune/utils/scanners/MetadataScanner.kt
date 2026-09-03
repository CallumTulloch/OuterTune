/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.models.SongTempData
import com.dd3boh.outertune.models.cleanLocalMetadataText
import com.dd3boh.outertune.models.normalizeLocalMetadataText
import java.io.File
import java.util.Locale
import java.util.UUID


/**
 * Returns metadata information
 */
interface MetadataScanner {

    /**
     * Given a path to a file, extract necessary metadata.
     *
     * @param file Full file path
     */
    suspend fun getAllMetadataFromFile(file: File): SongTempData
}

/**
 * A wrapper containing extra raw metadata that MediaStore fails to read properly
 */
data class ExtraMetadataWrapper(val artists: String?, val genres: String?, val date: String?, var format: FormatEntity?)

private val albumArtistSeparator = Regex("\\s*[;\\u0000]\\s*")

private fun normalizedMetadataKey(key: String): String =
    key.trim()
        .uppercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

internal fun isAlbumArtistTag(key: String): Boolean =
    normalizedMetadataKey(key) == "ALBUMARTIST"

internal fun isMusicBrainzAlbumIdTag(key: String): Boolean =
    normalizedMetadataKey(key) == "MUSICBRAINZALBUMID"

internal fun parseMusicBrainzAlbumId(values: Iterable<String>): String? =
    values.asSequence()
        .map(::cleanLocalMetadataText)
        .map { it.removePrefix("{").removeSuffix("}") }
        .mapNotNull { value ->
            runCatching { UUID.fromString(value).toString() }.getOrNull()
        }
        .firstOrNull()

internal fun parseAlbumArtistNames(values: Iterable<String>): List<String> =
    values
        .flatMap { it.split(albumArtistSeparator) }
        .map(::cleanLocalMetadataText)
        .filter(String::isNotEmpty)
        .distinctBy(::normalizeLocalMetadataText)

internal fun albumArtistEntities(values: Iterable<String>): List<ArtistEntity> =
    parseAlbumArtistNames(values).map { name ->
        ArtistEntity(
            id = ArtistEntity.generateArtistId(),
            name = name,
            isLocal = true,
        )
    }
