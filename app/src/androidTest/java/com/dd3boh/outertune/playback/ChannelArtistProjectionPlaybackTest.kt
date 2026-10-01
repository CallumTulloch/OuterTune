package com.dd3boh.outertune.playback

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.withCurrentDisplayMetadata
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.singleArtistTarget
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChannelArtistProjectionPlaybackTest {
    @Test
    fun channelLinkChangeAndUnlinkUpdateActiveSessionWithoutReplacingAudioOrUploaderCredit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("channel-projection-", ".wav", context.cacheDir)
        var player: ExoPlayer? = null
        try {
            audio.writeBytes(silentWav())
            val sourceChannelId = "UCPCiIrrrNJOKvi_5vr3G6PA"
            val credit = ArtistCredit("投稿チャンネル", listOf(Artist("投稿チャンネル", null,
                sourceChannelId = sourceChannelId, isChannel = true)), ArtistCreditStatus.COMPLETE,
                "video-channel", "ja", listOf("video-source:MUSIC_VIDEO_TYPE_OMV"))
            val raw = MediaMetadata("channel-playback", "Video", emptyList(), duration = 12, genre = null)
                .withArtistCredit(credit)
            val sourceId = raw.artists.single().id!!
            val original = raw.toMediaItem().buildUpon().setUri(audio.toUri()).setMimeType(MimeTypes.AUDIO_WAV).build()
            val active = withContext(Dispatchers.Main) {
                ArtistDisplayProjection.publish(emptyList())
                MetadataNames.publish(emptyMap())
                ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, false).build().also {
                    player = it
                    it.volume = 0f
                    it.setMediaItem(original)
                    it.prepare()
                    it.play()
                }
            }
            suspend fun awaitPosition(position: Long) = withTimeout(15_000) {
                while (!withContext(Dispatchers.Main) {
                    assertNull(active.playerError)
                    active.playbackState == Player.STATE_READY && active.currentPosition >= position
                }) delay(25)
            }
            awaitPosition(250)

            suspend fun assertChange(name: String, destination: String, mappings: List<ArtistDisplayMapping>) {
                val before = withContext(Dispatchers.Main) {
                    val position = active.currentPosition
                    ArtistDisplayProjection.publish(mappings)
                    // MusicService uses this display-only operation for active queue metadata.
                    active.replaceMediaItem(0, active.currentMediaItem!!.withCurrentDisplayMetadata())
                    position
                }
                awaitPosition(before + 150)
                withContext(Dispatchers.Main) {
                    val current = active.currentMediaItem!!
                    assertEquals(name, current.mediaMetadata.artist.toString())
                    assertEquals(name, current.mediaMetadata.subtitle.toString())
                    assertEquals(destination, current.metadata!!.singleArtistTarget())
                    assertSame(raw, current.metadata)
                    assertEquals(sourceId, current.metadata!!.artists.single().id)
                    assertTrue(current.metadata!!.artists.single().isChannel)
                    assertEquals(sourceChannelId, current.metadata!!.artists.single().sourceChannelId)
                    assertEquals("投稿チャンネル", current.metadata!!.artistCredit!!.rawText)
                    assertNull(current.metadata!!.artists.single().onlineId)
                    assertEquals(original.localConfiguration!!.uri, current.localConfiguration!!.uri)
                    assertEquals(original.localConfiguration!!.customCacheKey, current.localConfiguration!!.customCacheKey)
                    assertEquals(1, active.mediaItemCount)
                    assertTrue(active.isPlaying)
                    assertTrue(active.currentPosition >= before)
                    assertNull(active.playerError)
                }
            }

            assertChange("選択した人物", sourceChannelId,
                listOf(ArtistDisplayMapping(sourceId, sourceChannelId, "選択した人物", "online-image")))
            val alternative = "UCbrWU0y_rLsEOYgaTX5Y74A"
            assertChange("変更した人物", alternative,
                listOf(ArtistDisplayMapping(sourceId, alternative, "変更した人物", null)))
            assertChange("投稿チャンネル", sourceId, emptyList())
            withContext(Dispatchers.Main) { active.seekTo(8_000) }
            awaitPosition(8_150)
        } finally {
            withContext(Dispatchers.Main) {
                player?.release()
                ArtistDisplayProjection.publish(emptyList())
                MetadataNames.publish(emptyMap())
            }
            audio.delete()
        }
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 8_000
        val dataLength = sampleRate * 2 * 12
        return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataLength)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataLength)
        }.array()
    }
}
