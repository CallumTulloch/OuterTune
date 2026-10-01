package com.dd3boh.outertune.utils.scanners

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.AlbumSortType
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.SCANNER_OWNER_LM
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.constants.ScannerSensitivityKey
import com.dd3boh.outertune.constants.ScannerStrictExtKey
import com.dd3boh.outertune.constants.ScannerStrictFilePathsKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.SongTempData
import com.dd3boh.outertune.utils.dataStore
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt in with forkScannerRegression=true on a disposable emulator, and run this class alone.
 * The scanner singleton is shared, but every database and private fixture file is isolated.
 * The SAF test additionally requires a granted, initially empty Music/OuterTuneRegression tree
 * passed as forkScannerTreeUri. It restores only the preferences it changed and deletes only
 * the two directories it created. No production database, account, or network is used.
 */
class ForkLocalMediaIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val savedAt = LocalDateTime.of(2026, 10, 1, 12, 0)
    private val releaseA = "42d3f760-8f20-4f8a-96bd-955e680c4374"
    private val releaseB = "7b047c87-bf07-4af6-9e84-2172878de59f"

    private data class Tags(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val releaseId: String,
    )

    @Test(timeout = 90_000)
    fun repeatFileScanRetagsSamePathAndKeepsTrackAndAlbumArtistsSeparateWithoutChangingOnlineLibrary() =
        runBlocking(Dispatchers.IO) {
            withFixture { database, directory, databaseName ->
                val tags = Tags("元の曲名", "曲の出演者", "共同アルバム", "アルバムの出演者", releaseA)
                val audio = createAudio(File(directory, "日本語 #1; %_ title.mp3"), tags)
                val online = SongEntity("fork-streaming-online", "Saved streaming track", inLibrary = savedAt, localPath = null)
                val invalid = SongEntity("LS-fork-missing-path", "Unavailable local track",
                    isLocal = true, inLibrary = savedAt, localPath = null)
                database.insert(online)
                database.insert(invalid)

                scanFiles(database, listOf(audio))
                val original = assertLocalTags(database, audio, tags)
                val originalId = original.id
                val originalAlbumId = original.song.albumId!!
                val originalArtistId = original.artists.single().id
                val albumArtist = database.album(originalAlbumId).first()!!.artists.single()
                assertNotEquals(originalArtistId, albumArtist.id)
                assertEquals(0, database.artist(albumArtist.id).first()!!.songCount)
                assertEquals(listOf(originalArtistId), database.artists(ArtistFilter.FOLDER,
                    ArtistSortType.NAME, false).first().map { it.id })
                assertEquals(listOf(originalId), database.folderSongs(SongSortType.NAME, false).first().map { it.id })
                // Missing paths are disabled locally; a streaming song is allowed to have no path.
                assertNull(database.songForArtistCredit(invalid.id)!!.inLibrary)
                assertEquals(online, database.songForArtistCredit(online.id))

                scanFiles(database, listOf(audio))
                assertEquals(originalId, assertLocalTags(database, audio, tags).id)
                assertEquals(2, database.allLocalDbSongs().size) // Includes the disabled missing-path row.
                assertEquals(listOf(originalAlbumId), database.albums(AlbumFilter.FOLDER,
                    AlbumSortType.NAME, false).first().map { it.id })

                val corrected = Tags("修正された曲名", "修正された曲Artist", "修正されたアルバム",
                    "修正されたAlbum Artist", releaseB)
                retag(audio, corrected)
                scanFiles(database, listOf(audio))
                val updated = assertLocalTags(database, audio, corrected)
                assertEquals(originalId, updated.id)
                assertNotEquals(originalAlbumId, updated.song.albumId)
                assertEquals(listOf(originalId), database.folderSongs(SongSortType.NAME, false).first().map { it.id })
                assertEquals(listOf(updated.song.albumId), database.albums(AlbumFilter.FOLDER,
                    AlbumSortType.NAME, false).first().map { it.id })
                assertNull(database.albumById(originalAlbumId))
                assertNull(database.artistById(originalArtistId))
                assertEquals(online, database.songForArtistCredit(online.id))
                assertNull(database.songForArtistCredit(invalid.id)!!.inLibrary)

                val updatedAlbumArtistId = database.album(updated.song.albumId!!).first()!!.artists.single().id
                database.close()
                val reopened = InternalDatabase.newTestInstance(context, databaseName, allowDestructiveMigration = false)
                try {
                    val persisted = assertLocalTags(reopened, audio, corrected)
                    assertEquals(originalId, persisted.id)
                    assertEquals(updated.song, persisted.song)
                    assertEquals(updated.artists.map { it.id }, persisted.artists.map { it.id })
                    val persistedAlbumArtist = reopened.album(persisted.song.albumId!!).first()!!.artists.single()
                    assertEquals(updatedAlbumArtistId, persistedAlbumArtist.id)
                    assertNotEquals(persisted.artists.single().id, persistedAlbumArtist.id)
                    assertEquals(0, reopened.artist(persistedAlbumArtist.id).first()!!.songCount)
                    assertEquals(online, reopened.songForArtistCredit(online.id))
                    assertNull(reopened.songForArtistCredit(invalid.id)!!.inLibrary)
                    assertNull(reopened.albumById(originalAlbumId))
                    assertNull(reopened.artistById(originalArtistId))

                    scanFiles(reopened, listOf(audio))
                    assertEquals(originalId, assertLocalTags(reopened, audio, corrected).id)
                    assertEquals(setOf(originalId, invalid.id), reopened.allLocalDbSongs().map { it.id }.toSet())
                    assertEquals(2, reopened.allLocalDbSongs().size)
                    assertEquals(listOf(originalId), reopened.folderSongs(SongSortType.NAME, false).first().map { it.id })
                    assertEquals(listOf(updated.song.albumId), reopened.albums(AlbumFilter.FOLDER,
                        AlbumSortType.NAME, false).first().map { it.id })
                    assertEquals(online, reopened.songForArtistCredit(online.id))
                    assertNull(reopened.songForArtistCredit(invalid.id)!!.inLibrary)
                } finally { reopened.close() }
            }
        }

    @Test(timeout = 90_000)
    fun movingARealFileReusesItsUniqueIdentityWhileConflictingAlbumArtistsAndReleasesRemainSeparate() =
        runBlocking(Dispatchers.IO) {
            withFixture { database, directory, _ ->
                val firstTags = Tags("Same title", "Track performer", "Same album", "Album Artist A", releaseA)
                val secondTags = firstTags.copy(albumArtist = "Album Artist B", releaseId = releaseB)
                val first = createAudio(File(directory, "release-a/shared.mp3"), firstTags)
                val second = createAudio(File(directory, "release-b/shared.mp3"), secondTags)
                scanFiles(database, listOf(first, second))
                val firstSaved = assertLocalTags(database, first, firstTags)
                val secondSaved = assertLocalTags(database, second, secondTags)
                assertNotEquals(firstSaved.id, secondSaved.id)
                assertNotEquals(firstSaved.song.albumId, secondSaved.song.albumId)
                assertEquals(2, database.allLocalDbSongs().size)

                val moved = File(directory, "moved/日本語 #1; %_ shared.mp3")
                assertTrue(moved.parentFile!!.mkdirs())
                assertTrue(first.renameTo(moved))
                assertFalse(first.exists())
                scanFiles(database, listOf(moved, second))
                val movedSaved = assertLocalTags(database, moved, firstTags)
                val unchanged = assertLocalTags(database, second, secondTags)
                assertEquals(firstSaved.id, movedSaved.id)
                assertEquals(firstSaved.song.albumId, movedSaved.song.albumId)
                assertEquals(secondSaved.id, unchanged.id)
                assertEquals(secondSaved.song.albumId, unchanged.song.albumId)
                assertEquals(setOf(firstSaved.id, secondSaved.id), database.allLocalDbSongs().map { it.id }.toSet())
                assertEquals(2, database.albums(AlbumFilter.FOLDER, AlbumSortType.NAME, false).first().size)
                scanFiles(database, listOf(moved, second))
                assertEquals(2, database.allLocalDbSongs().size)
                assertEquals(firstSaved.id, assertLocalTags(database, moved, firstTags).id)
            }
        }

    @Test(timeout = 90_000)
    fun offWaitsForExtractedScannerWorkRejectsQueuedWorkAndPurgesOnlyImportedDataBeforeFreshReimport() =
        runBlocking(Dispatchers.IO) {
            withFixture { database, directory, _ ->
                val tags = Tags("Imported song", "Track performer", "Imported album", "Album performer", releaseA)
                val audio = createAudio(File(directory, "imported.mp3"), tags)
                scanFiles(database, listOf(audio))
                val imported = assertLocalTags(database, audio, tags)
                database.upsert(LyricsEntity(imported.id, "[00:00.00]Local fixture"))
                database.insert(PlayCountEntity(imported.id, 2026, 10, 4))
                database.insert(Event(songId = imported.id, timestamp = savedAt, playTime = 1_000))
                val online = SongEntity("fork-purge-online", "Online survivor", inLibrary = savedAt,
                    liked = true, likedDate = savedAt, localPath = null)
                val download = SongEntity("fork-purge-download", "Downloaded survivor", dateDownload = savedAt,
                    localPath = "/fixture/online-download.mka")
                listOf(online, download).forEach { song ->
                    database.insert(song)
                    database.upsert(LyricsEntity(song.id, "[00:00.00]Online fixture"))
                    database.upsert(FormatEntity(song.id, 140, "audio/mp4", "mp4a", 128_000, 44_100,
                        contentLength = 12_345))
                    database.insert(PlayCountEntity(song.id, 2026, 10, 7))
                    database.insert(Event(songId = song.id, timestamp = savedAt, playTime = 2_000))
                }
                val playlist = PlaylistEntity("LP-fork-purge", "Mixed fixture", isLocal = true, bookmarkedAt = savedAt)
                database.insert(playlist)
                listOf(imported.id, online.id, download.id).forEachIndexed { index, id ->
                    database.insert(PlaylistSongMap(playlistId = playlist.id, songId = id, position = index))
                }
                val lateAudio = createAudio(File(directory, "late.mp3"), tags.copy(title = "Late extracted song"))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val queuedBodyEntered = AtomicBoolean(false)
                coroutineScope {
                    val active = async {
                        runCatching {
                            LocalMediaScanner.withScannerOperation(SCANNER_OWNER_LM) {
                                val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_LM)
                                val extracted = scanner.advancedScan(lateAudio)
                                entered.complete(Unit)
                                release.await()
                                scanner.syncDB(database, arrayListOf(extracted), ScannerMatchCriteria.LEVEL_3,
                                    strictFileNames = false, strictFilePaths = false, refreshExisting = true)
                            }
                        }.exceptionOrNull()
                    }
                    withTimeout(15_000) { entered.await() }
                    // UNDISPATCHED reaches the real operation mutex before OFF changes its generation.
                    val queued = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            LocalMediaScanner.withScannerOperation(SCANNER_OWNER_LM) {
                                queuedBodyEntered.set(true)
                                error("Queued scanner work entered after OFF")
                            }
                        }.exceptionOrNull()
                    }
                    val off = async(start = CoroutineStart.UNDISPATCHED) {
                        LocalMediaLifecycle.removeImportedMedia(database, null)
                    }
                    try {
                        assertEquals(LocalMediaLifecycleState.REMOVING, LocalMediaLifecycle.state.value)
                        assertFalse(off.isCompleted)
                        release.complete(Unit)
                        withTimeout(20_000) {
                            assertTrue(active.await() is ScannerAbortException)
                            assertTrue(queued.await() is ScannerAbortException)
                            off.await()
                        }
                    } finally {
                        release.complete(Unit)
                        active.cancelAndJoin()
                        queued.cancelAndJoin()
                        off.cancelAndJoin()
                    }
                }
                assertFalse(queuedBodyEntered.get())
                assertEquals(LocalMediaLifecycleState.IDLE, LocalMediaLifecycle.state.value)
                assertFalse(database.hasLocalSongs())
                assertNull(database.songForArtistCredit(imported.id))
                assertNull(database.lyrics(imported.id).first())
                assertNull(database.format(imported.id).first())
                assertEquals(0, database.getLifetimePlayCount(imported.id))
                assertEquals(setOf(online.id, download.id), database.events().first().map { it.event.songId }.toSet())
                assertEquals(setOf(online.id, download.id), database.playlistSongs(playlist.id).first().map { it.song.id }.toSet())
                assertTrue(audio.isFile)
                assertTrue(lateAudio.isFile)
                listOf(online, download).forEach { song ->
                    assertEquals(song, database.songForArtistCredit(song.id))
                    assertEquals("[00:00.00]Online fixture", database.lyrics(song.id).first()!!.lyrics)
                    assertEquals(12_345L, database.format(song.id).first()!!.contentLength)
                    assertEquals(7, database.getLifetimePlayCount(song.id))
                }
                val enteredWhileOff = AtomicBoolean(false)
                val rejection = runCatching {
                    LocalMediaScanner.withScannerOperation(SCANNER_OWNER_LM) { enteredWhileOff.set(true) }
                }.exceptionOrNull()
                assertTrue(rejection is ScannerAbortException)
                assertFalse(enteredWhileOff.get())
                LocalMediaLifecycle.removeImportedMedia(database, null) // OFF is idempotent.
                assertFalse(database.hasLocalSongs())

                LocalMediaScanner.resumeScannerOperations()
                scanFiles(database, listOf(audio))
                val reimported = assertLocalTags(database, audio, tags)
                assertNotEquals(imported.id, reimported.id)
                assertNull(database.lyrics(reimported.id).first())
                assertEquals(0, database.getLifetimePlayCount(reimported.id))
                assertEquals(online, database.songForArtistCredit(online.id))
                assertEquals(download, database.songForArtistCredit(download.id))
                assertEquals(listOf(reimported.id), database.folderSongs(SongSortType.NAME, false).first().map { it.id })
            }
        }

    @Test(timeout = 90_000)
    fun savedSafTreeImportHonorsExclusionsAndRealOffOnUsesFreshRowsWithoutDeletingFilesOrOnlineState() =
        runBlocking(Dispatchers.IO) {
            requireOptIn()
            val supplied = InstrumentationRegistry.getArguments().getString("forkScannerTreeUri")
            assumeTrue("Grant an empty Music/OuterTuneRegression tree and pass forkScannerTreeUri", !supplied.isNullOrBlank())
            val treeUri = Uri.parse(supplied)
            assertEquals("com.android.externalstorage.documents", treeUri.authority)
            assertEquals("primary:Music/OuterTuneRegression", DocumentsContract.getTreeDocumentId(treeUri))
            val tree = DocumentFile.fromTreeUri(context, treeUri)!!
            assertTrue("The dedicated tree must have a read/write picker grant", tree.canRead() && tree.canWrite())
            assertTrue("Only an empty disposable fixture tree may be scanned", tree.listFiles().isEmpty())
            val originalPreferences = context.dataStore.data.first()
            val created = ArrayList<DocumentFile>()
            try {
                withFixture { database, _, _ ->
                    val included = tree.createDirectory("included")!!.also(created::add)
                    val excluded = tree.createDirectory("excluded")!!.also(created::add)
                    val tags = Tags("Kept SAF song", "SAF Track Artist", "SAF Album", "SAF Album Artist", releaseA)
                    val kept = createSafAudio(included, "kept.mp3", tags)
                    val ignored = createSafAudio(excluded, "ignored.mp3", tags.copy(title = "Excluded SAF song"))
                    val keptPath = fileFromUri(context, kept.uri)!!.absolutePath
                    val ignoredPath = fileFromUri(context, ignored.uri)!!.absolutePath
                    val online = SongEntity("fork-saf-online", "Streaming online survivor", inLibrary = savedAt, localPath = null)
                    database.insert(online)
                    context.dataStore.edit {
                        it[AutomaticScannerKey] = false
                        it[LocalLibraryEnableKey] = true
                        it[ScannerImplKey] = ScannerImpl.TAGLIB.toString()
                        it[ScannerSensitivityKey] = ScannerMatchCriteria.LEVEL_3.toString()
                        it[ScannerStrictExtKey] = false
                        it[ScannerStrictFilePathsKey] = false
                        it[ScanPathsKey] = treeUri.toString()
                        it[ExcludedScanPathsKey] = excluded.uri.toString()
                        it[LastLocalScanKey] = 0L
                    }
                    LocalMediaLifecycle.importSavedMedia(context, database, null)
                    val first = assertLocalTags(database, File(keptPath), tags)
                    assertEquals(listOf(keptPath), database.allLocalSongs().map { it.song.localPath })
                    assertFalse(database.allLocalDbSongs().any { it.song.localPath == ignoredPath })
                    assertTrue(context.dataStore.data.first()[LastLocalScanKey]!! > 0)
                    assertEquals(online, database.songForArtistCredit(online.id))
                    LocalMediaLifecycle.importSavedMedia(context, database, null)
                    assertEquals(first.id, assertLocalTags(database, File(keptPath), tags).id)
                    database.update(database.songForArtistCredit(first.id)!!.copy(liked = true, lyricsOffsetMs = 500))
                    database.upsert(LyricsEntity(first.id, "[00:00.00]Old local lyrics"))
                    context.dataStore.edit { it[LocalLibraryEnableKey] = false }
                    LocalMediaLifecycle.removeImportedMedia(database, null)
                    assertFalse(database.hasLocalSongs())
                    assertTrue(kept.exists())
                    assertTrue(ignored.exists())
                    assertEquals(online, database.songForArtistCredit(online.id))
                    assertNull(database.lyrics(first.id).first())
                    val rejected = runCatching {
                        LocalMediaLifecycle.importSavedMedia(context, database, null)
                    }.exceptionOrNull()
                    assertTrue(rejected is ScannerAbortException)
                    assertFalse(database.hasLocalSongs())
                    assertEquals(LocalMediaLifecycleState.IDLE, LocalMediaLifecycle.state.value)

                    context.dataStore.edit { it[LocalLibraryEnableKey] = true }
                    LocalMediaLifecycle.importSavedMedia(context, database, null)
                    val fresh = assertLocalTags(database, File(keptPath), tags)
                    assertNotEquals(first.id, fresh.id)
                    assertFalse(fresh.song.liked)
                    assertEquals(0L, fresh.song.lyricsOffsetMs)
                    assertEquals(listOf(fresh.id), database.folderSongs(SongSortType.NAME, false).first().map { it.id })
                    assertFalse(database.allLocalDbSongs().any { it.song.localPath == ignoredPath })
                    assertEquals(online, database.songForArtistCredit(online.id))
                    assertEquals(treeUri.toString(), context.dataStore.data.first()[ScanPathsKey])
                    assertEquals(excluded.uri.toString(), context.dataStore.data.first()[ExcludedScanPathsKey])
                }
            } finally {
                withContext(NonCancellable) {
                    context.dataStore.edit {
                        it.restore(originalPreferences, AutomaticScannerKey)
                        it.restore(originalPreferences, LocalLibraryEnableKey)
                        it.restore(originalPreferences, ScannerImplKey)
                        it.restore(originalPreferences, ScannerSensitivityKey)
                        it.restore(originalPreferences, ScannerStrictExtKey)
                        it.restore(originalPreferences, ScannerStrictFilePathsKey)
                        it.restore(originalPreferences, ScanPathsKey)
                        it.restore(originalPreferences, ExcludedScanPathsKey)
                        it.restore(originalPreferences, LastLocalScanKey)
                    }
                    if (originalPreferences[LocalLibraryEnableKey] != false) LocalMediaScanner.resumeScannerOperations()
                    else LocalMediaScanner.requestScannerShutdown()
                    created.forEach { assertTrue("Could not remove the synthetic fixture directory", it.delete()) }
                }
            }
        }

    private fun requireOptIn() {
        assumeTrue("Pass forkScannerRegression=true and run this class alone on a disposable emulator",
            InstrumentationRegistry.getArguments().getString("forkScannerRegression") == "true")
        assumeTrue("Global scanner tests are limited to an Android emulator",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertTrue("Run in an idle fresh app process", LocalMediaScanner.scannerState.value <= 0)
        assertEquals(LocalMediaLifecycleState.IDLE, LocalMediaLifecycle.state.value)
    }

    private suspend fun withFixture(block: suspend (MusicDatabase, File, String) -> Unit) {
        requireOptIn()
        val wasEnabled = context.dataStore.data.first()[LocalLibraryEnableKey] != false
        val fixtureId = UUID.randomUUID()
        val databaseName = "fork-regression-scanner-$fixtureId.db"
        check(!context.getDatabasePath(databaseName).exists())
        val database = InternalDatabase.newTestInstance(context, databaseName, allowDestructiveMigration = false)
        val directory = File(context.cacheDir, "fork-regression-scanner-$fixtureId")
        assertTrue(directory.mkdirs())
        try {
            LocalMediaScanner.resumeScannerOperations()
            block(database, directory, databaseName)
        } finally {
            withContext(NonCancellable) {
                LocalMediaScanner.cancelScannerAndAwaitIdle()
                if (wasEnabled) LocalMediaScanner.resumeScannerOperations()
                database.close()
                assertTrue(context.deleteDatabase(databaseName))
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                assertTrue(directory.deleteRecursively())
            }
        }
    }

    private suspend fun scanFiles(database: MusicDatabase, files: List<File>) {
        LocalMediaScanner.withScannerOperation(SCANNER_OWNER_LM) {
            val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_LM)
            val extracted = ArrayList<SongTempData>()
            files.forEach { extracted.add(scanner.advancedScan(it)) }
            scanner.syncDB(database, extracted, ScannerMatchCriteria.LEVEL_3,
                strictFileNames = false, strictFilePaths = false, refreshExisting = true)
        }
    }

    private suspend fun assertLocalTags(database: MusicDatabase, audio: File, tags: Tags): Song {
        val saved = database.allLocalDbSongs().single { it.song.localPath == audio.absolutePath }
        assertTrue(saved.song.isLocal)
        assertNotNull(saved.song.inLibrary)
        assertEquals(tags.title, saved.song.title)
        assertEquals(listOf(tags.artist), saved.artists.map { it.name })
        assertTrue(saved.artists.all { it.isLocal })
        assertEquals(1, saved.song.trackNumber!!)
        assertEquals(1, saved.song.discNumber!!)
        assertEquals(audio.absolutePath, saved.song.thumbnailUrl)
        val album = database.album(saved.song.albumId!!).first()!!
        assertTrue(album.album.isLocal)
        assertEquals(tags.album, album.album.title)
        assertEquals(tags.releaseId, album.album.musicBrainzId)
        assertEquals(listOf(tags.albumArtist), album.artists.map { it.name })
        assertTrue(album.artists.all { it.isLocal })
        assertEquals(listOf(saved.id), database.albumSongs(album.id).first().map { it.id })
        assertTrue(database.format(saved.id).first()!!.sampleRate!! > 0)
        return saved
    }

    private fun createAudio(audio: File, tags: Tags): File {
        audio.parentFile!!.mkdirs()
        instrumentation.context.assets.open("local-artwork/checkerboard.mp3").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        retag(audio, tags)
        return audio
    }

    private fun retag(audio: File, tags: Tags) {
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_WRITE).use { saveTags(it, tags) }
    }

    private fun createSafAudio(directory: DocumentFile, name: String, tags: Tags): DocumentFile {
        val audio = directory.createFile("audio/mpeg", name)!!
        instrumentation.context.assets.open("local-artwork/checkerboard.mp3").use { input ->
            checkNotNull(context.contentResolver.openOutputStream(audio.uri, "wt")).use { input.copyTo(it) }
        }
        checkNotNull(context.contentResolver.openFileDescriptor(audio.uri, "rw")).use { saveTags(it, tags) }
        return audio
    }

    private fun saveTags(fd: ParcelFileDescriptor, tags: Tags) {
        val properties: PropertyMap = TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)!!.propertyMap.apply {
            this["TITLE"] = arrayOf(tags.title)
            this["ARTIST"] = arrayOf(tags.artist)
            this["ALBUM"] = arrayOf(tags.album)
            this["ALBUMARTIST"] = arrayOf(tags.albumArtist)
            this["MUSICBRAINZ_ALBUMID"] = arrayOf(tags.releaseId)
            this["TRACKNUMBER"] = arrayOf("1")
            this["DISCNUMBER"] = arrayOf("1")
            this["GENRE"] = arrayOf("Fixture genre")
        }
        assertTrue("Native TagLib must write the real audio fixture", TagLib.savePropertyMap(fd.dup().detachFd(), properties))
        val written = TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)!!.propertyMap
        assertEquals(tags.title, written["TITLE"]!!.single())
        assertEquals(tags.albumArtist, written["ALBUMARTIST"]!!.single())
        assertEquals(tags.releaseId, written["MUSICBRAINZ_ALBUMID"]!!.single())
    }

    private fun <T> MutablePreferences.restore(previous: Preferences, key: Preferences.Key<T>) {
        previous[key]?.let { this[key] = it } ?: remove(key)
    }
}
