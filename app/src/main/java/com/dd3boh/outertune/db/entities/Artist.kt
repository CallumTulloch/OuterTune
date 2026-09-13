package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Relation

@Immutable
data class Artist(
    @Embedded
    val artist: ArtistEntity,
    val songCount: Int,
    val downloadCount: Int,
    @Relation(parentColumn = "id", entityColumn = "localArtistId")
    val localLink: LocalArtistLink? = null,
) : LocalItem() {
    override val id: String
        get() = artist.id
    override val title: String
        get() = artist.name
    override val thumbnailUrl: String?
        get() = (if (artist.isLocal) localLink?.thumbnailUrl else null) ?: artist.thumbnailUrl
}
