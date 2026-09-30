package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import kotlinx.coroutines.flow.Flow

/** One coherent display projection; the potentially large proof payload stays off the UI path. */
data class MetadataDisplayName(
    @Embedded val name: MetadataNameEntity,
    val englishName: String?,
)

@Dao
interface MetadataNamesDao {
    @Query("SELECT * FROM metadata_target")
    fun allMetadataTargets(): List<MetadataTargetEntity>

    /** Includes persisted playback/sync entries without promoting them into the user's library. */
    @Query("""
        SELECT 'SONG' AS kind, id AS targetId FROM song WHERE isLocal = 0
        UNION
        SELECT 'ALBUM' AS kind, id AS targetId FROM album WHERE isLocal = 0
        UNION
        SELECT 'ARTIST' AS kind,
            CASE WHEN onlineId GLOB 'UC*' OR onlineId GLOB 'FEmusic_library_privately_owned_artist*'
                THEN onlineId ELSE id END AS targetId
        FROM artist
        WHERE isLocal = 0 AND (
            onlineId GLOB 'UC*' OR onlineId GLOB 'FEmusic_library_privately_owned_artist*'
            OR id GLOB 'UC*' OR id GLOB 'FEmusic_library_privately_owned_artist*'
        )
        UNION
        SELECT 'ARTIST' AS kind, onlineArtistId AS targetId FROM local_artist_link
    """)
    fun metadataLibraryTargets(): Flow<List<MetadataTargetEntity>>

    /** Refresh saved interests, not every album/song retained after browsing or playback. */
    @Query("""
        WITH saved_songs AS (
            SELECT s.id, s.albumId FROM song s
            WHERE s.isLocal = 0 AND (
                s.inLibrary IS NOT NULL OR s.liked = 1 OR s.dateDownload IS NOT NULL
                OR EXISTS (
                    SELECT 1 FROM playlist_song_map psm JOIN playlist p ON p.id = psm.playlistId
                    WHERE psm.songId = s.id AND (p.bookmarkedAt IS NOT NULL OR p.isLocal = 1)
                )
                OR EXISTS (
                    SELECT 1 FROM album a WHERE a.id = s.albumId AND a.isLocal = 0 AND a.bookmarkedAt IS NOT NULL
                )
                OR EXISTS (
                    SELECT 1 FROM song_album_map sam JOIN album a ON a.id = sam.albumId
                    WHERE sam.songId = s.id AND a.isLocal = 0 AND a.bookmarkedAt IS NOT NULL
                )
            )
        ), saved_albums AS (
            SELECT a.id FROM album a WHERE a.isLocal = 0 AND (
                a.bookmarkedAt IS NOT NULL OR a.id IN (SELECT albumId FROM saved_songs)
                OR EXISTS (SELECT 1 FROM song_album_map sam WHERE sam.albumId = a.id
                    AND sam.songId IN (SELECT id FROM saved_songs))
            )
        )
        SELECT 'SONG' AS kind, id AS targetId FROM saved_songs
        UNION
        SELECT 'ALBUM' AS kind, id AS targetId FROM saved_albums
        UNION
        SELECT 'ARTIST' AS kind,
            CASE WHEN a.onlineId GLOB 'UC*' OR a.onlineId GLOB 'FEmusic_library_privately_owned_artist*'
                THEN a.onlineId ELSE a.id END AS targetId
        FROM artist a
        WHERE a.isLocal = 0 AND (
            a.onlineId GLOB 'UC*' OR a.onlineId GLOB 'FEmusic_library_privately_owned_artist*'
            OR a.id GLOB 'UC*' OR a.id GLOB 'FEmusic_library_privately_owned_artist*'
        ) AND (
            a.bookmarkedAt IS NOT NULL
            OR EXISTS (SELECT 1 FROM song_artist_map sam WHERE sam.artistId = a.id
                AND sam.songId IN (SELECT id FROM saved_songs))
            OR EXISTS (SELECT 1 FROM album_artist_map aam WHERE aam.artistId = a.id
                AND aam.albumId IN (SELECT id FROM saved_albums))
        )
        UNION
        SELECT 'ARTIST' AS kind, onlineArtistId AS targetId FROM local_artist_link
    """)
    fun metadataRefreshTargets(): Flow<List<MetadataTargetEntity>>

