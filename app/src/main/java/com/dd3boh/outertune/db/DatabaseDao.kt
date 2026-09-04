package com.dd3boh.outertune.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.db.daos.AlbumsDao
import com.dd3boh.outertune.db.daos.ArtistsDao
import com.dd3boh.outertune.db.daos.PlaylistsDao
import com.dd3boh.outertune.db.daos.QueueDao
import com.dd3boh.outertune.db.daos.SongsDao
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.EventWithSong
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.db.entities.RecentActivityEntity
import com.dd3boh.outertune.db.entities.RecentActivityType
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongGenreMap
import com.dd3boh.outertune.extensions.toSQLiteQuery
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.cleanLocalMetadataText
import com.dd3boh.outertune.models.encodeMetadataArtistBrowseIds
import com.dd3boh.outertune.models.isYouTubeArtistBrowseId
import com.dd3boh.outertune.models.normalizeLocalMetadataText
import com.dd3boh.outertune.models.selectMatchingLocalAlbum
import com.dd3boh.outertune.models.toLocalAlbumCandidates
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.coroutines.flow.Flow

internal fun resolveAlbumId(
    metadataAlbumId: String,
    isLocal: Boolean,
    existingLocalAlbumId: String? = null,
): String = when {
    isLocal && existingLocalAlbumId != null -> existingLocalAlbumId
    metadataAlbumId.isNotBlank() -> metadataAlbumId
    else -> AlbumEntity.generateAlbumId()
}

@Dao
interface DatabaseDao : SongsDao, AlbumsDao, ArtistsDao, PlaylistsDao, QueueDao {

