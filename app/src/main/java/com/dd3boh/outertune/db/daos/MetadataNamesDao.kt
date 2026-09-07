package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import kotlinx.coroutines.flow.Flow

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
    """)
    fun metadataLibraryTargets(): Flow<List<MetadataTargetEntity>>

    @Query("SELECT * FROM metadata_name")
    fun allMetadataNames(): Flow<List<MetadataNameEntity>>

    @Query("SELECT * FROM metadata_name")
    fun metadataNameSnapshot(): List<MetadataNameEntity>

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
            observedAt = MAX(observedAt, :observedAt),
            originEvidenceJson = CASE
                WHEN :originEvidenceJson IS NULL THEN originEvidenceJson
                WHEN originEvidenceJson IS NULL OR :observedAt >= observedAt THEN :originEvidenceJson
                ELSE originEvidenceJson
            END
        WHERE kind = :kind AND targetId = :targetId AND language = :language AND name = :name AND source = :source
    """)
    fun mergeMetadataNameCandidate(
        kind: String, targetId: String, language: String, name: String, source: String,
        sourcePriority: Int, observedAt: Long, originEvidenceJson: String?,
    )

    /** A thin byline and an artist detail can share a source: preserve the strongest observation. */
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
