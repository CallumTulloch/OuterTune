package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.createDatabaseSnapshot
import com.dd3boh.outertune.utils.createBackupArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream
import java.io.File
import java.util.UUID
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
            context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.zipInputStream().use { inputStream ->
                    var entry = inputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            SETTINGS_FILENAME -> {
                                (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream()
                                    .use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                            }

                            InternalDatabase.DB_NAME -> {
                                Log.i(TAG, "Starting database restore")
                                runBlocking(Dispatchers.IO) {
                                    database.checkpoint()
                                }
                                database.close()

                                Log.i(TAG, "Testing new database for compatibility...")
                                val destFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                destFile.parentFile?.apply {
                                    if (!exists()) mkdirs()
                                }
                                FileOutputStream(destFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }

                                val status = try {
                                    val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                    t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                    t.close()
                                    true
                                } catch (e: Exception) {
                                    Log.e(TAG, "DB validation failed", e)
                                    false
                                }

                                if (status) {
                                    Log.i(TAG, "Found valid database, proceeding with restore")
                                    destFile.inputStream().use { inputStream ->
                                        FileOutputStream(database.openHelper.writableDatabase.path).use { outputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Incompatible database, aborting restore")
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.err_restore_incompatible_database),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }

            val stopIntent = Intent(context, MusicService::class.java)
            context.stopService(stopIntent)
            val startIntent = Intent(context, MainActivity::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(startIntent)
            exitProcess(0)
        }.onFailure {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}
