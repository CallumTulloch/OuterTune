package com.dd3boh.outertune.repositories

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MetadataDisplayIncrementalTest {
    @Test(timeout = 120_000)
    fun oneChangedTargetAmongTwentyFourThousandCandidatesNeverRepeatsTheFullJoin(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val targets = (0 until 4_000).map { song("display-$it") }
            f.database.recordMetadataNames(targets.flatMap { target ->
                listOf("ja", "en", "fr").flatMap { language ->
                    listOf(name(target, language, "$language ${target.id}", priority = 2),
                        name(target, language, "$language alternate ${target.id}", source = "search", priority = 1))
                }
            })
            val reader = MetadataDisplayReader(f.database)
            val initial = requireNotNull(reader.read(JAPANESE, false))
            assertEquals(4_000, initial.names.size)
            assertEquals(24_000, initial.aliases.values.sumOf { it.size })
            assertEquals(1, f.fullReads())
            val changed = targets.first()
            val untouched = targets.last()

            repeat(8) { index ->
                val manualName = "Changed display $index"
                f.database.recordMetadataNames(listOf(name(changed, "ja", manualName,
                    source = "manual", at = 200L + index)))
                val next = requireNotNull(reader.read(JAPANESE, false))
                assertEquals(manualName, next.names[changed])
                assertTrue(next.aliases.getValue(changed).contains(manualName))
                assertEquals(initial.names[untouched], next.names[untouched])
                assertSame("Untouched alias lists must be retained", initial.aliases[untouched], next.aliases[untouched])
            }
            assertEquals(1, f.fullReads())
            assertEquals(8, f.targetReads().size)
            assertTrue(f.targetReads().all { it.kind == "SONG" && it.ids == listOf(changed.id) })

            // A new proof with the same visible observation must not dirty the display projection.
            val proofOnly = name(changed, "en", "en ${changed.id}", priority = 2)
                .copy(originEvidenceJson = "{\"diagnostic\":true}")
            f.database.recordMetadataNames(listOf(proofOnly))
            assertNull(reader.read(JAPANESE, false))
            assertEquals(8, f.targetReads().size)
            assertEquals(1, f.fullReads())
        }
    }

    @Test(timeout = 45_000)
    fun publicationBeforeCandidatesAndAtomicReplacementRemainVisibleWithoutAFullReload(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val target = song("publication-first")
            f.database.recordMetadataOriginalPublications(listOf(publication(target, "Committed English")))
            val reader = MetadataDisplayReader(f.database)
            assertTrue(requireNotNull(reader.read(JAPANESE, true)).names.isEmpty())

            f.database.recordMetadataNames(listOf(name(target, "ja", "設定名"), name(target, "en", "Observed English")))
            assertEquals("Committed English", requireNotNull(reader.read(JAPANESE, true)).names[target])
            f.database.recordMetadataOriginalPublications(listOf(publication(target, null, 200)))
            assertEquals("設定名", requireNotNull(reader.read(JAPANESE, true)).names[target])

            f.database.awaitTransaction {
                recordMetadataOriginalPublications(listOf(publication(target, "Replacement English", 300)))
                recordMetadataNames(listOf(name(target, "en", "Replacement English", at = 300)))
            }
            val replacement = requireNotNull(reader.read(JAPANESE, true))
            assertEquals("Replacement English", replacement.names[target])
            assertTrue(replacement.aliases.getValue(target).contains("Replacement English"))
            val reads = f.targetReads().size
            f.database.recordMetadataOriginalPublications(listOf(publication(target, "Replacement English", 400)
                .copy(evidenceJson = "{\"updatedProof\":true}")))
            assertNull(reader.read(JAPANESE, true))
            assertEquals(reads, f.targetReads().size)
            assertEquals(1, f.fullReads())
        }
    }

    @Test(timeout = 45_000)
    fun deletionAndReinsertionCannotHideBehindAReusedRevisionOrASecondKindWithTheSameId(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val target = song("same-id")
            val artist = OriginalNameTarget(OriginalNameKind.ARTIST, target.id)
            f.database.recordMetadataNames(listOf(name(target, "ja", "Before"), name(artist, "ja", "Artist")))
            val reader = MetadataDisplayReader(f.database)
            val initial = requireNotNull(reader.read(JAPANESE, false))
            f.database.deleteMetadataTarget("SONG", target.id)
            val deleted = requireNotNull(reader.read(JAPANESE, false))
            assertFalse(deleted.names.containsKey(target))
            assertFalse(deleted.aliases.containsKey(target))
            assertEquals("Artist", deleted.names[artist])
            assertSame(initial.aliases[artist], deleted.aliases[artist])

            f.database.recordMetadataNames(listOf(name(target, "ja", "Reinserted")))
            assertEquals("Reinserted", requireNotNull(reader.read(JAPANESE, false)).names[target])
            // No read between removal and reinsertion: a recreated revision of 1 would lose this.
            f.database.awaitTransaction {
                deleteMetadataTarget("SONG", target.id)
                recordMetadataNames(listOf(name(target, "ja", "Replaced between reads")))
            }
            assertEquals("Replaced between reads", requireNotNull(reader.read(JAPANESE, false)).names[target])
            assertNull(reader.read(JAPANESE, false))
            assertEquals(1, f.fullReads())
            assertTrue(f.targetReads().all { it.kind == "SONG" && it.ids == listOf(target.id) })
        }
    }

    @Test(timeout = 45_000)
    fun settingChangesReselectAllNamesWhileIncrementalUpdatesPreserveOtherLanguageAliasesAndManualPriority(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val manual = song("manual-name")
            val target = song("configured-name")
            f.database.recordMetadataNames(listOf(manual, target).flatMap {
                listOf(name(it, "ja", "日本語"), name(it, "en", "English"), name(it, "fr", "Francais"))
            } + name(manual, "ja", "Manual", source = "manual"))
            f.database.recordMetadataOriginalPublications(listOf(publication(target, "Committed English")))
            val reader = MetadataDisplayReader(f.database)
            val japanese = requireNotNull(reader.read(JAPANESE, false))
            assertEquals("日本語", japanese.names[target])
            assertEquals("Manual", japanese.names[manual])
            assertNull(reader.read(JAPANESE, false))
            assertEquals(1, f.fullReads())

            val frenchLocale = YouTubeLocale("FR", "fr")
            val french = requireNotNull(reader.read(frenchLocale, false))
            assertEquals("Francais", french.names[target])
            assertEquals("Manual", french.names[manual])
            val original = requireNotNull(reader.read(frenchLocale, true))
            assertEquals("Committed English", original.names[target])
            assertEquals("Manual", original.names[manual])
            assertEquals(3, f.fullReads())

            f.database.recordMetadataNames(listOf(name(target, "fr", "Nouvel alias", at = 200)))
            val delta = requireNotNull(reader.read(frenchLocale, true))
            assertEquals("Committed English", delta.names[target])
            assertTrue(delta.aliases.getValue(target).containsAll(listOf("日本語", "English", "Francais", "Nouvel alias")))
            assertSame(original.aliases[manual], delta.aliases[manual])
            assertEquals(3, f.fullReads())
            assertEquals(1, f.targetReads().size)
        }
    }

    @Test(timeout = 45_000)
    fun discardedInitialAndSettingsFramesCanBeRepublishedWithTheSameOptionsAndUnchangedRevisions(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val target = song("discarded-frame")
            f.database.recordMetadataNames(listOf(name(target, "ja", "日本語"), name(target, "fr", "Francais")))
            f.database.recordMetadataOriginalPublications(listOf(publication(target, "Committed English")))
            val revisions = f.database.metadataDisplayRevisionSnapshot()
            val reader = MetadataDisplayReader(f.database)
            val french = YouTubeLocale("FR", "fr")

            listOf(Triple(JAPANESE, false, "日本語"), Triple(french, false, "Francais"),
                Triple(french, true, "Committed English")).forEach { (locale, preferOriginal, expected) ->
                val discarded = requireNotNull(reader.read(locale, preferOriginal))
                assertEquals(expected, discarded.names[target])
                // Main rejects this frame after locale A changes to B. B then changes back to A
                // before the conflated collector runs again, so neither options nor DB revisions
                // can reveal that the UI never received its initial/settings publication.
                reader.invalidate()
                val replayed = reader.read(locale, preferOriginal)
                assertNotNull("A discarded frame must be published again without another DB write", replayed)
                assertEquals(discarded, replayed)
                assertEquals(revisions, f.database.metadataDisplayRevisionSnapshot())
                assertNull("Once delivered, unchanged frames remain suppressed", reader.read(locale, preferOriginal))
            }
            assertEquals(6, f.fullReads())
            assertTrue(f.targetReads().isEmpty())
        }
    }

    @Test(timeout = 45_000)
    fun nonemptyRevisionTableStillSignalsEveryLaterDisplayChange(): Unit = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val target = song("flow-signal")
            f.database.recordMetadataNames(listOf(name(target, "ja", "Initial")))
            val reader = MetadataDisplayReader(f.database)
            val publications = Channel<MetadataDisplayReader.Publication>(Channel.UNLIMITED)
            val collector = launch {
                f.database.metadataDisplayChanges().collect {
                    reader.read(JAPANESE, false)?.let { publications.send(it) }
                }
            }
            try {
                assertEquals("Initial", withTimeout(10_000) { publications.receive() }.names[target])
                repeat(3) { index ->
                    val value = "Flow change $index"
                    f.database.recordMetadataNames(listOf(name(target, "ja", value, source = "manual", at = 200L + index)))
                    assertEquals(value, withTimeout(10_000) { publications.receive() }.names[target])
                }
                assertEquals(1, f.fullReads())
                assertEquals(3, f.targetReads().size)
            } finally {
                collector.cancelAndJoin()
                publications.close()
            }
        }
    }

    /** Opt-in measurement of a supplied standalone backup. Room migrates a private test copy;
     * the source file and the application's song.db are never opened for writing.
     */
    @Test(timeout = 120_000)
    fun suppliedBackupMigratesAndReadsOneChangedTargetWithoutReloadingTheCache(): Unit = runBlocking(Dispatchers.IO) {
        val path = InstrumentationRegistry.getArguments().getString("metadataDisplayDatabasePath")
        assumeTrue("Optional standalone backup path was not supplied", !path.isNullOrBlank())
        val source = File(requireNotNull(path)).canonicalFile
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(source.isFile && source != context.getDatabasePath("song.db").canonicalFile)
        require(!File(source.path + "-wal").let { it.exists() && it.length() > 0 }) { "Provide a standalone backup without a live WAL" }
        val originalDigest = digest(source)
        try {
            withFixture(source) { f ->
                val start = SystemClock.elapsedRealtime()
                val reader = MetadataDisplayReader(f.database)
                val initial = requireNotNull(reader.read(JAPANESE, true))
                val firstReadMs = SystemClock.elapsedRealtime() - start
                assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION.toLong(), f.scalar("PRAGMA user_version"))
                val saved = f.scalar("SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL")
                val metadataRows = f.scalar("SELECT COUNT(*) FROM metadata_name")
                val target = initial.aliases.keys.firstOrNull() ?: error("Backup contains no observed metadata")
                f.database.recordMetadataNames(listOf(name(target, "ja", "Incremental diagnostic display",
                    source = "manual", priority = Int.MAX_VALUE, at = System.currentTimeMillis())))
                val changedAt = SystemClock.elapsedRealtime()
                val changed = requireNotNull(reader.read(JAPANESE, true))
                val deltaReadMs = SystemClock.elapsedRealtime() - changedAt
                assertEquals("Incremental diagnostic display", changed.names[target])
                assertEquals(saved, f.scalar("SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL"))
                assertEquals(1, f.fullReads())
                assertEquals(listOf(DisplayRead(target.kind.name, listOf(target.id))), f.targetReads())
                val changedRows = f.database.metadataNames(target.kind.name, target.id).size
                Log.i(TAG, "copiedBackup savedSongs=$saved metadataRows=$metadataRows changedTargetRows=$changedRows " +
                    "initialIncludingMigrationMs=$firstReadMs deltaReadMs=$deltaReadMs fullJoins=${f.fullReads()} targetJoins=${f.targetReads().size}")
            }
        } finally {
            assertArrayEquals("The supplied backup must remain unchanged", originalDigest, digest(source))
        }
    }

    private suspend fun withFixture(source: File? = null, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(source)
        try { block(fixture) } finally { fixture.close() }
    }

    private data class DisplayRead(val kind: String?, val ids: List<String>)

    private class Fixture(source: File?) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val filename = "metadata-display-incremental-${UUID.randomUUID()}.db"
        private val reads = Collections.synchronizedList(mutableListOf<DisplayRead>())
        private val whitespace = Regex("\\s+")
        private val room = Room.databaseBuilder(context, InternalDatabase::class.java, filename)
            .apply { source?.let { createFromFile(it) } }
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    val sql = sqlQuery.replace(whitespace, " ").trim().lowercase(Locale.ROOT)
                    if (!sql.startsWith("select n.kind, n.targetid") || !sql.contains("left join metadata_original_publication")) return
                    val bounded = sql.contains("where n.kind =") && sql.contains("n.targetid in (")
                    reads += if (bounded) DisplayRead(bindArgs.first().toString(), bindArgs.drop(1).map { it.toString() })
                    else DisplayRead(null, emptyList())
                }
            }, Executor { it.run() })
            .build()
        val database = MusicDatabase(room)

        fun fullReads(): Int = synchronized(reads) { reads.count { it.kind == null } }
        fun targetReads(): List<DisplayRead> = synchronized(reads) { reads.filter { it.kind != null } }
        fun scalar(sql: String): Long = database.openHelper.readableDatabase.query(sql).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        fun close() {
            database.close()
            context.deleteDatabase(filename)
        }
    }

    private fun song(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
    private fun name(target: OriginalNameTarget, language: String, value: String, source: String = "detail",
        priority: Int = 0, at: Long = 100) =
        MetadataNameEntity(target.kind.name, target.id, language, value, source, priority, at)
    private fun publication(target: OriginalNameTarget, english: String?, at: Long = 100) =
        MetadataOriginalPublicationEntity(target.kind.name, target.id, english, "{}", at)

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    companion object {
        private const val TAG = "MetadataDisplayTest"
        private val JAPANESE = YouTubeLocale("JP", "ja")
    }
}
