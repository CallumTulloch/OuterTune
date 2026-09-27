package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageDetector
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageScore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalAssessmentRetentionTest {
    private val source = "abcdefghijk"
    private val second = "12345678901"
    private val albumId = "MPREalbum"
    private val song = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, source), "Original title", source, albumId)
    private val artist = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist"), "Original artist", source, albumId)
    private val album = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ALBUM, albumId), "Original album", source, albumId)
    private val other = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, second), "Other song", second, albumId)
    private val candidates = listOf(song, artist, album, other)

    @Test
    fun `identical full refresh keeps valid assessments and their original evaluation time`() {
        val previous = assessed(candidates)
        val incoming = listOf(album, song, artist).map { row(it, 200) }
        val retained = retainOriginalAssessments(previous.reversed(), source, incoming)
        assertEquals(incoming.map { it.name }, retained.map { it.name })
        assertTrue(retained.all { it.observedAt == 200L })
        retained.forEach { updated ->
            val earlier = previous.single { originalCandidate(it) == originalCandidate(updated) }
            assertEquals(earlier.originEvidenceJson, updated.originEvidenceJson)
            assertEquals(100L, assessment(updated)!!.evaluatedAt)
            assertTrue(hasCurrentOriginalAssessmentInputs(updated, retained + previous.filter { it.source.endsWith(second) }))
        }
    }

    @Test
    fun `changed name album link source identity and omitted relationships never inherit assessments`() {
        val previous = assessed(candidates)
        val changedSnapshots = listOf(
            listOf(song.copy(name = "Replacement title"), artist, album),
            listOf(song, artist.copy(name = "Replacement artist"), album),
            listOf(song.copy(albumId = "other-album"), artist, album),
            listOf(song.copy(albumId = null), artist.copy(albumId = null)),
            listOf(song, album),
            listOf(song, artist),
            listOf(song),
            emptyList(),
        )
        changedSnapshots.forEach { snapshot ->
            assertEquals(snapshot.map { it.name }, retainOriginalAssessments(previous, source,
                snapshot.map { row(it, 200) }).also(::assertUnassessed).map { it.name })
        }
        val newSource = "zyxwvutsrqp"
        val moved = listOf(song.copy(target = OriginalNameTarget(OriginalNameKind.SONG, newSource), sourceVideoId = newSource),
            artist.copy(sourceVideoId = newSource), album.copy(sourceVideoId = newSource))
        assertUnassessed(retainOriginalAssessments(previous, newSource, moved.map { row(it, 200) }))
    }

    @Test
    fun `unchanged row does not inherit an assessment made before its album context changed`() {
        val evaluated = assessed(candidates)
        val changes = listOf(
            listOf(row(other.copy(name = "Changed context"), 150)),
            emptyList(),
            listOf(row(other.copy(albumId = null), 150)),
        )
        changes.forEach { otherRows ->
            val latest = evaluated.filter { !it.source.endsWith(second) } + otherRows
            assertUnassessed(retainOriginalAssessments(latest, source, listOf(song, artist, album).map { row(it, 200) }))
            assertFalse(hasCurrentOriginalAssessmentInputs(evaluated.first(), latest))
        }
        val third = "09876543210"
        val added = row(other.copy(target = OriginalNameTarget(OriginalNameKind.SONG, third), sourceVideoId = third), 150)
        assertUnassessed(retainOriginalAssessments(evaluated + added, source,
            listOf(song, artist, album).map { row(it, 200) }))
    }

    @Test
    fun `legacy malformed and obsolete model evidence must be evaluated again`() {
        val valid = assessed(listOf(song)).single()
        val legacy = row(song).copy(originEvidenceJson = ArtTrackOriginalNameCodec.encode(song, assessmentFor(song)))
        val invalidPayloads = listOf(
            legacy.originEvidenceJson,
            "not-json",
            valid.originEvidenceJson!!.replace("\"resolverVersion\":2", "\"resolverVersion\":1"),
            valid.originEvidenceJson!!.replace(OriginalAlbumLanguageResolver.METHOD_VERSION, "obsolete-model"),
            valid.originEvidenceJson!!.replace("\"version\":3,\"model\"", "\"version\":2,\"model\""),
            valid.originEvidenceJson!!.replace("\"version\":3,\"model\"", "\"version\":999,\"model\""),
            valid.originEvidenceJson!!.replace("\"fingerprint\":\"", "\"fingerprint\":\"altered-"),
        )
        invalidPayloads.forEach { payload ->
            val previous = listOf(valid.copy(originEvidenceJson = payload))
            assertFalse(hasCurrentOriginalAssessmentInputs(previous.single(), previous))
            assertUnassessed(retainOriginalAssessments(previous, source, listOf(row(song, 200))))
        }
        assertFalse(hasCurrentOriginalAssessmentInputs(legacy, listOf(legacy)))
        assertTrue(hasCurrentOriginalAssessmentInputs(valid, listOf(valid)))
    }

    @Test
    fun `older mixed or foreign source observations cannot reuse a complete snapshot`() {
        val previous = assessed(candidates)
        assertUnassessed(retainOriginalAssessments(previous, source,
            listOf(song, artist, album).map { row(it, 99) }))
        assertUnassessed(retainOriginalAssessments(previous, source,
            listOf(row(song, 200), row(artist, 199), row(album, 200))))
        assertUnassessed(retainOriginalAssessments(previous, source,
            listOf(song, artist, album, other).map { row(it, 200) }))
    }

    @Test
    fun `historical aliases are not current evidence and are never revived by a returning relation`() {
        val historical = assessed(candidates).map { it.copy(observedAt = 1) }
        val currentSong = song.copy(albumId = null)
        val current = assessed(listOf(currentSong, other))
        val previous = historical + current
        val retained = retainOriginalAssessments(previous, source, listOf(row(currentSong, 200)))
        assertTrue(hasCurrentOriginalAssessmentInputs(retained.single(), retained + current.filter { it.source.endsWith(second) }))
        assertUnassessed(retainOriginalAssessments(previous, source, listOf(song, artist, album).map { row(it, 200) }))
        assertEquals(historical + current, previous)
    }

    @Test
    fun `observation times and input order do not alter the semantic input fingerprint`() {
        val previous = assessed(candidates)
        val observedAgain = previous.reversed().map { it.copy(observedAt = 999) }
        assertTrue(previous.all { hasCurrentOriginalAssessmentInputs(it, observedAgain) })
        assertEquals(encodeOriginalAssessment(song, assessmentFor(song), previous),
            encodeOriginalAssessment(song, assessmentFor(song), observedAgain))
    }

    @Test
    fun `adding and removing an unrelated album preserves assessed names and complete source refresh`() {
        val previous = assessed(candidates)
        val unrelated = unrelatedSongs(3)
        val expanded = previous + unrelated.map { row(it, 150) }
        val inputs = originalAssessmentInputs(expanded)

        assertTrue(previous.all { hasCurrentOriginalAssessmentInputs(it, inputs) })
        assertEquals(unrelated.toSet(), inputs.withAlbumContext(
            expanded.filterNot { hasCurrentOriginalAssessmentInputs(it, inputs) }.mapNotNull(::originalCandidate),
        ).toSet())
        val retained = retainOriginalAssessments(expanded, source, listOf(song, artist, album).map { row(it, 200) })
        assertEquals(previous.filter { it.source.endsWith(source) }.map { it.originEvidenceJson }.toSet(),
            retained.map { it.originEvidenceJson }.toSet())

        val assessedTogether = assessed(candidates + unrelated)
        assertTrue(assessedTogether.filter { it.targetId !in unrelated.map { name -> name.target.id } }
            .all { hasCurrentOriginalAssessmentInputs(it, previous) })
    }

    @Test
    fun `shared artist conflict preserves independent model work but blocks publication until assessed`() {
        val previous = assessed(candidates)
        val outsideSong = unrelatedSongs(1).single()
        val outsideArtist = artist.copy(name = "Another original artist", sourceVideoId = outsideSong.sourceVideoId,
            albumId = outsideSong.albumId)
        val expanded = previous + listOf(outsideSong, outsideArtist).map { row(it, 150) }
        val inputs = originalAssessmentInputs(expanded)

        previous.forEach { original ->
            assertTrue(hasCurrentOriginalAssessmentInputs(original, inputs))
        }
        assertTrue(prepareOriginalPublications(expanded, emptyList(), 200).none { it.kind == "ARTIST" })
        val together = assessed(candidates + outsideSong + outsideArtist)
        assertNull(prepareOriginalPublications(together, emptyList(), 200).single { it.kind == "ARTIST" }.englishName)
        val remaining = together.filterNot { it.source.endsWith(outsideSong.sourceVideoId) }
        remaining.forEach { original ->
            assertTrue(hasCurrentOriginalAssessmentInputs(original, remaining))
        }
    }

    @Test
    fun `stale artist receives all its album songs without unrelated albums or other artist candidates`() = runBlocking {
        val thirdId = "09876543210"
        val third = other.copy(target = OriginalNameTarget(OriginalNameKind.SONG, thirdId), sourceVideoId = thirdId)
        val all = candidates + third + unrelatedSongs(3)
        val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector { text ->
            listOf(OriginalTextLanguageScore("en", if ('\n' in text) 0.99f else 0.1f))
        })
        val rows = all.map { row(it) }
        val inputs = originalAssessmentInputs(rows)
        val subset = inputs.withAlbumContext(listOf(artist))
        assertEquals(setOf(song, other, third, artist), subset.toSet())
        val full = resolver.assess(all, 200).single { it.target == artist.target }
        val partial = resolver.assess(subset, 200).single { it.target == artist.target }
        assertEquals(OriginalNameLanguage.ENGLISH, full.language)
        assertEquals(full, partial)
    }

    @Test
    fun `incremental classification retains full conflict foreign and source withdrawal semantics`() = runBlocking {
        val songs = (0..2).map { index ->
            val id = "scope${index.toString().padStart(6, '0')}"
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), "Short $index", id, albumId)
        }
        val artist = this@OriginalAssessmentRetentionTest.artist.copy(sourceVideoId = songs.first().sourceVideoId)
        val detector = OriginalTextLanguageDetector { text ->
            listOf(OriginalTextLanguageScore(if (text.equals("Alors on danse", true)) "fr" else "en",
                if ('\n' in text || text.equals("Alors on danse", true)) 0.99f else 0.1f))
        }
        val resolver = OriginalAlbumLanguageResolver(detector)
        val before = songs + artist + unrelatedSongs(3)
        val previousRows = before.map { row(it) }
        val previousInputs = originalAssessmentInputs(previousRows)
        val previousResults = resolver.assess(before, 100).associateBy { Triple(it.target, it.originalName, it.sourceVideoId) }
        val assessed = previousRows.map { original ->
            val candidate = originalCandidate(original)!!
            original.copy(originEvidenceJson = encodeOriginalAssessment(candidate,
                previousResults.getValue(Triple(candidate.target, candidate.name, candidate.sourceVideoId)), previousInputs))
        }
        val changedSnapshots = listOf(
            assessed + row(songs.last().copy(name = "Conflicting original")),
            assessed.filterNot { it.targetId == songs.last().target.id } + row(songs.last().copy(name = "Alors on danse"), 150),
            assessed.filterNot { it.source.endsWith(songs.last().sourceVideoId) },
        )
        changedSnapshots.forEach { rows ->
            val inputs = originalAssessmentInputs(rows)
            val stale = latestOriginalRows(rows).filterNot { hasCurrentOriginalAssessmentInputs(it, inputs) }
                .mapNotNull(::originalCandidate)
            val bounded = inputs.withAlbumContext(stale)
            assertTrue(bounded.none { it.albumId == "MPREunrelated" })
            val incremental = resolver.assess(bounded, 200).associateBy { Triple(it.target, it.originalName, it.sourceVideoId) }
            val full = resolver.assess(inputs.candidates.toList(), 200).associateBy { Triple(it.target, it.originalName, it.sourceVideoId) }
            stale.forEach { candidate ->
                val key = Triple(candidate.target, candidate.name, candidate.sourceVideoId)
                assertEquals(full.getValue(key), incremental.getValue(key))
            }
            assertEquals(OriginalNameLanguage.UNKNOWN, incremental.getValue(
                Triple(artist.target, artist.name, artist.sourceVideoId)).language)
        }
    }

    @Test
    fun `new album classification does not include two thousand cached songs`() {
        val saved = (0 until 2_000).map { index ->
            val id = "saved${index.toString().padStart(6, '0')}"
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), "Saved title $index", id,
                "saved-album-${index / 10}")
        }
        val savedRows = saved.map { row(it) }
        val oldInputs = originalAssessmentInputs(savedRows)
        val assessed = savedRows.map { original ->
            val candidate = originalCandidate(original)!!
            original.copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessmentFor(candidate), oldInputs))
        }
        val newAlbum = unrelatedSongs(3)
        val current = assessed + newAlbum.map { row(it) }
        val inputs = originalAssessmentInputs(current)
        val stale = current.filterNot { hasCurrentOriginalAssessmentInputs(it, inputs) }.mapNotNull(::originalCandidate)
        assertEquals(newAlbum.toSet(), inputs.withAlbumContext(stale).toSet())
    }

    private fun unrelatedSongs(count: Int) = (0 until count).map { index ->
        val id = "other${index.toString().padStart(6, '0')}"
        ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), "Unrelated title $index", id, "MPREunrelated")
    }

    private fun row(candidate: ArtTrackOriginalName, observedAt: Long = 100) = MetadataNameEntity(
        kind = candidate.target.kind.name, targetId = candidate.target.id, language = "und", name = candidate.name,
        source = ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, sourcePriority = 10, observedAt = observedAt,
        originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
    )

    private fun assessmentFor(candidate: ArtTrackOriginalName) = OriginalNameAssessment(
        target = candidate.target, originalName = candidate.name, sourceVideoId = candidate.sourceVideoId,
        sourceUrl = "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", language = OriginalNameLanguage.ENGLISH,
        confidence = 0.99f, method = OriginalAlbumLanguageResolver.METHOD_VERSION + "/album-context:$albumId:tracks=3",
        inputFingerprint = "fixture-resolver-result", evaluatedAt = 100,
    )

    private fun assessed(values: List<ArtTrackOriginalName>): List<MetadataNameEntity> {
        val raw = values.map { row(it) }
        return raw.map { row ->
            val candidate = originalCandidate(row)!!
            row.copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessmentFor(candidate), raw))
        }
    }

    private fun assessment(row: MetadataNameEntity): OriginalNameAssessment? =
        OriginalNameAssessmentCodec.decode(row.originEvidenceJson, OriginalNameTarget(OriginalNameKind.valueOf(row.kind), row.targetId), row.name)

    private fun assertUnassessed(rows: List<MetadataNameEntity>) = rows.forEach { assertNull(it.name, assessment(it)) }
}
