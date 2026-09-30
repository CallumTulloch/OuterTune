package com.zionhuang.innertube

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class AlbumTrackSources(
    val songs: List<SongItem>,
    val hasUnresolvedSources: Boolean = false,
)

private const val ALBUM_AUDIO_TYPE = "MUSIC_VIDEO_TYPE_ATV"
private const val ALBUM_VIDEO_TYPE = "MUSIC_VIDEO_TYPE_OMV"

/**
 * Select the audio row explicitly supplied for this album's canonical playlist entry. This is
 * local to the album track list; it does not declare the video and audio recordings equivalent.
 * A complete source snapshot is required before publishing any replacement.
 */
internal suspend fun resolveAlbumTrackSources(
    album: AlbumItem,
    shelfRows: List<MusicResponsiveListItemRenderer>,
    language: String,
    fetchPlaylist: suspend (continuation: String?) -> JsonElement,
): AlbumTrackSources {
    currentCoroutineContext().ensureActive()
    val original = shelfRows.map { requireNotNull(AlbumPage.getSong(it, album, language)) { "Incomplete album track" } }
    require(original.map { it.id }.distinct().size == original.size) { "Repeated album video identity" }
    val candidates = shelfRows.indices.filter { shelfRows[it].videoType() == ALBUM_VIDEO_TYPE }
    if (candidates.isEmpty()) return AlbumTrackSources(original)
    fun unresolved() = AlbumTrackSources(original, hasUnresolvedSources = true)
    val playlistId = album.playlistId?.takeIf { playlistReferenceToken.matches(it) } ?: return unresolved()
    val entries = original.map { it.setVideoId }
    if (entries.any { it == null || !playlistReferenceToken.matches(it) }) return unresolved()
    require(entries.distinct().size == entries.size) { "Repeated album playlist entry" }
    shelfRows.forEach { it.validateIdentity(playlistId) }
    // A shelf without a playback endpoint bound to this entry cannot establish the proposed pair.
    if (candidates.any { index -> shelfRows[index].playbackEndpoints().none {
            it.videoId == original[index].id && it.playlistId == playlistId &&
                it.playlistSetVideoId == original[index].setVideoId
        } }) return unresolved()

    val initial = fetchPlaylist(null)
    currentCoroutineContext().ensureActive()
    // Music can return a successful, header-only response for restricted canonical playlists.
    // This differs from a failed request or malformed/unfinished response chain.
    if (initial.isHeaderOnlyPlaylist()) return unresolved()
    val sourceRows = loadPlaylistBrowseSourceRows(playlistId, language) { continuation ->
        currentCoroutineContext().ensureActive()
        (if (continuation == null) initial else fetchPlaylist(continuation)).also {
            currentCoroutineContext().ensureActive()
        }
    }
    val sources = sourceRows.map { row ->
        row.validateIdentity(playlistId)
        val song = requireNotNull(AlbumPage.getSong(row, language = language)) { "Incomplete canonical album source" }
        require(song.album == null || song.album.id == album.id) { "Canonical source belongs to another album" }
        require(row.flexColumns.flatMap { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
            .mapNotNull { it.navigationEndpoint?.browseEndpoint?.takeIf { endpoint -> endpoint.isAlbumEndpoint } }
            .all { it.browseId == album.id }) { "Canonical source has a foreign album link" }
        row.videoType() // Reject conflicting explicit source types before choosing an endpoint.
        song
    }
    val sourceByEntry = sources.associateBy { it.setVideoId }
    require(sourceByEntry.size == sources.size && sourceByEntry.keys == entries.toSet()) {
        "Canonical playlist and album entries differ"
    }
    val sourceTypeByEntry = sourceRows.associate { it.playlistItemData!!.playlistSetVideoId to it.videoType() }
    // Every changed identity must have direct OMV -> ATV evidence. A thinner response is not
    // permission to make a subset of replacements or to fabricate an endpoint/type.
    if (original.indices.any { index ->
            val song = original[index]
            val source = sourceByEntry.getValue(song.setVideoId)
            source.id != song.id && (index !in candidates || sourceTypeByEntry[song.setVideoId] != ALBUM_AUDIO_TYPE)
        }) return unresolved()
    val resolved = original.map { song ->
        val source = sourceByEntry.getValue(song.setVideoId)
        if (source.id == song.id) song else source.copy(
            album = Album(album.title, album.id),
            isPlayable = song.isPlayable && source.isPlayable,
        )
    }
    require(resolved.map { it.id }.distinct().size == resolved.size) { "Canonical sources collapse album tracks" }
    currentCoroutineContext().ensureActive()
    return AlbumTrackSources(resolved)
}

private fun MusicResponsiveListItemRenderer.playbackEndpoints(): List<WatchEndpoint> = (
    listOfNotNull(overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint,
        navigationEndpoint) + flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text
        ?.runs.orEmpty().mapNotNull { it.navigationEndpoint }
    ).flatMap { listOfNotNull(it.watchEndpoint, it.watchPlaylistEndpoint) }

private fun MusicResponsiveListItemRenderer.videoType(): String? {
    val types = playbackEndpoints().mapNotNull {
        it.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
    }.distinct()
    require(types.size <= 1) { "Conflicting album playback types" }
    return types.singleOrNull()
}

private fun MusicResponsiveListItemRenderer.validateIdentity(playlistId: String) {
    val item = requireNotNull(playlistItemData) { "Missing album entry identity" }
    require(Regex("[A-Za-z0-9_-]{11}").matches(item.videoId)) { "Invalid album video identity" }
    playbackEndpoints().forEach { endpoint ->
        require(endpoint.videoId == null || endpoint.videoId == item.videoId) { "Conflicting album video endpoint" }
        require(endpoint.playlistId == null || endpoint.playlistId == playlistId) { "Foreign album playlist endpoint" }
        require(endpoint.playlistSetVideoId == null || endpoint.playlistSetVideoId == item.playlistSetVideoId) {
            "Conflicting album entry endpoint"
        }
    }
}

private fun JsonElement.isHeaderOnlyPlaylist(): Boolean {
    val root = this as? JsonObject ?: return false
    return root["responseContext"] is JsonObject && root.keys.all {
        it in setOf("responseContext", "microformat", "header", "trackingParams", "background")
    }
}