    @Query("SELECT * FROM metadata_name")
    fun allMetadataNames(): Flow<List<MetadataNameEntity>>

    @Query("SELECT * FROM metadata_name")
    fun metadataNameSnapshot(): List<MetadataNameEntity>

    /** Source retention and album language inputs do not depend on translated display aliases. */
    @Query("SELECT * FROM metadata_name WHERE language = 'und' AND source GLOB 'art-track-original:*'")
    fun metadataOriginalNameSnapshot(): List<MetadataNameEntity>

    /** Keep every observation from this source so a newer song row can withdraw related proof. */
    @Query("SELECT * FROM metadata_name WHERE source = :source")
    fun metadataNamesForSource(source: String): List<MetadataNameEntity>

    /** Read a candidate source and all links to its target in one coherent, bounded snapshot. */
    @Query("""
        SELECT n.* FROM metadata_name n
        WHERE n.source IN ('art-track-original:' || :sourceId, 'main-song-reference:' || :sourceId)
        OR (n.kind = 'SONG' AND n.targetId = :targetId AND n.language = 'und'
            AND n.source GLOB 'main-song-reference:*')
        OR (n.language = 'und' AND n.source IN (
            SELECT 'art-track-original:' || SUBSTR(r.source, LENGTH('main-song-reference:') + 1)
            FROM metadata_name r
            WHERE r.kind = 'SONG' AND r.targetId = :targetId AND r.language = 'und'
                AND r.source GLOB 'main-song-reference:*'
        ))
    """)
    fun metadataProviderReferenceInputs(targetId: String, sourceId: String): List<MetadataNameEntity>

    @Query("""
        SELECT n.kind, n.targetId, n.language, n.name, n.source, n.sourcePriority, n.observedAt,
            NULL AS originEvidenceJson, p.englishName
        FROM metadata_name n
        LEFT JOIN metadata_original_publication p ON n.kind = p.kind AND n.targetId = p.targetId
    """)
    fun metadataDisplayNames(): Flow<List<MetadataDisplayName>>

    @Query("SELECT COUNT(*) FROM metadata_original_publication")
    fun metadataOriginalPublicationCount(): Int

    @Query("SELECT * FROM metadata_original_publication")
    fun allMetadataOriginalPublications(): Flow<List<MetadataOriginalPublicationEntity>>

    @Query("SELECT * FROM metadata_original_publication")
    fun metadataOriginalPublicationSnapshot(): List<MetadataOriginalPublicationEntity>

    @Query("SELECT * FROM metadata_original_publication WHERE kind = :kind AND targetId = :targetId")
    fun metadataOriginalPublication(kind: String, targetId: String): MetadataOriginalPublicationEntity?

    @Upsert
    fun upsertMetadataOriginalPublications(publications: List<MetadataOriginalPublicationEntity>)

    /** Invoke with the candidate/assessment write in the same outer transaction. */
    @Transaction
    fun recordMetadataOriginalPublications(publications: List<MetadataOriginalPublicationEntity>) {
        require(publications.all {
            it.kind in setOf("SONG", "ALBUM", "ARTIST") && it.targetId.isNotBlank() &&
                (it.englishName == null || it.englishName.isNotBlank()) &&
                it.evidenceJson.isNotBlank() && it.evaluatedAt > 0
        })
        insertMetadataTargets(publications.map { MetadataTargetEntity(it.kind, it.targetId) }.distinct())
        upsertMetadataOriginalPublications(publications)
    }

    @Query("""
        SELECT * FROM metadata_name WHERE kind = :kind AND targetId = :targetId
        ORDER BY sourcePriority DESC, observedAt DESC, language, name, source
    """)
    fun metadataNames(kind: String, targetId: String): List<MetadataNameEntity>

