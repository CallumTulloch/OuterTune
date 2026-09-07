package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey

/** Each requested language succeeds or retries independently; failures do not erase names. */
@Entity(
    tableName = "metadata_fetch",
    primaryKeys = ["kind", "targetId", "language", "contextKey"],
    foreignKeys = [ForeignKey(
        entity = MetadataTargetEntity::class,
        parentColumns = ["kind", "targetId"],
        childColumns = ["kind", "targetId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MetadataFetchEntity(
    val kind: String,
    val targetId: String,
    val language: String,
    val status: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val contextKey: String = "",
) {
    companion object {
        const val PENDING = "PENDING"
        const val SUCCESS = "SUCCESS"
        const val EMPTY = "EMPTY"
        const val FAILED = "FAILED"
    }
}
