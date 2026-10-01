package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import com.dd3boh.outertune.utils.artistDisplayText
import com.dd3boh.outertune.utils.displayArtists
import com.dd3boh.outertune.utils.singleArtistTarget
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ChannelArtistTest {
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val credit = ArtistCredit("投稿チャンネル", listOf(Artist("投稿チャンネル", null,
        sourceChannelId = channelId)), ArtistCreditStatus.COMPLETE, "queue", "ja",
        listOf("video-source:MUSIC_VIDEO_TYPE_OMV"))

    @After fun reset() = ArtistDisplayProjection.publish(emptyList())

    @Test fun `channel identity survives different songs and spelling but cannot join an artist or namesake`() {
        val one = ArtistIdentity.withStableRefs("one", credit).artists.single()
        val renamed = ArtistIdentity.withStableRefs("two", credit.copy(artists = listOf(
            credit.artists.single().copy(name = "New channel name")))).artists.single()
        assertEquals(one.ref, renamed.ref)
        assertNull(one.id)
        assertNotEquals(one.ref, ArtistIdentity.withStableRefs("one", credit.copy(artists = listOf(
            credit.artists.single().copy(sourceChannelId = "UCABCDEFGHIJKLMNOPQRSTUV")))).artists.single().ref)
        val explicit = credit.copy(artists = listOf(Artist(one.name, channelId)))
        assertEquals(channelId, ArtistIdentity.withStableRefs("one", explicit, credit).artists.single().ref)
        val noId = credit.copy(artists = listOf(Artist(one.name, null, isChannel = true)))
        assertNotEquals(ArtistIdentity.withStableRefs("one", noId).artists.single().ref,
            ArtistIdentity.withStableRefs("two", noId).artists.single().ref)
    }

    @Test fun `saved and queue channel artists use reversible manual display without rewriting raw credits`() {
        val queue = SongItem("one", "動画", credit.artists, thumbnail = "", artistCredit = credit).toMediaMetadata()
        val sourceId = queue.artists.single().id!!
        assertTrue(queue.artists.single().isChannel)
        assertEquals(channelId, queue.artists.single().sourceChannelId)
        assertEquals(sourceId, queue.singleArtistTarget())
        val stableCredit = queue.artistCredit!!
        val saved = Song(SongEntity("one", "動画", localPath = null, artistCreditJson = stableCredit.toStoredJson()),
            listOf(ArtistEntity(sourceId, credit.rawText, isChannel = true, sourceChannelId = channelId)))
        val restored = saved.toMediaMetadata()
        assertEquals(queue.artists, restored.artists)
        val rawJson = queue.toSongEntity().artistCreditJson
        ArtistDisplayProjection.publish(listOf(ArtistDisplayMapping(sourceId, channelId, "選択した人物", "image")))
        assertEquals("選択した人物", queue.artistDisplayText())
        assertEquals(queue.artistDisplayText(), saved.artistDisplayText())
        assertEquals(queue.artistDisplayText(), restored.artistDisplayText())
        assertEquals(channelId, queue.singleArtistTarget())
        assertEquals("image", queue.displayArtists().single().thumbnailUrl)
        assertEquals(rawJson, queue.toSongEntity().artistCreditJson)
        ArtistDisplayProjection.publish(emptyList())
        assertEquals(credit.rawText, queue.artistDisplayText())
        assertEquals(sourceId, restored.singleArtistTarget())
        assertEquals(stableCredit, restored.artistCredit)
    }

    @Test fun `player uploader fills a video with no artists while established artists keep priority`() {
        val blank = MediaMetadata("one", "動画", emptyList(), 180, genre = null)
        val fallback = blank.withChannelFallback("Uploader", channelId, "MUSIC_VIDEO_TYPE_OMV")
        assertTrue(fallback.artists.single().isChannel)
        assertNull(fallback.artists.single().onlineId)
        assertEquals(channelId, fallback.artists.single().sourceChannelId)
        assertEquals(blank, blank.withChannelFallback("Uploader", channelId, "MUSIC_VIDEO_TYPE_ATV"))
        assertEquals(blank, blank.withChannelFallback("Uploader", channelId, null))
        val established = blank.withArtistCredit(credit.copy(artists = listOf(Artist("Performer", channelId))))
        assertEquals(established, established.withChannelFallback("Uploader", channelId, "MUSIC_VIDEO_TYPE_OMV"))
    }
}
