package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.*
import org.junit.Assert.*
import org.junit.Test

class MetadataOriginalSnapshotTest {
    private val video = "abcdefghijk"
    private val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist")
    private val album = OriginalNameTarget(OriginalNameKind.ALBUM, "MPREalbum")
    private fun song(source: String = video) = OriginalNameTarget(OriginalNameKind.SONG, source)

    @Test fun `new song-only snapshot withdraws its old artist and album evidence but retains aliases`() {
        val older = listOf(row(song(), "Song", 1), row(artist, "Artist", 1), row(album, "Album", 1))
        val latest = row(song(), "Song", 2, assessed = false, albumId = null)
        val stored = older + latest
        assertEquals(listOf(latest), latestOriginalRows(stored))
        assertTrue(originalAssessmentsByTarget(stored).isEmpty())
        // The filter does not delete historical names from the set available to search.
        assertTrue(stored.any { it.name == "Artist" })
        assertTrue(stored.any { it.name == "Album" })
        assertEquals("設定表記", select(artist, "Artist", originalAssessmentsByTarget(stored)[artist].orEmpty()).name)
    }

    @Test fun `another current source can still support the same artist after one source withdraws it`() {
        val second = "12345678901"
        val stored = listOf(row(song(), "First", 1), row(artist, "Artist", 1),
            row(song(), "First", 3, assessed = false), row(song(second), "Second", 2, source = second),
            row(artist, "Artist", 2, source = second))
        val assessments = originalAssessmentsByTarget(stored).getValue(artist)
        assertEquals(listOf(second), assessments.map { it.sourceVideoId })
        assertEquals(OriginalNameSelectionReason.AUTOMATIC_ENGLISH_ORIGINAL, select(artist, "Artist", assessments).reason)
    }

    @Test fun `replacement names and missing source anchors cannot preserve obsolete assessments`() {
        val old = row(artist, "Older Artist Name", 1)
        assertTrue(latestOriginalRows(listOf(old)).isEmpty())
        val stored = listOf(row(song(), "Song", 1), old, row(song(), "Song", 2), row(artist, "Current Artist Name", 2))
        assertEquals(listOf("Current Artist Name"), originalAssessmentsByTarget(stored).getValue(artist).map { it.originalName })
        assertTrue(stored.any { it.name == "Older Artist Name" })
    }

    @Test fun `conflicting names in the same snapshot survive to conservative policy selection`() {
        val rows = listOf(row(song(), "Song", 1), row(artist, "First Name", 1), row(artist, "Second Name", 1))
        val assessments = originalAssessmentsByTarget(rows).getValue(artist)
        assertEquals(2, assessments.size)
        assertEquals(OriginalNameSelectionReason.CONFLICTING_EVIDENCE, select(artist, "First Name", assessments).reason)
        assertEquals(originalInputKey(latestOriginalRows(rows)), originalInputKey(latestOriginalRows(rows.reversed())))
    }

    private fun row(target: OriginalNameTarget, name: String, observedAt: Long, source: String = video,
        assessed: Boolean = true, albumId: String? = album.id): MetadataNameEntity {
        val original = ArtTrackOriginalName(target, name, source, albumId)
        val assessment = if (!assessed) null else OriginalNameAssessment(target, name, source,
            "https://www.youtube.com/watch?v=$source", OriginalNameLanguage.ENGLISH, 0.99f,
            "album-context-test", "fingerprint-$observedAt", observedAt)
        return MetadataNameEntity(target.kind.name, target.id, "und", name, ORIGINAL_NAME_SOURCE_PREFIX + source,
            observedAt = observedAt, originEvidenceJson = ArtTrackOriginalNameCodec.encode(original, assessment))
    }

    private fun select(target: OriginalNameTarget, english: String, assessments: List<OriginalNameAssessment>) =
        OriginalNamePolicy.select(target, "設定表記", english, "Fallback", true, emptyList(), assessments)
}
