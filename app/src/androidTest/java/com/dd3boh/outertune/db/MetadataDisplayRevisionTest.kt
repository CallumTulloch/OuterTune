package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.daos.MetadataDisplayName
import com.dd3boh.outertune.db.entities.MetadataDisplayRevision
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room storage and invalidation, including a cache larger than the saved music library. */
class MetadataDisplayRevisionTest {
    private fun withDatabase(block: suspend (MusicDatabase, AtomicInteger) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fullDisplayReads = AtomicInteger()
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.contains("FROM metadata_name n") &&
                        sqlQuery.contains("LEFT JOIN metadata_original_publication") && !sqlQuery.contains("WHERE n.kind")) {
                        fullDisplayReads.incrementAndGet()
                    }
                }
            }, Executor { it.run() })
            .build()
        try {
            block(MusicDatabase(internal), fullDisplayReads)
        } finally {
            internal.close()
        }
    }

    private fun name(id: String, language: String = "ja", text: String = "Localized $id") =
        MetadataNameEntity("SONG", id, language, text, "detail", sourcePriority = 100, observedAt = 10)

    private fun publication(id: String, englishName: String?, proof: String = "{}", at: Long = 10) =
        MetadataOriginalPublicationEntity("SONG", id, englishName, proof, at)

    private fun MusicDatabase.revisions() = metadataDisplayRevisionSnapshot()
        .associate { MetadataTargetEntity(it.kind, it.targetId) to it.revision }

    @Test(timeout = 60_000)
    fun oneTargetChangesInATwentyFourThousandNameCacheWithoutAnotherFullDisplayRead() =
        withDatabase { database, fullDisplayReads ->
            val initial = (0 until 8_000).flatMap { index ->
                listOf("ja", "en", "fr").map { language -> name("track-$index", language, "$language title $index") }
            }
            database.recordMetadataNames(initial)
            assertEquals(24_000, database.metadataDisplayNameSnapshot().size)
            val before = database.revisions()
            assertEquals(8_000, before.size)
            assertTrue(before.values.all { it == 1L })
            assertEquals(1, fullDisplayReads.get())

            database.recordMetadataNames(listOf(name("track-17", "ja", "新しい表示名").copy(observedAt = 20)))
            val after = database.revisions()
            val changed = after.filter { (target, revision) -> before[target] != revision }.keys
            assertEquals(setOf(MetadataTargetEntity("SONG", "track-17")), changed)
            val delta = database.metadataDisplayNamesForTargets("SONG", changed.map { it.targetId })
            assertEquals(4, delta.size)
            assertEquals(setOf("track-17"), delta.map { it.name.targetId }.toSet())
            assertTrue(delta.all { it.name.originEvidenceJson == null })
            assertEquals(1, fullDisplayReads.get())
        }

    @Test
    fun repeatedOrWeakerNamesAndFetchStateDoNotAdvanceTheDisplayRevision() = withDatabase { database, _ ->
        val original = name("track").copy(originEvidenceJson = "{\"proof\":1}")
        database.recordMetadataNames(listOf(original))
        val before = database.revisions()
        database.recordMetadataNames(List(50) { original })
        database.recordMetadataNames(listOf(original.copy(sourcePriority = 20, observedAt = 100)))
        database.recordMetadataNames(listOf(original.copy(observedAt = 1)))
        database.recordMetadataFetch(MetadataFetchEntity("SONG", original.targetId, "ja", MetadataFetchEntity.SUCCESS))
        assertEquals(before, database.revisions())
        assertEquals(original, database.metadataNames("SONG", original.targetId).single())

        database.recordMetadataNames(listOf(original.copy(observedAt = 11)))
        assertEquals(2L, database.revisions().getValue(MetadataTargetEntity("SONG", "track")))
        database.recordMetadataNames(listOf(original.copy(sourcePriority = 200, observedAt = 2)))
        assertEquals(3L, database.revisions().getValue(MetadataTargetEntity("SONG", "track")))
        assertEquals(2L, database.metadataNames("SONG", "track").single().observedAt)
    }

    @Test
    fun evidenceCanChangeAtTheSameTimestampWithoutReloadingDisplayUntilItsDecisionChanges() =
        withDatabase { database, _ ->
            val original = name("track").copy(originEvidenceJson = "{\"proof\":1}")
            database.recordMetadataNames(listOf(original))
            database.recordMetadataOriginalPublications(listOf(publication("track", "First original")))
            val before = database.revisions()
            val projection = database.metadataDisplayNameSnapshot()

            val newProof = "{\"proof\":2}"
            database.recordMetadataNames(listOf(original.copy(originEvidenceJson = newProof)))
            database.recordMetadataOriginalPublications(listOf(publication("track", "First original", newProof)))
            assertEquals(newProof, database.metadataNames("SONG", "track").single().originEvidenceJson)
            assertEquals(newProof, database.metadataOriginalPublication("SONG", "track")!!.evidenceJson)
            assertEquals(before, database.revisions())
            assertEquals(projection, database.metadataDisplayNamesForTargets("SONG", listOf("track")))

            database.recordMetadataOriginalPublications(listOf(publication("track", "Corrected original", newProof)))
            val target = MetadataTargetEntity("SONG", "track")
            assertEquals(before.getValue(target) + 1, database.revisions().getValue(target))
            assertEquals("Corrected original", database.metadataDisplayNamesForTargets("SONG", listOf("track")).single().englishName)
            database.recordMetadataOriginalPublications(listOf(publication("track", null, newProof)))
            assertEquals(before.getValue(target) + 2, database.revisions().getValue(target))
            assertNull(database.metadataDisplayNamesForTargets("SONG", listOf("track")).single().englishName)
        }

    @Test
    fun booleanInvalidationsPublishNamesAndTheirDecisionAsOneCommittedSnapshot() = withDatabase { database, _ ->
        database.recordMetadataNames(listOf(name("track")))
        database.recordMetadataOriginalPublications(listOf(publication("track", "Previous original")))
        val before = database.revisions().getValue(MetadataTargetEntity("SONG", "track"))
        coroutineScope {
            val initialObserved = CompletableDeferred<Unit>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    database.metadataDisplayChanges().map {
                        lateinit var snapshot: DisplaySnapshot
                        database.awaitTransaction {
                            snapshot = DisplaySnapshot(metadataDisplayRevisionSnapshot(), metadataDisplayNameSnapshot())
                        }
                        snapshot
                    }.onEach { initialObserved.complete(Unit) }
                        .first { it.revisions.single().revision > before }
                }
            }
            withTimeout(5_000) { initialObserved.await() }
            database.awaitTransaction {
                recordMetadataNames(listOf(name("track", text = "新しい曲名")))
                recordMetadataOriginalPublications(listOf(publication("track", "New original")))
            }
            val snapshot = result.await()
            assertEquals(2, snapshot.names.size)
            assertTrue(snapshot.names.any { it.name.name == "新しい曲名" })
            assertEquals(setOf("New original"), snapshot.names.map { it.englishName }.toSet())
        }

        val committedRevisions = database.revisions()
        val committedNames = database.metadataDisplayNameSnapshot()
        val failure = runCatching {
            database.awaitTransaction {
                recordMetadataNames(listOf(name("track", text = "Rolled back")))
                recordMetadataOriginalPublications(listOf(publication("track", "Rolled back")))
                error("Rollback fixture")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(committedRevisions, database.revisions())
        assertEquals(committedNames, database.metadataDisplayNameSnapshot())
    }

    @Test
    fun deletionLeavesATombstoneAndSameTargetReinsertionCannotReuseAnOldRevision() = withDatabase { database, _ ->
        val original = name("same-id")
        database.insert(SongEntity("same-id", "Saved song", localPath = null,
            inLibrary = LocalDateTime.of(2026, 9, 30, 0, 0)))
        database.recordMetadataNames(listOf(original, original.copy(kind = "ALBUM")))
        database.recordMetadataOriginalPublications(listOf(publication("same-id", "Original")))
        val songTarget = MetadataTargetEntity("SONG", "same-id")
        val albumTarget = MetadataTargetEntity("ALBUM", "same-id")
        val before = database.revisions()
        database.deleteMetadataTarget("SONG", "same-id")
        assertEquals(before.getValue(songTarget) + 1, database.revisions().getValue(songTarget))
        assertEquals(before.getValue(albumTarget), database.revisions().getValue(albumTarget))
        assertTrue(database.metadataDisplayNamesForTargets("SONG", listOf("same-id")).isEmpty())
        assertNull(database.metadataOriginalPublication("SONG", "same-id"))
        assertEquals("Saved song", database.song("same-id").first()!!.title)
        database.deleteMetadataTarget("SONG", "same-id")
        assertEquals(before.getValue(songTarget) + 1, database.revisions().getValue(songTarget))

        database.recordMetadataNames(listOf(original))
        assertEquals(before.getValue(songTarget) + 2, database.revisions().getValue(songTarget))
        assertEquals(original, database.metadataDisplayNamesForTargets("SONG", listOf("same-id")).single().name)
        val reinserted = database.revisions().getValue(songTarget)
        database.awaitTransaction {
            deleteMetadataTarget("SONG", "same-id")
            recordMetadataNames(listOf(original))
        }
        assertEquals(reinserted + 2, database.revisions().getValue(songTarget))
    }

    @Test
    fun directCandidateAndPublicationWritersAlsoMaintainTheJournal() = withDatabase { database, _ ->
        val candidate = name("direct")
        val target = MetadataTargetEntity("SONG", "direct")
        database.insertMetadataTargets(listOf(target))
        assertTrue(database.insertMetadataNameCandidate(candidate) != -1L)
        assertEquals(1L, database.revisions().getValue(target))
        assertEquals(-1L, database.insertMetadataNameCandidate(candidate))
        assertEquals(1L, database.revisions().getValue(target))
        database.mergeMetadataNameCandidate(candidate.kind, candidate.targetId, candidate.language, candidate.name,
            candidate.source, candidate.sourcePriority, 20, null)
        assertEquals(2L, database.revisions().getValue(target))
        database.upsertMetadataOriginalPublications(listOf(publication("direct", "Original")))
        assertEquals(3L, database.revisions().getValue(target))
        database.upsertMetadataOriginalPublications(listOf(publication("direct", "Original", at = 30)))
        assertEquals(3L, database.revisions().getValue(target))
    }

    @Test
    fun removingASourceInvalidatesOnlyItsTargetsAndKeepsOtherAliases() = withDatabase { database, _ ->
        val sourceName = name("track").copy(source = "art-track-original:source")
        val albumName = sourceName.copy(kind = "ALBUM", targetId = "album")
        val retained = name("track", "en", "Retained alias")
        val unrelated = name("unrelated")
        database.recordMetadataNames(listOf(sourceName, albumName, retained, unrelated))
        val before = database.revisions()
        database.deleteMetadataNamesForSource(sourceName.source)
        val after = database.revisions()
        assertEquals(setOf(MetadataTargetEntity("SONG", "track"), MetadataTargetEntity("ALBUM", "album")),
            after.filter { (target, revision) -> before[target] != revision }.keys)
        assertEquals(listOf(retained), database.metadataNames("SONG", "track"))
        assertTrue(database.metadataDisplayNamesForTargets("ALBUM", listOf("album")).isEmpty())
        assertEquals(listOf(unrelated), database.metadataNames("SONG", "unrelated"))
        database.deleteMetadataNamesForSource(sourceName.source)
        assertEquals(after, database.revisions())
    }

    @Test
    fun freshDatabaseKeepsNinetyFiveSavedSongsAndReadsCacheWithAnEmptyJournalAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "metadata-display-reopen-${UUID.randomUUID()}.db"
        fun open() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database: MusicDatabase? = open()
        try {
            val created = requireNotNull(database)
            created.awaitTransaction {
                repeat(95) { index ->
                    insert(SongEntity("saved-$index", "Saved $index", localPath = null,
                        inLibrary = LocalDateTime.of(2026, 9, 30, 0, 0)))
                }
                recordMetadataNames(listOf(name("saved-0")))
                recordMetadataOriginalPublications(listOf(publication("saved-0", "Preserved original")))
            }
            val originalDisplay = created.metadataDisplayNameSnapshot()
            // A first display read must load the saved cache even without revision entries.
            // Keep the current schema intact; old-version migration is outside this test's scope.
            created.openHelper.writableDatabase.execSQL("DELETE FROM metadata_display_revision")
            created.close()
            database = null
            val reopened = open().also { database = it }
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, reopened.openHelper.readableDatabase.version)
            reopened.openHelper.readableDatabase.query("SELECT id FROM song WHERE inLibrary IS NOT NULL ORDER BY id").use {
                val savedIds = mutableSetOf<String>()
                while (it.moveToNext()) savedIds += it.getString(0)
                assertEquals((0 until 95).map { index -> "saved-$index" }.toSet(), savedIds)
            }
            assertTrue(reopened.metadataDisplayRevisionSnapshot().isEmpty())
            assertFalse(reopened.metadataDisplayChanges().first())
            assertEquals(originalDisplay, reopened.metadataDisplayNameSnapshot())
            reopened.recordMetadataNames(listOf(name("saved-0", text = "New alias")))
            assertEquals(1, reopened.metadataDisplayRevisionSnapshot().size)
            assertEquals(2, reopened.metadataDisplayNamesForTargets("SONG", listOf("saved-0")).size)
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private data class DisplaySnapshot(
        val revisions: List<MetadataDisplayRevision>,
        val names: List<MetadataDisplayName>,
    )
}
