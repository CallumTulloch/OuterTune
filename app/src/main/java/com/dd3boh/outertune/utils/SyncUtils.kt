/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.LastAlbumSyncKey
import com.dd3boh.outertune.constants.LastArtistSyncKey
import com.dd3boh.outertune.constants.LastFullSyncKey
import com.dd3boh.outertune.constants.LastLibSongSyncKey
import com.dd3boh.outertune.constants.LastLikeSongSyncKey
import com.dd3boh.outertune.constants.LastPlaylistSyncKey
import com.dd3boh.outertune.constants.LastRecentActivitySyncKey
import com.dd3boh.outertune.constants.SYNC_CD
import com.dd3boh.outertune.constants.SyncConflictResolution
import com.dd3boh.outertune.constants.SyncContent
import com.dd3boh.outertune.constants.YtmSyncConflictKey
import com.dd3boh.outertune.constants.YtmSyncContentKey
import com.dd3boh.outertune.constants.decodeSyncString
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.isAutoSyncEnabled
import com.dd3boh.outertune.extensions.isInternetConnected
import com.dd3boh.outertune.extensions.isUserLoggedIn
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

internal fun isSyncEligible(lastSyncEpochSeconds: Long, currentEpochSeconds: Long): Boolean {
    if (lastSyncEpochSeconds <= 0L) return true
    return currentEpochSeconds - lastSyncEpochSeconds >= SYNC_CD
}

internal fun <T> combineCompleteRemoteData(results: List<Result<List<T>>>): Result<List<T>> {
    results.forEach { result ->
        result.exceptionOrNull()?.let { return Result.failure(it) }
    }
    return Result.success(results.flatMap { it.getOrThrow() })
}

internal class LibraryRefreshCoordinator(
    private val refreshDownloads: suspend () -> Boolean,
) {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    suspend fun refresh(refreshRemote: suspend () -> Boolean = { true }): Boolean {
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return false

        return try {
            if (!refreshDownloads()) return false
            refreshRemote()
        } finally {
            _isRefreshing.value = false
        }
    }
}

/**
 * Singleton class for syncing local data from remote YouTube Music
 */
