package com.dd3boh.outertune.db.entities

import androidx.room.Entity

/**
 * A small, monotonic invalidation journal for the name display projection.
 * No foreign key: deleted targets remain as tombstones, including across reinsertion.
 */
@Entity(tableName = "metadata_display_revision", primaryKeys = ["kind", "targetId"])
data class MetadataDisplayRevision(
    val kind: String,
    val targetId: String,
    val revision: Long,
)
