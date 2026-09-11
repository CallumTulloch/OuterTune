package com.dd3boh.outertune.utils

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveTest {
    @Test fun `archive round trips both complete files and rejects truncated or empty output`() {
        val dir = Files.createTempDirectory("backup-test").toFile()
        try {
            val settings = File(dir, "settings").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val db = File(dir, "database").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
            val output = File(dir, "result.backup")
            createBackupArchive(settings, db, output)
            ZipFile(output).use {
                assertArrayEquals(settings.readBytes(), it.getInputStream(it.getEntry(BACKUP_SETTINGS)).readBytes())
                assertArrayEquals(db.readBytes(), it.getInputStream(it.getEntry(BACKUP_DATABASE)).readBytes())
            }
            val bytes = output.readBytes()
            output.writeBytes(bytes.copyOf(bytes.size / 2))
            assertTrue(runCatching { validateBackupArchive(output) }.isFailure)
            output.writeBytes(byteArrayOf())
            assertTrue(runCatching { validateBackupArchive(output) }.isFailure)
            db.delete()
            assertTrue(runCatching { createBackupArchive(settings, db, output) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}
