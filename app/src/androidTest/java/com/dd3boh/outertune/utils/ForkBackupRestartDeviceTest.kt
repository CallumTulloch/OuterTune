package com.dd3boh.outertune.utils

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.artTrackOriginalNames
import com.dd3boh.outertune.models.withArtistCredit
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.repositories.ORIGINAL_NAME_SOURCE_PREFIX
import com.dd3boh.outertune.repositories.encodeOriginalAssessment
import com.dd3boh.outertune.repositories.hasCurrentOriginalAssessmentInputs
import com.dd3boh.outertune.repositories.metadataFetchContextKey
import com.dd3boh.outertune.repositories.originalMetadataContextKey
import com.dd3boh.outertune.repositories.prepareOriginalPublications
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.CHANNEL_SONG
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.LOCAL_SONG
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.LYRICS
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.ONLINE_SONG
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.PERFORMER
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.SAVED_AT
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.SENTINEL
import com.dd3boh.outertune.utils.BackupRestoreIntegrationTest.Companion.UPLOADER
import com.dd3boh.outertune.utils.scanners.LocalMediaLifecycle
import com.dd3boh.outertune.utils.scanners.LocalMediaLifecycleState
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Destructive, explicitly opted-in fixture for an otherwise disposable, idle debug emulator.
 * Run this class alone with forkBackupRestart=true, forkBackupRestartDisposable=resetLibrary,
 * and phase=prepare. Prepare resets only the library; settings and persisted SAF grants stay intact.
 *
 * Export files/fork-restore.backup with run-as, push it into Download, and restore through the
 * ordinary settings/picker UI. Record the UI app PID before/after restore externally: instrumentation
 * itself starts a new process, so the verify phase cannot prove the UI's restart by comparing PIDs.
 * Then run phase=verify with the same opt-in arguments. Neither test calls production restore.
 */
