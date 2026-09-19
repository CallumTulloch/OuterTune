package com.dd3boh.outertune.playback

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real MusicService, decoder and PlaybackStatsListener; the audio is a short local WAV fixture. */
class PlaybackHistoryServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 75_000)
    fun repeatedLocalPlaybackRecordsHistoryCountsAndStatisticsWithoutBlockingTheLibrary() {
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "history-service-$token").apply { mkdirs() }
        val albumId = "LB-history-service-$token"
        val artistId = "LA-history-service-$token"
        val tracks = listOf("A", "B").map { name ->
            val audio = File(directory, "$name.wav").apply { writeBytes(silentWav()) }
            MediaMetadata(
                id = "LS-history-service-$token-$name",
                title = "History service $name $token",
                artists = listOf(MediaMetadata.Artist(artistId, "History fixture $token", isLocal = true)),
                duration = 3,
                genre = null,
                album = MediaMetadata.Album(albumId, "History fixture album $token", isLocal = true),
                isLocal = true,
                localPath = audio.absolutePath,
            )
        }
        val ids = tracks.map { it.id }.toSet()
        val previousSettings = runBlocking(Dispatchers.IO) { context.dataStore.data.first() }
        var activity: SearchUiFixtureActivity? = null
        var connection: PlayerConnection? = null
        var database: MusicDatabase? = null
        var ownsIdleService = false
        var serviceShutdownComplete = false
        var previousVolume = 1f
        var previousRepeat = Player.REPEAT_MODE_OFF
        var previousShuffle = false
        val errors = CopyOnWriteArrayList<PlaybackException>()
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errors += error }
        }
        try {
            runBlocking(Dispatchers.IO) {
                context.dataStore.edit {
                    it[PersistentQueueKey] = false
                    it[AutoLoadMoreKey] = false
                    it[PauseListenHistoryKey] = false
                    it[minPlaybackDurKey] = 30
                    it[SkipSilenceKey] = false
                }
            }
            val host = instrumentation.startActivitySync(
                Intent(context, SearchUiFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SearchUiFixtureActivity
            activity = host
            val connected = AtomicReference<PlayerConnection?>()
            onMain {
                ViewModelProvider(host)[MediaControllerViewModel::class.java].also { model ->
                    model.addControllerCallback(host.lifecycle) { _, _ ->
                        connected.compareAndSet(null, PlayerConnection(model, model.getService()!!.database))
                    }
                    host.lifecycle.addObserver(model)
                }
            }
            awaitCondition("MusicService connection") { connected.get() != null }
            val active = connected.get()!!
            connection = active
            database = active.database
            awaitCondition("Queue initialization") { active.service.qbInit.value }

            // Run in the dedicated verification process with no pre-existing playback session.
            // PersistentQueue=false preserves the user's stored queues while this service starts.
            onMain {
                assertEquals("Start this test with an idle MusicService", 0, active.player.mediaItemCount)
                assertTrue("Start this test in a fresh process with queue persistence disabled",
                    active.service.queueBoard.value.masterQueues.isEmpty())
                previousVolume = active.player.volume
                previousRepeat = active.player.repeatMode
                previousShuffle = active.player.shuffleModeEnabled
                ownsIdleService = true
            }
            runBlocking(Dispatchers.IO) { tracks.forEach { active.database.insert(it) } }
            val start = LocalDateTime.now().minusSeconds(1).toInstant(ZoneOffset.UTC).toEpochMilli()
            val items = listOf(tracks[0], tracks[1], tracks[0], tracks[0]).map {
                it.toMediaItem().buildUpon().setMimeType(MimeTypes.AUDIO_WAV).build()
            }
            onMain {
                active.player.addListener(errorListener)
                active.player.volume = 0f
                active.player.repeatMode = Player.REPEAT_MODE_OFF
                active.player.shuffleModeEnabled = false
                active.player.setMediaItems(items)
                active.player.prepare()
                active.player.play()
            }
            awaitCondition("Four local playback sessions", timeoutMs = 25_000) {
                assertTrue("Unexpected playback errors: $errors", errors.isEmpty())
                onMain { active.player.playbackState == Player.STATE_ENDED }
            }
            // Finishing the final timeline session delivers its actual PlaybackStats callback.
            onMain { active.player.clearMediaItems() }

            runBlocking(Dispatchers.IO) {
                val events = withTimeout(10_000) {
                    active.database.events().first { rows -> rows.count { it.song.id in ids } >= 4 }
                }.filter { it.song.id in ids }
                assertEquals(4, events.size)
                assertEquals(3, events.count { it.song.id == tracks[0].id })
                assertEquals(1, events.count { it.song.id == tracks[1].id })
                assertTrue(events.all { it.event.playTime >= 900 })
                assertEquals(3, active.database.getLifetimePlayCount(tracks[0].id))
                assertEquals(1, active.database.getLifetimePlayCount(tracks[1].id))
                tracks.zip(listOf(3, 1)).forEach { (track, count) ->
                    val stored = withTimeout(5_000) { active.database.song(track.id).first() }!!
                    // This is the value consumed by the song/player DetailsDialog.
                    assertEquals(count, stored.playCount.orEmpty().sumOf { it.count })
                }
                val library = withTimeout(5_000) { active.database.songsByCreateDateAsc().first() }
                assertEquals(ids, library.filter { it.id in ids }.map { it.id }.toSet())
                val statistics = withTimeout(5_000) {
                    active.database.mostPlayedSongs(start, limit = Int.MAX_VALUE).first()
                }
                assertEquals(ids, statistics.filter { it.id in ids }.map { it.id }.toSet())
                val actualAlbumId = active.database.albumIdsForSong(tracks[0].id).single()
                val albums = withTimeout(5_000) {
                    active.database.mostPlayedAlbums(start, limit = Int.MAX_VALUE).first()
                }
                assertNotNull(albums.firstOrNull { it.id == actualAlbumId })
            }
            onMain { assertNull(active.player.playerError) }
        } finally {
            try {
                connection?.let { active ->
                    onMain {
                        if (ownsIdleService) {
                            active.player.pause()
                            active.player.clearMediaItems()
                            active.player.removeListener(errorListener)
                            active.player.volume = previousVolume
                            active.player.repeatMode = previousRepeat
                            active.player.shuffleModeEnabled = previousShuffle
                        }
                        active.dispose()
                    }
                }
                database?.let { db ->
                    runBlocking(Dispatchers.IO) {
                        db.awaitTransaction {
                            tracks.forEach { track ->
                                val albums = albumIdsForSong(track.id)
                                val artists = artistIdsForSong(track.id)
                                openHelper.writableDatabase.execSQL("DELETE FROM playCount WHERE song = ?", arrayOf(track.id))
                                deleteLyricById(track.id)
                                delete(track.toSongEntity())
                                albums.forEach(::safeDeleteAlbum)
                                artists.forEach(::safeDeleteArtist)
                            }
                        }
                    }
                }
                activity?.let { host -> onMain { host.fixtureView.disposeComposition(); host.finish() } }
                if (ownsIdleService) {
                    // MusicService also binds its own notification controller. Releasing only
                    // the activity browser leaves that binding alive after stopService().
                    // Session release disconnects every controller without releasing the player;
                    // the real onDestroy callback still performs queue and player cleanup.
                    onMain { connection?.service?.sessions?.forEach { it.release() } }
                    // The empty test board must never be saved over existing persisted queues.
                    // onDestroy calls deInitQueue before setting qbInit=false; inspect this on Main
                    // so the complete onDestroy callback has returned before restoring preferences.
                    context.stopService(Intent(context, MusicService::class.java))
                    awaitCondition("MusicService shutdown before preference restoration", timeoutMs = 10_000) {
                        onMain { connection?.service?.qbInit?.value == false }
                    }
                    serviceShutdownComplete = true
                }
            } finally {
                runBlocking(Dispatchers.IO) {
                    context.dataStore.edit {
                        if (!ownsIdleService || serviceShutdownComplete) {
                            it.restore(previousSettings, PersistentQueueKey)
                        } else {
                            // A failed/hung shutdown must not make an empty test board eligible
                            // to overwrite the user's queue later. The failed run reports this.
                            Log.e("PlaybackHistoryService", "Service shutdown failed; leaving queue persistence disabled")
                        }
                        it.restore(previousSettings, AutoLoadMoreKey)
                        it.restore(previousSettings, PauseListenHistoryKey)
                        it.restore(previousSettings, minPlaybackDurKey)
                        it.restore(previousSettings, SkipSilenceKey)
                    }
                }
                directory.deleteRecursively()
            }
        }
    }

    private fun <T> MutablePreferences.restore(previous: Preferences, key: Preferences.Key<T>) {
        previous[key]?.let { this[key] = it } ?: remove(key)
    }

    private fun awaitCondition(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            SystemClock.sleep(25)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 8_000
        val length = sampleRate * 2 * 3
        return ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + length)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(length)
        }.array()
    }
}
