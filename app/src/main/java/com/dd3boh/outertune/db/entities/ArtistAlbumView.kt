package com.dd3boh.outertune.db.entities

import androidx.room.DatabaseView

@DatabaseView(viewName = "artist_album", value = """
    SELECT DISTINCT map.albumId, identity.canonicalArtistId AS artistId
    FROM album_artist_map map JOIN artist_identity identity ON identity.sourceArtistId = map.artistId
""")
data class ArtistAlbumView(val albumId: String, val artistId: String)
