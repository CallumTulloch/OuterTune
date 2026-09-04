package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.AlbumSortType
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.models.LocalAlbumCandidateRow
import com.dd3boh.outertune.models.LocalSongAlbumArtistRow
import com.zionhuang.innertube.models.AlbumItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * Logic related to albums entities and their mapping
 */

@Dao
interface AlbumsDao : ArtistsDao {

    // region Gets
    @Transaction
    @Query("""
        SELECT album.*, count(song.dateDownload) downloadCount
        FROM album
            LEFT JOIN song_album_map ON song_album_map.albumId = album.id
            LEFT JOIN song ON song.id = song_album_map.songId
        WHERE album.id = :id
        GROUP BY album.id
    """)
    fun album(id: String): Flow<Album?>

    @Query("SELECT * FROM album WHERE id = :id")
    fun albumById(id: String): AlbumEntity?

    @Transaction
    @Query("""
        SELECT album.*, count(song.dateDownload) downloadCount
        FROM album
            LEFT JOIN song_album_map ON song_album_map.albumId = album.id
            LEFT JOIN song ON song.id = song_album_map.songId
        WHERE album.title LIKE '%' || :query || '%' AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        GROUP BY album.id
        LIMIT :previewSize
    """)
    fun searchAlbums(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Album>>

    @Transaction
    @Query("""
        SELECT *
        FROM album
        WHERE album.isLocal = 1 AND album.title LIKE '%' || :query || '%'
        LIMIT :previewSize
    """)
    fun localAlbumsByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): List<AlbumEntity>

    @Transaction
    @Query("""
        SELECT * FROM album
        WHERE album.isLocal = 1
        ORDER BY album.title ASC
    LIMIT :previewSize""")
    fun allLocalAlbumsByName(previewSize: Int = Int.MAX_VALUE): List<AlbumEntity>

    @Query("""
        SELECT * FROM album
        WHERE album.isLocal = 1 AND album.title = :title COLLATE BINARY
        ORDER BY album.rowId ASC
        LIMIT 1
    """)
    fun localAlbumByTitleExact(title: String): AlbumEntity?

    @Query("""
        SELECT
            album.rowId AS albumRowId,
            album.id AS albumId,
            album.title AS albumTitle,
            album.year AS albumYear,
            album.musicBrainzId AS albumMusicBrainzId,
            artist.name AS albumArtistName,
            album_artist_map.`order` AS albumArtistOrder,
            NULL AS localPath
        FROM album
            LEFT JOIN album_artist_map
                ON album_artist_map.albumId = album.id
            LEFT JOIN artist
                ON artist.id = album_artist_map.artistId
        WHERE album.isLocal = 1
            AND (
                TRIM(album.title) = TRIM(:title) COLLATE NOCASE
                OR (:musicBrainzId IS NOT NULL AND album.musicBrainzId = :musicBrainzId)
            )

        UNION ALL

        SELECT
            album.rowId AS albumRowId,
            album.id AS albumId,
            album.title AS albumTitle,
            album.year AS albumYear,
            album.musicBrainzId AS albumMusicBrainzId,
            NULL AS albumArtistName,
            NULL AS albumArtistOrder,
            song.localPath AS localPath
        FROM album
            LEFT JOIN song_album_map
                ON song_album_map.albumId = album.id
            LEFT JOIN song
                ON song.id = song_album_map.songId
        WHERE album.isLocal = 1
            AND (
                TRIM(album.title) = TRIM(:title) COLLATE NOCASE
                OR (:musicBrainzId IS NOT NULL AND album.musicBrainzId = :musicBrainzId)
            )
        ORDER BY albumRowId ASC, albumArtistOrder ASC
    """)
    fun localAlbumCandidateRows(
        title: String,
        musicBrainzId: String?,
    ): List<LocalAlbumCandidateRow>

    @Query("""
        SELECT song.id AS songId, artist.name AS artistName
        FROM song
            JOIN song_album_map ON song_album_map.songId = song.id
            JOIN album_artist_map ON album_artist_map.albumId = song_album_map.albumId
            JOIN artist ON artist.id = album_artist_map.artistId
        WHERE song.isLocal = 1
        ORDER BY song.rowId ASC, album_artist_map.`order` ASC
    """)
    fun allLocalSongAlbumArtistRows(): List<LocalSongAlbumArtistRow>

    @Query("UPDATE song_album_map SET albumId = :newId WHERE albumId = :oldId")
    fun updateSongAlbumMapRows(oldId: String, newId: String)

