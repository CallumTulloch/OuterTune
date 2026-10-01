package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.createBackupArchive
import com.dd3boh.outertune.utils.createDatabaseSnapshot
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.validateBackupArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    val TAG = BackupRestoreViewModel::class.simpleName.toString()
    val isBackingUp = MutableStateFlow(false)
    fun backup(uri: Uri) {
        if (isBackingUp.value) return
        isBackingUp.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val staging = File(context.cacheDir, "backup-${UUID.randomUUID()}")
                    check(staging.mkdirs())
                    try {
                        val snapshot = File(staging, InternalDatabase.DB_NAME)
                        createDatabaseSnapshot(checkNotNull(database.openHelper.writableDatabase.path), snapshot)
                        val archive = File(staging, "complete.backup")
                        createBackupArchive(context.filesDir / "datastore" / SETTINGS_FILENAME, snapshot, archive)
                        // Finish and validate privately before touching the user's chosen output.
                        val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                            "Could not open backup destination"
                        }
                        output.buffered().use { destination ->
                            val written = archive.inputStream().use { it.copyTo(destination) }
                            check(written == archive.length()) { "Incomplete backup output" }
                        }
                    } finally {
                        staging.deleteRecursively()
                    }
                }
                Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportException(error)
                Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
            } finally {
                isBackingUp.value = false
            }
        }
    }

    fun restore(uri: Uri) {
        if (isBackingUp.value) return
        runCatching {
            runBlocking(Dispatchers.IO) { restoreValidatedBackup(uri) }

            restartApplication()
        }.onFailure {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            // Publication already closed Room. Reopen the recovered library in a fresh process.
            if (it is RestorePublicationFailure && it.recovered) restartApplication()
        }
    }

    private fun restartApplication() {
        context.stopService(Intent(context, MusicService::class.java))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        exitProcess(0)
    }

    private suspend fun restoreValidatedBackup(uri: Uri) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}")
        check(staging.mkdirs())
        val probeName = "restore-probe-${UUID.randomUUID()}.db"
        val probeFile = context.getDatabasePath(probeName)
        var preparedDatabase: File? = null
        var preparedSettings: File? = null
        var preserveRecoveryFiles = false
        try {
            val archive = File(staging, "input.backup")
            checkNotNull(context.applicationContext.contentResolver.openInputStream(uri)) {
                "Could not open backup"
            }.use { input -> archive.outputStream().use { input.copyTo(it) } }
            validateBackupArchive(archive)
            val settings = File(staging, SETTINGS_FILENAME)
            probeFile.parentFile?.mkdirs()
            ZipFile(archive).use { zip ->
                for ((name, file) in listOf(SETTINGS_FILENAME to settings, InternalDatabase.DB_NAME to probeFile)) {
                    zip.getInputStream(zip.getEntry(name)).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
            }

            // Parse privately; never replace corrupt preferences with defaults during validation.
            val settingsJob = SupervisorJob()
            try {
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(settingsJob + Dispatchers.IO),
                    produceFile = { settings },
                ).data.first()
            } finally { settingsJob.cancelAndJoin() }

            SQLiteDatabase.openDatabase(probeFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
                check(it.isDatabaseIntegrityOk) { "Backup database failed integrity validation" }
            }
            val validatedDatabase = File(staging, "validated.db")
            val probe = InternalDatabase.newTestInstance(context, probeName, allowDestructiveMigration = false)
            try {
                check(probe.openHelper.writableDatabase.isDatabaseIntegrityOk) { "Backup database is incompatible" }
                probe.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                    check(!it.moveToFirst()) { "Backup database has invalid relationships" }
                }
                createDatabaseSnapshot(checkNotNull(probe.openHelper.writableDatabase.path), validatedDatabase)
            } finally { probe.close() }

            // Capture the path while Room is open; accessing openHelper after close would reopen it.
            val destination = File(checkNotNull(database.openHelper.writableDatabase.path))
            val destinationSettings = context.filesDir / "datastore" / SETTINGS_FILENAME
            val previousDatabase = File(staging, "previous.db")
            val previousSettings = File(staging, "previous.preferences_pb")
            // Prepare both replacements before closing or changing the running library.
            val databaseReplacement = prepareReplacement(validatedDatabase, destination).also { preparedDatabase = it }
            val settingsReplacement = prepareReplacement(settings, destinationSettings).also { preparedSettings = it }
            createDatabaseSnapshot(destination.path, previousDatabase)
            val hadSettings = destinationSettings.exists()
            if (hadSettings) destinationSettings.copyTo(previousSettings)
            database.close()
            try {
                for (suffix in listOf("-wal", "-shm")) {
                    val sidecar = File(destination.path + suffix)
                    check(!sidecar.exists() || sidecar.delete()) { "Could not retire database journal" }
                }
            } catch (error: Exception) {
                // No replacement has been published; the old library only needs Room reopened.
                throw RestorePublicationFailure(error, recovered = true)
            }
            try {
                publishReplacement(databaseReplacement, destination)
                publishReplacement(settingsReplacement, destinationSettings)
            } catch (error: Exception) {
                var recovered = false
                try {
                    replaceFile(previousDatabase, destination)
                    if (hadSettings) replaceFile(previousSettings, destinationSettings)
                    else check(!destinationSettings.exists() || destinationSettings.delete())
                    recovered = true
                } catch (rollback: Exception) {
                    preserveRecoveryFiles = true
                    error.addSuppressed(rollback)
                    Log.e(TAG, "Original files retained for recovery in ${staging.path}", error)
                }
                throw RestorePublicationFailure(error, recovered)
            }
            Log.i(TAG, "Validated backup restored")
        } finally {
            preparedDatabase?.delete()
            preparedSettings?.delete()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(probeFile.path + suffix).delete()
            if (!preserveRecoveryFiles) staging.deleteRecursively()
        }
    }

    private fun replaceFile(source: File, destination: File) {
        val temporary = prepareReplacement(source, destination)
        try { publishReplacement(temporary, destination) }
        finally { temporary.delete() }
    }

    private fun prepareReplacement(source: File, destination: File): File {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.restore")
        try {
            source.copyTo(temporary)
            return temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun publishReplacement(temporary: File, destination: File) {
        Os.rename(temporary.path, destination.path)
    }

    private class RestorePublicationFailure(cause: Exception, val recovered: Boolean) :
        RuntimeException("Could not restore backup", cause)

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}
