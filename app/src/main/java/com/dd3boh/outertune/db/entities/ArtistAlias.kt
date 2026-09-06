package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Old internal routes remain usable after two confirmed identities are joined. */
@Entity(
    tableName = "artist_alias",
    indices = [Index("artistId")],
    foreignKeys = [ForeignKey(
        entity = ArtistEntity::class,
        parentColumns = ["id"],
        childColumns = ["artistId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistAlias(@PrimaryKey val aliasId: String, val artistId: String)
