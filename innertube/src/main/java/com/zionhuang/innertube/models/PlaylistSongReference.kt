package com.zionhuang.innertube.models

/**
 * Two video IDs explicitly attached to the same provider playlist entry. This lends song-title
 * attribution only; it does not establish recording, playback, artist or album equivalence.
 */
data class PlaylistSongReference(
    val playlistId: String,
    val playlistSetVideoId: String,
    val sourceVideoId: String,
    val targetVideoId: String,
)

/** A complete, unambiguous observation. Self pairs are retained to account for every entry. */
data class PlaylistSongReferences(
    val playlistId: String,
    val references: List<PlaylistSongReference>,
    val sourceSongs: List<SongItem>,
    /** Only names actually included in the next response; blocked videos may omit all metadata. */
    val targetSongs: List<SongItem>,
)
