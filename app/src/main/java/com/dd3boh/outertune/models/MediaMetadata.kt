package com.dd3boh.outertune.models

import androidx.compose.runtime.Immutable
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.zionhuang.innertube.models.SongItem
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
    /** True after the displayed credits were replaced from the track-credits endpoint. */
    val artistCreditsResolved: Boolean = false,
    /**
     * Typed browse endpoints advertised by the source renderer.
     *
     * These are discovery hints only. In particular, an artist endpoint must not be assigned to
     * a display credit until its browse page name has been verified.
     */
    val metadataEndpointHints: MetadataEndpointHints = MetadataEndpointHints(),
    val composeUidWorkaround: Double = Math.random(), // compose will crash without this hax

    var shuffleIndex: Int = -1
) : Serializable {
    data class Artist(
        val id: String?,
        val name: String,
        val isLocal: Boolean = false,
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

    data class MetadataEndpointHints(
        val albumBrowseId: String? = null,
        val artistBrowseIds: List<String> = emptyList(),
        val creditsBrowseId: String? = null,
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
        metadataAlbumBrowseId = metadataEndpointHints.albumBrowseId
            ?: album?.id?.takeIf { !isLocal },
        metadataArtistBrowseIds = metadataEndpointHints.artistBrowseIds
            .encodeMetadataArtistBrowseIds(),
        metadataCreditsBrowseId = metadataEndpointHints.creditsBrowseId,
        artistCreditsResolved = artistCreditsResolved,
        year = year,
        date = date,
        dateModified = dateModified,
        liked = liked,
        isLocal = isLocal,
        inLibrary = if (isLocal) LocalDateTime.now() else null,
        localPath = localPath
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

fun Song.toMediaMetadata() = MediaMetadata(
    id = song.id,
    title = song.title,
    artists = artists.map {
        MediaMetadata.Artist(
            id = it.navigationId,
            name = it.name,
            isLocal = it.isLocal
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
    artistCreditsResolved = song.artistCreditsResolved,
    isLocal = song.isLocal,
    localPath = song.localPath,
    metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
        albumBrowseId = song.metadataAlbumBrowseId,
        artistBrowseIds = song.metadataArtistBrowseIds.decodeMetadataArtistBrowseIds(),
        creditsBrowseId = song.metadataCreditsBrowseId,
    )
)

private const val METADATA_ARTIST_ID_SEPARATOR = "\u001F"

internal fun List<String>.encodeMetadataArtistBrowseIds(): String? = asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .joinToString(METADATA_ARTIST_ID_SEPARATOR)
    .takeIf(String::isNotEmpty)

internal fun String?.decodeMetadataArtistBrowseIds(): List<String> = this
    ?.split(METADATA_ARTIST_ID_SEPARATOR)
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    .orEmpty()

fun SongItem.toMediaMetadata() = MediaMetadata(
    id = id,
    title = title,
    artists = artists.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name
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
    metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
        albumBrowseId = metadataEndpointHints.album?.browseId,
        artistBrowseIds = metadataEndpointHints.artistCandidates
            .map { it.browseId }
            .distinct(),
        creditsBrowseId = metadataEndpointHints.credits?.browseId,
    ),
)
