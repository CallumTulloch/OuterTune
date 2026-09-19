@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dd3boh.outertune.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.AlbumPage
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in repair of a supplied database copy on a disposable emulator. No network or app settings
 * are used. The two assets contain only anonymous album response excerpts captured on 2026-09-19;
 * no supplied database records are embedded in the test APK.
 *
 * Stage files/album-first-track/provided-song.db, then pass verifyProvidedAlbumTracks=true.
 * Room migrates only the isolated test database from schema 27 to 28, retaining every old column.
 * A verified, checkpointed copy is exported to
 * files/album-first-track/repaired-song.db for a separate release UI check.
 */
class ProvidedAlbumTrackRepairTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private data class AlbumFixture(
        val name: String,
        val id: String,
        val expectedSongIds: List<String>,
        val staleSongId: String,
    )

    private val fixtures = listOf(
        AlbumFixture(
            "chop-suey", "MPREb_hscoNGfCKJW",
            listOf("-cid1qHuy_U", "cmna-FAi6t0"), "CSvFpBOe8eY",
        ),
        AlbumFixture(
            "nevermind", "MPREb_jPOYfjGgApr",
            listOf("ljUtuoFt-8c", "ng_vqlKtxLs", "ZEMBDKMtHqM", "ox_BG6sLPq8",
                "_oWUgfpGi0M", "h5cZvNWxnho", "W64Bz9wXvRs", "jFU6xiWbHT0",
                "jwcq2Ci6jx0", "UGA6zBEyo8Y", "INFH5bt8hxM", "SVSjcS-N224",
                "MCBzJ2vjYyg"), "hTWKbfoikeg",
        ),
    )

    @Test
    fun repairProvidedAlbumsWithoutDeletingSongsOrSavedStateAndExportForReleaseUi() = runBlocking {
        assumeTrue("Pass verifyProvidedAlbumTracks=true to verify the supplied database copy",
            InstrumentationRegistry.getArguments().getString("verifyProvidedAlbumTracks") == "true")
        assumeTrue("The provided-data probe is restricted to an Android emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "album-first-track")
        val provided = File(directory, "provided-song.db")
        val exported = File(directory, "repaired-song.db")
        assertTrue("Stage a checkpointed provided-song.db before running this probe", provided.isFile)
        assertFalse("The staged database must not depend on a WAL file", File(provided.path + "-wal").exists())
        val originalHash = sha256(provided)
        val databaseName = "provided-album-track-repair-${UUID.randomUUID()}.db"
        val workingFile = context.getDatabasePath(databaseName)
        workingFile.parentFile!!.mkdirs()
        provided.copyTo(workingFile)
        if (exported.exists()) assertTrue("Remove the previous probe export", exported.delete())
        var internal: InternalDatabase? = null
        try {
            val originalTables = SQLiteDatabase.openDatabase(
                workingFile.path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { original ->
                assertEquals("The provided reproduction must use the original schema", 27, original.version)
                migrationSnapshot(original)
            }
            val opened = Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build()
            internal = opened
            val database = MusicDatabase(opened)
            val sqlite = database.openHelper.writableDatabase
            assertEquals(28, sqlite.version)
            originalTables.forEach { (table, snapshot) ->
                val migrated = rows(sqlite, snapshot.query).toSet()
                // Compare every original table and column, without printing private row contents.
                assertTrue("Schema migration must preserve all original rows in $table", snapshot.rows == migrated)
            }
            assertEquals("Migrated albums await their first confirmed track list", "0",
                rows(sqlite, "SELECT COUNT(*) FROM album WHERE hasTrackList != 0").single().single())
            val before = preservedRows(sqlite)
            val pages = fixtures.associateWith { fixture ->
                val response = instrumentation.context.assets
                    .open("album-first-track/${fixture.name}.json").bufferedReader().use {
                        json.decodeFromString<BrowseResponse>(it.readText())
                    }
                val album = AlbumPage.getAlbum(fixture.id, response, "ja")
                val songs = AlbumPage.getSongs(response, album, "ja")
                assertEquals("The captured album shelf changed", fixture.expectedSongIds, songs.map { it.id })
                assertEquals(null, AlbumPage.continuation(response))
                AlbumPage(album, songs, emptyList())
            }

            fixtures.forEach { fixture ->
                val originalSongs = database.albumWithSongs(fixture.id).first()!!.songs.map { it.id }
                assertEquals("The supplied album must reproduce the extra first track",
                    fixture.expectedSongIds.size + 1, originalSongs.size)
                // The service may now return different recording IDs for the other tracks too.
                // This supplied reproduction proves the first-track pair, not the newer full ID set.
                assertTrue(originalSongs.containsAll(listOf(fixture.expectedSongIds.first(), fixture.staleSongId)))
                val existing = requireNotNull(database.albumById(fixture.id))
                database.update(existing, pages.getValue(fixture))
                assertTrackList(database, sqlite, fixture)
            }
            assertRowsPreserved(before, preservedRows(sqlite))
            assertEquals("ok", rows(sqlite, "PRAGMA quick_check").single().single())
            checkpoint(sqlite)
            opened.close()
            internal = null

            // Reopen through Room so success is based on committed rows, not an in-memory result.
            val reopened = Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build()
            internal = reopened
            val restored = MusicDatabase(reopened)
            val restoredSqlite = restored.openHelper.writableDatabase
            fixtures.forEach { assertTrackList(restored, restoredSqlite, it) }
            assertRowsPreserved(before, preservedRows(restoredSqlite))
            checkpoint(restoredSqlite)
            reopened.close()
            internal = null

            assertEquals("The staged source database must remain untouched", originalHash, sha256(provided))
            workingFile.copyTo(exported, overwrite = true)
            assertEquals("The export must contain the verified database", sha256(workingFile), sha256(exported))
            println("Provided album repair: schema 27 to 28; all original table rows preserved; " +
                "Chop Suey! 2 tracks; Nevermind 13 tracks; " +
                "preserved ${before.getValue("song state").size} song rows; checkpointed export ready")
        } finally {
            internal?.close()
            context.deleteDatabase(databaseName)
            assertEquals("The staged source database must remain untouched", originalHash, sha256(provided))
        }
    }

    private suspend fun assertTrackList(
        database: MusicDatabase,
        sqlite: SupportSQLiteDatabase,
        fixture: AlbumFixture,
    ) {
        val album = database.albumWithSongs(fixture.id).first()!!
        assertTrue("Only a successful album-page import confirms the track list", album.album.hasTrackList)
        assertEquals(fixture.expectedSongIds.size, album.album.songCount)
        assertEquals(fixture.expectedSongIds, album.songs.map { it.id })
        assertEquals(fixture.expectedSongIds, database.albumSongs(fixture.id).first().map { it.id })
        val order = rows(sqlite,
            "SELECT songId, `index` FROM song_album_map WHERE albumId = '${fixture.id}' ORDER BY `index`")
        assertTrue("The former version must retain its album provenance outside the track list",
            listOf(fixture.staleSongId, "-1") in order)
        assertEquals(fixture.expectedSongIds.mapIndexed { index, id -> listOf(id, index.toString()) },
            order.filter { it[1] != "-1" })
    }

    private fun preservedRows(sqlite: SupportSQLiteDatabase): Map<String, Set<List<String?>>> {
        val result = linkedMapOf(
            "song state" to rows(sqlite, """SELECT id, title, duration, thumbnailUrl, inLibrary,
                isLocal, localPath, dateDownload, liked, likedDate, trackNumber, discNumber,
                year, date, dateModified, lyricsOffsetMs FROM song""").toSet(),
            "song album provenance" to rows(sqlite, "SELECT songId, albumId FROM song_album_map").toSet(),
            "album bookmarks" to rows(sqlite, "SELECT id, bookmarkedAt FROM album").toSet(),
            "artist identities" to rows(sqlite, "SELECT id FROM artist").toSet(),
            "foreign key violations" to rows(sqlite, "PRAGMA foreign_key_check").toSet(),
        )
        listOf("song_artist_map", "album_artist_map", "playlist", "playlist_song_map", "event",
            "format", "lyrics", "playCount", "queue", "queue_song_map", "song_genre_map",
            "artist_alias", "local_artist_link", "related_song_map", "recent_activity").forEach { table ->
            result[table] = rows(sqlite, "SELECT * FROM `$table`").toSet()
        }
        return result
    }

    private fun assertRowsPreserved(
        before: Map<String, Set<List<String?>>>,
        after: Map<String, Set<List<String?>>>,
    ) {
        before.forEach { (name, originalRows) ->
            val missing = originalRows.count { it !in after.getValue(name) }
            // Counts only: failures must not print supplied song paths or personal playlist data.
            assertEquals("Lost or changed rows in $name", 0, missing)
        }
        assertEquals("The repair must not create new foreign key violations",
            before.getValue("foreign key violations").size, after.getValue("foreign key violations").size)
    }

    private data class TableSnapshot(val query: String, val rows: Set<List<String?>>)

    /** Capture schema 27 columns explicitly so the new schema 28 flag is the only addition. */
    private fun migrationSnapshot(sqlite: SQLiteDatabase): Map<String, TableSnapshot> {
        val tables = readRows(sqlite.rawQuery("""SELECT name FROM sqlite_master WHERE type = 'table'
            AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')""", null))
        return tables.associate { row ->
            val table = requireNotNull(row.single())
            val tableIdentifier = quoteIdentifier(table)
            val columns = readRows(sqlite.rawQuery("PRAGMA table_info($tableIdentifier)", null))
                .map { quoteIdentifier(requireNotNull(it[1])) }
            val query = "SELECT ${columns.joinToString(", ")} FROM $tableIdentifier"
            table to TableSnapshot(query, readRows(sqlite.rawQuery(query, null)).toSet())
        }
    }

    private fun quoteIdentifier(value: String) = "`" + value.replace("`", "``") + "`"

    private fun rows(sqlite: SupportSQLiteDatabase, sql: String): List<List<String?>> = readRows(sqlite.query(sql))

    private fun readRows(source: Cursor): List<List<String?>> =
        source.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP)
                        else -> cursor.getString(column)
                    }
                })
            }
        }

    private fun checkpoint(sqlite: SupportSQLiteDatabase) {
        sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue("The WAL checkpoint must report a result", cursor.moveToFirst())
            assertEquals("The WAL checkpoint must finish before export", 0, cursor.getInt(0))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
