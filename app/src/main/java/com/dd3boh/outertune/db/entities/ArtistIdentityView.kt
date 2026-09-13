package com.dd3boh.outertune.db.entities

import androidx.room.DatabaseView

/** Read-only grouping. Stored artist IDs and participation maps remain unchanged. */
@DatabaseView(viewName = "artist_identity", value = """
    SELECT artist.id AS sourceArtistId,
        CASE WHEN artist.isLocal = 1 THEN COALESCE(link.onlineArtistId, artist.id)
            WHEN artist.onlineId GLOB 'UC*' OR artist.onlineId GLOB 'FEmusic_library_privately_owned_artist*'
                THEN artist.onlineId
            ELSE artist.id END AS canonicalArtistId
    FROM artist LEFT JOIN local_artist_link link ON link.localArtistId = artist.id AND artist.isLocal = 1
""")
data class ArtistIdentityView(val sourceArtistId: String, val canonicalArtistId: String)
