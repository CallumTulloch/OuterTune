package com.dd3boh.outertune.db.entities

import androidx.room.ColumnInfo
import androidx.room.DatabaseView

@DatabaseView(
    viewName = "sorted_album_artist_map",
    value = "SELECT * FROM album_artist_map ORDER BY `order`",
)
data class SortedAlbumArtistMap(
    @ColumnInfo(index = true) val albumId: String,
    @ColumnInfo(index = true) val artistId: String,
    val order: Int,
)
