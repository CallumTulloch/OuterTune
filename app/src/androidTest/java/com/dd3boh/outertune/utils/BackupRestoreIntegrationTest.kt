package com.dd3boh.outertune.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.withArtistCredit
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual backup/restore ViewModel IO; every DB/settings path is inside unique test sandboxes.
 * Service stop and Activity restart are intercepted before restore's exitProcess, so these tests
 * neither restart the real app nor read, replace, or export its library/preferences.
 */
class BackupRestoreIntegrationTest {
    @Test(timeout = 60_000)
    fun viewModelBackupRestoreKeepsRawArtistLinksNamesLyricsPreferencesAndMixedQueueAcrossReopen() = runBlocking {
        withFixture { fixture ->
            val before = snapshot(fixture.sourceDatabase)
            val archive = fixture.backup()
            validateBackupArchive(archive)
            fixture.restore(archive)
            val restored = fixture.reopenTarget()
            assertEquals(before, snapshot(restored))
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, restored.openHelper.readableDatabase.version)
            assertNull(restored.song(SENTINEL).first())
            assertArrayEquals(fixture.source.settings.readBytes(), fixture.target.settings.readBytes())
            fixture.target.assertSettings("ja", preferOriginal = true)
            assertEquals(1, fixture.target.restartRequests)

            val folderId = restored.artistIdsForSong(LOCAL_SONG).single()
            val channelId = restored.artistIdsForSong(CHANNEL_SONG).single()
            assertTrue(restored.artistById(folderId)!!.isLocal)
            assertTrue(restored.artistById(channelId)!!.isChannel)
            assertEquals(UPLOADER, restored.artistById(channelId)!!.sourceChannelId)
            assertNull(restored.artistById(channelId)!!.onlineArtistId)
            assertEquals(PERFORMER, restored.artistDisplayById(folderId)!!.id)
            assertEquals(PERFORMER, restored.artistDisplayById(channelId)!!.id)
            assertEquals("folder-choice", restored.localArtistLinkById(folderId)!!.revision)
            assertEquals("channel-choice", restored.localArtistLinkById(channelId)!!.revision)
            assertEquals("Confirmed original", restored.metadataOriginalPublication("SONG", CHANNEL_SONG)!!.englishName)
            assertEquals(LYRICS, restored.lyrics(CHANNEL_SONG).first()!!.lyrics)
            assertEquals(-400L, restored.song(CHANNEL_SONG).first()!!.song.lyricsOffsetMs)
            val queue = restored.readQueue().single()
            assertEquals(listOf(CHANNEL_SONG, LOCAL_SONG, ONLINE_SONG), queue.queue.map { it.id })
            assertEquals(listOf(2, 0, 1), queue.queue.map { it.shuffleIndex })
            assertTrue(queue.shuffled)
            assertEquals(1, queue.queuePos)
            assertEquals(43_210L, queue.lastSongPos)
            assertTrue(queue.queue[1].isLocal)
            assertTrue(queue.queue[0].artists.single().isChannel)
            restored.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            restored.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
            }
            // Backup does not close the running source library or mutate its raw values.
            assertEquals(before, snapshot(fixture.sourceDatabase))
        }
    }

    @Test(timeout = 60_000)
    fun truncatedArchiveWithCompleteLocalEntriesCannotReplaceTargetLibraryOrPreferences() = runBlocking {
        withFixture { fixture ->
            val bytes = fixture.backup().readBytes()
            // createBackupArchive writes a normal ZIP with no comment; use its EOCD offset,
            // not a magic-byte search that could accidentally match compressed database bytes.
            assertEquals(0x06054b50, ByteBuffer.wrap(bytes, bytes.size - 22, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            val centralDirectory = ByteBuffer.wrap(bytes, bytes.size - 6, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val truncated = File(fixture.source.cacheDir, "truncated.backup").apply {
                writeBytes(bytes.copyOf(centralDirectory))
            }
            assertTrue("A backup without its ZIP directory must fail whole-archive validation",
                runCatching { validateBackupArchive(truncated) }.isFailure)
            fixture.assertRejectedRestore(truncated)
        }
    }

    @Test(timeout = 60_000)
    fun archiveMissingDatabaseCannotReplaceTargetPreferencesOrRequestRestart() = runBlocking {
        withFixture { fixture ->
            val incomplete = File(fixture.source.cacheDir, "missing-database.backup")
            ZipOutputStream(incomplete.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_SETTINGS))
                zip.write(fixture.source.settings.readBytes())
                zip.closeEntry()
            }
            assertTrue(runCatching { validateBackupArchive(incomplete) }.isFailure)
            fixture.assertRejectedRestore(incomplete)
        }
    }

    @Test(timeout = 60_000)
    fun invalidSqlitePayloadCannotReplaceTargetLibraryPreferencesOrRequestRestart() = runBlocking {
        withFixture { fixture ->
            val invalidDatabase = File(fixture.source.cacheDir, "invalid.db").apply {
                writeText("This is synthetic data, not a SQLite database.")
            }
            val archive = File(fixture.source.cacheDir, "invalid-database.backup")
            createBackupArchive(fixture.source.settings, invalidDatabase, archive)
            validateBackupArchive(archive) // ZIP completeness alone cannot validate SQLite contents.
            fixture.assertRejectedRestore(archive)
        }
    }

    @Test(timeout = 60_000)
    fun invalidPreferencesPayloadCannotReplaceTargetLibraryPreferencesOrRequestRestart() = runBlocking {
        withFixture { fixture ->
            val invalidSettings = File(fixture.source.cacheDir, "invalid.preferences_pb").apply {
                // An unfinished protobuf varint, rather than an arbitrary but potentially valid
                // Preferences message containing only unknown fields.
                writeBytes(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
            }
            val job = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
                    produceFile = { invalidSettings })
                val failure = runCatching { store.data.first() }.exceptionOrNull()
                assertTrue("The real Preferences serializer must reject this fixture", failure is CorruptionException)
            } finally { job.cancelAndJoin() }

            val snapshot = File(fixture.source.cacheDir, "valid-database-snapshot.db")
            createDatabaseSnapshot(checkNotNull(fixture.sourceDatabase.openHelper.writableDatabase.path), snapshot)
            val archive = File(fixture.source.cacheDir, "invalid-preferences.backup")
            createBackupArchive(invalidSettings, snapshot, archive)
            validateBackupArchive(archive) // Both ZIP entries and the database are otherwise valid.
            fixture.assertRejectedRestore(archive)
        }
    }

    @Test(timeout = 60_000)
    fun settingsStagingFailureKeepsTheOpenTargetDatabaseAndPreviousPreferencesWithoutRestart() = runBlocking {
        withFixture { fixture ->
            val archive = fixture.backup()
            validateBackupArchive(archive)
            // A complete, readable backup reaches staging. The target settings directory permits
            // reads of the old file but rejects creation of the prepared replacement. The target
            // Room instance must remain usable when this preparation fails before publication.
            fixture.assertRejectedRestore(archive, denySettingsPublication = true)
        }
    }

    @Test(timeout = 60_000)
    fun validArchiveAndCurrentRoomSchemaWithOrphanArtistRelationCannotReplaceTheTarget() = runBlocking {
        withFixture { fixture ->
            val probeName = "orphan-artist-${UUID.randomUUID()}.db"
            val snapshot = fixture.source.getDatabasePath(probeName)
            createDatabaseSnapshot(checkNotNull(fixture.sourceDatabase.openHelper.writableDatabase.path), snapshot)
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                copy.setForeignKeyConstraintsEnabled(false)
                copy.execSQL("INSERT INTO song_artist_map (songId, artistId, position) VALUES (?, ?, 1)",
                    arrayOf<Any?>(CHANNEL_SONG, "missing-artist-in-synthetic-backup"))
                assertTrue("Ordinary SQLite integrity must pass despite this relational orphan", copy.isDatabaseIntegrityOk)
                copy.rawQuery("PRAGMA foreign_key_check", null).use {
                    assertTrue("The fixture must contain an actual foreign-key violation", it.moveToFirst())
                    assertEquals("song_artist_map", it.getString(0))
                    assertEquals("artist", it.getString(2))
                }
            }
            val schemaProbe = InternalDatabase.newTestInstance(fixture.source, probeName, allowDestructiveMigration = false)
            try {
                assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, schemaProbe.openHelper.writableDatabase.version)
                assertTrue("The current schema must open successfully without destructive fallback",
                    schemaProbe.openHelper.writableDatabase.isDatabaseIntegrityOk)
                schemaProbe.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                    assertTrue("Room compatibility must not silently repair the orphan", it.moveToFirst())
                }
            } finally { schemaProbe.close() }
            val archive = File(fixture.source.cacheDir, "orphan-artist.backup")
            createBackupArchive(fixture.source.settings, snapshot, archive)
            validateBackupArchive(archive)
            fixture.assertRejectedRestore(archive)
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.initialize()
            test(fixture)
        } finally { fixture.close() }
    }

    internal class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = SandboxContext(context)
        val target = SandboxContext(context)
        val sourceDatabase = InternalDatabase.newTestInstance(source, InternalDatabase.DB_NAME)
        private var targetDatabase = InternalDatabase.newTestInstance(target, InternalDatabase.DB_NAME)

        suspend fun initialize() {
            assertSame(source, source.applicationContext)
            assertSame(target, target.applicationContext)
            assertNotEquals(context.getDatabasePath(InternalDatabase.DB_NAME).canonicalPath,
                source.getDatabasePath(InternalDatabase.DB_NAME).canonicalPath)
            assertNotEquals(context.getDatabasePath(InternalDatabase.DB_NAME).canonicalPath,
                target.getDatabasePath(InternalDatabase.DB_NAME).canonicalPath)
            source.writeSettings("ja", preferOriginal = true)
            target.writeSettings("fr", preferOriginal = false)
            targetDatabase.insert(MediaMetadata(SENTINEL, "Keep the target library", emptyList(), 90, genre = null)) {
                it.copy(inLibrary = SAVED_AT, liked = true, likedDate = SAVED_AT)
            }
            val folder = MediaMetadata(LOCAL_SONG, "Folder title", listOf(MediaMetadata.Artist(null, "Folder tag", isLocal = true)),
                180, genre = null, isLocal = true, localPath = "/synthetic/folder/song.flac")
            val channel = MediaMetadata(CHANNEL_SONG, "Video title", emptyList(), 181, genre = null).withArtistCredit(
                ArtistCredit("Uploader label", listOf(Artist("Uploader label", null,
                    isChannel = true, sourceChannelId = UPLOADER)), ArtistCreditStatus.COMPLETE,
                    "video-channel", "ja", listOf("video-source:MUSIC_VIDEO_TYPE_OMV")))
            val online = MediaMetadata(ONLINE_SONG, "Online title", emptyList(), 182, genre = null).withArtistCredit(
                ArtistCredit("Explicit performer", listOf(Artist("Explicit performer", PERFORMER)),
                    ArtistCreditStatus.COMPLETE, "structured-fixture", "ja"))
            sourceDatabase.awaitTransaction {
                listOf(folder, channel, online).forEach { metadata ->
                    insert(metadata) { it.copy(inLibrary = SAVED_AT, liked = true, likedDate = SAVED_AT,
                        dateDownload = if (metadata.id == ONLINE_SONG) SAVED_AT else null,
                        lyricsOffsetMs = if (metadata.id == CHANNEL_SONG) -400L else 0L) }
                }
                setLocalArtistLink(LocalArtistLink(artistIdsForSong(LOCAL_SONG).single(), PERFORMER,
                    "Chosen performer", "synthetic-image", "folder-choice"))
                setLocalArtistLink(LocalArtistLink(artistIdsForSong(CHANNEL_SONG).single(), PERFORMER,
                    "Chosen performer", "synthetic-image", "channel-choice"))
                upsert(LyricsEntity(CHANNEL_SONG, LYRICS))
                recordMetadataNames(listOf(
                    MetadataNameEntity("SONG", CHANNEL_SONG, "ja", "保存済み動画名", "detail", 100, 10),
                    MetadataNameEntity("SONG", CHANNEL_SONG, "en", "Confirmed original", "detail", 100, 10)))
                recordMetadataOriginalPublications(listOf(MetadataOriginalPublicationEntity("SONG", CHANNEL_SONG,
                    "Confirmed original", "{\"synthetic\":true}", 10)))
            }
            val queue = listOf(CHANNEL_SONG, LOCAL_SONG, ONLINE_SONG).mapIndexed { index, id ->
                sourceDatabase.song(id).first()!!.toMediaMetadata().copy(shuffleIndex = listOf(2, 0, 1)[index])
            }.toMutableList()
            sourceDatabase.saveQueueSnapshot(listOf(MultiQueueObject(41, "Mixed source queue", queue,
                shuffled = true, queuePos = 1, lastSongPos = 43_210, index = 0, playlistId = "synthetic-playlist")))
        }

        suspend fun backup(): File {
            val output = File(source.cacheDir, "roundtrip-${UUID.randomUUID()}.backup")
            val viewModel = BackupRestoreViewModel(source, sourceDatabase)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.backup(Uri.fromFile(output)) }
            withTimeout(15_000) { while (viewModel.isBackingUp.value) delay(20) }
            assertTrue("The ViewModel must finish writing a nonempty archive", output.isFile && output.length() > 0)
            return output
        }

        fun restore(archive: File) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                BackupRestoreViewModel(target, targetDatabase).restore(Uri.fromFile(archive))
            }
        }

        fun reopenTarget(): MusicDatabase {
            targetDatabase.close()
            return InternalDatabase.newTestInstance(target, InternalDatabase.DB_NAME).also { targetDatabase = it }
        }

        suspend fun assertRejectedRestore(archive: File, denySettingsPublication: Boolean = false) {
            val before = snapshot(targetDatabase)
            val settingsBefore = target.settings.readBytes()
            try {
                if (denySettingsPublication) target.makeSettingsDirectoryReadOnly()
                restore(archive)
            } finally {
                // Preferences DataStore may need a lock file even for its subsequent read.
                // Restore permissions before verification and also in final sandbox cleanup.
                if (denySettingsPublication) target.restoreSettingsDirectoryPermissions()
            }
            assertEquals("A rejected restore must leave the original injected Room instance usable",
                "Keep the target library", withTimeout(5_000) { targetDatabase.song(SENTINEL).first() }!!.song.title)
            assertEquals("Rejected backups must preserve the existing target library", before, snapshot(reopenTarget()))
            assertArrayEquals("Rejected backups must preserve existing preferences", settingsBefore, target.settings.readBytes())
            target.assertSettings("fr", preferOriginal = false)
            assertEquals("Rejected backups must not schedule an application restart", 0, target.restartRequests)
        }

        fun close() {
            sourceDatabase.close()
            targetDatabase.close()
            source.cleanup()
            target.cleanup()
        }
    }

    internal class SandboxContext(base: Context) : ContextWrapper(base) {
        private val directory = File(base.cacheDir, "backup-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val files = File(directory, "files").apply { check(mkdirs()) }
        private val cache = File(directory, "cache").apply { check(mkdirs()) }
        private val databases = File(directory, "databases").apply { check(mkdirs()) }
        val settings = File(files, "datastore/${BackupRestoreViewModel.SETTINGS_FILENAME}").also { check(it.parentFile!!.mkdirs()) }
        var restartRequests = 0
            private set

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getDatabasePath(name: String): File {
            check(name == File(name).name)
            return File(databases, name).also {
                check(it.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            }
        }
        override fun stopService(service: Intent): Boolean = false
        override fun startActivity(intent: Intent) {
            restartRequests += 1
            throw RestoreRestartIntercepted()
        }

        suspend fun writeSettings(language: String, preferOriginal: Boolean) {
            val job = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { settings })
                store.edit {
                    it[ContentCountryKey] = "JP"
                    it[ContentLanguageKey] = language
                    it[PreferEnglishOriginalKey] = preferOriginal
                    it[PersistentQueueKey] = true
                }
            } finally { job.cancelAndJoin() }
        }

        suspend fun assertSettings(language: String, preferOriginal: Boolean) {
            val job = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { settings })
                val values = store.data.first()
                assertEquals("JP", values[ContentCountryKey])
                assertEquals(language, values[ContentLanguageKey])
                assertEquals(preferOriginal, values[PreferEnglishOriginalKey])
                assertEquals(true, values[PersistentQueueKey])
            } finally { job.cancelAndJoin() }
        }

        fun makeSettingsDirectoryReadOnly() {
            val parent = settings.parentFile!!
            check(parent.setWritable(false, false)) { "Could not inject a settings publication failure" }
            check(!parent.canWrite()) { "The test process must not bypass directory write permissions" }
            val denied = File(parent, "denied-replacement.preferences_pb")
            assertTrue("The target directory must reject new replacement files",
                runCatching { denied.writeBytes(byteArrayOf(1)) }.isFailure)
            assertFalse(denied.exists())
            assertTrue("The existing preferences must remain readable", settings.canRead())
        }

        fun restoreSettingsDirectoryPermissions() {
            check(settings.parentFile!!.setWritable(true, true)) { "Could not restore sandbox directory permissions" }
        }

        fun cleanup() {
            check(directory.canonicalPath.startsWith(baseContext.cacheDir.canonicalPath + File.separator))
            restoreSettingsDirectoryPermissions()
            directory.deleteRecursively()
        }
    }

    private class RestoreRestartIntercepted : RuntimeException("Test intercepted restart before exitProcess")

    internal companion object {
        const val SENTINEL = "backup-target-sentinel"
        const val CHANNEL_SONG = "backup-channel-song"
        const val LOCAL_SONG = "backup-folder-song"
        const val ONLINE_SONG = "backup-online-song"
        const val UPLOADER = "UCPCiIrrrNJOKvi_5vr3G6PA"
        const val PERFORMER = "UCbrWU0y_rLsEOYgaTX5Y74A"
        const val LYRICS = "[00:01.00] First synthetic line\n[00:02.00] Second synthetic line"
        val SAVED_AT: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0)
        val TABLES = linkedMapOf(
            "song" to "id", "artist" to "id", "song_artist_map" to "songId, position",
            "local_artist_link" to "localArtistId", "lyrics" to "id", "metadata_target" to "kind, targetId",
            "metadata_name" to "kind, targetId, language, name, source", "metadata_original_publication" to "kind, targetId",
            "metadata_display_revision" to "kind, targetId", "queue" to "id", "queue_song_map" to "queueId, `index`")

        fun snapshot(database: MusicDatabase): Map<String, List<List<String?>>> = TABLES.mapValues { (table, order) ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $order").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }
    }
}