    @Transaction
    @Query("""
        SELECT song.*
        FROM (SELECT *, COUNT(1) AS referredCount
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        WHERE songId IN (SELECT songId
                         FROM (SELECT songId
                               FROM event
                               ORDER BY ROWID DESC
                               LIMIT 5)
                         UNION
                         SELECT songId
                         FROM (SELECT songId
                               FROM event
                               WHERE timestamp > :now - 86400000 * 7
                               GROUP BY songId
                               ORDER BY SUM(playTime) DESC
                               LIMIT 5)
                         UNION
                         SELECT id
                         FROM (SELECT id
                               FROM song
                               LIMIT 10))
        ORDER BY referredCount DESC
        LIMIT 100
    """)
    fun quickPicks(now: Long = System.currentTimeMillis()): Flow<List<Song>>

    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    fun clearListenHistory()

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    fun clearSearchHistory()

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    fun hasRelatedSongs(songId: String): Boolean

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT *
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN
             song
             ON song.id = map.relatedSongId
        WHERE songId = :songId
        """
    )
    fun relatedSongs(songId: String): List<Song>

    @Query("""
        SELECT * FROM genre
        WHERE genre.isLocal = 1
        ORDER BY genre.title ASC
    LIMIT :previewSize""")
    fun allLocalGenresByName(previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Query("SELECT * FROM genre WHERE id = :id")
    fun genreById(id: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title = :name")
    fun genreByName(name: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE isLocal = 1 AND title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun localGenreByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Transaction
    @Query("UPDATE song_genre_map SET genreId = :newId WHERE genreId = :oldId")
    fun updateSongGenreMap(oldId: String, newId: String)

    @Query(
        """
        DELETE FROM genre
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_genre_map
            WHERE song_genre_map.genreId = :genreId
        )
        AND id = :genreId
    """
    )
    fun safeDeleteGenre(genreId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongGenreMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchHistory: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: Event)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: RelatedSongMap)

    /**
     * Finds or creates an album for a song metadata record.
     *
     * Local album ids generated by metadata scanners are intentionally not treated as stable.
     * Existing local albums are selected by title and Album Artist, with the local file's parent
     * directory used only as a conservative fallback when Album Artist is absent. Remote albums
     * continue to use their canonical metadata id.
     */
    @Transaction
    fun resolveAndInsertAlbum(
        albumMetadata: MediaMetadata.Album,
        isLocal: Boolean,
        thumbnailUrl: String?,
        duration: Int,
        year: Int?,
        localPath: String?,
    ): AlbumEntity {
        val albumTitle = if (isLocal) {
            cleanLocalMetadataText(albumMetadata.title)
        } else {
            albumMetadata.title
        }
        val localYear = year?.takeIf { it > 0 }
        val localMusicBrainzId = albumMetadata.musicBrainzId?.takeIf(String::isNotBlank)
        val existingLocalAlbumId = if (isLocal) {
            selectMatchingLocalAlbum(
                albumArtistNames = albumMetadata.artists.map { it.name },
                year = localYear,
                localPath = localPath,
                musicBrainzId = localMusicBrainzId,
                candidates = localAlbumCandidateRows(
                    title = albumTitle,
                    musicBrainzId = localMusicBrainzId,
                ).toLocalAlbumCandidates(),
            )?.id
        } else {
            null
        }
        val albumId = resolveAlbumId(
            metadataAlbumId = albumMetadata.id,
            isLocal = isLocal,
            existingLocalAlbumId = existingLocalAlbumId,
        )
        val existingAlbum = albumById(albumId)
        val album = if (existingAlbum == null) {
            AlbumEntity(
                id = albumId,
                title = albumTitle,
                year = if (isLocal) localYear else null,
                musicBrainzId = if (isLocal) localMusicBrainzId else null,
                thumbnailUrl = thumbnailUrl,
                songCount = 1,
                duration = duration,
                isLocal = isLocal,
            ).also(::insert)
        } else if (isLocal) {
            existingAlbum.copy(
                title = albumTitle,
                year = existingAlbum.year?.takeIf { it > 0 } ?: localYear,
                musicBrainzId = existingAlbum.musicBrainzId ?: localMusicBrainzId,
                // Local artwork is loaded through the media file path. Prefer the currently
                // scanned path so a full rescan repairs artwork after a file is moved or removed.
                thumbnailUrl = thumbnailUrl ?: existingAlbum.thumbnailUrl,
            ).also { updatedAlbum ->
                if (updatedAlbum != existingAlbum) update(updatedAlbum)
            }
        } else {
            existingAlbum
        }

        if (isLocal) {
            insertLocalAlbumArtists(album.id, albumMetadata.artists)
        }
        return album
    }

    /** Replaces normalized, local-only Album Artist rows in the metadata's original order. */
    @Transaction
    fun insertLocalAlbumArtists(
        albumId: String,
        albumArtists: List<MediaMetadata.Artist>,
    ) {
        val names = albumArtists
            .map { cleanLocalMetadataText(it.name) }
            .filter(String::isNotEmpty)
            .distinctBy(::normalizeLocalMetadataText)
        // Empty metadata may be a temporary extractor failure. Keep an existing credit instead of
        // erasing it; a non-empty scan is authoritative and replaces stale names/order.
        if (names.isEmpty()) return

        val artists = names.map { artistName ->
            resolveAndInsertArtist(
                id = null,
                name = artistName,
                isLocal = true,
            )
        }
        replaceAlbumArtistMaps(albumId, artists)
    }

    /**
     * Replaces a song's artist credits when [artists] came from an authoritative metadata source.
     * Normal queue/library persistence intentionally remains additive because incomplete search
     * metadata must not erase already resolved credits.
     */
    @Transaction
    fun replaceSongArtistMaps(
        songId: String,
        artists: List<MediaMetadata.Artist>,
        isLocal: Boolean = false,
    ) {
        val previousArtistIds = artistIdsForSong(songId)
        val resolvedArtists = artists
            .map { artist ->
                val resolved = if (
                    !isLocal && artist.id?.isYouTubeArtistBrowseId() != true
                ) {
                    resolveAndInsertUnidentifiedRemoteArtist(artist.name)
                } else {
                    resolveAndInsertArtist(
                        id = artist.id,
                        name = artist.name,
                        isLocal = isLocal,
                    )
                }
                if (!isLocal && resolved.name != artist.name) {
                    // Credits are authoritative. Repair a stale display name even when the
                    // canonical browse id (and therefore the relation row) did not change.
                    resolved.copy(name = artist.name).also(::update)
                } else {
                    resolved
                }
            }
            .distinctBy { it.id }
        val newArtistIds = resolvedArtists.map { it.id }
        markSongArtistCreditsResolved(songId)
        if (previousArtistIds == newArtistIds) return

        unlinkSongArtists(songId)
        resolvedArtists.forEachIndexed { index, artist ->
            insert(
                SongArtistMap(
                    songId = songId,
                    artistId = artist.id,
                    position = index,
                )
            )
        }
        previousArtistIds
            .filterNot(newArtistIds::contains)
            .forEach(::safeDeleteArtist)
    }

    /** Atomically records the verified credits provenance and replaces its artist relations. */
    @Transaction
    fun replaceResolvedSongArtistMetadata(
        songId: String,
        endpointHints: MediaMetadata.MetadataEndpointHints,
        artists: List<MediaMetadata.Artist>,
    ) {
        replaceSongArtistMetadataEndpointHints(
            songId = songId,
            artistBrowseIds = endpointHints.artistBrowseIds.encodeMetadataArtistBrowseIds(),
            creditsBrowseId = endpointHints.creditsBrowseId?.takeIf(String::isNotBlank),
        )
        replaceSongArtistMaps(songId = songId, artists = artists, isLocal = false)
    }

    /** Replaces stale album relations after an authoritative playback lookup. */
    @Transaction
    fun replaceSongAlbumMap(
        songId: String,
        album: MediaMetadata.Album,
    ) {
        albumById(album.id)
            ?.takeIf { !it.isLocal && it.title != album.title }
            ?.copy(title = album.title)
            ?.let(::update)
        val previousAlbumIds = albumIdsForSong(songId)
        if (previousAlbumIds != listOf(album.id)) {
            unlinkSongAlbums(songId)
            insert(
                SongAlbumMap(
                    songId = songId,
                    albumId = album.id,
                    index = 0,
                )
            )
            previousAlbumIds
                .filterNot { it == album.id }
                .forEach(::safeDeleteAlbum)
        }
        updateSongAlbumIdentity(songId, album.id, album.title)
    }

    /** Atomically records the verified album endpoint and replaces its relation. */
    @Transaction
    fun replaceResolvedSongAlbumMetadata(
        songId: String,
        albumBrowseId: String?,
        album: MediaMetadata.Album,
        thumbnailUrl: String?,
        duration: Int,
        year: Int?,
    ) {
        // A generic song insert may intentionally protect an older endpoint from an unverified
        // queue snapshot. The verified path therefore has to create the target AlbumEntity itself
        // before replacing the foreign-key relation.
        resolveAndInsertAlbum(
            albumMetadata = album,
            isLocal = false,
            thumbnailUrl = thumbnailUrl,
            duration = duration,
            year = year,
            localPath = null,
        )
        replaceSongAlbumMetadataEndpointHint(
            songId = songId,
            albumBrowseId = albumBrowseId?.takeIf(String::isNotBlank) ?: album.id,
        )
        replaceSongAlbumMap(songId, album)
    }

    @Transaction
    fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {
        val songEntity = mediaMetadata.toSongEntity().let(block)
        val songAlreadyExists = songExists(songEntity.id)
        val artistCreditsWereResolved = areSongArtistCreditsResolved(songEntity.id)
        val existingAlbumHint = songMetadataAlbumBrowseId(songEntity.id)
        val incomingAlbumHint = songEntity.metadataAlbumBrowseId?.takeIf(String::isNotBlank)
        val protectsExistingAlbumSource =
            !mediaMetadata.isLocal &&
                songAlreadyExists &&
                existingAlbumHint != null &&
                ((incomingAlbumHint != null && existingAlbumHint != incomingAlbumHint) ||
                    (mediaMetadata.album != null && existingAlbumHint != mediaMetadata.album.id))
        val protectsResolvedArtistSource =
            !mediaMetadata.isLocal && artistCreditsWereResolved
        insert(songEntity)
        retainSongMetadataEndpointHints(
            songId = songEntity.id,
            albumBrowseId = songEntity.metadataAlbumBrowseId.takeUnless {
                protectsExistingAlbumSource
            },
            artistBrowseIds = songEntity.metadataArtistBrowseIds.takeUnless {
                protectsResolvedArtistSource
            },
            creditsBrowseId = songEntity.metadataCreditsBrowseId.takeUnless {
                protectsResolvedArtistSource
            },
        )
        val authoritativeArtists = !mediaMetadata.isLocal &&
            !protectsResolvedArtistSource &&
            songEntity.artistCreditsResolved &&
            mediaMetadata.artists.isNotEmpty()
        if (authoritativeArtists) {
            // A resolved snapshot may seed an unresolved row. Once Room contains authoritative
            // credits, generic queue/library writes are read-only for their provenance and maps.
            replaceSongArtistMaps(
                songId = songEntity.id,
                artists = mediaMetadata.artists,
                isLocal = false,
            )
        }
        val artistsToInsert = if (
            mediaMetadata.isLocal ||
            (!artistCreditsWereResolved && !authoritativeArtists && !protectsResolvedArtistSource)
        ) {
            mediaMetadata.artists
        } else {
            // A normal queue/library save is not authoritative and must not append a stale
            // combined display name after credits have already been resolved.
            emptyList()
        }
        artistsToInsert.forEachIndexed { index, artist ->
            val resolvedArtist = resolveAndInsertArtist(
                id = artist.id,
                name = artist.name,
                isLocal = mediaMetadata.isLocal,
            )
            insert(
                SongArtistMap(
                    songId = mediaMetadata.id,
                    artistId = resolvedArtist.id,
                    position = index
                )
            )
        }
        mediaMetadata.genre?.forEachIndexed { index, genre ->
            val genreId = genreByName(genre.title)?.id ?: GenreEntity.generateGenreId()
            insert( // TODO: use upsert???
                GenreEntity(
                    id = genreId,
                    title = genre.title,
                    isLocal = genre.isLocal
                )
            )
            insert(
                SongGenreMap(
                    songId = mediaMetadata.id,
                    genreId = genreId,
                    index = index
                )
            )
        }

        mediaMetadata.album?.takeUnless { protectsExistingAlbumSource }?.let { albumMetadata ->
            val album = resolveAndInsertAlbum(
                albumMetadata = albumMetadata,
                isLocal = mediaMetadata.isLocal,
                thumbnailUrl = mediaMetadata.thumbnailUrl,
                duration = mediaMetadata.duration,
                year = mediaMetadata.year,
                localPath = mediaMetadata.localPath,
            )
            insert(
                SongAlbumMap(
                    songId = mediaMetadata.id,
                    albumId = album.id,
                    index = 0
                )
            )
            updateSongAlbumIdentity(mediaMetadata.id, album.id, album.title)
        }
    }

    @Transaction
    fun insert(albumPage: AlbumPage) {
        upsert(albumPage)
    }

    @Transaction
    fun update(album: AlbumEntity, albumPage: AlbumPage) {
        upsert(albumPage, album)
    }

    @Transaction
    fun upsert(albumPage: AlbumPage, previousAlbum: AlbumEntity? = null) {
        val currentAlbum = albumById(albumPage.album.browseId)
        val preservedAlbum = currentAlbum ?: previousAlbum
        upsert(
            AlbumEntity(
                id = albumPage.album.browseId,
                playlistId = albumPage.album.playlistId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                themeColor = preservedAlbum?.themeColor,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 },
                bookmarkedAt = preservedAlbum?.bookmarkedAt,
            )
        )
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists?.let { artists ->
            val resolvedArtists = artists.map { artist ->
                resolveAndInsertArtist(
                    id = artist.id,
                    name = artist.name,
                    isLocal = false,
                )
            }
            replaceAlbumArtistMaps(albumPage.album.browseId, resolvedArtists)
        }

        previousAlbum
            ?.takeIf { it.id != albumPage.album.browseId }
            ?.let { safeDeleteAlbum(it.id) }
    }

    @Upsert
    fun upsertLyricsUnchecked(lyrics: LyricsEntity)

    @Transaction
    fun upsert(lyrics: LyricsEntity) {
        if (songExists(lyrics.id)) {
            upsertLyricsUnchecked(lyrics)
        }
    }

    @Upsert
    fun upsertFormatUnchecked(format: FormatEntity)

    @Transaction
    fun upsert(format: FormatEntity) {
        if (songExists(format.id)) {
            upsertFormatUnchecked(format)
        }
    }

    @Delete
    fun delete(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics where id = :id")
    fun deleteLyricById(id: String)

    @Delete
    fun delete(searchHistory: SearchHistory)

    @Delete
    fun delete(event: Event)


    /**
     * WARNING: This removes all queue song data and re-adds the queue. Did you mean to use updateQueue()?
     */
    @Transaction
    fun saveQueue(mq: MultiQueueObject) {
        if (mq.queue.isEmpty()) {
            return
        }

        insert(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )

        deleteAllQueueSongs(mq.id)
        // insert songs

        // why does kotlin not have for i loop???
        var i = 0
        while (i < mq.getSize()) {
            insert(mq.queue[i]) // make sure song exists
            insert(
                QueueSongMap(
                    queueId = mq.id,
                    songId = mq.queue[i].id,
                    index = i.toLong(),
                    shuffledIndex = mq.queue[i].shuffleIndex.toLong()
                )
            )
            i ++
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: RecentActivityEntity)

    @Delete
    fun delete(item: RecentActivityEntity)

    @Query("DELETE FROM recent_activity")
    fun clearRecentActivity()

    @Transaction
    fun insertRecentActivityItem(item: YTItem) {
        when (item) {
            is AlbumItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.browseId,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ALBUM,
                        playlistId = item.playlistId,
                        radioPlaylistId = null,
                        shufflePlaylistId = null
                    )
                )
            }

            is PlaylistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.PLAYLIST,
                        playlistId = item.id,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            is ArtistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ARTIST,
                        playlistId = item.playEndpoint?.playlistId,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            else -> {
                // do nothing
            }
        }
    }

    @Query("SELECT * FROM recent_activity ORDER BY date DESC")
    fun recentActivity(): Flow<List<RecentActivityEntity>>

    /**
     * Nukes
     */

    @Query("SELECT EXISTS(SELECT 1 FROM song WHERE isLocal = 1 LIMIT 1)")
    fun hasLocalSongs(): Boolean

    @Transaction
    @Query("DELETE FROM genre WHERE isLocal = 1")
    fun nukeLocalGenre()

    @Transaction
    @Query("""
