package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One observed spelling, not a replacement for a library name or a claim about its original language.
 * The requested language and source are retained even when two routes return different spellings.
 */
@Entity(
    tableName = "metadata_name",
    primaryKeys = ["kind", "targetId", "language", "name", "source"],
    foreignKeys = [ForeignKey(
        entity = MetadataTargetEntity::class,
        parentColumns = ["kind", "targetId"],
        childColumns = ["kind", "targetId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MetadataNameEntity(
    val kind: String,
    val targetId: String,
    val language: String,
    val name: String,
    val source: String,
    val sourcePriority: Int = 0,
    val observedAt: Long = System.currentTimeMillis(),
    val originEvidenceJson: String? = null,
)
