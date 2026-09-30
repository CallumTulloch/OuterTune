package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget

/** The complete read set of one display decision, including absent/withdrawn source snapshots. */
internal data class OriginalPublicationInput(
    val targetRows: Set<MetadataNameEntity>,
    val sourceRows: Set<MetadataNameEntity>,
    val albumSongs: Set<ArtTrackOriginalName>,
)

/** Index one database snapshot. Unrelated albums do not participate in a target's commit guard. */
internal class OriginalPublicationInputs(rows: List<MetadataNameEntity>) {
    val originals = latestOriginalRows(rows)
    val assessmentInputs by lazy { originalAssessmentInputs(originals) }
    private val candidates = originals.associateWith { requireNotNull(originalCandidate(it)) }
    private val byTarget = originals.groupBy { candidates.getValue(it).target }
    val bySource = originals.groupBy { candidates.getValue(it).sourceVideoId }
    private val songsByAlbum = candidates.values.filter { it.target.kind == OriginalNameKind.SONG }
        .groupBy { it.albumId }
    private val targetRows = rows.filter { it.language in setOf("en", "und") && it.source != "manual" }
        .groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }

    private fun referencedSources(target: OriginalNameTarget): Set<String> = targetRows[target].orEmpty()
        .filter { it.language == "und" && !it.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX) }
        .groupBy { it.source }.values.flatMap { observations ->
            val newest = observations.maxOf { it.observedAt }
            observations.filter { it.observedAt == newest }.mapNotNull { row ->
                ProviderSongReferenceCodec.decode(row)?.sourceVideoId
                    ?: PlaylistSongReferenceCodec.decode(row)?.sourceVideoId
            }
        }.toSet()

    private fun requiredRows(target: OriginalNameTarget): List<MetadataNameEntity> =
        (byTarget[target].orEmpty() + referencedSources(target).flatMap { id ->
            bySource[id].orEmpty().filter { it.kind == "SONG" }
        }).distinct()

    /** An unassessed referenced source is pending too; it must not withdraw the last good name. */
    fun ready(target: OriginalNameTarget): Boolean = requiredRows(target)
        .all { hasCurrentOriginalAssessmentInputs(it, assessmentInputs) }

    fun forTarget(target: OriginalNameTarget): OriginalPublicationInput {
        val required = requiredRows(target)
        val sourceIds = required.map { candidates.getValue(it).sourceVideoId }.toSet() + referencedSources(target)
        val albums = required.mapNotNull { candidates.getValue(it).albumId }.toSet()
        return OriginalPublicationInput(
            targetRows[target].orEmpty().toSet(),
            sourceIds.flatMap { bySource[it].orEmpty() }.toSet(),
            albums.flatMap { songsByAlbum[it].orEmpty() }.toSet(),
        )
    }
}
