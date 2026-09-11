package com.dd3boh.outertune.utils

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val BACKUP_SETTINGS = "settings.preferences_pb"
internal const val BACKUP_DATABASE = "song.db"

internal fun createBackupArchive(settings: File, snapshot: File, output: File) {
    ZipOutputStream(output.outputStream().buffered()).use { archive ->
        for ((name, file) in listOf(BACKUP_SETTINGS to settings, BACKUP_DATABASE to snapshot)) {
            check(file.isFile && file.length() > 0) { "Backup source is missing: $name" }
            archive.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(archive) }
            archive.closeEntry()
        }
    }
    validateBackupArchive(output)
}

/** Validate every entry before restore or before copying a staged archive to a document provider. */
internal fun validateBackupArchive(file: File) {
    ZipFile(file).use { archive ->
        val entries = archive.entries().toList()
        check(entries.map { it.name }.toSet() == setOf(BACKUP_SETTINGS, BACKUP_DATABASE) && entries.size == 2) {
            "Backup must contain settings and database exactly once"
        }
        for (entry in entries) {
            check(!entry.isDirectory && entry.size > 0) { "Backup entry is empty" }
            val crc = CRC32()
            var size = 0L
            archive.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                    size += count
                }
            }
            check(size == entry.size && crc.value == entry.crc) { "Backup entry is incomplete" }
        }
    }
}
