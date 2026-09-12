package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.dd3boh.outertune.db.entities.ArtistAlias
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.artistCreditFromJson
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.isEmptyByline
import com.zionhuang.innertube.models.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StoredSongArtistCredit(val id: String, val artistCreditJson: String?)
data class StoredAlbumArtistCredit(val id: String, val artistCreditJson: String?)

/** Updates interpretation without changing the user's library state or playback metadata. */
@Dao
interface ArtistCreditDao : ArtistsDao {
    @Query("SELECT artistCreditJson FROM song WHERE id = :videoId")
    fun artistCreditJson(videoId: String): Flow<String?>

    fun artistCredit(videoId: String): Flow<ArtistCredit?> = artistCreditJson(videoId).map(::artistCreditFromJson)

    @Query("SELECT * FROM song WHERE id = :videoId")
    fun songForArtistCredit(videoId: String): SongEntity?

    @Query("UPDATE song SET artistCreditJson = :json WHERE id = :videoId")
    fun updateArtistCreditJson(videoId: String, json: String)

    @Query("DELETE FROM song_artist_map WHERE songId = :videoId")
    fun deleteSongArtistMaps(videoId: String)

    @Transaction
    fun applyArtistCredit(videoId: String, credit: ArtistCredit) {
        // A pending/failed lookup contains no evidence that stored people disappeared.
        if (credit.isEmptyByline()) return
        val song = songForArtistCredit(videoId)?.takeUnless { it.isLocal } ?: return
        val previous = artistCreditFromJson(song.artistCreditJson)
        val adopted = ArtistIdentity.withStableRefs(videoId, previous?.merge(credit) ?: credit, previous)
        val resolved = adopted.artists.map { resolveCreditArtist(videoId, it) }
            .map { artistById(it.id) ?: it }
        val persisted = adopted.copy(artists = adopted.artists.zip(resolved).map { (label, entity) ->
            label.copy(ref = entity.id, id = entity.onlineArtistId)
        })
        val json = persisted.toStoredJson()
        val uniqueArtists = resolved.distinctBy(ArtistEntity::id)
        val sameRelations = artistIdsForSong(videoId) == uniqueArtists.map(ArtistEntity::id)
        if (song.artistCreditJson == json && sameRelations) return
        // Song insertion, raw display, confirmed people and ordering are committed together.
        if (song.artistCreditJson != json) updateArtistCreditJson(videoId, json)
        if (sameRelations) return
        deleteSongArtistMaps(videoId)
        uniqueArtists.forEachIndexed { index, artist ->
            insert(SongArtistMap(songId = videoId, artistId = artist.id, position = index))
        }
    }

    fun resolveCreditArtist(videoId: String, artist: Artist): ArtistEntity {
        val remoteId = ArtistIdentity.onlineId(artist.id)
        val internalId = artist.ref ?: remoteId ?: ArtistIdentity.stableId(videoId, artist.name)
        val byRef = artistById(internalId)?.takeUnless { it.isLocal }?.takeIf {
            remoteId == null || it.onlineArtistId == null || it.onlineArtistId == remoteId
        }
        val byOnline = remoteId?.let(::artistByOnlineId)
        val existing = if (byRef != null && byOnline != null && byRef.id != byOnline.id) {
            // Keep an existing surrogate over a UC route; between surrogates reuse the older
            // established online identity. Both previous routes remain valid through aliases.
            val canonical = if (byRef.id.startsWith("LA") && !byOnline.id.startsWith("LA")) byRef else byOnline
            val duplicate = if (canonical.id == byRef.id) byOnline else byRef
            mergeArtistIdentity(canonical, duplicate)
        } else byRef ?: byOnline
        val entity = existing?.copy(onlineId = existing.onlineArtistId ?: remoteId)
            ?: ArtistEntity(
                id = if (artistById(internalId) == null) internalId
                    else remoteId ?: ArtistIdentity.stableId(videoId, artist.name),
                name = artist.name,
                onlineId = remoteId,
            )
        if (existing == null) insert(entity) else if (existing != entity) update(entity)
        return entity
    }

    @Query("""SELECT song.id, song.artistCreditJson FROM song
        JOIN song_artist_map ON song.id = song_artist_map.songId WHERE artistId = :artistId""")
    fun songCreditsForArtist(artistId: String): List<StoredSongArtistCredit>

