package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.IOException
import java.io.InputStream

data class MainDownloadDeletionResult(
    val deletedMediaIds: Set<String>,
    val failedMediaIds: Set<String>,
)

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    var availableFiles: Set<DocumentFile> = mutableSetOf()

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager: $dir")
        this.context = context
        this.dir = dir
        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            if (extraDirs.isNotEmpty()) {
                newAllDirs.addAll(
                    documentFileFromUri(context, extraDirs.filterNot { it == dir }).filter { it.isDirectory }
                )
            }
            allDirs = newAllDirs.toList()
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
//            reportException(e)
//            Toast.makeText(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG).show()
            // TODO: snackbar for failed uri or not set up?
        }
    }

    fun deleteFile(mediaId: String): Boolean {
        val file = isExists(mediaId)
        return file?.delete() == true
    }

    /**
     * Deletes app-managed files from the configured main download directory only.
     * Extra import directories and unrelated files in the main directory are left untouched.
     */
    fun deleteMainDownloads(): MainDownloadDeletionResult {
        val directory = mainDir
            ?: return MainDownloadDeletionResult(emptySet(), emptySet())
        val files = ArrayList<DocumentFile>()
        scanDfRecursive(directory, files, true)

        val deletedMediaIds = mutableSetOf<String>()
        val failedMediaIds = mutableSetOf<String>()
        files.forEach { file ->
            val mediaId = (file as? TreeDocumentFileOt)?.id
                ?.takeIf { it.isNotBlank() && file.name?.endsWith(".mka", ignoreCase = true) == true }
                ?: return@forEach
            if (file.delete()) {
                deletedMediaIds += mediaId
            } else {
                failedMediaIds += mediaId
            }
        }

        // Keep lookups in sync with the files that still exist, including extra import paths.
        getAvailableFiles(false)
        return MainDownloadDeletionResult(
            deletedMediaIds = deletedMediaIds,
            failedMediaIds = failedMediaIds,
        )
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val resolver = context.contentResolver
        val directory = DocumentFile.fromTreeUri(context, dir)

        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val fileName = "$displayName [$mediaId].mka"
        val newFile = directory.createFile("audio/mka", fileName)

        newFile?.uri?.let { uri ->
            resolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out)
            }
            return uri
        }

        return null
    }

    fun isExists(mediaId: String): DocumentFile? {
        return availableFiles.find { (it as TreeDocumentFileOt).id == mediaId }
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false)
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
        val availableFiles = HashMap<String, Uri>()
        val result = ArrayList<DocumentFile>()
        if (useCache) {
            result.addAll(this.availableFiles.toList())
        } else {
            for (dir in allDirs) {
                scanDfRecursive(dir, result, true)
            }
        }

        for (file in result) {
            val path = file.name ?: continue
            availableFiles.put(path.substringAfterLast('[').substringBeforeLast(']'), file.uri)
        }
        if (!useCache) {
            this.availableFiles = result.toSet()
        }
        return availableFiles
    }

    fun getMainDlStorageUsage(): Long {
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getTotalDlStorageUsage(): Long {
        if (allDirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        availableFiles.sumOf { it.length() }

        return availableFiles.sumOf { it.length() }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }
}
