package com.dd3boh.outertune.repositories

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in fixture retained for ordinary UI review on a disposable emulator.
 * Launch the app once, then run only this class with seedChannelArtistLink=true.
 * It saves two online-source tracks and resets only this fixture's manual choice.
 */
class ChannelArtistUiSeedTest {
    @Test
    fun seedUnlinkedChannelAndTwoSavedOnlineTracks(): Unit = runBlocking {
        assumeTrue("Pass seedChannelArtistLink=true to install the UI fixture",
            InstrumentationRegistry.getArguments().getString("seedChannelArtistLink") == "true")
        assumeTrue("This fixture may only be installed on an Android emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath(InternalDatabase.DB_NAME)
        assumeTrue("Open the target app once before seeding; this test does not initialize its database", file.isFile)
        // Refuse an old database before Room has an opportunity to migrate anything.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals("The initialized app database must already use the final schema",
                MusicDatabase.MUSIC_DATABASE_VERSION, it.version)
        }
        val internal = Room.databaseBuilder(context, InternalDatabase::class.java, InternalDatabase.DB_NAME).build()
        try {
            val database = MusicDatabase(internal)
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.writableDatabase.version)
            val directory = File(context.filesDir, "ui-fixtures/channel-artist-link").apply { mkdirs() }
            assertTrue(directory.isDirectory)
            val image = File(directory, "channel-source.png")
            if (!image.isFile) writeChannelImage(image)
            assertTrue(image.isFile && image.length() > 0)
            val sourceId = ArtistIdentity.channelId(CHANNEL_ID, SONG_IDS.first(), CHANNEL_NAME)
            val credit = ArtistCredit(CHANNEL_NAME,
                listOf(Artist(CHANNEL_NAME, null, sourceChannelId = CHANNEL_ID, isChannel = true)),
                ArtistCreditStatus.COMPLETE, "video-channel", "ja",
                listOf("video-source:MUSIC_VIDEO_TYPE_OMV", "channel-source:ui-fixture"))
            val now = LocalDateTime.now()

            database.awaitTransaction {
                // Reject collisions before writing; never adopt unrelated songs by their names.
                assertEquals(sourceId, resolveArtistId(sourceId))
                artistById(sourceId)?.let {
                    assertTrue(it.isChannel)
                    assertFalse(it.isLocal)
                    assertEquals(CHANNEL_ID, it.sourceChannelId)
                    assertEquals(CHANNEL_NAME, it.name)
                    assertNull(it.onlineArtistId)
                    assertTrue(songCreditsForArtist(sourceId).all { row -> row.id in SONG_IDS })
                }
                SONG_IDS.forEachIndexed { index, songId ->
                    songForArtistCredit(songId)?.let {
                        assertFalse(it.isLocal)
                        assertEquals("チャンネル紐付け確認曲 ${index + 1}", it.title)
                        assertNull(it.localPath)
                    }
                    assertTrue(artistIdsForSong(songId).all { it == sourceId })
                }
                SONG_IDS.forEachIndexed { index, songId ->
                    val metadata = MediaMetadata(songId, "チャンネル紐付け確認曲 ${index + 1}", emptyList(),
                        duration = 180 + index, thumbnailUrl = image.absolutePath, genre = null)
                        .withArtistCredit(credit)
                    insert(metadata) { it.copy(inLibrary = now) }
                    val saved = songForArtistCredit(songId)!!
                    if (saved.inLibrary == null) update(saved.copy(inLibrary = now))
                }
                val source = artistById(sourceId)!!
                if (source.thumbnailUrl == null) update(source.copy(thumbnailUrl = image.absolutePath))

                val originalSource = artistById(sourceId)!!
                val originalSongs = SONG_IDS.associateWith { songForArtistCredit(it)!! }
                localArtistLinkById(sourceId)?.let { choice ->
                    assertTrue(removeLocalArtistLink(sourceId, choice.revision))
                }
                // Resetting the fixture choice must preserve the raw channel and every saved field.
                assertEquals(originalSource, artistById(sourceId))
                originalSongs.forEach { (songId, original) ->
                    assertEquals(original, songForArtistCredit(songId))
                    assertEquals(listOf(sourceId), artistIdsForSong(songId))
                }
            }
            val source = database.artistById(sourceId)!!
            assertTrue(source.isChannel)
            assertFalse(source.isLocal)
            assertEquals(CHANNEL_ID, source.sourceChannelId)
            assertNull(source.onlineArtistId)
            assertNull(source.albumGroupId)
            assertNull(database.localArtistLinkById(sourceId))
            assertEquals(sourceId, database.artistDisplayById(sourceId)!!.id)
            assertEquals(2, database.artist(sourceId).first()!!.songCount)
            assertEquals(SONG_IDS.toSet(), database.artistSongsByNameAsc(sourceId).first().map { it.id }.toSet())
            SONG_IDS.forEach { songId ->
                val saved = database.song(songId).first()!!
                assertFalse(saved.song.isLocal)
                assertNotNull(saved.song.inLibrary)
                assertNull(saved.song.localPath)
                val label = saved.artistCredit!!.artists.single()
                assertTrue(label.isChannel)
                assertEquals(CHANNEL_ID, label.sourceChannelId)
                assertNull(label.id)
                assertEquals(sourceId, label.ref)
            }
            Log.i("ChannelArtistUiSeed", "Seeded channel source $sourceId; channelId=$CHANNEL_ID; songs=${SONG_IDS.joinToString()}")
        } finally { internal.close() }
    }

    private fun writeChannelImage(file: File) {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(34, 58, 104))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 179, 59) }
            canvas.drawCircle(128f, 128f, 92f, paint)
            paint.color = Color.rgb(34, 58, 104)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 88f
            paint.isFakeBoldText = true
            canvas.drawText("CH", 128f, 159f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    companion object {
        const val CHANNEL_NAME = "チャンネル紐付け確認"
        const val CHANNEL_ID = "UCPCiIrrrNJOKvi_5vr3G6PA"
        val SONG_IDS = listOf("channel-ui-fixture-one-20261001", "channel-ui-fixture-two-20261001")
    }
}