    @Query("""
        UPDATE song
        SET albumId = :albumId,
            albumName = (SELECT title FROM album WHERE id = :albumId)
        WHERE id IN (
            SELECT songId FROM song_album_map WHERE albumId = :albumId
        )
    """)
    fun updateSongAlbumIdentityForAlbum(albumId: String)

    @Transaction
    fun updateSongAlbumMap(oldId: String, newId: String) {
        updateSongAlbumMapRows(oldId, newId)
        updateSongAlbumIdentityForAlbum(newId)
        refreshLocalAlbumStats(oldId)
        refreshLocalAlbumStats(newId)
    }

    @Query("SELECT artistId FROM album_artist_map WHERE albumId = :albumId ORDER BY `order` ASC")
    fun albumArtistIdsForAlbum(albumId: String): List<String>

    @Query(
        """
        DELETE FROM album
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_album_map
            WHERE song_album_map.albumId = :albumId
        )
        AND id = :albumId
    """
    )
    fun deleteAlbumIfUnreferenced(albumId: String): Int

    @Transaction
    fun safeDeleteAlbum(albumId: String) {
        val previousArtistIds = albumArtistIdsForAlbum(albumId)
        if (deleteAlbumIfUnreferenced(albumId) > 0) {
            previousArtistIds.forEach(::safeDeleteArtist)
        }
    }

    @Transaction
    @Query("""
        SELECT album.*, count(song.dateDownload) downloadCount
        FROM album
            LEFT JOIN song_album_map ON song_album_map.albumId = album.id
            LEFT JOIN song ON song.id = song_album_map.songId
        WHERE album.id = :albumId
        GROUP BY album.id
    """)
    fun albumWithSongs(albumId: String): Flow<AlbumWithSongs?>

