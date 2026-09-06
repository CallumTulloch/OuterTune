package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import androidx.room.Ignore
import com.zionhuang.innertube.models.ArtistCredit

@Immutable
data class Album(
    @Embedded
    val album: AlbumEntity,
    val downloadCount: Int,
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
) : LocalItem() {
    @get:Ignore
    val artistCredit: ArtistCredit?
        get() = album.artistCredit
    override val id: String
        get() = album.id
    override val title: String
        get() = album.title
    override val thumbnailUrl: String?
        get() = album.thumbnailUrl
}
