package com.dd3boh.outertune.extensions

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.SongItem

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.id)
    .setCustomCacheKey(song.id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(toMediaMetadata().toMedia3Metadata())
    .build()

fun SongItem.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(toMediaMetadata().toMedia3Metadata())
    .build()

internal fun MediaMetadata.toMedia3Metadata(
    base: androidx.media3.common.MediaMetadata? = null,
) = (base?.buildUpon() ?: androidx.media3.common.MediaMetadata.Builder()).apply {
    setTitle(title)
    setSubtitle(artists.joinToString { it.name })
    setArtist(artists.joinToString { it.name })
    // This object is a complete logical snapshot. Explicitly clear fields that disappeared when
    // switching between same-ID queue items, while retaining unrelated Media3 extras from base.
    setArtworkUri(thumbnailUrl?.toUri())
    setAlbumTitle(album?.title)
    setMediaType(MEDIA_TYPE_MUSIC)
}.build()

/** Updates presentation metadata while preserving every playback-related MediaItem property. */
internal fun MediaItem.withMetadata(metadata: MediaMetadata): MediaItem {
    if (mediaId != metadata.id) return this
    return buildUpon()
        .setTag(metadata)
        .setMediaMetadata(metadata.toMedia3Metadata(mediaMetadata))
        .build()
}

fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(thumbnailUrl?.toUri())
            .setAlbumTitle(album?.title)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()
