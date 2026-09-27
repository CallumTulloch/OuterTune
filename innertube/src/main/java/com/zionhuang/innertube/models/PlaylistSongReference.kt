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

/** An explicitly restricted browse entry absent from the terminal playback queue. */
data class UnavailablePlaylistSourceEntry(
    val playlistSetVideoId: String,
    val sourceVideoId: String,
)

/**
 * Both response chains terminated without ambiguity. Every returned pair has direct stable-ID
 * evidence; a restricted source may have no target observation. That absence is not a withdrawal
 * or a new mapping. Self pairs are retained among the observed entries.
 */
data class PlaylistSongReferences(
    val playlistId: String,
    val references: List<PlaylistSongReference>,
    /** Source metadata for the returned pairs only; restricted unpaired entries are separate. */
    val sourceSongs: List<SongItem>,
    /** Only names actually included in the next response; blocked videos may omit all metadata. */
    val targetSongs: List<SongItem>,
    /** No target identity was observed for these entries; callers must keep that coverage explicit. */
    val unavailableSourceEntries: List<UnavailablePlaylistSourceEntry> = emptyList(),
) {
    val hasCompleteIdentityCoverage: Boolean get() = unavailableSourceEntries.isEmpty()
}
