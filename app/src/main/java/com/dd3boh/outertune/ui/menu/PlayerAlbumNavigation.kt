package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata

/** A folder rescan can replace an album while the playing queue still holds its old metadata. */
internal fun MediaMetadata.playerAlbumId(librarySong: Song?): String? =
    if (isLocal) {
        librarySong?.takeIf { it.id == id }?.album?.id?.takeIf(String::isNotBlank)
    } else {
        album?.id?.takeIf(String::isNotBlank)
    }
