package com.dd3boh.outertune.utils

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Copy one committed SQLite state, including WAL data, without closing the running Room DB.
 * Uses SQL available on all supported Android versions (VACUUM INTO requires newer SQLite).
 * The separate connection holds the writer lock only while copying into the attached snapshot.
 */
internal fun createDatabaseSnapshot(sourcePath: String, destination: File) {
    require(!destination.exists()) { "Snapshot destination already exists" }
    destination.parentFile?.mkdirs()
    try {
        // ATTACH inherits the main connection's open flags; create the private file explicitly.
        check(destination.createNewFile())
        SQLiteDatabase.openDatabase(sourcePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { source ->
            source.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
            source.execSQL("ATTACH DATABASE ? AS backup_snapshot", arrayOf(destination.absolutePath))
            try {
                source.beginTransactionNonExclusive()
                try {
                    data class Definition(val type: String, val name: String, val sql: String)
                    val definitions = source.rawQuery(
                        "SELECT type, name, sql FROM main.sqlite_master " +
                            "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                            "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 ELSE 2 END", null,
                    ).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(Definition(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
                    }
                    fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
                    definitions.forEach { definition ->
                        require(definition.type in setOf("table", "index", "trigger", "view"))
                        val prefix = Regex("(?i)^CREATE\\s+(?:UNIQUE\\s+)?${definition.type}\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?")
                        val match = requireNotNull(prefix.find(definition.sql)) { "Unsupported database schema" }
                        // Qualify the created object; all its unqualified table references resolve in the attached DB.
                        source.execSQL(definition.sql.substring(0, match.range.last + 1) +
                            "backup_snapshot." + definition.sql.substring(match.range.last + 1))
                        if (definition.type == "table") source.execSQL(
                            "INSERT INTO backup_snapshot.${quote(definition.name)} SELECT * FROM main.${quote(definition.name)}",
                        )
                    }
                    // Preserve AUTOINCREMENT high-water marks, including deleted rows.
                    val hasSequence = source.rawQuery("SELECT 1 FROM main.sqlite_master WHERE name='sqlite_sequence'", null)
                        .use { it.moveToFirst() }
                    if (hasSequence) {
                        source.execSQL("DELETE FROM backup_snapshot.sqlite_sequence")
                        source.execSQL("INSERT INTO backup_snapshot.sqlite_sequence SELECT * FROM main.sqlite_sequence")
                    }
                    source.execSQL("PRAGMA backup_snapshot.user_version=${source.version}")
                    source.setTransactionSuccessful()
                } finally { source.endTransaction() }
            } finally { source.execSQL("DETACH DATABASE backup_snapshot") }
        }
        SQLiteDatabase.openDatabase(destination.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            check(it.isDatabaseIntegrityOk) { "Database snapshot failed validation" }
        }
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}
