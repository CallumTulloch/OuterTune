package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName

/** The decoded candidate is exactly the data formerly normalized by encoding it without an
 * assessment. Keep every other row field, and retain raw proof for invalid originals/references.
 */
internal data class OriginalPublicationInputKeyRow(
    val row: MetadataNameEntity,
    val original: ArtTrackOriginalName?,
)

private val originalPublicationInputOrder = compareBy<OriginalPublicationInputKeyRow>(
    { it.row.kind }, { it.row.targetId }, { it.row.language }, { it.row.source }, { it.row.name },
)

/** Evaluation output is excluded; names, source identity, links and withdrawals remain inputs. */
internal fun originalPublicationInputKey(names: List<MetadataNameEntity>): List<OriginalPublicationInputKeyRow> =
    names.asSequence().filter { (it.language == "en" || it.language == "und") && it.source != "manual" }
        .map { row ->
            val original = originalCandidate(row)
            OriginalPublicationInputKeyRow(
                row = if (original != null) row.copy(originEvidenceJson = null) else row,
                original = original,
            )
        }.sortedWith(originalPublicationInputOrder).toList()