    @Query("""SELECT album.id, album.artistCreditJson FROM album
        JOIN album_artist_map ON album.id = album_artist_map.albumId WHERE artistId = :artistId""")
    fun albumCreditsForArtist(artistId: String): List<StoredAlbumArtistCredit>

    @Query("UPDATE album SET artistCreditJson = :json WHERE id = :albumId")
    fun updateAlbumArtistCreditJson(albumId: String, json: String)

    @Query("""UPDATE song_artist_map SET position = MIN(position,
        (SELECT previous.position FROM song_artist_map previous
            WHERE previous.songId = song_artist_map.songId AND previous.artistId = :oldId))
        WHERE artistId = :newId AND EXISTS (SELECT 1 FROM song_artist_map previous
            WHERE previous.songId = song_artist_map.songId AND previous.artistId = :oldId)""")
    fun preserveMergedSongArtistOrder(oldId: String, newId: String)

    @Query("""INSERT OR IGNORE INTO song_artist_map(songId, artistId, position)
        SELECT songId, :newId, position FROM song_artist_map WHERE artistId = :oldId""")
    fun copySongArtistRelations(oldId: String, newId: String)

    @Query("DELETE FROM song_artist_map WHERE artistId = :artistId")
    fun deleteArtistSongRelations(artistId: String)

    @Query("""UPDATE album_artist_map SET `order` = MIN(`order`,
        (SELECT previous.`order` FROM album_artist_map previous
            WHERE previous.albumId = album_artist_map.albumId AND previous.artistId = :oldId))
        WHERE artistId = :newId AND EXISTS (SELECT 1 FROM album_artist_map previous
            WHERE previous.albumId = album_artist_map.albumId AND previous.artistId = :oldId)""")
    fun preserveMergedAlbumArtistOrder(oldId: String, newId: String)

    @Query("""INSERT OR IGNORE INTO album_artist_map(albumId, artistId, `order`)
        SELECT albumId, :newId, `order` FROM album_artist_map WHERE artistId = :oldId""")
    fun copyAlbumArtistRelations(oldId: String, newId: String)

    @Query("DELETE FROM album_artist_map WHERE artistId = :artistId")
    fun deleteArtistAlbumRelations(artistId: String)

    @Query("UPDATE artist_alias SET artistId = :newId WHERE artistId = :oldId")
    fun redirectArtistAliases(oldId: String, newId: String)

    @Transaction
    fun mergeArtistIdentity(canonical: ArtistEntity, duplicate: ArtistEntity): ArtistEntity {
        if (canonical.id == duplicate.id) return canonical
        require(!canonical.isLocal && !duplicate.isLocal)
        require(canonical.onlineArtistId == null || duplicate.onlineArtistId == null ||
            canonical.onlineArtistId == duplicate.onlineArtistId)
        val combined = canonical.copy(
            onlineId = canonical.onlineArtistId ?: duplicate.onlineArtistId,
            thumbnailUrl = canonical.thumbnailUrl ?: duplicate.thumbnailUrl,
            channelId = canonical.channelId ?: duplicate.channelId,
            bookmarkedAt = canonical.bookmarkedAt ?: duplicate.bookmarkedAt,
        )
        update(combined)
        songCreditsForArtist(duplicate.id).forEach { row ->
            artistCreditFromJson(row.artistCreditJson)?.let { credit ->
                updateArtistCreditJson(row.id, credit.copy(artists = credit.artists.map { artist ->
                    if (artist.ref == duplicate.id) artist.copy(ref = canonical.id) else artist
                }).toStoredJson())
            }
        }
        albumCreditsForArtist(duplicate.id).forEach { row ->
            artistCreditFromJson(row.artistCreditJson)?.let { credit ->
                updateAlbumArtistCreditJson(row.id, credit.copy(artists = credit.artists.map { artist ->
                    if (artist.ref == duplicate.id) artist.copy(ref = canonical.id) else artist
                }).toStoredJson())
            }
        }
        preserveMergedSongArtistOrder(duplicate.id, canonical.id)
        copySongArtistRelations(duplicate.id, canonical.id)
        deleteArtistSongRelations(duplicate.id)
        preserveMergedAlbumArtistOrder(duplicate.id, canonical.id)
        copyAlbumArtistRelations(duplicate.id, canonical.id)
        deleteArtistAlbumRelations(duplicate.id)
        redirectArtistAliases(duplicate.id, canonical.id)
        insert(ArtistAlias(duplicate.id, canonical.id))
        delete(duplicate)
        return combined
    }
}
