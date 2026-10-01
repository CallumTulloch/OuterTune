package com.dd3boh.outertune.utils

import androidx.compose.runtime.mutableStateOf
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.RecentActivityEntity
import com.dd3boh.outertune.db.entities.RecentActivityType
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only display projection. Provider text, stored credits and playback identities stay intact. */
object MetadataNames {
    private val displayNames = mutableStateOf<Map<OriginalNameTarget, String>>(emptyMap())
    private val searchNames = mutableStateOf<Map<OriginalNameTarget, List<String>>>(emptyMap())
    private val revision = MutableStateFlow(0L)
    val updates = revision.asStateFlow()

    internal fun publish(names: Map<OriginalNameTarget, String>, aliases: Map<OriginalNameTarget, List<String>> = emptyMap()) {
        if (displayNames.value == names && searchNames.value == aliases) return
        displayNames.value = names
        searchNames.value = aliases
        revision.value += 1
    }

    fun resolve(kind: OriginalNameKind, id: String?, fallback: String): String =
        id?.let { displayNames.value[OriginalNameTarget(kind, it)] } ?: fallback

    fun matches(kind: OriginalNameKind, id: String?, query: String): Boolean = id != null &&
        searchNames.value[OriginalNameTarget(kind, id)].orEmpty().any { it.contains(query, ignoreCase = true) }

}

val YTItem.displayTitle: String
    get() = when (this) {
        is SongItem -> MetadataNames.resolve(OriginalNameKind.SONG, id, title)
        is AlbumItem -> MetadataNames.resolve(OriginalNameKind.ALBUM, browseId, title)
        is ArtistItem -> MetadataNames.resolve(OriginalNameKind.ARTIST, id, title)
        else -> title
    }

val SongEntity.displayTitle: String
    get() = if (isLocal) title else MetadataNames.resolve(OriginalNameKind.SONG, id, title)
val AlbumEntity.displayTitle: String
    get() = if (isLocal) title else MetadataNames.resolve(OriginalNameKind.ALBUM, id, title)
val ArtistEntity.displayName: String
    get() = ArtistDisplayProjection.resolve(id)?.name
        ?: if (isLinkableSource) name else MetadataNames.resolve(OriginalNameKind.ARTIST, onlineArtistId, name)
val MediaMetadata.displayTitle: String
    get() = if (isLocal) title else MetadataNames.resolve(OriginalNameKind.SONG, id, title)
val MediaMetadata.Artist.displayName: String
    get() = ArtistDisplayProjection.resolve(id)?.name ?: if (isLocal || isChannel) name else MetadataNames.resolve(OriginalNameKind.ARTIST,
        ArtistIdentity.onlineId(onlineId) ?: ArtistIdentity.onlineId(id), name)
val MediaMetadata.Album.displayTitle: String
    get() = if (isLocal) title else MetadataNames.resolve(OriginalNameKind.ALBUM, id, title)
val com.zionhuang.innertube.models.Artist.displayName: String
    get() = ArtistDisplayProjection.resolve(ref ?: id)?.name
        ?: if (isChannel) name else MetadataNames.resolve(OriginalNameKind.ARTIST, ArtistIdentity.onlineId(id), name)
val com.zionhuang.innertube.models.Album.displayTitle: String
    get() = MetadataNames.resolve(OriginalNameKind.ALBUM, id, name)

val SongEntity.displayAlbumTitle: String?
    get() = if (isLocal) albumName else albumName?.let { MetadataNames.resolve(OriginalNameKind.ALBUM, albumId, it) }

val RecentActivityEntity.displayTitle: String
    get() = when (type) {
        RecentActivityType.ALBUM -> MetadataNames.resolve(OriginalNameKind.ALBUM, id, title)
        RecentActivityType.ARTIST -> ArtistDisplayProjection.resolve(id)?.name
            ?: MetadataNames.resolve(OriginalNameKind.ARTIST, ArtistIdentity.onlineId(id), title)
        else -> title
    }

fun MediaMetadata.matchesMetadataQuery(query: String): Boolean =
    title.contains(query, ignoreCase = true) || artists.any { artist ->
        val projected = ArtistDisplayProjection.resolve(artist.id)
        if (projected != null) projected.name.contains(query, ignoreCase = true) ||
            MetadataNames.matches(OriginalNameKind.ARTIST, projected.onlineIdentity, query)
        else artist.name.contains(query, ignoreCase = true)
    } ||
        (!isLocal && (MetadataNames.matches(OriginalNameKind.SONG, id, query) ||
            artists.any { !it.isChannel && ArtistDisplayProjection.resolve(it.id) == null && MetadataNames.matches(OriginalNameKind.ARTIST,
                ArtistIdentity.onlineId(it.onlineId) ?: ArtistIdentity.onlineId(it.id), query) }))
