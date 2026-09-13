package com.dd3boh.outertune.repositories

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in UI fixture for a disposable emulator. It deliberately survives this test.
 * Run only this class with instrumentation argument seedLocalArtistLink=true, then reopen the app.
 * No accounts, preferences, network requests, database resets, or existing library IDs are used.
 */
class LocalArtistLinkUiSeedTest {
    @Test
    fun seedUnlinkedLocalArtistAndPlayableSyntheticSong() = runBlocking {
        assumeTrue("Pass seedLocalArtistLink=true to install the UI fixture",
            InstrumentationRegistry.getArguments().getString("seedLocalArtistLink") == "true")
        assumeTrue("This fixture may only be installed on an Android emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Open the target app once before seeding; this test does not initialize its database",
            context.getDatabasePath(InternalDatabase.DB_NAME).isFile)
        // Unlike the production opener, this has no destructive fallback or migration path.
        // A schema mismatch fails before any fixture is written.
        val internal = Room.databaseBuilder(context, InternalDatabase::class.java, InternalDatabase.DB_NAME).build()
        try {
            val database = MusicDatabase(internal)
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.writableDatabase.version)
            val directory = File(context.filesDir, "ui-fixtures/local-artist-link").apply { mkdirs() }
            assertTrue(directory.isDirectory)
            val audio = File(directory, "synthetic-chime.wav")
            audio.writeBytes(syntheticWav())
            assertPlayableWav(audio)

            val now = LocalDateTime.now()
            val canonicalGroup = InstrumentationRegistry.getArguments().getString("seedCanonicalArtistGroup") == "true"
            val artist = ArtistEntity(
                id = ARTIST_ID, name = if (canonicalGroup) "フォルダ表記A" else "椎名林檎", isLocal = true,
                bookmarkedAt = now, lastUpdateTime = now,
            )
            val credit = ArtistCredit(
                rawText = artist.name,
                artists = listOf(Artist(artist.name, id = null, ref = artist.id)),
                status = ArtistCreditStatus.COMPLETE,
                source = "file-tags", language = "ja",
            ).toStoredJson()
            val song = SongEntity(
                id = SONG_ID, title = "手動紐付け確認用の合成音声", duration = DURATION_SECONDS,
                inLibrary = now, isLocal = true, localPath = audio.absolutePath,
                artistCreditJson = credit,
            )
            database.awaitTransaction {
                // Fixed fixture IDs make repeated runs idempotent without matching real music by name.
                assertEquals(ARTIST_ID, resolveArtistId(ARTIST_ID))
                artistById(ARTIST_ID)?.let { assertTrue(it.id == ARTIST_ID && it.isLocal) }
                songForArtistCredit(SONG_ID)?.let { assertTrue(it.isLocal && it.localPath == audio.absolutePath) }
                assertTrue(artistIdsForSong(SONG_ID).all { it == ARTIST_ID })
                insert(artist)
                update(artist)
                insert(song)
                update(song)
                insert(SongArtistMap(SONG_ID, ARTIST_ID, 0))
                // Reset only this synthetic artist's previous manual choice so the UI starts unlinked.
                localArtistLinkById(ARTIST_ID)?.let { link ->
                    assertTrue(removeLocalArtistLink(ARTIST_ID, link.revision))
                }
            }
            val savedArtist = database.artistById(ARTIST_ID)!!
            assertTrue(savedArtist.isLocal)
            assertEquals(artist.name, savedArtist.name)
            assertNull(savedArtist.onlineArtistId)
            assertNull(database.localArtistLinkById(ARTIST_ID))
            val savedSong = database.song(SONG_ID).first()!!
            assertTrue(savedSong.song.isLocal)
            assertNotNull(savedSong.song.inLibrary)
            assertEquals(audio.absolutePath, savedSong.song.localPath)
            assertEquals(listOf(ARTIST_ID), savedSong.artists.map { it.id })
            assertEquals(listOf(SONG_ID), database.artistSongsByNameAsc(ARTIST_ID).first().map { it.id })

            if (canonicalGroup) {
                val secondDirectory = File(directory, "second-folder").apply { mkdirs() }
                val secondAudio = File(secondDirectory, "second-chime.wav").apply { writeBytes(syntheticWav()) }
                val second = ArtistEntity("LA_fixture_canonical_second", "フォルダ表記B", isLocal = true)
                val empty = ArtistEntity("LA_fixture_canonical_empty", "曲なしのフォルダ表記", isLocal = true)
                database.awaitTransaction {
                    insert(ArtistEntity(CANONICAL_ID, "Sheena Ringo", bookmarkedAt = now))
                    insert(second)
                    insert(empty)
                    insert(SongEntity("LS_fixture_canonical_second", "別フォルダの合成音声", duration = DURATION_SECONDS,
                        inLibrary = now, isLocal = true, localPath = secondAudio.absolutePath))
                    insert(SongArtistMap("LS_fixture_canonical_second", second.id, 0))
                    insert(SongEntity("canonical-online-fixture-20260913", "保存済みオンライン曲（表示確認用）",
                        inLibrary = now, localPath = null))
                    insert(SongArtistMap("canonical-online-fixture-20260913", CANONICAL_ID, 0))
                    // A starts unlinked for the UI confirmation path; B and a zero-song source already share the target.
                    setLocalArtistLink(LocalArtistLink(second.id, CANONICAL_ID, "Sheena Ringo", null, "fixture-second"))
                    setLocalArtistLink(LocalArtistLink(empty.id, CANONICAL_ID, "Sheena Ringo", null, "fixture-empty"))
                }
                assertEquals(2, database.artistSongsByNameAsc(CANONICAL_ID).first().size)
                assertEquals(2, database.localArtistLinkSources(CANONICAL_ID).first().size)
            }
        } finally {
            internal.close()
        }
    }

    private fun assertPlayableWav(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/raw", format.getString(MediaFormat.KEY_MIME))
            assertEquals(SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) >= (DURATION_SECONDS * 1_000_000L) - 1_000)
            extractor.selectTrack(0)
            assertTrue(extractor.readSampleData(ByteBuffer.allocate(32_768), 0) > 0)
        } finally {
            extractor.release()
        }
    }

    /** Six quiet sine-wave notes with short fades, generated here rather than copied from music. */
    private fun syntheticWav(): ByteArray {
        val sampleCount = SAMPLE_RATE * DURATION_SECONDS
        val bytesPerSample = 2
        val dataLength = sampleCount * bytesPerSample
        return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLength)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * bytesPerSample)
            putShort(bytesPerSample.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLength)
            val frequencies = doubleArrayOf(261.63, 329.63, 392.0, 329.63, 261.63, 392.0)
            for (sample in 0 until sampleCount) {
                val noteSample = sample % SAMPLE_RATE
                val noteTime = noteSample.toDouble() / SAMPLE_RATE
                val envelope = min(1.0, min(noteTime / 0.05, (1.0 - noteTime) / 0.15)).coerceAtLeast(0.0)
                putShort((sin(2 * PI * frequencies[sample / SAMPLE_RATE] * noteTime) * envelope * 2_000).toInt().toShort())
            }
        }.array()
    }

    companion object {
        const val ARTIST_ID = "LA_fixture_manual_artist_link_20260912"
        const val SONG_ID = "LS_fixture_manual_artist_link_20260912"
        const val CANONICAL_ID = "UCbrWU0y_rLsEOYgaTX5Y74A"
        private const val SAMPLE_RATE = 44_100
        private const val DURATION_SECONDS = 6
    }
}
