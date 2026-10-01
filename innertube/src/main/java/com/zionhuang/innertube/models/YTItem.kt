package com.zionhuang.innertube.models

sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
    abstract val explicit: Boolean
    abstract val shareLink: String
}

@kotlinx.serialization.Serializable
data class Artist(
    val name: String,
    val id: String?,
    val ref: String? = null,
    /** Uploader identity; this is not an online artist-page ID. */
    val sourceChannelId: String? = null,
    val isChannel: Boolean = sourceChannelId != null,
) : java.io.Serializable

@kotlinx.serialization.Serializable
data class Album(
    val name: String,
    val id: String,
) : java.io.Serializable

data class SongItem(
    override val id: String,
    override val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val duration: Int? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
    val endpoint: WatchEndpoint? = null,
    val setVideoId: String? = null,
    val artistCredit: ArtistCredit? = null,
    val artistBrowseIds: List<String> = emptyList(),
    val isPlayable: Boolean = true,
) : YTItem() {
    override val shareLink: String
        get() = "https://music.youtube.com/watch?v=$id"
}

data class AlbumItem(
    val browseId: String,
    val playlistId: String?,
    override val id: String = browseId,
    override val title: String,
    val artists: List<Artist>?,
    val year: Int? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
    val artistCredit: ArtistCredit? = null,
) : YTItem() {
    override val shareLink: String
        get() = playlistId?.let { "https://music.youtube.com/playlist?list=$it" }
            ?: "https://music.youtube.com/browse/$browseId"
}

data class PlaylistItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val songCountText: String?,
    override val thumbnail: String?,
    val playEndpoint: WatchEndpoint?,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
    val isEditable: Boolean = false,
) : YTItem() {
    override val explicit: Boolean
        get() = false
    override val shareLink: String
        get() = "https://music.youtube.com/playlist?list=$id"
}

data class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val channelId: String? = null,
    val playEndpoint: WatchEndpoint? = null,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
) : YTItem() {
    override val explicit: Boolean
        get() = false
    override val shareLink: String
        get() = "https://music.youtube.com/channel/$id"
}
