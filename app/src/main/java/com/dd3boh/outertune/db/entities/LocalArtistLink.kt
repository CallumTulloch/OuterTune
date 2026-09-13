package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** A user-selected online destination, independent of local tags and song relationships. */
@Entity(
    tableName = "local_artist_link",
    foreignKeys = [ForeignKey(
        entity = ArtistEntity::class,
        parentColumns = ["id"],
        childColumns = ["localArtistId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class LocalArtistLink(
    @PrimaryKey val localArtistId: String,
    val onlineArtistId: String,
    val onlineName: String,
    val thumbnailUrl: String?,
    val revision: String,
)
