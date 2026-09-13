package com.dd3boh.outertune.playback

import android.app.LocaleManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.toStoredJson
import com.dd3boh.outertune.utils.dataStore
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
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in fixture that survives the test for manual player UI verification on a disposable emulator.
 * Run this class with seedPlayerAdjustments=true after opening the debug app once, then reopen it.
 * Changes the fixture IDs, onboarding/content settings and app locale; never clears the database.
 * No accounts or network calls are used. Exported audio paths must be rewritten for a release restore.
 */
class PlayerAdjustmentsUiSeedTest {
    @Test
    fun seedLocalAlbumAndStandaloneSongsWithExistingLyricsOffset() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass seedPlayerAdjustments=true to install the UI fixture",
            InstrumentationRegistry.getArguments().getString("seedPlayerAdjustments") == "true")
        assumeTrue("This fixture may only be installed on an Android emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assumeTrue("Japanese app locale requires this fixture's API 33+ emulator",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val context = instrumentation.targetContext
        assumeTrue("Open the target app once before seeding; no database is created by this fixture",
            context.getDatabasePath(InternalDatabase.DB_NAME).isFile)

        // Disable automatic scanning before completing onboarding: these deliberately synthetic rows
        // have app-private files and do not belong to a selected MediaStore scan directory.
        context.dataStore.edit { preferences ->
            preferences[AutomaticScannerKey] = false
            preferences[LocalLibraryEnableKey] = true
            preferences[ContentLanguageKey] = "ja"
            preferences[ContentCountryKey] = "JP"
            preferences[OobeStatusKey] = OOBE_VERSION
        }
        instrumentation.runOnMainSync {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags("ja")
        }

        // No migration or destructive fallback: an incompatible schema fails before writing rows.
        val internal = Room.databaseBuilder(context, InternalDatabase::class.java, InternalDatabase.DB_NAME).build()
        try {
            val database = MusicDatabase(internal)
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.writableDatabase.version)
            val directory = File(context.filesDir, "ui-fixtures/player-adjustments").apply { mkdirs() }
            assertTrue(directory.isDirectory)
            val albumAudio = File(directory, "album-chime.wav").apply { writeBytes(syntheticWav(261.63)) }
            val standaloneAudio = File(directory, "standalone-chime.wav").apply { writeBytes(syntheticWav(329.63)) }
            assertPlayableWav(albumAudio)
            assertPlayableWav(standaloneAudio)
            File(directory, "album-chime.lrc").writeText(SYNCED_LYRICS)

            val now = LocalDateTime.now()
            val artist = ArtistEntity(ARTIST_ID, ARTIST_NAME, isLocal = true, bookmarkedAt = now)
            val creditJson = ArtistCredit(
                rawText = artist.name,
                artists = listOf(Artist(artist.name, id = null, ref = artist.id)),
                status = ArtistCreditStatus.COMPLETE,
                source = "file-tags", language = "ja",
            ).toStoredJson()
            val album = AlbumEntity(
                id = ALBUM_ID, title = ALBUM_TITLE, isLocal = true, songCount = 1,
                duration = DURATION_SECONDS, bookmarkedAt = now, artistCreditJson = creditJson,
            )
            val albumSong = SongEntity(
                id = ALBUM_SONG_ID, title = ALBUM_SONG_TITLE, duration = DURATION_SECONDS,
                inLibrary = now, isLocal = true, localPath = albumAudio.absolutePath,
                albumId = ALBUM_ID, albumName = ALBUM_TITLE, trackNumber = 1,
                lyricsOffsetMs = 500L, artistCreditJson = creditJson,
            )
            val standaloneSong = SongEntity(
                id = STANDALONE_SONG_ID, title = STANDALONE_SONG_TITLE, duration = DURATION_SECONDS,
                inLibrary = now, isLocal = true, localPath = standaloneAudio.absolutePath,
                artistCreditJson = creditJson,
            )
            database.awaitTransaction {
                assertEquals(ARTIST_ID, resolveArtistId(ARTIST_ID))
                artistById(ARTIST_ID)?.let { assertTrue(it.isLocal) }
                albumById(ALBUM_ID)?.let { assertTrue(it.isLocal && it.title == ALBUM_TITLE) }
                for (song in listOf(albumSong, standaloneSong)) {
                    songForArtistCredit(song.id)?.let { saved ->
                        assertTrue(saved.isLocal && saved.localPath == song.localPath)
                    }
                    assertTrue(artistIdsForSong(song.id).all { it == ARTIST_ID })
                }
                insert(artist)
                update(artist)
                insert(album)
                update(album)
                insert(AlbumArtistMap(ALBUM_ID, ARTIST_ID, 0))
                for (song in listOf(albumSong, standaloneSong)) {
                    insert(song)
                    update(song)
                    insert(SongArtistMap(song.id, ARTIST_ID, 0))
                }
                insert(SongAlbumMap(ALBUM_SONG_ID, ALBUM_ID, 0))
                upsert(LyricsEntity(ALBUM_SONG_ID, SYNCED_LYRICS))
                // Avoid a remote lyrics lookup if the standalone fixture is opened in lyrics mode.
                upsert(LyricsEntity(STANDALONE_SONG_ID, "アルバム未設定の合成音声です"))
            }

            val savedAlbumSong = database.song(ALBUM_SONG_ID).first()!!
            assertEquals(ALBUM_ID, savedAlbumSong.song.albumId)
            assertEquals(ALBUM_TITLE, savedAlbumSong.song.albumName)
            assertEquals(500L, savedAlbumSong.song.lyricsOffsetMs)
            assertEquals(listOf(ARTIST_ID), savedAlbumSong.artists.map { it.id })
            assertEquals(1, database.album(ALBUM_ID).first()!!.album.songCount)
            assertNull(database.song(STANDALONE_SONG_ID).first()!!.song.albumId)
            assertNull(database.song(STANDALONE_SONG_ID).first()!!.song.albumName)
            val lyrics = database.lyrics(ALBUM_SONG_ID).first()!!.lyrics
            assertTrue(parseLrc(lyrics, false, true) is SemanticLyrics.SyncedLyrics)
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
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) >= DURATION_SECONDS * 1_000_000L - 1_000)
            extractor.selectTrack(0)
            assertTrue(extractor.readSampleData(ByteBuffer.allocate(32_768), 0) > 0)
        } finally {
            extractor.release()
        }
    }

    /** Quiet repeated notes with fades; generated locally and unrelated to an actual recording. */
    private fun syntheticWav(frequency: Double): ByteArray {
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
            for (sample in 0 until sampleCount) {
                val noteTime = (sample % SAMPLE_RATE).toDouble() / SAMPLE_RATE
                val envelope = min(1.0, min(noteTime / 0.05, (1.0 - noteTime) / 0.15)).coerceAtLeast(0.0)
                putShort((sin(2 * PI * frequency * noteTime) * envelope * 2_000).toInt().toShort())
            }
        }.array()
    }

    companion object {
        const val ARTIST_ID = "LA_fixture_player_adjustments_20260913"
        const val ARTIST_NAME = "プレイヤー確認用アーティスト"
        const val ALBUM_ID = "LB_fixture_player_adjustments_20260913"
        const val ALBUM_TITLE = "フォルダアルバム確認"
        const val ALBUM_SONG_ID = "LS_fixture_player_album_20260913"
        const val ALBUM_SONG_TITLE = "アルバム付き・歌詞調整の合成音声"
        const val STANDALONE_SONG_ID = "LS_fixture_player_standalone_20260913"
        const val STANDALONE_SONG_TITLE = "アルバムなしの合成音声"
        private const val SAMPLE_RATE = 44_100
        private const val DURATION_SECONDS = 12
        private val SYNCED_LYRICS = """
            [00:00.00]一行目・合成音声の開始
            [00:02.00]二行目・二秒の位置
            [00:04.00]三行目・四秒の位置
            [00:06.00]四行目・六秒の位置
            [00:08.00]五行目・八秒の位置
            [00:10.00]六行目・十秒の位置
        """.trimIndent()
    }
}
