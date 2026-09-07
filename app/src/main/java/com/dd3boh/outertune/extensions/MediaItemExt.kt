package com.dd3boh.outertune.extensions

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.artistDisplayText
import com.dd3boh.outertune.utils.displayAlbumTitle
import com.dd3boh.outertune.utils.displayTitle
import com.zionhuang.innertube.models.SongItem

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.id)
    .setCustomCacheKey(song.id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.displayTitle)
            .setSubtitle(artistDisplayText())
            .setArtist(artistDisplayText())
            .setArtworkUri(song.thumbnailUrl?.toUri())
            .setAlbumTitle(song.displayAlbumTitle)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun SongItem.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setSubtitle(artistDisplayText())
            .setArtist(artistDisplayText())
            .setArtworkUri(thumbnail.toUri())
            .setAlbumTitle(album?.displayTitle)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setSubtitle(artistDisplayText())
            .setArtist(artistDisplayText())
            .setArtworkUri(thumbnailUrl?.toUri())
            .setAlbumTitle(if (isLocal) album?.title else album?.displayTitle)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()
