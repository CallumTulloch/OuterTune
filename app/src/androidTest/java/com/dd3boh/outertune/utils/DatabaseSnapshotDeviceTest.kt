package com.dd3boh.outertune.utils

import android.database.sqlite.SQLiteDatabase
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DatabaseSnapshotDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun backupRunsOffMainThreadAndExportsAValidatedArchive() = runBlocking {
        val directory = File(context.cacheDir, "backup-vm-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "backup-vm-${UUID.randomUUID()}.db"
        val db = InternalDatabase.newTestInstance(context, name)
        try {
            db.insert(MediaMetadata(id = "backup-track", title = "Backup", artists = emptyList(), duration = 1, genre = null))
            val isolatedContext = object : ContextWrapper(context) {
                override fun getFilesDir() = directory
                override fun getCacheDir() = directory
            }
            File(directory, "datastore/${BackupRestoreViewModel.SETTINGS_FILENAME}").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(10, 1, 1))
            }
            val vm = BackupRestoreViewModel(isolatedContext, db)
            val output = File(directory, "export.backup")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.backup(Uri.fromFile(output))
                assertTrue(vm.isBackingUp.value)
                vm.backup(Uri.fromFile(File(directory, "duplicate.backup")))
            }
            // A UI task can run while the IO coroutine owns backup work.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { assertTrue(true) }
            withTimeout(15_000) { while (vm.isBackingUp.value) delay(20) }
            validateBackupArchive(output)
            assertFalse(File(directory, "duplicate.backup").exists())
            assertEquals("Backup", db.song("backup-track").first()!!.song.title)
            assertTrue(directory.listFiles()!!.none { it.isDirectory && it.name.startsWith("backup-") })
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun snapshotIncludesCommittedWalSchemaAndAutoincrementState() {
        val directory = File(context.cacheDir, "snapshot-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(directory, "original.db")
            SQLiteDatabase.openOrCreateDatabase(original, null).use { db ->
                assertTrue(db.enableWriteAheadLogging())
                db.version = 24
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX entry_name ON entries(name)")
                db.execSQL("CREATE VIEW entry_names AS SELECT name FROM entries")
                db.execSQL("INSERT INTO entries(id,name) VALUES(50,'deleted')")
                db.execSQL("DELETE FROM entries")
                db.execSQL("INSERT INTO entries(name) VALUES('committed in WAL')")
                val snapshot = File(directory, "snapshot.db")
                createDatabaseSnapshot(original.absolutePath, snapshot)
                db.execSQL("INSERT INTO entries(name) VALUES('after snapshot')")
                SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                    assertTrue(copy.isDatabaseIntegrityOk)
                    assertEquals(24, copy.version)
                    copy.rawQuery("SELECT name FROM entry_names", null).use {
                        assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals("committed in WAL", it.getString(0))
                    }
                    copy.execSQL("INSERT INTO entries(name) VALUES('snapshot insert')")
                    copy.rawQuery("SELECT max(id) FROM entries", null).use { it.moveToFirst(); assertEquals(52, it.getInt(0)) }
                    assertTrue(runCatching { copy.execSQL("INSERT INTO entries(name) VALUES('snapshot insert')") }.isFailure)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun snapshotWaitsForActiveWriterWithoutLosingCommittedRows() {
        val directory = File(context.cacheDir, "snapshot-writer-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(directory, "original.db")
            SQLiteDatabase.openOrCreateDatabase(original, null).use { db ->
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE entries(value TEXT)")
                db.beginTransactionNonExclusive()
                db.execSQL("INSERT INTO entries VALUES('committing')")
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                val snapshot = File(directory, "snapshot.db")
                val worker = thread {
                    try { createDatabaseSnapshot(original.absolutePath, snapshot) }
                    catch (failure: Throwable) { error.set(failure) }
                    finally { done.countDown() }
                }
                try {
                    assertFalse(done.await(150, TimeUnit.MILLISECONDS))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                assertTrue(done.await(10, TimeUnit.SECONDS)); worker.join()
                error.get()?.let { throw AssertionError("Snapshot failed", it) }
                SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                    copy.rawQuery("SELECT value FROM entries", null).use { assertTrue(it.moveToFirst()); assertEquals("committing", it.getString(0)) }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun snapshotReopensUsingActualRoomSchemaAndKeepsOriginalUsable() = runBlocking {
        val name = "snapshot-room-${UUID.randomUUID()}.db"
        val backupName = "snapshot-copy-${UUID.randomUUID()}.db"
        val db = InternalDatabase.newTestInstance(context, name)
        try {
            db.insert(MediaMetadata(id = "snapshot-track", title = "Snapshot", artists = emptyList(), duration = 123, genre = null))
            val snapshot = context.getDatabasePath(backupName)
            createDatabaseSnapshot(checkNotNull(db.openHelper.writableDatabase.path), snapshot)
            val copy = InternalDatabase.newTestInstance(context, backupName)
            try {
                assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, copy.openHelper.readableDatabase.version)
                assertEquals("Snapshot", copy.song("snapshot-track").first()!!.song.title)
            } finally { copy.close() }
            assertEquals("Snapshot", db.song("snapshot-track").first()!!.song.title)
        } finally {
            db.close()
            context.deleteDatabase(name); context.deleteDatabase(backupName)
        }
    }
}