DELETE FROM format 
WHERE format.id IS NOT NULL 
AND NOT EXISTS (
    SELECT 1 FROM song WHERE song.id = format.id
);
    """)
    fun nukeDanglingFormatEntities()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id IN (SELECT song.id FROM song WHERE song.isLocal)")
    fun nukeLocalLyrics()

    @Transaction
    @Query("DELETE FROM playCount WHERE playCount.song IN (SELECT song.id FROM song WHERE song.isLocal = 1)")
    fun nukeLocalPlayCounts()

    @Transaction
    @Query("DELETE FROM format WHERE format.id IN (SELECT song.id FROM song WHERE song.isLocal = 1)")
    fun nukeLocalFormats()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id NOT IN (SELECT song.id FROM song)")
    fun nukeDanglingLyrics()

    @Transaction
    @Query("DELETE FROM playlist WHERE isLocal = 0")
    fun nukeRemotePlaylists()

    @Transaction
    fun nukeLocalData() {
        // These tables do not have foreign keys to SongEntity. Resolve their local song IDs
        // before deleting the songs so downloaded/online song data is never included.
        nukeLocalPlayCounts()
        nukeLocalLyrics()
        nukeLocalFormats()

        // Song deletion cascades through playlist, queue, history, relation and mapping tables.
        nukeLocalSongs()
        nukeLocalArtists()
        nukeLocalAlbums()
        nukeLocalGenre()
    }

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw("PRAGMA wal_checkpoint(FULL)".toSQLiteQuery())
    }
}