class ForkBackupRestartDeviceTest {
    @Test(timeout = 60_000)
    fun prepareBackupAndSentinelForOrdinaryUiRestore(): Unit = runBlocking {
        val context = requireOptIn("prepare")
        val output = artifact(context, BACKUP)
        val expectedFile = artifact(context, EXPECTED)
        val expectedPreferences = artifact(context, EXPECTED_PREFERENCES)
        assertFalse("Previous process-restore fixtures must be inspected before preparing again", expectedFile.exists())
        assertFalse(output.exists())
        assertFalse(expectedPreferences.exists())
        requireCurrentDatabase(context)

        val fixture = BackupRestoreIntegrationTest.Fixture()
        try {
            fixture.initialize()
            // Synthetic settings only: skip onboarding and leave local music enabled without an
            // automatic scan of the intentionally nonexistent synthetic local path after restart.
            withPreferences(fixture.source.settings) { store ->
                store.edit {
                    it[OobeStatusKey] = OOBE_VERSION
                    it[AutomaticScannerKey] = false
                    it[LocalLibraryEnableKey] = true
                }
            }
            prepareStableStartupMetadata(fixture.sourceDatabase)
            fixture.backup().copyTo(output)
            validateBackupArchive(output)
            fixture.source.settings.copyTo(expectedPreferences)
            val expected = JSONObject()
                .put("package", context.packageName)
                .put("version", MusicDatabase.MUSIC_DATABASE_VERSION)
                .put("backupSha256", sha256(output))
                .put("preferencesSha256", sha256(expectedPreferences))
                .put("tables", encodeSnapshot(snapshotAllTables(fixture.sourceDatabase)))

            // Current schema only, no migrations/fallback and no data outside the app library.
            val internal = Room.databaseBuilder(context, InternalDatabase::class.java, InternalDatabase.DB_NAME).build()
            try {
                val database = MusicDatabase(internal)
                assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.writableDatabase.version)
                internal.clearAllTables()
                database.insert(MediaMetadata(SENTINEL, "Keep the target library", emptyList(), 90, genre = null)) {
                    it.copy(inLibrary = SAVED_AT, liked = true, likedDate = SAVED_AT)
                }
                database.openHelper.readableDatabase.query("SELECT id FROM song").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(SENTINEL, it.getString(0))
                    assertFalse(it.moveToNext())
                }
                assertTrue(database.readQueue().isEmpty())
            } finally { internal.close() }
            // Publish the marker last: verify must never accept an incomplete prepare phase.
            expectedFile.writeText(expected.toString())
        } finally { fixture.close() }
    }

    @Test(timeout = 60_000)
    fun verifyOrdinaryUiRestoreAfterActualApplicationRestart(): Unit = runBlocking {
        val context = requireOptIn("verify")
        val expectedFile = artifact(context, EXPECTED)
        val expectedPreferences = artifact(context, EXPECTED_PREFERENCES)
        val output = artifact(context, BACKUP)
        assertTrue("Run prepare and restore its backup through the ordinary UI first", expectedFile.isFile)
        val expected = JSONObject(expectedFile.readText())
        assertEquals(context.packageName, expected.getString("package"))
        assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, expected.getInt("version"))
        assertEquals(expected.getString("backupSha256"), sha256(output))
        assertEquals(expected.getString("preferencesSha256"), sha256(expectedPreferences))
        validateBackupArchive(output)
        requireCurrentDatabase(context)

        val database = InternalDatabase.newTestInstance(context, InternalDatabase.DB_NAME, allowDestructiveMigration = false)
        try {
            assertEquals("All exported library tables must survive the actual UI restore and reopen",
                decodeSnapshot(expected.getJSONObject("tables")), snapshotAllTables(database))
            assertNull(database.song(SENTINEL).first())
            val folderId = database.artistIdsForSong(LOCAL_SONG).single()
            val channelId = database.artistIdsForSong(CHANNEL_SONG).single()
            assertTrue(database.artistById(folderId)!!.isLocal)
            assertTrue(database.artistById(channelId)!!.isChannel)
            assertEquals(UPLOADER, database.artistById(channelId)!!.sourceChannelId)
            assertNull(database.artistById(channelId)!!.onlineArtistId)
            assertEquals(PERFORMER, database.artistDisplayById(folderId)!!.id)
            assertEquals(PERFORMER, database.artistDisplayById(channelId)!!.id)
            assertEquals("folder-choice", database.localArtistLinkById(folderId)!!.revision)
            assertEquals("channel-choice", database.localArtistLinkById(channelId)!!.revision)
            // An uploader video has no Art Track language proof. Its observed English alias must
            // remain separate from the valid, completed Main source proof for ORIGINAL_SONG.
            assertNull(database.metadataOriginalPublication("SONG", CHANNEL_SONG)!!.englishName)
            assertEquals(ORIGINAL_TITLE, database.metadataOriginalPublication("SONG", ORIGINAL_SONG)!!.englishName)
            assertNull(database.song(ONLINE_SONG).first()!!.song.dateDownload)
            assertEquals(LYRICS, database.lyrics(CHANNEL_SONG).first()!!.lyrics)
            assertEquals(-400L, database.song(CHANNEL_SONG).first()!!.song.lyricsOffsetMs)
            val queue = database.readQueue().single()
            assertEquals(listOf(CHANNEL_SONG, LOCAL_SONG, ONLINE_SONG), queue.queue.map { it.id })
            assertEquals(listOf(2, 0, 1), queue.queue.map { it.shuffleIndex })
            assertTrue(queue.shuffled)
            assertEquals(1, queue.queuePos)
            assertEquals(43_210L, queue.lastSongPos)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            database.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
            }

            // Parse a copy of the actual PB. Opening another DataStore for the live app path would
            // compete with App's process-wide DataStore and could hide the actual serialized state.
            val actualPreferences = File(context.cacheDir, "fork-restore-read-${UUID.randomUUID()}.preferences_pb")
            try {
                File(context.filesDir, "datastore/${BackupRestoreViewModel.SETTINGS_FILENAME}").copyTo(actualPreferences)
                val expectedValues = readPreferences(expectedPreferences)
                val actualValues = readPreferences(actualPreferences)
                expectedValues.asMap().forEach { (key, value) ->
                    assertEquals("Restored preference ${key.name}", value, actualValues.asMap()[key])
                }
                assertEquals("JP", actualValues[ContentCountryKey])
                assertEquals("ja", actualValues[ContentLanguageKey])
                assertEquals(true, actualValues[PreferEnglishOriginalKey])
                assertEquals(true, actualValues[PersistentQueueKey])
                assertEquals(OOBE_VERSION, actualValues[OobeStatusKey])
                assertEquals(false, actualValues[AutomaticScannerKey])
                assertEquals(true, actualValues[LocalLibraryEnableKey])
            } finally { actualPreferences.delete() }
        } finally { database.close() }

        // Preserve restored app data and grants. Only these three synthetic fixture files are removed.
        listOf(output, expectedPreferences, expectedFile).forEach { assertTrue("Could not delete fixture ${it.name}", it.delete()) }
    }

    @Suppress("DEPRECATION")
    private fun requireOptIn(phase: String): Context {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required", arguments.getString("forkBackupRestart") == "true")
        assumeTrue("Run prepare/verify separately", arguments.getString("phase") == phase)
        assertEquals("Acknowledge that prepare resets this disposable app library",
            "resetLibrary", arguments.getString("forkBackupRestartDisposable"))
        assertTrue("Restricted to an Android emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Restricted to the isolated debug app", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue("Stop playback and scanning before running this class alone", LocalMediaScanner.scannerState.value <= 0)
        assertEquals(LocalMediaLifecycleState.IDLE, LocalMediaLifecycle.state.value)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        assertFalse("MusicService must be stopped before preparing or inspecting the library",
            manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == MusicService::class.java.name })
        return context
    }

    private fun requireCurrentDatabase(context: Context) {
        val file = context.getDatabasePath(InternalDatabase.DB_NAME)
        assertTrue("Launch the disposable app once; this fixture never creates or migrates its initial database", file.isFile)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals("Refuse an older schema before opening Room", MusicDatabase.MUSIC_DATABASE_VERSION, it.version)
        }
    }

    private suspend fun readPreferences(file: File): Preferences = withPreferences(file) { it.data.first() }

    /** Build ordinary completed cache state, instead of making startup repair a fabricated download
     * or a publication whose evidence is just {synthetic:true}. The original-name proof mirrors
     * MetadataStartupTest's completed-input fixture and uses production parsing/codecs/preparer.
     */
    private suspend fun prepareStableStartupMetadata(database: MusicDatabase) {
        val now = System.currentTimeMillis()
        val target = OriginalNameTarget(OriginalNameKind.SONG, ORIGINAL_SONG)
        val music = SongItem(ORIGINAL_SONG, ORIGINAL_TITLE, emptyList(), thumbnail = "", endpoint = WatchEndpoint(
            videoId = ORIGINAL_SONG,
            watchEndpointMusicSupportedConfigs = WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV"))))
        val main = ArtTrackOriginalMetadata(ORIGINAL_SONG, ORIGINAL_TITLE, "Synthetic Topic", null,
            "Provided to YouTube by Synthetic Distributor\n\n$ORIGINAL_TITLE · Explicit performer\n\n" +
                "Synthetic release\n\n℗ 2026 Synthetic fixture\n\nAuto-generated by YouTube.")
        val candidate = artTrackOriginalNames(main, music).single { it.target == target }
        val raw = MetadataNameEntity("SONG", ORIGINAL_SONG, "und", ORIGINAL_TITLE,
            ORIGINAL_NAME_SOURCE_PREFIX + ORIGINAL_SONG, observedAt = now,
            originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate))
        val assessment = OriginalNameAssessment(target, ORIGINAL_TITLE, ORIGINAL_SONG,
            "https://www.youtube.com/watch?v=$ORIGINAL_SONG", OriginalNameLanguage.ENGLISH, 0.99f,
            OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-english", "restart-fixture", now)
        val assessed = raw.copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, listOf(raw)))
        assertTrue("The completed Main proof must already have the current resolver/input fingerprint",
            hasCurrentOriginalAssessmentInputs(assessed, listOf(assessed)))
        val original = MediaMetadata(ORIGINAL_SONG, "Saved Art Track title", emptyList(), 183, genre = null).withArtistCredit(
            ArtistCredit("Explicit performer", listOf(Artist("Explicit performer", PERFORMER)),
                ArtistCreditStatus.COMPLETE, "structured-fixture", "ja", listOf("video-source:MUSIC_VIDEO_TYPE_ATV")))
        val locale = YouTubeLocale(hl = "ja", gl = "JP")
        // Source settings contain no account/cookie/visitor values. The normal anonymous default
        // uses useLogin=true; calculate its cache key without reading any app authentication data.
        val contextKey = metadataFetchContextKey(locale, useLogin = true, cookie = null, dataSyncId = null, visitorData = null)
        database.awaitTransaction {
            // This archive has no actual downloaded audio/cache. dateDownload must not claim one.
            update(songForArtistCredit(ONLINE_SONG)!!.copy(dateDownload = null))
            insert(original) { it.copy(inLibrary = SAVED_AT, liked = true, likedDate = SAVED_AT) }
            recordMetadataNames(metadataNames("SONG", CHANNEL_SONG).map { it.copy(observedAt = now) } + listOf(
                MetadataNameEntity("SONG", ONLINE_SONG, "ja", "保存済みオンライン曲名", "detail", 100, now),
                MetadataNameEntity("SONG", ONLINE_SONG, "en", "Online title", "detail", 100, now),
                MetadataNameEntity("SONG", ORIGINAL_SONG, "ja", "保存済み原曲名", "detail", 100, now),
                MetadataNameEntity("SONG", ORIGINAL_SONG, "en", ORIGINAL_TITLE, "detail", 100, now),
                MetadataNameEntity("ARTIST", PERFORMER, "ja", "保存済み歌手名", "detail", 100, now),
                MetadataNameEntity("ARTIST", PERFORMER, "en", "Explicit performer", "detail", 100, now),
                assessed,
            ))
            // Complete both configured and English detail requests for every saved remote target.
            // Successful normal TTLs suppress startup retries; the source proof has its own Main TTL.
            for ((kind, id) in listOf("SONG" to CHANNEL_SONG, "SONG" to ONLINE_SONG,
                "SONG" to ORIGINAL_SONG, "ARTIST" to PERFORMER)) {
                for (language in listOf("ja", "en")) {
                    recordMetadataFetch(MetadataFetchEntity(kind, id, language, MetadataFetchEntity.SUCCESS, now, contextKey))
                }
            }
            recordMetadataFetch(MetadataFetchEntity("SONG", ORIGINAL_SONG, "und", MetadataFetchEntity.SUCCESS,
                now, originalMetadataContextKey(locale)))
            val names = metadataOriginalEvaluationSnapshot()
            val publications = prepareOriginalPublications(names, metadataOriginalPublicationSnapshot(), now)
            recordMetadataOriginalPublications(publications)
            assertEquals(ORIGINAL_TITLE, metadataOriginalPublication("SONG", ORIGINAL_SONG)!!.englishName)
            assertNull(metadataOriginalPublication("SONG", CHANNEL_SONG)!!.englishName)
            assertEquals("Re-evaluating identical completed inputs must preserve every publication exactly",
                publications, prepareOriginalPublications(names, publications, now + 1))
        }
    }

    private suspend fun <T> withPreferences(file: File, action: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T {
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            return action(store)
        } finally { job.cancelAndJoin() }
    }

    private data class TableSnapshot(val columns: List<String>, val rows: List<List<String?>>)

    private fun snapshotAllTables(database: MusicDatabase): Map<String, TableSnapshot> {
        val sqlite = database.openHelper.readableDatabase
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return tables.associateWith { table ->
            val columns = sqlite.query("PRAGMA table_info(${quoteIdentifier(table)})").use {
                buildList { while (it.moveToNext()) add(it.getString(1)) }
            }
            val order = columns.joinToString(", ", transform = ::quoteIdentifier)
            val rows = sqlite.query("SELECT * FROM ${quoteIdentifier(table)} ORDER BY $order").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(columns.indices.map { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                            else -> cursor.getString(index)
                        }
                    })
                }
            }
            TableSnapshot(columns, rows)
        }
    }

    private fun encodeSnapshot(snapshot: Map<String, TableSnapshot>): JSONObject = JSONObject().apply {
        snapshot.forEach { (table, value) ->
            put(table, JSONObject().put("columns", JSONArray(value.columns)).put("rows", JSONArray().apply {
                value.rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it ?: JSONObject.NULL) } }) }
            }))
        }
    }

    private fun decodeSnapshot(json: JSONObject): Map<String, TableSnapshot> = json.keys().asSequence().sorted().associateWith { table ->
        val value = json.getJSONObject(table)
        val columns = value.getJSONArray("columns").let { array -> (0 until array.length()).map { array.getString(it) } }
        val rows = value.getJSONArray("rows").let { array -> (0 until array.length()).map { rowIndex ->
            array.getJSONArray(rowIndex).let { row -> (0 until row.length()).map { if (row.isNull(it)) null else row.getString(it) } }
        } }
        TableSnapshot(columns, rows)
    }

    private fun quoteIdentifier(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun artifact(context: Context, name: String): File = File(context.filesDir, name)

    private companion object {
        const val BACKUP = "fork-restore.backup"
        const val EXPECTED = "fork-restore-expected.json"
        const val EXPECTED_PREFERENCES = "fork-restore-expected.preferences_pb"
        const val ORIGINAL_SONG = "forkOrig001"
        const val ORIGINAL_TITLE = "Confirmed original"
    }
}
