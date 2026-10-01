package com.dd3boh.outertune.db.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import java.io.File

data class LocalArtistLinkSource(val localArtist: Artist, val folders: List<String>, val songs: List<Song> = emptyList())

/** Room-only raw source snapshot; never uses the display grouping for management. */
data class LocalArtistLinkSourceRow(
    @Embedded val localArtist: Artist,
    @Relation(
        entity = SongEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = SongArtistMap::class, parentColumn = "artistId", entityColumn = "songId"),
        projection = ["localPath"],
    )
    val localPaths: List<String?>,
    @Relation(
        entity = SongEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = SongArtistMap::class, parentColumn = "artistId", entityColumn = "songId"),
    )
    val relatedSongs: List<Song>,
) {
    fun toSource() = LocalArtistLinkSource(
        localArtist = localArtist,
        folders = localPaths.mapNotNull { it?.let { path -> File(path).parent } }.distinct().sorted(),
        songs = if (localArtist.artist.isChannel) relatedSongs.filter {
            it.song.inLibrary != null || it.song.dateDownload != null
        }.sortedBy { it.song.title } else emptyList(),
    )
}
