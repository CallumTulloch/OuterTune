package com.dd3boh.outertune.db.entities

import androidx.room.Entity

/** A remote metadata cache entry, independent of whether it is in the user's library. */
@Entity(tableName = "metadata_target", primaryKeys = ["kind", "targetId"])
data class MetadataTargetEntity(
    val kind: String,
    val targetId: String,
)
