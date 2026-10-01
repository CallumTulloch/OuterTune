package com.dd3boh.outertune.db.entities

import androidx.room.DatabaseView
import java.time.LocalDateTime

/** A representative can exist through a manual link without a saved online artist row. */
@DatabaseView(viewName = "artist_display", value = """
    WITH representatives AS (
        SELECT identity.canonicalArtistId AS id,
            COALESCE(MIN(CASE WHEN source.isLocal = 0 AND source.id = identity.canonicalArtistId THEN source.rowId END),
                MIN(CASE WHEN source.isLocal = 0 AND (source.onlineId GLOB 'UC*'
                    OR source.onlineId GLOB 'FEmusic_library_privately_owned_artist*'
                    OR source.id GLOB 'UC*' OR source.id GLOB 'FEmusic_library_privately_owned_artist*')
                    THEN source.rowId END)) AS remoteRowId,
            MIN(source.rowId) AS sourceRowId,
            MIN(source.lastUpdateTime) AS lastUpdateTime,
            MIN(source.bookmarkedAt) AS bookmarkedAt
        FROM artist_identity identity JOIN artist source ON source.id = identity.sourceArtistId
        GROUP BY identity.canonicalArtistId
    ), link_choices AS (
        SELECT onlineArtistId, MIN(localArtistId) AS localArtistId
        FROM local_artist_link GROUP BY onlineArtistId
    )
    SELECT representative.id,
        COALESCE(remote.name, link.onlineName, source.name) AS name,
        CASE WHEN link.localArtistId IS NOT NULL THEN COALESCE(remote.thumbnailUrl, link.thumbnailUrl)
            ELSE COALESCE(remote.thumbnailUrl, source.thumbnailUrl) END AS thumbnailUrl,
        CASE WHEN link.localArtistId IS NOT NULL THEN remote.channelId
            ELSE COALESCE(remote.channelId, source.channelId) END AS channelId,
        COALESCE(remote.lastUpdateTime, representative.lastUpdateTime) AS lastUpdateTime,
        CASE WHEN remote.id IS NOT NULL THEN remote.bookmarkedAt ELSE representative.bookmarkedAt END AS bookmarkedAt,
        CASE WHEN link.localArtistId IS NOT NULL THEN 0 ELSE source.isLocal END AS isLocal,
        CASE WHEN representative.id GLOB 'UC*' OR representative.id GLOB 'FEmusic_library_privately_owned_artist*'
            THEN representative.id ELSE NULL END AS onlineId,
        CASE WHEN representative.id GLOB 'AG*' THEN representative.id ELSE NULL END AS albumGroupId,
        CASE WHEN link.localArtistId IS NOT NULL THEN 0 ELSE source.isChannel END AS isChannel,
        CASE WHEN link.localArtistId IS NOT NULL THEN NULL ELSE source.sourceChannelId END AS sourceChannelId,
        representative.sourceRowId AS sortOrder
    FROM representatives representative
        JOIN artist source ON source.rowId = representative.sourceRowId
        LEFT JOIN artist remote ON remote.rowId = representative.remoteRowId
        LEFT JOIN link_choices choice ON choice.onlineArtistId = representative.id
        LEFT JOIN local_artist_link link ON link.localArtistId = choice.localArtistId
""")
data class ArtistDisplayView(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val channelId: String?,
    val lastUpdateTime: LocalDateTime,
    val bookmarkedAt: LocalDateTime?,
    val isLocal: Boolean,
    val onlineId: String?,
    val albumGroupId: String?,
    val sortOrder: Long,
    val isChannel: Boolean,
    val sourceChannelId: String?,
)

data class ArtistDisplayMapping(
    val sourceArtistId: String,
    val canonicalArtistId: String,
    val name: String,
    val thumbnailUrl: String?,
)
