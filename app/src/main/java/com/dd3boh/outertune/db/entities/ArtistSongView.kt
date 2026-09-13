package com.dd3boh.outertune.db.entities

import androidx.room.DatabaseView

/** Several linked tag credits on one song still count as one participation. */
@DatabaseView(viewName = "artist_song", value = """
    SELECT DISTINCT map.songId, identity.canonicalArtistId AS artistId
    FROM song_artist_map map JOIN artist_identity identity ON identity.sourceArtistId = map.artistId
""")
data class ArtistSongView(val songId: String, val artistId: String)
