package com.dd3boh.outertune.playback

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.withCurrentDisplayMetadata
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.singleArtistTarget
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalArtistProjectionPlaybackTest {
    @Test fun linkLanguageChangeAndUnlinkUpdateSessionDisplayWithoutReplacingLocalAudioOrRawTags() = runBlocking {
        verifyDisplayUpdates(isLocal = true)
    }

    @Test fun albumGroupingAndLaterOnlineIdentityKeepPlaybackAndOriginalCreditReferences() = runBlocking {
        verifyDisplayUpdates(isLocal = false)
    }

    private suspend fun verifyDisplayUpdates(isLocal: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("local-artist-projection-", ".wav", context.cacheDir)
        val localId = "LA-playback-local-source"
        val onlineId = "UCabcdefghijklmnopqrstuv"
        val originalName = if (isLocal) "ファイルの人物名" else "翟锦彦"
        val groupId = "AG-playback-album-artist"
        val errors = CopyOnWriteArrayList<PlaybackException>()
        val discontinuities = CopyOnWriteArrayList<Int>()
        var player: ExoPlayer? = null
        try {
            audio.writeBytes(silentWav())
            val raw = MediaMetadata(
                id = "LS-playback-local-source", title = "Local audio", duration = 12, genre = null,
                artists = listOf(MediaMetadata.Artist(localId, originalName, isLocal = isLocal)),
                isLocal = isLocal, localPath = audio.absolutePath.takeIf { isLocal },
                artistCredit = ArtistCredit(originalName, listOf(Artist(originalName, null, localId)),
                    ArtistCreditStatus.COMPLETE, if (isLocal) "file-tags" else "queue", "ja"),
            )
            // Generated WAV transport isolates metadata updates from remote stream availability.
            val original = raw.toMediaItem().buildUpon().setUri(audio.toUri()).setMimeType(MimeTypes.AUDIO_WAV).build()
            val activePlayer = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT, false).build().also { value ->
                    player = value
                    value.volume = 0f
                    value.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { errors += error }
                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) { discontinuities += reason }
                    })
                    value.setMediaItem(original)
                    value.prepare()
                    value.play()
                }
            }
            suspend fun awaitPosition(position: Long) = withTimeout(15_000) {
                while (true) {
                    assertTrue("Unexpected local playback error: $errors", errors.isEmpty())
                    if (withContext(Dispatchers.Main) {
                        activePlayer.playbackState == Player.STATE_READY && activePlayer.currentPosition >= position
                    }) break
                    delay(25)
                }
            }
            awaitPosition(250)
            val originalDiscontinuities = discontinuities.size
            suspend fun update(expectedName: String, expectedTarget: String, change: () -> Unit) {
                val before = withContext(Dispatchers.Main) {
                    val position = activePlayer.currentPosition
                    change()
                    // Exactly the production MusicService operation, including the shared display-only helper.
                    val item = activePlayer.currentMediaItem!!
                    activePlayer.replaceMediaItem(0, item.withCurrentDisplayMetadata())
                    position
                }
                awaitPosition(before + 150)
                withContext(Dispatchers.Main) {
                    val current = activePlayer.currentMediaItem!!
                    assertEquals(expectedName, current.mediaMetadata.artist.toString())
                    assertEquals(expectedName, current.mediaMetadata.subtitle.toString())
                    assertEquals(expectedTarget, current.metadata!!.singleArtistTarget())
                    assertSame(raw, current.metadata)
                    assertEquals(original.localConfiguration!!.uri, current.localConfiguration!!.uri)
                    assertEquals(original.localConfiguration!!.customCacheKey, current.localConfiguration!!.customCacheKey)
                    assertEquals(MimeTypes.AUDIO_WAV, current.localConfiguration!!.mimeType)
                    assertEquals(audio.absolutePath.takeIf { isLocal }, current.metadata!!.localPath)
                    assertEquals(localId, current.metadata!!.artists.single().id)
                    assertEquals(originalName, current.metadata!!.artistCredit!!.rawText)
                    assertEquals(1, activePlayer.mediaItemCount)
                    assertTrue(activePlayer.currentPosition >= before)
                    assertTrue(activePlayer.isPlaying)
                    assertNull(activePlayer.playerError)
                }
                assertEquals(originalDiscontinuities, discontinuities.size)
            }

            if (isLocal) {
                update("Online selected", onlineId) {
                    ArtistDisplayProjection.publish(listOf(ArtistDisplayMapping(localId, onlineId, "Online selected", null)))
                }
                update("言語設定の名前", onlineId) {
                    MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "言語設定の名前"))
                }
            } else {
                update(originalName, groupId) {
                    ArtistDisplayProjection.publish(listOf(ArtistDisplayMapping(localId, groupId, originalName, null)))
                }
                update(originalName, groupId) {
                    MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, groupId) to "Unverified English name"))
                }
                update("Verified performer", onlineId) {
                    ArtistDisplayProjection.publish(listOf(ArtistDisplayMapping(localId, onlineId, "Verified performer", null)))
                }
            }
            update(originalName, localId) { ArtistDisplayProjection.publish(emptyList()) }
            withContext(Dispatchers.Main) { activePlayer.seekTo(8_000) }
            awaitPosition(8_150)
            assertTrue(errors.isEmpty())
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
