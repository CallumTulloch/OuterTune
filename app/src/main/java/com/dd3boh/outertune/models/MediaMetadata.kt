package com.dd3boh.outertune.models

import androidx.compose.runtime.Immutable
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.Artist as OnlineArtist
import java.io.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val album: Album? = null,
    val genre: List<Genre>?,
    val year: Int? = null,
    private val date: LocalDateTime? = null, // ID3 tag property
    private val dateModified: LocalDateTime? = null, // file property
    val inLibrary: LocalDateTime? = null, // doubles as "date added"
    val setVideoId: String? = null,
    val isLocal: Boolean = false,
    val localPath: String? = null,
    val liked: Boolean = false,
    val composeUidWorkaround: Double = Math.random(), // compose will crash without this hax

    var shuffleIndex: Int = -1,
    val artistCredit: ArtistCredit? = null,
) : Serializable {
    data class Artist(
        val id: String?,
        val name: String,
        val isLocal: Boolean = false,
        val onlineId: String? = null,
    ) : Serializable

    data class Album(
        val id: String,
        val title: String,
        val isLocal: Boolean = false,
        val artists: List<Artist> = emptyList(),
        val musicBrainzId: String? = null,
    ) : Serializable

    data class Genre(
        val id: String?,
        val title: String,
        val isLocal: Boolean = false,
    ) : Serializable

    fun toSongEntity() = SongEntity(
        id = id,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumId = album?.id,
        albumName = album?.title,
        year = year,
        date = date,
        dateModified = dateModified,
        liked = liked,
        isLocal = isLocal,
        inLibrary = if (isLocal) LocalDateTime.now() else null,
        localPath = localPath,
        artistCreditJson = artistCredit?.toStoredJson(),
    )

    /**
     * Returns a full date string. If no full date is present, returns the year.
     * This is the song's tag's date/year, NOT dateModified.
     */
    fun getDateString(): String? {
        return date?.toLocalDate()?.toString()
            ?: if (year != null) {
                return year.toString()
            } else {
                return null
            }
    }

    /**
     * Returns a full date modified string
     */
    fun getDateModifiedString(): String? {
        return dateModified?.toLocalDate()?.toString()
    }

    /**
     * Get the value of the date released in Epoch Seconds
     */
    fun getDateLong(): Long? = date?.toEpochSecond(ZoneOffset.UTC)

    /**
     * Get the value of the date modified in Epoch Seconds
     */
    fun getDateModifiedLong(): Long? = dateModified?.toEpochSecond(ZoneOffset.UTC)

    fun getThumbnailModel(sizeX: Int = -1, sizeY: Int = -1): Any? {
        return if (isLocal) {
            LocalArtworkPath(thumbnailUrl ?: localPath, sizeX, sizeY)
        } else {
            thumbnailUrl?.resize(
                width = sizeX.takeIf { it > 0 },
                height = sizeY.takeIf { it > 0 }
            )
        }
    }
}

fun Song.toMediaMetadata(): MediaMetadata {
    val credit = artistCredit?.let { stored ->
        stored.copy(artists = stored.artists.map { artist ->
            val entity = artists.firstOrNull { it.id == artist.ref }
                ?: artists.firstOrNull { artist.id != null && it.onlineArtistId == artist.id }
            artist.copy(ref = entity?.id ?: artist.ref, id = artist.id ?: entity?.onlineArtistId)
        })
    }
    return MediaMetadata(
    id = song.id,
    title = song.title,
    artists = credit?.artists?.map {
        MediaMetadata.Artist(
            id = it.ref ?: ArtistIdentity.stableId(song.id, it.name),
            name = it.name,
            onlineId = ArtistIdentity.onlineId(it.id),
        )
    } ?: artists.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name,
            isLocal = it.isLocal,
            onlineId = it.onlineArtistId,
        )
    },
    duration = song.duration,
    thumbnailUrl = song.thumbnailUrl,
    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    album = album?.let {
        MediaMetadata.Album(
            id = it.id,
            title = it.title,
            isLocal = it.isLocal,
            musicBrainzId = it.musicBrainzId,
        )
    } ?: song.albumId?.let { albumId ->
        MediaMetadata.Album(
            id = albumId,
            title = song.albumName.orEmpty(),
            // no possible local albums somehow
        )
    },
    genre = genre?.map {
        MediaMetadata.Genre(
            id = it.id,
            title = it.title,
            isLocal = it.isLocal
        )
    },
    year = song.year,
    date = song.date,
    dateModified = song.dateModified,
    inLibrary = song.inLibrary,
    liked = song.liked,
    isLocal = song.isLocal,
    localPath = song.localPath,
    artistCredit = credit,
)
}

fun SongItem.toMediaMetadata(): MediaMetadata {
    val credit = artistCredit?.let { ArtistIdentity.withStableRefs(id, it) }
    return MediaMetadata(
    id = id,
    title = title,
    artists = (credit?.artists ?: artists).map {
        MediaMetadata.Artist(
            id = it.ref ?: ArtistIdentity.onlineId(it.id) ?: ArtistIdentity.stableId(id, it.name),
            name = it.name,
            onlineId = ArtistIdentity.onlineId(it.id),
        )
    },
    duration = duration ?: -1,
    thumbnailUrl = thumbnail.resize(544, 544),
    album = album?.let {
        MediaMetadata.Album(
            id = it.id,
            title = it.name
        )
    },
    genre = null,
    setVideoId = setVideoId,
    artistCredit = credit,
)
}

/** Applies an accepted credit without changing playback state or any album metadata. */
fun MediaMetadata.withArtistCredit(credit: ArtistCredit): MediaMetadata {
    val stable = ArtistIdentity.withStableRefs(id, credit, artistCredit)
    return copy(
        artistCredit = stable,
        artists = stable.artists.map {
            MediaMetadata.Artist(id = it.ref, name = it.name, onlineId = it.id)
        },
    )
}

/** Older callers can supply labels without proof that each label identifies one person. */
internal fun MediaMetadata.creditForPersistence(): ArtistCredit {
    artistCredit?.let { return it }
    val confirmed = artists.mapNotNull { artist ->
        val onlineId = ArtistIdentity.onlineId(artist.onlineId) ?: ArtistIdentity.onlineId(artist.id)
        onlineId?.let { OnlineArtist(name = artist.name, id = it, ref = artist.id) }
    }
    return ArtistCredit(
        rawText = artists.joinToString { it.name },
        artists = confirmed,
        status = when {
            confirmed.isEmpty() -> ArtistCreditStatus.RAW
            confirmed.size == artists.size -> ArtistCreditStatus.COMPLETE
            else -> ArtistCreditStatus.PARTIAL
        },
        source = "legacy-metadata",
        language = "",
        evidence = emptyList(),
    )
}
