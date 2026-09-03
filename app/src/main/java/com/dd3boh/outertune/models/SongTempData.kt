/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.Song

/**
 * For passing along song metadata
 */
data class SongTempData(
    val song: Song,
    val format: FormatEntity?,
    val albumArtists: List<ArtistEntity> = emptyList(),
    val albumMusicBrainzId: String? = null,
)

fun SongTempData.toMediaMetadata(): MediaMetadata {
    val metadata = song.toMediaMetadata()
    return metadata.copy(
        album = metadata.album?.copy(
            artists = albumArtists.map { artist ->
                MediaMetadata.Artist(
                    id = artist.id,
                    name = artist.name,
                    isLocal = true,
                )
            },
            musicBrainzId = albumMusicBrainzId,
        )
    )
}
