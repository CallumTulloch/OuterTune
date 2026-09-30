package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import android.database.Cursor
import com.dd3boh.outertune.db.entities.MetadataDisplayRevision
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.originalEvidenceCache
import kotlinx.coroutines.flow.Flow

/** One coherent display projection; the potentially large proof payload stays off the UI path. */
data class MetadataDisplayName(
    @Embedded val name: MetadataNameEntity,
    val englishName: String?,
)

/** Share exact proof text across the simultaneously live evaluation and transaction snapshots. */
private fun List<MetadataNameEntity>.canonicalizeOriginalProofs(): List<MetadataNameEntity> = map { row ->
    val proof = row.originEvidenceJson
    if (proof == null) row else {
        val canonical = originalEvidenceCache.canonicalize(proof,
            OriginalNameTarget(OriginalNameKind.valueOf(row.kind), row.targetId), row.name)
        if (canonical === proof) row else row.copy(originEvidenceJson = canonical)
    }
}

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

    /** Invalidation signal only: the serial evaluation worker owns the large snapshot. */
    @Query("SELECT EXISTS(SELECT 1 FROM metadata_name LIMIT 1)")
    fun metadataNameChanges(): Flow<Boolean>

    @Query("SELECT * FROM metadata_name")
    fun metadataNameSnapshot(): List<MetadataNameEntity>

    /** Original publication depends only on English aliases and und source/reference evidence.
     * Configured-language display and manual overrides are handled by metadataDisplayNames.
     */
    @Query("SELECT * FROM metadata_name WHERE language IN ('en', 'und') AND source != 'manual'")
    fun metadataOriginalEvaluationRows(): List<MetadataNameEntity>

    fun metadataOriginalEvaluationSnapshot(): List<MetadataNameEntity> =
        metadataOriginalEvaluationRows().canonicalizeOriginalProofs()

    /** Source retention and album language inputs do not depend on translated display aliases. */
    @Query("SELECT * FROM metadata_name WHERE language = 'und' AND source GLOB 'art-track-original:*'")
    fun metadataOriginalNameRows(): List<MetadataNameEntity>

    fun metadataOriginalNameSnapshot(): List<MetadataNameEntity> =
        metadataOriginalNameRows().canonicalizeOriginalProofs()

    /** Keep every observation from this source so a newer song row can withdraw related proof. */
    @Query("SELECT * FROM metadata_name WHERE source = :source")
    fun metadataNamesForSource(source: String): List<MetadataNameEntity>

    @Query("""
        SELECT * FROM metadata_name WHERE source GLOB 'playlist-song-reference:*'
        AND SUBSTR(source, -LENGTH(:playlistId) - 1) = ':' || :playlistId
    """)
    fun metadataPlaylistReferenceNames(playlistId: String): List<MetadataNameEntity>

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

    /** Initial/settings snapshots also include caches created before the journal existed. */
    @Query("""
        SELECT n.kind, n.targetId, n.language, n.name, n.source, n.sourcePriority, n.observedAt,
            NULL AS originEvidenceJson, p.englishName
        FROM metadata_name n
        LEFT JOIN metadata_original_publication p ON n.kind = p.kind AND n.targetId = p.targetId
    """)
    fun metadataDisplayNameSnapshot(): List<MetadataDisplayName>

    /** The metadata_name primary key starts with (kind, targetId), bounding this read to changes. */
    @Query("""
        SELECT n.kind, n.targetId, n.language, n.name, n.source, n.sourcePriority, n.observedAt,
            NULL AS originEvidenceJson, p.englishName
        FROM metadata_name n
        LEFT JOIN metadata_original_publication p ON n.kind = p.kind AND n.targetId = p.targetId
        WHERE n.kind = :kind AND n.targetId IN (:targetIds)
    """)
    fun metadataDisplayNamesForTargets(kind: String, targetIds: List<String>): List<MetadataDisplayName>

    /** Invalidation only; do not distinctUntilChanged this boolean signal. */
    @Query("SELECT EXISTS(SELECT 1 FROM metadata_display_revision LIMIT 1)")
    fun metadataDisplayChanges(): Flow<Boolean>

    @Query("SELECT * FROM metadata_display_revision")
    fun metadataDisplayRevisionSnapshot(): List<MetadataDisplayRevision>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMetadataDisplayRevisions(revisions: List<MetadataDisplayRevision>)

    @Query("UPDATE metadata_display_revision SET revision = revision + 1 WHERE kind = :kind AND targetId = :targetId")
    fun incrementMetadataDisplayRevision(kind: String, targetId: String)

    /** Must share the writing transaction, so a revision can never precede its display contents. */
    @Transaction
    fun advanceMetadataDisplayRevisions(targets: Set<MetadataTargetEntity>) {
        if (targets.isEmpty()) return
        insertMetadataDisplayRevisions(targets.map { MetadataDisplayRevision(it.kind, it.targetId, 0) })
        targets.forEach { incrementMetadataDisplayRevision(it.kind, it.targetId) }
    }

    @Query("SELECT COUNT(*) FROM metadata_original_publication")
    fun metadataOriginalPublicationCount(): Int

    @Query("SELECT * FROM metadata_original_publication")
    fun allMetadataOriginalPublications(): Flow<List<MetadataOriginalPublicationEntity>>

    @Query("SELECT * FROM metadata_original_publication")
    fun metadataOriginalPublicationSnapshot(): List<MetadataOriginalPublicationEntity>

    /** Stream large proof strings instead of materializing the whole publication table. */
    @Query("SELECT kind, targetId, englishName, evidenceJson, evaluatedAt FROM metadata_original_publication")
    fun metadataOriginalPublicationCursor(): Cursor

    @Query("SELECT * FROM metadata_original_publication WHERE kind = :kind AND targetId = :targetId")
    fun metadataOriginalPublication(kind: String, targetId: String): MetadataOriginalPublicationEntity?

    @Query("SELECT englishName FROM metadata_original_publication WHERE kind = :kind AND targetId = :targetId")
    fun metadataOriginalPublishedName(kind: String, targetId: String): String?

    /** Raw storage primitive; callers use the transactional wrapper below. */
    @Upsert
    fun upsertMetadataOriginalPublicationsRaw(publications: List<MetadataOriginalPublicationEntity>)

    @Transaction
    fun upsertMetadataOriginalPublications(publications: List<MetadataOriginalPublicationEntity>) {
        val finalPublications = publications.associateBy { MetadataTargetEntity(it.kind, it.targetId) }
        val changed = finalPublications.filter { (target, publication) ->
            metadataOriginalPublishedName(target.kind, target.targetId) != publication.englishName
        }.keys
        upsertMetadataOriginalPublicationsRaw(finalPublications.values.toList())
        advanceMetadataDisplayRevisions(changed)
    }

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

    /** Raw storage primitive; callers use the transactional wrappers below. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMetadataNameCandidateRaw(name: MetadataNameEntity): Long

    @Transaction
    fun insertMetadataNameCandidate(name: MetadataNameEntity): Long {
        val inserted = insertMetadataNameCandidateRaw(name)
        if (inserted != -1L) advanceMetadataDisplayRevisions(setOf(MetadataTargetEntity(name.kind, name.targetId)))
        return inserted
    }

    @Query("""
        SELECT EXISTS(SELECT 1 FROM metadata_name
            WHERE kind = :kind AND targetId = :targetId AND language = :language AND name = :name AND source = :source
                AND (:sourcePriority > sourcePriority OR (:sourcePriority = sourcePriority AND :observedAt > observedAt)))
    """)
    fun metadataNameCandidateDisplayChanges(
        kind: String, targetId: String, language: String, name: String, source: String,
        sourcePriority: Int, observedAt: Long,
    ): Boolean

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
            AND (
                :sourcePriority > sourcePriority OR (:sourcePriority = sourcePriority AND :observedAt > observedAt)
                OR (:originEvidenceJson IS NOT NULL AND originEvidenceJson IS NOT :originEvidenceJson AND (
                    originEvidenceJson IS NULL OR :sourcePriority > sourcePriority
                    OR (:sourcePriority = sourcePriority AND :observedAt >= observedAt)
                ))
            )
    """)
    fun mergeMetadataNameCandidateRaw(
        kind: String, targetId: String, language: String, name: String, source: String,
        sourcePriority: Int, observedAt: Long, originEvidenceJson: String?,
    )

    @Transaction
    fun mergeMetadataNameCandidate(
        kind: String, targetId: String, language: String, name: String, source: String,
        sourcePriority: Int, observedAt: Long, originEvidenceJson: String?,
    ) {
        if (mergeMetadataNameAndCheckDisplayChange(MetadataNameEntity(
                kind, targetId, language, name, source, sourcePriority, observedAt, originEvidenceJson,
            ))) advanceMetadataDisplayRevisions(setOf(MetadataTargetEntity(kind, targetId)))
    }

    private fun mergeMetadataNameAndCheckDisplayChange(candidate: MetadataNameEntity): Boolean {
        val displayChanged = metadataNameCandidateDisplayChanges(
            candidate.kind, candidate.targetId, candidate.language, candidate.name, candidate.source,
            candidate.sourcePriority, candidate.observedAt,
        )
        mergeMetadataNameCandidateRaw(
            candidate.kind, candidate.targetId, candidate.language, candidate.name, candidate.source,
            candidate.sourcePriority, candidate.observedAt, candidate.originEvidenceJson,
        )
        return displayChanged
    }

    /** Keep the timestamp of the strongest observation; a later byline cannot rejuvenate an old header. */
    @Transaction
    fun upsertMetadataNames(names: List<MetadataNameEntity>) {
        val changed = mutableSetOf<MetadataTargetEntity>()
        names.forEach { candidate ->
            val inserted = insertMetadataNameCandidateRaw(candidate) != -1L
            if (inserted || mergeMetadataNameAndCheckDisplayChange(candidate))
                changed += MetadataTargetEntity(candidate.kind, candidate.targetId)
        }
        advanceMetadataDisplayRevisions(changed)
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

    @Query("SELECT DISTINCT kind, targetId FROM metadata_name WHERE source = :source")
    fun metadataTargetsForSource(source: String): List<MetadataTargetEntity>

    @Query("DELETE FROM metadata_name WHERE source = :source")
    fun deleteMetadataNamesForSourceRaw(source: String)

    @Transaction
    fun deleteMetadataNamesForSource(source: String) {
        val targets = metadataTargetsForSource(source).toSet()
        deleteMetadataNamesForSourceRaw(source)
        advanceMetadataDisplayRevisions(targets)
    }

    @Query("DELETE FROM metadata_target WHERE kind = :kind AND targetId = :targetId")
    fun deleteMetadataTargetRaw(kind: String, targetId: String): Int

    @Transaction
    fun deleteMetadataTarget(kind: String, targetId: String) {
        if (deleteMetadataTargetRaw(kind, targetId) != 0)
            advanceMetadataDisplayRevisions(setOf(MetadataTargetEntity(kind, targetId)))
    }
}