    @Transaction
    @Query("SELECT song.* FROM song JOIN song_album_map ON song.id = song_album_map.songId WHERE song_album_map.albumId = :albumId")
    fun albumSongs(albumId: String): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT album.*, count(song.dateDownload) downloadCount
        FROM album
            JOIN song ON album.id = song.albumId
            JOIN event ON song.id = event.songId
        WHERE event.timestamp > :fromTimeStamp
        GROUP BY album.id
        ORDER BY SUM(event.playTime) DESC
        LIMIT :limit OFFSET :offset;
    """)
    fun mostPlayedAlbums(fromTimeStamp: Long, limit: Int = 6, offset: Int = 0): Flow<List<Album>>

    @Transaction
    @Query("""
        SELECT album.*,
            COUNT(DISTINCT CASE WHEN song.dateDownload IS NOT NULL THEN song.id END) downloadCount
        FROM album
            JOIN song_album_map ON song_album_map.albumId = album.id
            JOIN song ON song.id = song_album_map.songId
            LEFT JOIN album_artist_map
                ON album_artist_map.albumId = album.id
                AND album_artist_map.artistId = :artistId
            LEFT JOIN song_artist_map
                ON song_artist_map.songId = song.id
                AND song_artist_map.artistId = :artistId
        WHERE (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL OR song.isLocal = 1)
            AND (
                album_artist_map.artistId IS NOT NULL
                OR (
                    NOT EXISTS (
                        SELECT 1 FROM album_artist_map existing_artist
                        WHERE existing_artist.albumId = album.id
                    )
                    AND song_artist_map.artistId IS NOT NULL
                )
            )
        GROUP BY album.id
        LIMIT :previewSize
    """)
    fun artistAlbumsPreview(artistId: String, previewSize: Int = 6): Flow<List<Album>>

    @RawQuery(
        observedEntities = [
            AlbumEntity::class,
            SongEntity::class,
            SongAlbumMap::class,
            ArtistEntity::class,
            AlbumArtistMap::class,
        ]
    )
    fun _getAlbum(query: SupportSQLiteQuery): Flow<List<Album>>

    fun albums(filter: AlbumFilter, sortType: AlbumSortType, descending: Boolean): Flow<List<Album>> =
        albums(albumContentCondition(filter), sortType, descending)

    fun albums(
        filters: Set<LibraryContentFilter>,
        sortType: AlbumSortType,
        descending: Boolean,
    ): Flow<List<Album>> = albums(libraryAlbumContentCondition(filters), sortType, descending)

    private fun albums(where: String, sortType: AlbumSortType, descending: Boolean): Flow<List<Album>> {
        val orderBy = when (sortType) {
            AlbumSortType.CREATE_DATE -> "album.rowId ASC"
            AlbumSortType.NAME -> "album.title COLLATE NOCASE ASC"
            AlbumSortType.ARTIST -> """(
                                        SELECT LOWER(GROUP_CONCAT(name, ''))
                                        FROM artist
                                        WHERE id IN (SELECT artistId FROM album_artist_map WHERE albumId = album.id)
                                        ORDER BY name
                                    ) COLLATE NOCASE ASC"""
            AlbumSortType.YEAR -> "album.year ASC"
            AlbumSortType.SONG_COUNT -> "album.songCount ASC"
            AlbumSortType.LENGTH -> "album.duration ASC"
        }

        val query = SimpleSQLiteQuery("""
            SELECT album.*,
                COUNT(CASE WHEN song.isLocal = 0 AND song.dateDownload IS NOT NULL THEN 1 END) downloadCount
            FROM album
                LEFT JOIN song_album_map ON album.id = song_album_map.albumId
                LEFT JOIN song ON song.id = song_album_map.songId
            WHERE $where
            GROUP BY album.id
            ORDER BY $orderBy
        """)

        return _getAlbum(query).map { it.reversed(descending) }
    }

    fun albumsInLibraryAsc() = albums(AlbumFilter.LIBRARY, AlbumSortType.CREATE_DATE, false)
    fun albumsLikedAsc() = albums(AlbumFilter.LIKED, AlbumSortType.CREATE_DATE, false)

    @Transaction
    @Query("""
        SELECT album.*, COUNT(song.dateDownload) AS downloadCount
        FROM album
            JOIN song_album_map ON album.id = song_album_map.albumId
            JOIN song ON song.id = song_album_map.songId
        WHERE song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL
        GROUP BY album.id
        ORDER BY album.rowId ASC
    """)
    fun savedAlbumsByCreateDateAsc(): Flow<List<Album>>

    @Query("SELECT * FROM album WHERE title = :name")
    fun albumsByName(name: String): AlbumEntity?

    @Query("""
        UPDATE song
        SET albumId = :albumId, albumName = :albumTitle
        WHERE id = :songId
    """)
    fun updateSongAlbumIdentity(songId: String, albumId: String, albumTitle: String)

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT n.songId      AS eid,
                     SUM(playTime) AS oldPlayTime,
                     newPlayTime
              FROM event
                       JOIN
                   (SELECT songId, SUM(playTime) AS newPlayTime
                    FROM event
                    WHERE timestamp > (:now - 86400000 * 30 * 1)
                    GROUP BY songId
                    ORDER BY newPlayTime) as n
                   ON event.songId = n.songId
              WHERE timestamp < (:now - 86400000 * 30 * 1)
              GROUP BY n.songId
              ORDER BY oldPlayTime) AS t
                 JOIN song on song.id = t.eid
        WHERE 0.2 * t.oldPlayTime > t.newPlayTime
        LIMIT 100
    """
    )
    fun forgottenFavorites(now: Long = System.currentTimeMillis()): Flow<List<Song>>
    @Transaction
    @Query(
        """
        SELECT song.*
        FROM event
                 JOIN
             song ON event.songId = song.id
        WHERE event.timestamp > (:now - 86400000 * 7 * 2)
        GROUP BY song.albumId
        HAVING song.albumId IS NOT NULL
        ORDER BY sum(event.playTime) DESC
        LIMIT :limit
        OFFSET :offset
        """,
    )
    fun recommendedAlbum(
        now: Long = System.currentTimeMillis(),
        limit: Int = 5,
        offset: Int = 0,
    ): Flow<List<Song>>
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSongAlbumMap(map: SongAlbumMap): Long

    @Transaction
    fun insert(map: SongAlbumMap) {
        insertSongAlbumMap(map)
        refreshLocalAlbumStats(map.albumId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: AlbumArtistMap)

    @Upsert
    fun upsertAlbumArtistMap(map: AlbumArtistMap)

    @Query("DELETE FROM album_artist_map WHERE albumId = :albumId")
    fun deleteAlbumArtistMaps(albumId: String)

    @Transaction
    fun replaceAlbumArtistMaps(albumId: String, artists: List<ArtistEntity>) {
        val distinctArtists = artists.distinctBy(ArtistEntity::id)
        val previousArtistIds = albumArtistIdsForAlbum(albumId)
        val newArtistIds = distinctArtists.map(ArtistEntity::id)
        if (previousArtistIds == newArtistIds) return

        deleteAlbumArtistMaps(albumId)
        distinctArtists.forEachIndexed { index, artist ->
            upsertAlbumArtistMap(
                AlbumArtistMap(
                    albumId = albumId,
                    artistId = artist.id,
                    order = index,
                )
            )
        }
        previousArtistIds
            .filterNot(newArtistIds::contains)
            .forEach(::safeDeleteArtist)
    }

    @Transaction
    fun insert(albumItem: AlbumItem) {
        if (insert(AlbumEntity(
                id = albumItem.browseId,
                playlistId = albumItem.playlistId,
                title = albumItem.title,
                year = albumItem.year,
                thumbnailUrl = albumItem.thumbnail,
                songCount = 0,
                duration = 0
            )) == -1L
        ) return
        albumItem.artists?.let { artists ->
            val resolvedArtists = artists.map { artist ->
                resolveAndInsertArtist(
                    id = artist.id,
                    name = artist.name,
                    isLocal = false,
                )
            }
            replaceAlbumArtistMaps(albumItem.browseId, resolvedArtists)
        }
    }
    // endregion

    // region Updates
    @Update
    fun update(album: AlbumEntity)

    @Upsert
    fun upsert(album: AlbumEntity)

    @Upsert
    fun upsertSongAlbumMap(map: SongAlbumMap)

    @Transaction
    fun upsert(map: SongAlbumMap) {
        upsertSongAlbumMap(map)
        refreshLocalAlbumStats(map.albumId)
    }

    @Query("""
        UPDATE album
        SET songCount = (
                SELECT COUNT(*)
                FROM song_album_map
                WHERE song_album_map.albumId = :albumId
            ),
            duration = COALESCE((
                SELECT SUM(CASE WHEN song.duration > 0 THEN song.duration ELSE 0 END)
                FROM song_album_map
                    JOIN song ON song.id = song_album_map.songId
                WHERE song_album_map.albumId = :albumId
            ), 0)
        WHERE album.id = :albumId AND album.isLocal = 1
    """)
    fun refreshLocalAlbumStats(albumId: String)

    /**
     * Set artistId
     */
    @Transaction
    @Query("UPDATE album_artist_map SET artistId = :newId WHERE artistId = :oldId")
    fun updateAlbumArtistMap(oldId: String, newId: String)

    @Transaction
    @Query("DELETE FROM song_artist_map WHERE songId = :songID")
    fun unlinkSongArtists(songID: String)

    @Query("SELECT albumId FROM song_album_map WHERE songId = :songID")
    fun albumIdsForSong(songID: String): List<String>

    @Query("DELETE FROM song_album_map WHERE songId = :songID")
    fun deleteSongAlbumMaps(songID: String)

    @Transaction
    fun unlinkSongAlbums(songID: String) {
        val albumIds = albumIdsForSong(songID)
        deleteSongAlbumMaps(songID)
        albumIds.forEach(::refreshLocalAlbumStats)
    }

    @Transaction
    @Query("DELETE FROM song_genre_map WHERE songId = :songID")
    fun unlinkSongGenres(songID: String)
    // endregion

    // region Deletes
    @Delete
    fun delete(album: AlbumEntity)

    @Transaction
    @Query("DELETE FROM album WHERE isLocal = 1")
    fun nukeLocalAlbums()
    // endregion
}

internal fun albumContentCondition(filter: AlbumFilter): String = when (filter) {
    AlbumFilter.DOWNLOADED -> "song.isLocal = 0 AND song.dateDownload IS NOT NULL"
    AlbumFilter.LIBRARY -> "song.inLibrary IS NOT NULL"
    AlbumFilter.LIKED -> "album.bookmarkedAt IS NOT NULL"
    AlbumFilter.FOLDER -> "song.isLocal = 1 AND song.inLibrary IS NOT NULL"
    AlbumFilter.ALL ->
        "song.inLibrary IS NOT NULL OR (song.isLocal = 0 AND song.dateDownload IS NOT NULL)"
}

internal fun libraryAlbumContentCondition(filters: Set<LibraryContentFilter>): String =
    LibraryContentFilter.effective(filters).map { filter ->
        when (filter) {
            LibraryContentFilter.DOWNLOADED ->
                "(song.isLocal = 0 AND song.dateDownload IS NOT NULL)"
            LibraryContentFilter.LIBRARY -> "(song.inLibrary IS NOT NULL)"
            LibraryContentFilter.FOLDER ->
                "(song.isLocal = 1 AND song.inLibrary IS NOT NULL)"
        }
    }.joinToString(separator = " OR ")
