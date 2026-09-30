package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumTrackAvailabilityTest {
    @Test fun `cold album restores all same identity restrictions despite unresolved sources`() {
        val songs = (0 until 21).map { track -> song("track-$track", playable = track >= 13) }
        val cachedIds = songs.mapTo(mutableSetOf()) { it.id }

        val result = refreshCachedAlbumAvailability(cachedIds, emptySet(), songs)

        assertEquals((0 until 13).mapTo(mutableSetOf()) { "track-$it" }, result)
        assertEquals(21, cachedIds.size)
    }

    @Test fun `same title and position never transfer video restrictions to a cached audio identity`() {
        val cachedIds = setOf("Kr4EQDVETuA", "other-audio")
        val incoming = listOf(song("Zi_XLOBDo_Y", playable = false), song("other-video", playable = true))

        assertEquals(setOf("other-audio"), refreshCachedAlbumAvailability(
            cachedIds, setOf("other-audio"), incoming,
        ))
        // A playable MV likewise cannot clear a restriction already known for its audio ID.
        assertEquals(setOf("Kr4EQDVETuA"), refreshCachedAlbumAvailability(
            cachedIds, setOf("Kr4EQDVETuA"), listOf(song("Zi_XLOBDo_Y", playable = true)),
        ))
    }

    @Test fun `matching playable updates clear old restrictions while unmatched cached IDs keep theirs`() {
        val cachedIds = setOf("now-playable", "still-unknown", "now-restricted")
        val previous = setOf("now-playable", "still-unknown")
        val incoming = listOf(song("now-playable", true), song("now-restricted", false), song("foreign", false))

        assertEquals(setOf("still-unknown", "now-restricted"),
            refreshCachedAlbumAvailability(cachedIds, previous, incoming))
        assertEquals(setOf("now-playable", "still-unknown"), previous)
    }

    private fun song(id: String, playable: Boolean) = SongItem(
        id = id, title = "Same title", artists = emptyList(), thumbnail = "", isPlayable = playable,
    )
}
