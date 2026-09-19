package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import androidx.room.Ignore
import com.zionhuang.innertube.models.ArtistCredit

@Immutable
data class AlbumWithSongs(
    @Embedded
    val album: AlbumEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SortedAlbumArtistMap::class,
            parentColumn = "albumId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>,
    @Relation(
        entity = SongEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SortedSongAlbumMap::class,
            parentColumn = "albumId",
            entityColumn = "songId"
        )
    )
    val songs: List<Song>,
    val downloadCount: Int,
    @Relation(parentColumn = "id", entityColumn = "albumId")
    val songAlbumMaps: List<SongAlbumMap> = emptyList(),
) {
    @get:Ignore
    val artistCredit: ArtistCredit?
        get() = album.artistCredit

    /** Keep saved alternate recordings associated with the album, outside its official track list. */
    fun withTrackOrder(): AlbumWithSongs {
        // Old index=0 associations are ambiguous until a complete page has been fetched.
        if (album.isLocal || !album.hasTrackList) return this
        val trackOrder = songAlbumMaps.filter { it.index >= 0 }.associate { it.songId to it.index }
        val tracks = songs.filter { it.id in trackOrder }.sortedBy { trackOrder.getValue(it.id) }
        return copy(songs = tracks, downloadCount = tracks.count { it.song.dateDownload != null })
    }
}