@Singleton
class SyncUtils @Inject constructor(
    val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
    @ApplicationContext private val context: Context
) {
    private val TAG = "SyncUtils"

    private val scope =  CoroutineScope(syncCoroutine)

    private val _isSyncingRemoteLikedSongs = MutableStateFlow(false)
    private val _isSyncingRemoteSongs = MutableStateFlow(false)
    private val _isSyncingRemoteAlbums = MutableStateFlow(false)
    private val _isSyncingRemoteArtists = MutableStateFlow(false)
    private val _isSyncingRemotePlaylists = MutableStateFlow(false)
    private val _isSyncingRecentActivity = MutableStateFlow(false)
    private val libraryRefreshCoordinator = LibraryRefreshCoordinator(downloadUtil::reconcileDownloadIndex)

    val isSyncingRemoteLikedSongs: StateFlow<Boolean> = _isSyncingRemoteLikedSongs.asStateFlow()
    val isSyncingRemoteSongs: StateFlow<Boolean> = _isSyncingRemoteSongs.asStateFlow()
    val isSyncingRemoteAlbums: StateFlow<Boolean> = _isSyncingRemoteAlbums.asStateFlow()
    val isSyncingRemoteArtists: StateFlow<Boolean> = _isSyncingRemoteArtists.asStateFlow()
    val isSyncingRemotePlaylists: StateFlow<Boolean> = _isSyncingRemotePlaylists.asStateFlow()
    val isSyncingRecentActivity: StateFlow<Boolean> = _isSyncingRecentActivity.asStateFlow()
    val isRefreshingLibrary: StateFlow<Boolean> = libraryRefreshCoordinator.isRefreshing

    companion object {
        const val DEFAULT_SYNC_CONTENT = "ARPLSC"
    }

    private enum class SyncStartResult {
        STARTED,
        NOT_REQUIRED,
        BLOCKED,
    }

    suspend fun refreshLibrary(refreshRemote: suspend () -> Boolean = { true }): Boolean =
        libraryRefreshCoordinator.refresh(refreshRemote)

    suspend fun tryAutoSync(force: Boolean = false): Boolean {
        if (force) {
            // A user-requested sync must not depend on the automatic-sync preference.
            if (!context.isUserLoggedIn()) return false
        } else if (!context.isAutoSyncEnabled()) {
            return false
        }
        if (!context.isInternetConnected()) return false

        Log.d(TAG, "Starting ${if (force) "manual" else "automatic"} sync job")
        if (!force && !checkSyncEligibility(LastFullSyncKey)) {
            return true
        }

        return try {
            val allSucceeded = listOf(
                syncRemoteLikedSongs(force),
                syncRemoteSongs(force),
                syncRemoteAlbums(force),
                syncRemoteArtists(force),
                syncRemotePlaylists(force),
            ).all { it }

            if (allSucceeded) updateLastSync(LastFullSyncKey)
            allSucceeded
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Exception) {
            Log.e(TAG, "Full synchronization failed", throwable)
            false
        }
    }

    private suspend fun updateLastSync(key: Preferences.Key<Long>) {
        context.dataStore.edit { settings ->
            settings[key] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        }
    }

    private fun checkEnabled(item: SyncContent): Boolean {
        return decodeSyncString(context.dataStore.get(YtmSyncContentKey, DEFAULT_SYNC_CONTENT)).contains(item)
    }

    private fun checkSyncEligibility(key: Preferences.Key<Long>): Boolean {
        val lastSync = context.dataStore.get(key, 0L)
        val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        val elapsed = currentTime - lastSync
        if (!isSyncEligible(lastSync, currentTime)) {
            val remainingSeconds = (SYNC_CD - elapsed).coerceAtLeast(0L)
            Log.d(TAG, "Aborting auto sync. $remainingSeconds seconds until eligible")
            return false
        }
        return true
    }

    private fun tryStartSync(
        state: MutableStateFlow<Boolean>,
        content: SyncContent,
        lastSyncKey: Preferences.Key<Long>,
        force: Boolean,
        label: String,
    ): SyncStartResult {
        if (!checkEnabled(content)) return SyncStartResult.NOT_REQUIRED
        if (!context.isUserLoggedIn() || !context.isInternetConnected()) return SyncStartResult.BLOCKED
        if (!force && (!context.isAutoSyncEnabled() || !checkSyncEligibility(lastSyncKey))) {
            return SyncStartResult.NOT_REQUIRED
        }
        if (!state.compareAndSet(expect = false, update = true)) {
            Log.i(TAG, "$label synchronization already in progress")
            return SyncStartResult.BLOCKED
        }
        return SyncStartResult.STARTED
    }

    private suspend fun runStartedSync(
        state: MutableStateFlow<Boolean>,
        lastSyncKey: Preferences.Key<Long>,
        label: String,
        block: suspend () -> Unit,
    ): Boolean = try {
        block()
        updateLastSync(lastSyncKey)
        true
    } catch (throwable: CancellationException) {
        throw throwable
    } catch (throwable: Exception) {
        Log.e(TAG, "$label synchronization failed", throwable)
        false
    } finally {
        state.value = false
        Log.i(TAG, "$label synchronization ended")
    }

    private fun checkOverwrite(item: SyncConflictResolution): Boolean {
        return context.dataStore.get(YtmSyncConflictKey, SyncConflictResolution.ADD_ONLY.name)
            .toEnum(defaultValue = SyncConflictResolution.ADD_ONLY) == item
    }

    /**
     * Like single song
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun likeSong(s: SongEntity) {
        scope.launch {
            YouTube.likeVideo(s.id, s.liked)
        }
    }

    /**
     * Add/remove to library single song
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changeInLibrary(s: SongEntity) {
        scope.launch {
            // OuterTune has no stable endpoint for syncing per-song library membership to YTM yet.
            Log.d(TAG, "changeInLibrary: local-only for now, songId=${s.id}, inLibrary=${s.inLibrary != null}")
        }
    }

    /**
     * Singleton syncRemoteLikedSongs
     */
    suspend fun syncRemoteLikedSongs(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRemoteLikedSongs,
                content = SyncContent.LIKED_SONGS,
                lastSyncKey = LastLikeSongSyncKey,
                force = bypass,
                label = "Liked songs",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRemoteLikedSongs,
            lastSyncKey = LastLikeSongSyncKey,
            label = "Liked songs",
        ) {
            Log.d(TAG, "Liked songs synchronization started")

            // Get remote and local liked songs
            val remoteSongs = YouTube.playlist("LM").completed().getOrThrow().songs.reversed()

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                // Identify local songs to unlike
                val songsToUnlike = database.likedSongsByNameAsc().first()
                    .filterNot { it.song.isLocal }
                    .filterNot { localSong -> remoteSongs.any { it.id == localSong.id } }

                // Unlike local songs in the database
                runBlocking {
                    songsToUnlike.forEach { song ->
                        launch(Dispatchers.IO) {
                            database.update(song.song.localToggleLike())
                        }
                    }
                }
            }

            // Insert or like songs in the database
            for (remoteSong in remoteSongs) {
                val localSong = database.song(remoteSong.id).firstOrNull()
                database.awaitTransaction {
                    if (localSong == null) {
                        insert(remoteSong.toMediaMetadata(), SongEntity::localToggleLike)
                    } else if (!localSong.song.liked) {
                        update(localSong.song.localToggleLike())
                    }
                }
            }
        }
    }

    /**
     * Singleton syncRemoteSongs
     */
    suspend fun syncRemoteSongs(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRemoteSongs,
                content = SyncContent.PRIVATE_SONGS,
                lastSyncKey = LastLibSongSyncKey,
                force = bypass,
                label = "Library songs",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRemoteSongs,
            lastSyncKey = LastLibSongSyncKey,
            label = "Library songs",
        ) {
            Log.i(TAG, "Library songs synchronization started")

            // Get remote songs (from library and uploads)
            val remoteSongs = getRemoteData<SongItem>(
                "FEmusic_liked_videos",
                "FEmusic_library_privately_owned_tracks",
            ).getOrThrow()

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                // Identify local songs to remove
                val songsToRemoveFromLibrary = database.songsByNameAsc().first()
                    .filterNot { it.song.isLocal }
                    .filterNot { localSong -> remoteSongs.any { it.id == localSong.id } }

                // Remove local songs from the database
                runBlocking {
                    songsToRemoveFromLibrary.forEach { song ->
                        launch(Dispatchers.IO) {
                            database.update(song.song.toggleLibrary())
                        }
                    }
                }
            }

            // Inset or mark songs to library
            runBlocking {
                val jobs = remoteSongs.map { song ->
                    launch(Dispatchers.IO) {
                        val dbSong = database.song(song.id).firstOrNull()
                        database.awaitTransaction {
                            if (dbSong == null) {
                                insert(song.toMediaMetadata(), SongEntity::toggleLibrary)
                            } else if (dbSong.song.inLibrary == null) {
                                update(dbSong.song.toggleLibrary())
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }
    }

    /**
     * Singleton syncRemoteAlbums
     */
    suspend fun syncRemoteAlbums(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRemoteAlbums,
                content = SyncContent.ALBUMS,
                lastSyncKey = LastAlbumSyncKey,
                force = bypass,
                label = "Library albums",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRemoteAlbums,
            lastSyncKey = LastAlbumSyncKey,
            label = "Library albums",
        ) {
            Log.i(TAG, "Library albums synchronization started")

            // Get remote albums (from library and uploads)
            val remoteAlbums = getRemoteData<AlbumItem>(
                "FEmusic_liked_albums",
                "FEmusic_library_privately_owned_releases",
            ).getOrThrow()

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                // Identify local albums to remove
                val albumsToRemoveFromLibrary = database.albumsLikedAsc().first()
                    .filterNot { it.album.isLocal }
                    .filterNot { localAlbum -> remoteAlbums.any { it.id == localAlbum.id } }

                // Remove albums from local database
                runBlocking {
                    albumsToRemoveFromLibrary.forEach { album ->
                        launch(Dispatchers.IO) {
                            database.update(album.album.localToggleLike())
                        }
                    }
                }
            }

            // Add or mark albums in local database
            runBlocking {
                remoteAlbums.forEach { remoteAlbum ->
                    launch(Dispatchers.IO) {
                        val localAlbum = database.album(remoteAlbum.id).firstOrNull()
                        if (localAlbum == null) {
                            database.insert(remoteAlbum)
                            database.album(remoteAlbum.id).firstOrNull()?.let {
                                database.update(it.album.localToggleLike())
                            }
                        } else if (localAlbum.album.bookmarkedAt == null) {
                            database.update(localAlbum.album.localToggleLike())
                        }
                    }
                }
            }
        }
    }

    /**
     * Singleton syncRemoteArtists
     */
    suspend fun syncRemoteArtists(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRemoteArtists,
                content = SyncContent.ARTISTS,
                lastSyncKey = LastArtistSyncKey,
                force = bypass,
                label = "Library artists",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRemoteArtists,
            lastSyncKey = LastArtistSyncKey,
            label = "Library artists",
        ) {
            Log.i(TAG, "Artist subscriptions synchronization started")

            // Get remote artists (from library and uploads)
            val likedArtists = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_artists",
                "FEmusic_library_privately_owned_artists"
            ).getOrThrow()
            val trackArtists = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_track_artists",
                "FEmusic_library_privately_owned_artists"
            ).getOrThrow()
            val remoteArtists = mutableListOf<ArtistItem>().apply {
                addAll(likedArtists)
                addAll(trackArtists.filterNot { trackArtist ->
                    likedArtists.any { it.id == trackArtist.id }
                })
            }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                // Get local artists
                val artistsToRemoveFromSubscriptions = database.artistsBookmarkedAsc().first()
                    .filterNot { it.artist.isLocal }
                    .filterNot { localArtist -> likedArtists.any { it.id == localArtist.id } }

                // Remove local artists from the database
                runBlocking {
                    artistsToRemoveFromSubscriptions.forEach { artist ->
                        launch(Dispatchers.IO) {
                            database.update(artist.artist.localToggleLike())
                        }
                    }
                }
            }

            // Add or mark artists in the database
            runBlocking {
                remoteArtists.forEach { remoteArtist ->
                    launch(Dispatchers.IO) {
                        val localArtist = database.artist(remoteArtist.id).firstOrNull()
                        val isLikedArtist = likedArtists.contains(remoteArtist)

                        database.awaitTransaction {
                            if (localArtist == null) {
                                insert(
                                    ArtistEntity(
                                        id = remoteArtist.id,
                                        name = remoteArtist.title,
                                        thumbnailUrl = remoteArtist.thumbnail,
                                        channelId = remoteArtist.channelId,
                                        bookmarkedAt = if (isLikedArtist) LocalDateTime.now() else null
                                    )
                                )
                            } else if (localArtist.artist.bookmarkedAt == null && isLikedArtist) {
                                update(localArtist.artist.localToggleLike())
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Singleton syncRemotePlaylists
     */
    suspend fun syncRemotePlaylists(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRemotePlaylists,
                content = SyncContent.PLAYLISTS,
                lastSyncKey = LastPlaylistSyncKey,
                force = bypass,
                label = "Library playlists",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRemotePlaylists,
            lastSyncKey = LastPlaylistSyncKey,
            label = "Library playlists",
        ) {
            Log.i(TAG, "Library playlist synchronization started")

            // Get remote and local playlists
            val page = YouTube.library("FEmusic_liked_playlists").completed().getOrThrow()
            val remotePlaylists = page.items.filterIsInstance<PlaylistItem>()
                .filterNot { it.id == "LM" || it.id == "SE" }
                .reversed()

            val localPlaylists = database.playlistInLibraryAsc().first()

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                // Identify playlists to remove
                val playlistsToRemove = localPlaylists
                    .filterNot { it.playlist.isLocal }
                    .filterNot { it.playlist.browseId == null }
                    .filterNot { localPlaylist -> remotePlaylists.any { it.id == localPlaylist.playlist.browseId } }

                // Remove playlists from the database
                runBlocking {
                    playlistsToRemove.forEach { playlist ->
                        launch(Dispatchers.IO) {
                            database.update(playlist.playlist.localToggleLike())
                        }
                    }
                }
            }

            // Add or update playlists in the database
            val playlistSyncResults = coroutineScope {
                remotePlaylists.map { remotePlaylist ->
                    async(Dispatchers.IO) {
                        // forcefully assign isEditable. These playlists are at mercy of YouTube
                        var localPlaylist =
                            localPlaylists.find { remotePlaylist.id == it.playlist.browseId }?.playlist
                                ?.copy(isEditable = remotePlaylist.isEditable)
                        if (localPlaylist == null) {
                            localPlaylist = PlaylistEntity(
                                name = remotePlaylist.title,
                                browseId = remotePlaylist.id,
                                isEditable = remotePlaylist.isEditable,
                                bookmarkedAt = LocalDateTime.now(),
                                thumbnailUrl = remotePlaylist.thumbnail,
                                remoteSongCount = remotePlaylist.songCountText?.let {
                                    Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                },
                                playEndpointParams = remotePlaylist.playEndpoint?.params,
                                shuffleEndpointParams = remotePlaylist.shuffleEndpoint?.params,
                                radioEndpointParams = remotePlaylist.radioEndpoint?.params
                            )
                            database.insert(localPlaylist)
                        } else {
                            database.update(localPlaylist, remotePlaylist)
                        }

                        // Fetch the playlist again after potential insertion/update
                        val updatedPlaylist = database.playlistByBrowseId(remotePlaylist.id).firstOrNull()
                            ?: return@async false
                        val playlistSongMaps = database.songMapsToPlaylist(updatedPlaylist.id)
                        if (updatedPlaylist.playlist.isEditable || playlistSongMaps.isNotEmpty()) {
                            syncPlaylist(remotePlaylist.id, updatedPlaylist.id)
                        } else {
                            true
                        }
                    }
                }.awaitAll()
            }
            if (!playlistSyncResults.all { it }) {
                error("One or more playlist contents could not be synchronized")
            }
        }
    }

    suspend fun syncPlaylist(browseId: String, playlistId: String): Boolean {
        // this is also used for individual playlist sync
        if (!context.isInternetConnected()) return false

        return try {
            val playlistPage = YouTube.playlist(browseId).completed().getOrThrow()
            database.awaitTransaction {
                clearPlaylist(playlistId)
                val songEntities = playlistPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach { insert(it) }

                val playlistSongMaps = songEntities.mapIndexed { position, song ->
                    PlaylistSongMap(
                        songId = song.id,
                        playlistId = playlistId,
                        position = position,
                        setVideoId = song.setVideoId
                    )
                }
                playlistSongMaps.forEach { insert(it) }
            }
            true
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: Exception) {
            Log.e(TAG, "Playlist content synchronization failed for $browseId", throwable)
            false
        }
    }

    suspend fun syncRecentActivity(bypass: Boolean = false): Boolean {
        when (
            tryStartSync(
                state = _isSyncingRecentActivity,
                content = SyncContent.RECENT_ACTIVITY,
                lastSyncKey = LastRecentActivitySyncKey,
                force = bypass,
                label = "Recent activity",
            )
        ) {
            SyncStartResult.NOT_REQUIRED -> return true
            SyncStartResult.BLOCKED -> return false
            SyncStartResult.STARTED -> Unit
        }

        return runStartedSync(
            state = _isSyncingRecentActivity,
            lastSyncKey = LastRecentActivitySyncKey,
            label = "Recent activity",
        ) {
            Log.i(TAG, "Recent activity synchronization started")
            val page = YouTube.libraryRecentActivity().getOrThrow()
            val recentActivity = page.items.take(9).drop(1)

            database.awaitTransaction {
                clearRecentActivity()
                recentActivity.reversed().forEach { insertRecentActivityItem(it) }
            }
        }
    }

    private suspend inline fun <reified T> getRemoteData(
        libraryId: String,
        uploadsId: String,
    ): Result<List<T>> {
        val browseIds = listOf(
            libraryId to 0,
            uploadsId to 1
        )

        val results = coroutineScope {
            browseIds.map { (browseId, tab) ->
                async {
                    YouTube.library(browseId, tab).completed().map { page ->
                        page.items.filterIsInstance<T>().reversed()
                    }
                }
            }.awaitAll()
        }

        return combineCompleteRemoteData(results)
    }
}