    @Query("SELECT * FROM metadata_target WHERE kind = :kind AND targetId = :targetId")
    fun metadataTarget(kind: String, targetId: String): MetadataTargetEntity?

    @Query("""
        SELECT * FROM metadata_fetch
        WHERE kind = :kind AND targetId = :targetId AND language = :language AND contextKey = :contextKey
    """)
    fun metadataFetch(kind: String, targetId: String, language: String, contextKey: String = ""): MetadataFetchEntity?

    @Query("SELECT * FROM metadata_fetch WHERE kind = :kind AND targetId = :targetId")
    fun metadataFetchStates(kind: String, targetId: String): List<MetadataFetchEntity>

    // IGNORE is intentional: replacing a parent would cascade-delete its other spellings/languages.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMetadataTargets(targets: List<MetadataTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMetadataNameCandidate(name: MetadataNameEntity): Long

    @Query("""
        UPDATE metadata_name
        SET sourcePriority = MAX(sourcePriority, :sourcePriority),
            observedAt = CASE
                WHEN :sourcePriority > sourcePriority THEN :observedAt
                WHEN :sourcePriority = sourcePriority THEN MAX(observedAt, :observedAt)
                ELSE observedAt
            END,
            originEvidenceJson = CASE
                WHEN :originEvidenceJson IS NULL THEN originEvidenceJson
                WHEN originEvidenceJson IS NULL OR :sourcePriority > sourcePriority
                    OR (:sourcePriority = sourcePriority AND :observedAt >= observedAt) THEN :originEvidenceJson
                ELSE originEvidenceJson
            END
        WHERE kind = :kind AND targetId = :targetId AND language = :language AND name = :name AND source = :source
    """)
    fun mergeMetadataNameCandidate(
        kind: String, targetId: String, language: String, name: String, source: String,
        sourcePriority: Int, observedAt: Long, originEvidenceJson: String?,
    )

    /** Keep the timestamp of the strongest observation; a later byline cannot rejuvenate an old header. */
    @Transaction
    fun upsertMetadataNames(names: List<MetadataNameEntity>) {
        names.forEach { candidate ->
            if (insertMetadataNameCandidate(candidate) == -1L) {
                mergeMetadataNameCandidate(
                    candidate.kind, candidate.targetId, candidate.language, candidate.name, candidate.source,
                    candidate.sourcePriority, candidate.observedAt, candidate.originEvidenceJson,
                )
            }
        }
    }

    @Upsert
    fun upsertMetadataFetch(fetch: MetadataFetchEntity)

    /** An empty or failed response records retry state without removing previously observed names. */
    @Transaction
    fun recordMetadataNames(names: List<MetadataNameEntity>, fetch: MetadataFetchEntity? = null) {
        val targets = names.map { MetadataTargetEntity(it.kind, it.targetId) } +
            listOfNotNull(fetch?.let { MetadataTargetEntity(it.kind, it.targetId) })
        require(targets.all { it.kind in setOf("SONG", "ALBUM", "ARTIST") && it.targetId.isNotBlank() })
        require(names.all { it.language.isNotBlank() && it.name.isNotBlank() && it.source.isNotBlank() })
        require(fetch == null || (fetch.language.isNotBlank() && fetch.status in setOf(
            MetadataFetchEntity.PENDING, MetadataFetchEntity.SUCCESS, MetadataFetchEntity.EMPTY, MetadataFetchEntity.FAILED,
        )))
        insertMetadataTargets(targets.distinct())
        upsertMetadataNames(names)
        fetch?.let(::upsertMetadataFetch)
    }

    @Transaction
    fun recordMetadataFetch(fetch: MetadataFetchEntity) = recordMetadataNames(emptyList(), fetch)

    @Query("DELETE FROM metadata_target WHERE kind = :kind AND targetId = :targetId")
    fun deleteMetadataTarget(kind: String, targetId: String)
}
