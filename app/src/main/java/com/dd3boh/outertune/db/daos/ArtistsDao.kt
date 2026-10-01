package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.ArtistAlias
import com.dd3boh.outertune.db.entities.ArtistDisplayView
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.db.entities.ArtistSongView
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource
import com.dd3boh.outertune.db.entities.LocalArtistLinkSourceRow
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.models.cleanLocalMetadataText
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.selectArtistByNormalizedName
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

/*
 * Logic related to artists entities and their mapping
 */

@Dao
interface ArtistsDao {

    // region Gets
    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND (
                song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL OR song.isLocal = 1
            )
        WHERE artist.id = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :id), :id)
        GROUP BY artist.id
    """)
    fun rawArtist(id: String): Flow<Artist?>

    @Transaction
    @Query("""
        SELECT artist.*, COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.isLocal = 0 AND song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist_display artist
            LEFT JOIN artist_song sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND (
                song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL OR song.isLocal = 1
            )
        WHERE artist.id = COALESCE((SELECT canonicalArtistId FROM artist_identity
            WHERE sourceArtistId = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :id), :id)), :id)
        GROUP BY artist.id
    """)
    fun artist(id: String): Flow<Artist?>

    @Query("""SELECT * FROM artist_display WHERE id = COALESCE((SELECT canonicalArtistId FROM artist_identity
        WHERE sourceArtistId = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :id), :id)), :id)""")
    fun artistDisplayById(id: String): ArtistEntity?

    @Query("""
        SELECT identity.sourceArtistId, artist.id AS canonicalArtistId, artist.name, artist.thumbnailUrl
        FROM artist_identity identity JOIN artist_display artist ON artist.id = identity.canonicalArtistId
        WHERE identity.sourceArtistId != identity.canonicalArtistId
    """)
    fun artistDisplayMappings(): Flow<List<ArtistDisplayMapping>>

    @Transaction
    @Query("""
        SELECT artist.*, COUNT(DISTINCT song.id) AS songCount,
            COUNT(DISTINCT CASE WHEN song.dateDownload IS NOT NULL THEN song.id END) AS downloadCount
        FROM local_artist_link link JOIN artist ON artist.id = link.localArtistId AND (artist.isLocal = 1 OR artist.isChannel = 1)
            LEFT JOIN song_artist_map sam ON sam.artistId = artist.id
            LEFT JOIN song ON song.id = sam.songId AND (song.isLocal = 1 OR song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        WHERE :onlineId IS NULL OR link.onlineArtistId = :onlineId
        GROUP BY artist.id ORDER BY artist.name COLLATE NOCASE, artist.id
    """)
    fun localArtistLinkSourceRows(onlineId: String?): Flow<List<LocalArtistLinkSourceRow>>

    fun localArtistLinkSources(onlineId: String? = null): Flow<List<LocalArtistLinkSource>> =
        localArtistLinkSourceRows(onlineId).map { rows -> rows.map(LocalArtistLinkSourceRow::toSource) }

    @Transaction
    @Query("""
        SELECT artist.*, COUNT(DISTINCT song.id) AS songCount,
            COUNT(DISTINCT CASE WHEN song.dateDownload IS NOT NULL THEN song.id END) AS downloadCount
        FROM artist LEFT JOIN song_artist_map sam ON sam.artistId = artist.id
            LEFT JOIN song ON song.id = sam.songId AND (song.isLocal = 1 OR song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        WHERE artist.id = :id AND (artist.isLocal = 1 OR artist.isChannel = 1)
        GROUP BY artist.id
    """)
    fun linkableArtistSourceRow(id: String): Flow<LocalArtistLinkSourceRow?>

    fun linkableArtistSource(id: String): Flow<LocalArtistLinkSource?> =
        linkableArtistSourceRow(id).map { it?.toSource() }

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artistEntityByExactId(id: String): ArtistEntity?

    /** Group bookmarks belong to their representative; raw source favourites remain independent. */
    @Transaction
    fun toggleArtistBookmark(id: String) {
        val display = artistDisplayById(id) ?: return
        val bookmarkedAt = if (display.bookmarkedAt == null) LocalDateTime.now() else null
        val stored = artistEntityByExactId(display.id)
        if (stored == null) insert(display.copy(bookmarkedAt = bookmarkedAt))
        else update(stored.copy(bookmarkedAt = bookmarkedAt))
    }

    @Query("SELECT * FROM artist WHERE id = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :id), :id)")
    fun artistById(id: String): ArtistEntity?

    @Query("SELECT * FROM local_artist_link WHERE localArtistId = :id")
    fun localArtistLink(id: String): Flow<LocalArtistLink?>

    @Query("SELECT * FROM local_artist_link WHERE localArtistId = :id")
    fun localArtistLinkById(id: String): LocalArtistLink?

    @Query("SELECT * FROM local_artist_link ORDER BY localArtistId")
    fun localArtistLinks(): Flow<List<LocalArtistLink>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLocalArtistLink(link: LocalArtistLink)

    @Transaction
    fun setLocalArtistLink(link: LocalArtistLink) {
        val artist = artistById(link.localArtistId)
        require(artist != null && artist.id == link.localArtistId && artist.isLinkableSource) {
            "A folder or channel source artist must exist before linking"
        }
        require(Regex("^UC[A-Za-z0-9_-]{22}$").matches(link.onlineArtistId)) { "A public YouTube artist ID is required" }
        require(link.onlineName.isNotBlank()) { "The online artist name must not be blank" }
        require(link.revision.isNotBlank()) { "A link revision is required" }
        upsertLocalArtistLink(link)
    }

    @Query("DELETE FROM local_artist_link WHERE localArtistId = :id AND revision = :expectedRevision")
    fun deleteLocalArtistLink(id: String, expectedRevision: String): Int

    fun removeLocalArtistLink(id: String, expectedRevision: String): Boolean =
        deleteLocalArtistLink(id, expectedRevision) != 0

    @Query("SELECT COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :id), :id)")
    fun resolveArtistId(id: String): String

    @Query("""SELECT * FROM artist WHERE isLocal = 0 AND isChannel = 0 AND (onlineId = :onlineId OR id = :onlineId)
        ORDER BY CASE WHEN id LIKE 'LA%' THEN 0 ELSE 1 END, rowId LIMIT 1""")
    fun artistByOnlineId(onlineId: String): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(alias: ArtistAlias)

    @Query("SELECT * FROM artist WHERE name = :name")
    fun artistByName(name: String): ArtistEntity?

    @Query("""
        SELECT * FROM artist
        WHERE artist.isLocal = :isLocal
            AND TRIM(artist.name) = TRIM(:name) COLLATE NOCASE
        ORDER BY artist.rowId ASC
        LIMIT 1
    """)
    fun artistByNameAndSourceExact(name: String, isLocal: Boolean): ArtistEntity?

    @Query("SELECT * FROM artist WHERE isLocal = :isLocal ORDER BY rowId ASC")
    fun artistsBySource(isLocal: Boolean): List<ArtistEntity>

    @Query("SELECT artistId FROM song_artist_map WHERE songId = :songId ORDER BY position ASC")
    fun artistIdsForSong(songId: String): List<String>

    /** Resolves id-less metadata without ever joining local and online artists by display name. */
    fun resolveArtist(
        id: String?,
        name: String,
        isLocal: Boolean,
        thumbnailUrl: String? = null,
        channelId: String? = null,
        contextId: String? = null,
    ): ArtistEntity {
        val resolvedName = if (isLocal) cleanLocalMetadataText(name) else name
        val explicitId = id?.takeIf(String::isNotBlank)

        if (!isLocal) {
            explicitId?.let { artistById(it)?.let { existing -> return existing } }
            val onlineId = ArtistIdentity.onlineId(explicitId)
            onlineId?.let { artistByOnlineId(it)?.let { existing -> return existing } }
            return ArtistEntity(
                id = explicitId ?: contextId?.let { ArtistIdentity.stableId(it, resolvedName) }
                    ?: ArtistEntity.generateArtistId(),
                name = resolvedName,
                thumbnailUrl = thumbnailUrl,
                channelId = channelId,
                isLocal = false,
                onlineId = onlineId,
            )
        }

        artistByNameAndSourceExact(resolvedName, isLocal)?.let { return it }
        selectArtistByNormalizedName(
            name = resolvedName,
            isLocal = isLocal,
            candidates = artistsBySource(isLocal),
        )?.let { return it }

        return ArtistEntity(
            id = if (isLocal) ArtistEntity.generateArtistId()
                else explicitId ?: ArtistEntity.generateArtistId(),
            name = resolvedName,
            thumbnailUrl = thumbnailUrl,
            channelId = channelId,
            isLocal = isLocal,
        )
    }

    @Transaction
    fun resolveAndInsertArtist(
        id: String?,
        name: String,
        isLocal: Boolean,
        thumbnailUrl: String? = null,
        channelId: String? = null,
        contextId: String? = null,
    ): ArtistEntity {
        val artist = resolveArtist(id, name, isLocal, thumbnailUrl, channelId, contextId)
        insert(artist)
        if (!isLocal) ArtistIdentity.onlineId(id)?.let { onlineId ->
            updateRemoteArtistProfile(onlineId, thumbnailUrl, channelId, LocalDateTime.now())
        }
        return artistById(artist.id) ?: artist
    }

    @Query("SELECT * FROM artist WHERE isLocal = 1 AND name LIKE '%' || :name || '%'")
    fun localArtistsByNameFuzzy(name: String): List<ArtistEntity>

    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist_display artist
            LEFT JOIN artist_song sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE (artist.name LIKE '%' || :query || '%' OR (artist.isLocal = 0 AND EXISTS (
            SELECT 1 FROM metadata_name
            WHERE kind = 'ARTIST' AND (targetId = artist.onlineId OR targetId = artist.id)
                AND name LIKE '%' || :query || '%'
        ))) AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        GROUP BY artist.id
        HAVING songCount > 0
        ORDER BY artist.bookmarkedAt ASC
        LIMIT :previewSize
    """)
    fun searchArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>

    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist_display artist
            LEFT JOIN artist_song sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE (artist.name LIKE '%' || :query || '%' OR (artist.isLocal = 0 AND EXISTS (
            SELECT 1 FROM metadata_name
            WHERE kind = 'ARTIST' AND (targetId = artist.onlineId OR targetId = artist.id)
                AND name LIKE '%' || :query || '%'
        ))) AND song.inLibrary IS NOT NULL AND song.isLocal
        GROUP BY artist.id
        HAVING songCount > 0
        LIMIT :previewSize
    """)
    fun searchLocalArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>


    @Transaction
    @Query("""
        SELECT DISTINCT song.*
        FROM artist_song JOIN song ON artist_song.songId = song.id
        WHERE artist_song.artistId IN (
            SELECT id FROM artist_display artist
            WHERE name LIKE '%' || :query || '%' OR (artist.isLocal = 0 AND EXISTS (
                SELECT 1 FROM metadata_name
                WHERE kind = 'ARTIST' AND (targetId = artist.onlineId OR targetId = artist.id)
                    AND name LIKE '%' || :query || '%'
            ))
        )
            AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        LIMIT :previewSize
    """)
    fun searchArtistSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Query("""
        SELECT * FROM artist_display artist
        WHERE name LIKE '%' || :query || '%' OR (artist.isLocal = 0 AND EXISTS (
            SELECT 1 FROM metadata_name
            WHERE kind = 'ARTIST' AND (targetId = artist.onlineId OR targetId = artist.id)
                AND name LIKE '%' || :query || '%'
        ))
        LIMIT :previewSize
    """)
    fun artistsByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal != 1 AND isChannel = 0")
    fun allRemoteArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal = 1")
    fun allLocalArtists(): List<ArtistEntity>

    @Transaction
    @Query("""
        WITH played AS (
            SELECT sam.artistId, COUNT(event.id) AS totalPlays
            FROM artist_song sam JOIN event ON event.songId = sam.songId
            WHERE event.timestamp > :fromTimeStamp AND event.playTime > 0
            GROUP BY sam.artistId
        )
        SELECT
            artist.*,
            COUNT(DISTINCT song.id) AS songCount,
            COUNT(DISTINCT CASE WHEN song.dateDownload IS NOT NULL THEN song.id END) AS downloadCount
        FROM artist_display artist
            JOIN played ON played.artistId = artist.id
            LEFT JOIN artist_song sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND song.inLibrary IS NOT NULL
        GROUP BY artist.id
        ORDER BY played.totalPlays DESC, artist.id
        LIMIT :limit
    """)
    fun mostPlayedArtists(fromTimeStamp: Long, limit: Int = 6): Flow<List<Artist>>

    @Transaction
    @RawQuery(observedEntities = [ArtistEntity::class, SongEntity::class, SongArtistMap::class, LocalArtistLink::class, ArtistDisplayView::class, ArtistSongView::class])
    fun _getArtists(query: SupportSQLiteQuery): Flow<List<Artist>>

    fun artists(
        filter: ArtistFilter,
        sortType: ArtistSortType,
        descending: Boolean,
        localOnly: Boolean? = null,
    ): Flow<List<Artist>> = artists(
        contentCondition = artistContentCondition(filter),
        sortType = sortType,
        descending = descending,
        localOnly = localOnly,
        filterUnsupportedArtists = filter != ArtistFilter.LIBRARY && filter != ArtistFilter.ALL,
    )

    fun artists(
        filters: Set<LibraryContentFilter>,
        sortType: ArtistSortType,
        descending: Boolean,
        likedOnly: Boolean = false,
    ): Flow<List<Artist>> {
        val effectiveFilters = LibraryContentFilter.effective(filters)
        return artists(
            contentCondition = libraryArtistContentCondition(filters, likedOnly),
            sortType = sortType,
            descending = descending,
            filterUnsupportedArtists = likedOnly || LibraryContentFilter.LIBRARY !in effectiveFilters,
        )
    }

    private fun artists(
        contentCondition: String,
        sortType: ArtistSortType,
        descending: Boolean,
        localOnly: Boolean? = null,
        filterUnsupportedArtists: Boolean,
    ): Flow<List<Artist>> {
        val orderBy = when (sortType) {
            ArtistSortType.CREATE_DATE -> "artist.sortOrder ASC"
            ArtistSortType.NAME -> "artist.name COLLATE NOCASE ASC"
            ArtistSortType.SONG_COUNT -> "songCount ASC"
        }

        val where = buildList {
            add("($contentCondition)")
            localOnly?.let {
                add("""EXISTS (SELECT 1 FROM artist_identity member JOIN artist source ON source.id = member.sourceArtistId
                    WHERE member.canonicalArtistId = artist.id AND source.isLocal = ${if (it) 1 else 0})""")
            }
        }.joinToString(" AND ")

        val query = SimpleSQLiteQuery("""
            SELECT 
                artist.*,
                COUNT(song.id) AS songCount,
                SUM(CASE WHEN song.isLocal = 0 AND song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM artist_display artist
                LEFT JOIN artist_song sam ON artist.id = sam.artistId
                LEFT JOIN song ON sam.songId = song.id
            WHERE $where
            GROUP BY artist.id
            ORDER BY $orderBy
        """)

        return _getArtists(query).map { artists ->
            val filtered = if (filterUnsupportedArtists) {
                artists.filter { it.artist.isYouTubeArtist || it.artist.isLinkableSource || it.artist.albumGroupId != null }
            } else {
                artists
            }
            filtered.reversed(descending)
        }
    }

    // The media browser lists saved artists from both sources, without a UI source filter.
    fun artistsInLibraryAsc() = artists(
        contentCondition = "song.inLibrary IS NOT NULL",
        sortType = ArtistSortType.CREATE_DATE,
        descending = false,
        filterUnsupportedArtists = false,
    )
    fun artistsBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false)
    fun artistsLocalBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false, true)

    @Transaction
    @Query("""
        SELECT
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist_display artist
            JOIN artist_song sam ON artist.id = sam.artistId
            JOIN song ON sam.songId = song.id
        WHERE song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL
        GROUP BY artist.id
        ORDER BY artist.sortOrder ASC
    """)
    fun savedArtistsByCreateDateAsc(): Flow<List<Artist>>

    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.isLocal = 1
        GROUP BY artist.id
        ORDER BY artist.name ASC
    """)
    fun localArtistsByName(): List<Artist>
    // endregion

    // region Artist Songs Sort
    @Transaction
    @Query("""
        SELECT song.*
        FROM artist_song
            JOIN song ON artist_song.songId = song.id
        WHERE artistId = COALESCE((SELECT canonicalArtistId FROM artist_identity WHERE sourceArtistId = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :artistId), :artistId)), :artistId)
            AND (inLibrary IS NOT NULL OR dateDownload IS NOT NULL OR isLocal = 1)
        ORDER BY COALESCE(inLibrary, dateDownload, dateModified, date)
    """)
    fun artistSongsByCreateDateAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT song.*
        FROM artist_song
            JOIN song ON artist_song.songId = song.id
        WHERE artistId = COALESCE((SELECT canonicalArtistId FROM artist_identity WHERE sourceArtistId = COALESCE((SELECT artistId FROM artist_alias WHERE aliasId = :artistId), :artistId)), :artistId)
            AND (inLibrary IS NOT NULL OR dateDownload IS NOT NULL OR isLocal = 1)
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun artistSongsByNameAsc(artistId: String): Flow<List<Song>>

    fun artistSongs(artistId: String, sortType: ArtistSongSortType, descending: Boolean) =
        when (sortType) {
            ArtistSongSortType.CREATE_DATE -> artistSongsByCreateDateAsc(artistId)
            ArtistSongSortType.NAME -> artistSongsByNameAsc(artistId)
        }.map { it.reversed(descending) }
    // endregion
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongArtistMap)
    // endregion

    // region Updates
    @Update
    fun update(artist: ArtistEntity)

    /** Profile replies must not replace a stale whole entity or clear a known image with a thin reply. */
    @Query("""
        UPDATE artist SET
            thumbnailUrl = COALESCE(NULLIF(TRIM(:thumbnailUrl), ''), thumbnailUrl),
            channelId = COALESCE(NULLIF(TRIM(:channelId), ''), channelId),
            lastUpdateTime = CASE WHEN NULLIF(TRIM(:thumbnailUrl), '') IS NOT NULL
                THEN :observedAt ELSE lastUpdateTime END
        WHERE isLocal = 0 AND isChannel = 0 AND (onlineId = :onlineId OR id = :onlineId)
            AND (:onlineId GLOB 'UC*' OR :onlineId GLOB 'FEmusic_library_privately_owned_artist*')
    """)
    fun updateRemoteArtistProfile(onlineId: String, thumbnailUrl: String?, channelId: String?, observedAt: LocalDateTime): Int

    fun saveArtistProfile(item: ArtistItem, observedAt: LocalDateTime = LocalDateTime.now()): Int {
        val onlineId = ArtistIdentity.onlineId(item.id) ?: return 0
        return updateRemoteArtistProfile(onlineId, item.thumbnail, item.channelId, observedAt)
    }

    @Transaction
    fun update(artist: ArtistEntity, artistPage: ArtistPage) {
        if (artist.onlineArtistId == artistPage.artist.id) saveArtistProfile(artistPage.artist)
    }

    @Transaction
    @Query("UPDATE song_artist_map SET artistId = :newId WHERE artistId = :oldId")
    fun updateSongArtistMap(oldId: String, newId: String)
    // endregion

    // region Deletes
    @Delete
    fun delete(artist: ArtistEntity)

    @Query("""
        DELETE FROM Artist
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_artist_map
            WHERE song_artist_map.artistId = :artistId
        )
        AND NOT EXISTS (
            SELECT 1
            FROM album_artist_map
            WHERE album_artist_map.artistId = :artistId
        )
        AND id = :artistId
        AND bookmarkedAt IS NULL
        AND NOT EXISTS (SELECT 1 FROM artist_alias WHERE artist_alias.artistId = artist.id)
        AND NOT EXISTS (SELECT 1 FROM local_artist_link WHERE localArtistId = artist.id)
        AND NOT EXISTS (SELECT 1 FROM local_artist_link
            WHERE onlineArtistId = artist.id OR (artist.isLocal = 0 AND onlineArtistId = artist.onlineId))
    """)
    fun safeDeleteArtist(artistId: String)

    @Transaction
    @Query("""DELETE FROM artist WHERE isLocal = 1
        AND NOT EXISTS (SELECT 1 FROM local_artist_link WHERE localArtistId = artist.id)""")
    fun nukeLocalArtists()
    // endregion
}

internal fun artistContentCondition(filter: ArtistFilter): String = when (filter) {
    ArtistFilter.DOWNLOADED -> songContentSourceCondition(LibraryContentFilter.DOWNLOADED)
    ArtistFilter.LIBRARY -> songContentSourceCondition(LibraryContentFilter.LIBRARY)
    ArtistFilter.LIKED -> "artist.bookmarkedAt IS NOT NULL"
    ArtistFilter.FOLDER -> songContentSourceCondition(LibraryContentFilter.FOLDER)
    ArtistFilter.ALL -> librarySongContentCondition(emptySet())
}

internal fun libraryArtistContentCondition(filters: Set<LibraryContentFilter>, likedOnly: Boolean = false): String =
    libraryBookmarkedContentCondition(filters, likedOnly, "artist")
