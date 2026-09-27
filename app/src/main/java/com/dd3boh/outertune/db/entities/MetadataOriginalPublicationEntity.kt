package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Last completed original-name decision. A null English name is a completed non-English,
 * unknown, conflicting or withdrawn decision; an absent row has never completed evaluation.
 * Pending candidates and retry state live separately and cannot replace this rendering.
 */
@Entity(
    tableName = "metadata_original_publication",
    primaryKeys = ["kind", "targetId"],
    foreignKeys = [ForeignKey(
        entity = MetadataTargetEntity::class,
        parentColumns = ["kind", "targetId"],
        childColumns = ["kind", "targetId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MetadataOriginalPublicationEntity(
    val kind: String,
    val targetId: String,
    val englishName: String?,
    val evidenceJson: String,
    val evaluatedAt: Long,
)
